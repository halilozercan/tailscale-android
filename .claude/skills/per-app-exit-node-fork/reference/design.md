# Per-app exit node — design reference

Detailed rationale and the load-bearing changes behind the feature. Read
this before editing anything that crosses the Android↔Go boundary, and use
the "Files changed" tables plus the per-layer notes as the watch list when
rebasing (see [rebase.md](rebase.md) for the rebase mechanics).

## What the feature does

Settings → **Per-app exit nodes** lets you, for each installed Android app,
choose one of:

- **Use global exit node** (default — follows the existing `ExitNodeID` pref).
- **No exit node** (drop exit-bound traffic from this app — useful to keep
  one app fully on-tailnet only).
- **A specific exit node** (any peer with `isExitNode`, including Mullvad
  countries).

Apps without an override use the global exit node selection (or none, if no
global is set). The feature does not require a global exit node — it works
standalone.

## Why this needed cross-repo changes

Tailscale's stock exit-node mechanism is *destination-based*: the chosen
exit peer gets `0.0.0.0/0` in its WireGuard AllowedIPs and the data plane
follows AllowedIPs. Per-app override needs *source-based* routing, which
doesn't exist in any single layer. It required fixes in **five** layers.

### 1. The wireguard-go peer-selection hook

The Tailscale fork of wireguard-go exposes `SetPeerByIPPacketFunc` — a
callback consulted before its own AllowedIPs trie. If the callback returns
a peer, that peer's tunnel encapsulates the packet regardless of
AllowedIPs. wgengine was only using the destination-IP part of the API,
discarding the full packet.

**Change:** added `SetPerAppPeerOverrideFunc` to the `wgengine.Engine`
interface. `userspaceEngine` chains the new override → the existing
destination fast path → BART trie inside the single wireguard-go callback.
`LocalBackend.lookupPerAppExitNodePeer` is the override: parses the packet,
asks Android for the originating package (via `connowner.Lookup`), looks up
`Prefs.PerAppExitNode[pkg]`, resolves the StableNodeID to a peer.

### 2. The Android VPN routing table

On Android, `VpnService.Builder.addRoute(prefix)` decides which
destinations *enter the tun at all*. Without a global exit node, only
tailnet ranges are added, so public-internet traffic from Chrome bypasses
the VPN entirely — the kernel routes it on the default network and the
wireguard hook never sees those packets.

**Change:** in `LocalBackend.routerConfigLocked`, the condition that adds
`0.0.0.0/0` and `::/0` to `rcfg.Routes` now also fires when
`prefs.PerAppExitNode().Len() > 0`. In per-app-only mode (no global exit),
LAN access is auto-preserved (the `ExitNodeAllowLANAccess` defaulting is
overridden) so non-overridden apps and the OS itself can still reach the LAN.

### 3. WireGuard receive-side AllowedIPs validation

`nmcfg.WGCfg` strips `0.0.0.0/0` from any peer that isn't the *globally
selected* exit. AllowedIPs is dual-purpose: outbound routing trie *and*
inbound source-IP validation. Without `/0`, when the per-app exit peer
returned a packet with a public source IP (e.g. the response to Chrome's
TCP SYN), the wireguard decryption path saw an "AllowedIP mismatch" and
silently dropped it — packets went out and were forwarded, but responses
were dropped before reaching netstack.

**Change:** `nmcfg.WGCfg` now takes a variadic list of "default-route-ok"
stable IDs in addition to the selected exit node. `LocalBackend` passes
both `prefs.ExitNodeID()` and the unique set of `prefs.PerAppExitNode()`
values. Peers in either set retain `/0` in their AllowedIPs.

### 4. BART destination trie /0 disambiguation

Layer 3 keeps `/0` in WireGuard AllowedIPs for both the global exit *and*
every per-app target. That fixes inbound validation but introduces an
outbound bug: wgengine's BART trie (`peerByIPRoute`) — the destination
fallback used when `PeerByIPPacketFunc` returns `(_, false)` — now sees
multiple peers claiming `/0`. The trie can't represent two peers at the
same prefix, so it returns whichever was inserted last, non-deterministically.
Concretely: with global = Amsterdam and per-app Firefox = Amsterdam,
Chrome (no per-app rule) was routing through a *different* per-app target
like London instead of the configured global Amsterdam.

**Change:** added `IsExitNode bool` to `wgcfg.Peer`. `nmcfg.WGCfg` sets it
only for the peer matching the globally selected `ExitNodeID` (per-app
targets stay false). In `userspaceEngine.maybeReconfigWireguardLocked`,
when building the BART trie, any `/0` prefix from a peer with
`IsExitNode == false` is filtered out. AllowedIPs are untouched, so the
layer-3 inbound-validation fix remains intact. The trie now has at most one
`/0` owner (the real global exit); per-app destinations are handled by the
override callback *before* the trie is consulted.

### 5. Netstack passthrough for non-configured apps in per-app-only mode

With layers 1–4, these work:

- global exit set, per-app set: per-app apps route via their target,
  non-configured apps fall through to the global exit. ✓
- global exit off, per-app unset: ordinary Tailscale (tailnet IPs on the
  tunnel, public traffic via underlying network — the tun never captured
  `/0`). ✓

This didn't:

- global exit *off*, per-app set: tun captures `/0` (layer 2), Firefox
  routes via its per-app peer (override), but Chrome has no per-app rule and
  no global exit — wireguard's BART trie has no `/0` owner (layer 4 filtered
  them out), so Chrome's packet is dropped.

The requirement: in per-app-only mode, non-configured apps should still
reach the public internet via the underlying network (LTE/WiFi), while
keeping tailnet access for things like Termux SSH to a VPS. Stock Android
`VpnService` can't express this — routes are global to the tun, and
`addAllowedApplication`/`addDisallowedApplication` is all-or-nothing per
UID. So we capture `/0` for everyone and *userspace-forward* the
non-configured apps' public traffic through a `protect()`-ed socket that
bypasses our own tunnel.

Tailscale's netstack already had the subnet-router path: `acceptTCP`/
`acceptUDP` accept inbound connections into gVisor, then `forwardTCP`/
`forwardUDP` dial the destination and proxy bytes. Missing on Android: (a)
the dial sockets weren't `protect()`-ed, so they'd loop back into
wireguard; (b) nothing routed passthrough packets into netstack.

**Change** (all in the Go fork):

- `LocalBackend.ShouldPassthroughForApp(p *packet.Parsed) bool` — checks
  the conditions for passthrough (per-app rules exist, no global exit, dst
  is non-tailnet/non-loopback/non-subnet-routed, `connowner.Lookup`
  resolves to an app with no per-app rule).
- `netstack.Impl.passthroughDecider` — new field, wired automatically from
  `Start` when LocalBackend is present. Consulted in `handleLocalPackets`
  before the service-IP switch: if true, the packet is injected into
  `linkEP.gro` and the tun outbound path stops with `filter.DropSilently`.
  wireguard never sees these.
- `netstack.forwardTCP` / `forwardUDP` default their egress dial/listen to
  `netns.NewDialer` / `netns.Listener` instead of stdlib `net.Dialer` /
  `net.ListenUDP`. On Android these route through the `controlC` hook in
  `net/netns/netns_android.go`, which calls `VpnService.protect(fd)` — the
  socket bypasses our tunnel and uses the underlying default network.
- `netstack.shouldSendToHost` — extended so synthesized response packets
  (src = a genuine public IP, dst = this node's tailnet IP) are sent via
  `InjectInboundPacketBuffer` to the kernel for local delivery, instead of
  being looped back into gVisor by `DeliverLoopback`. The guard is
  `ns.isLocalIP(dst) && !ns.isLocalIP(src) && !tsaddr.IsTailscaleIP(src)`.
  The `!tsaddr.IsTailscaleIP(src)` clause is load-bearing: without it a
  4via6 source (`fd7a:115c:a1e0::/48`) is wrongly diverted to the host
  instead of staying on WireGuard (regression caught by
  `TestShouldSendToHost/other_4via6_to_local`). Without the block at all,
  the SYN-ACK netstack synthesizes for the public backend never reaches the
  originating local app and connections never establish.

After these changes:

- Chrome (no per-app rule): SYN diverted → netstack accepts → dials
  `1.2.3.4:443` via protected socket → bytes proxied. The remote sees the
  device's underlying-network IP.
- Firefox (per-app = Amsterdam): override returns the Amsterdam peer;
  routed via wireguard as before.
- Termux SSH to `100.x.y.z`: dst is tailnet → `ShouldPassthroughForApp`
  returns false → normal wireguard path.

## Files changed

### Go fork (`third_party/tailscale`)

| File | What changed |
|---|---|
| `ipn/prefs.go` | New `PerAppExitNode map[string]tailcfg.StableNodeID` on `Prefs` and `PerAppExitNodeSet bool` on `MaskedPrefs`; Equals/Pretty updated |
| `ipn/ipn_clone.go`, `ipn/ipn_view.go` | Regenerated by `go generate ./ipn/` |
| `ipn/prefs_test.go` | `PerAppExitNode` added to the field-coverage check |
| `ipn/ipnlocal/local.go` | `lookupPerAppExitNodePeer` override; registered via `e.SetPerAppPeerOverrideFunc`; `routerConfigLocked` extended to add /0 routes when per-app rules exist; `ShouldPassthroughForApp`; `WGCfg` call passes per-app stable IDs |
| `wgengine/wgengine.go` | New `SetPerAppPeerOverrideFunc` on the `Engine` interface |
| `wgengine/userspace.go` | `userspaceEngine.perAppPeerOverride` atomic; the single wgdev callback runs override → fast path → BART; BART trie excludes `/0` from non-exit peers |
| `wgengine/wgcfg/nmcfg/nmcfg.go` | `WGCfg` accepts variadic per-app stable IDs; keeps /0 for any peer in either set; sets `cpeer.IsExitNode` only for the global exit |
| `wgengine/wgcfg/config.go`, `wgengine/wgcfg/wgcfg_clone.go` | `Peer.IsExitNode` field + clone-needs-regeneration sentinel; `Peer.Equal` updated |
| `wgengine/wgcfg/config_test.go` | `TestPeerEqual` allowlist must include `"IsExitNode"` |
| `wgengine/netstack/netstack.go` | `passthroughDecider` hook in `handleLocalPackets`; `forwardTCP`/`forwardUDP` default to `netns.NewDialer`/`netns.Listener`; `shouldSendToHost` returns true for synthesized public→local-tailnet responses (guarded by `!tsaddr.IsTailscaleIP(src)`) |
| `net/connowner/connowner.go` | New tiny package: atomic-pointer registry for a flow→app resolver |
| `ipn/ipnlocal/state_test.go` | `mockEngine` stub for the new interface method |

Note: upstream removed the Engine watchdog (`wgengine/watchdog.go`), so the
old pass-through wrapper there is gone — the real method lives on
`userspaceEngine` and the interface.

### `tailscale-android`

| File | What changed |
|---|---|
| `go.mod` | `replace tailscale.com => ./third_party/tailscale` |
| `libtailscale/interfaces.go` | New `LookupPackageByFlow(protocol, srcAddr, srcPort, dstAddr, dstPort) string` on the `IPNService` interface |
| `libtailscale/backend.go` | Registers/unregisters `connowner.SetLookupFunc` on VPN start/stop, alongside `SetAndroidProtectFunc` |
| `android/.../IPNService.kt` | `lookupPackageByFlow` via `ConnectivityManager.getConnectionOwnerUid` + `PackageManager.getNameForUid`; 1024-entry `LruCache` |
| `android/.../ui/model/Ipn.kt` | Kotlin `Prefs.PerAppExitNode` / `MaskedPrefs.PerAppExitNode`; `deepCopy` |
| `android/.../ui/viewModel/PerAppExitNodeViewModel.kt` | New list + picker viewmodels |
| `android/.../ui/view/PerAppExitNodeView.kt`, `PerAppExitNodePickerView.kt` | New Compose screens (overridden apps first; "Use global", "No exit node", full tailnet+Mullvad list) |
| `android/.../ui/view/SettingsView.kt`, `viewModel/SettingsViewModel.kt`, `MainActivity.kt` | Nav route + Settings entry |
| `android/src/main/res/values/strings.xml` | New strings |
| `android/.../IPNReceiver.java`, `AndroidManifest.xml`, `App.kt` | "Disable Exit Node" notification action: `com.tailscale.ipn.DISABLE_EXIT_NODE` broadcast, action shown when an exit node is active and MDM hasn't forced one, enqueues `DisableExitNodeWorker` |
| `android/.../DisableExitNodeWorker.kt` | New: mirrors the UI toggle via `Client.setUseExitNode(false)` so the selected exit node ID is preserved (re-enableable) instead of cleared |

## How a flow goes through the system

Chrome (UID 10228, `com.android.chrome`) opens a TCP socket to `8.8.8.8:443`:

1. The kernel routes the SYN through Tailscale's tun fd (only because we
   added `/0` to `rcfg.Routes`).
2. `tstun.Wrapper.Read` pulls it; `handleLocalPackets` returns `Accept`;
   the ACL filter accepts; the packet is handed to wireguard-go.
3. wireguard-go's `RoutineReadFromTUN` calls
   `AllowedIPs.LookupFromPacket(src, dst, ipPkt)`; the registered
   `PeerByIPPacketFunc` callback runs.
4. The callback (in `wgengine/userspace.go`) consults the per-app override
   atomic; it calls `LocalBackend.lookupPerAppExitNodePeer`.
5. The override checks `prefs.PerAppExitNode` (empty → fast `false`).
   Non-empty: parses `proto=TCP src=100.x:42364 dst=8.8.8.8:443`.
6. `connowner.Lookup` calls the libtailscale-registered function: a JNI
   call to `IPNService.lookupPackageByFlow` →
   `ConnectivityManager.getConnectionOwnerUid` → UID `10228` →
   `com.android.chrome` (cached in `LruCache`).
7. `perApp.GetOk("com.android.chrome")` → `nNVZjJXF2611CNTRL` (Amsterdam).
8. `b.currentNode().PeerByStableID(...)` → Amsterdam peer view.
9. Override returns Amsterdam's `NodePublic`.
10. wireguard-go stages the packet for Amsterdam's tunnel; encrypt + send.
11. Amsterdam decrypts, sees inner `src=100.x dst=8.8.8.8`, validates `src`
    is in our peer's AllowedIPs, forwards via local NAT.
12. Response returns to Amsterdam → SNAT'd → encrypted → back to us.
13. We decrypt and check the inner source against Amsterdam's AllowedIPs in
    *our* config. Because layer 3 restored `/0` for per-app peers, source
    `8.8.8.8` matches `/0` → accepted.
14. Delivered to netstack → up the TCP stack → Chrome.

## Diagnostic logging

`b.logf("perAppExitNode: ...")` lines in the override:

- `override hook installed` (one-shot)
- `wgdev callback fired first time …` / `hook fired first time …` (one-shot)
- `TCP/UDP <src>→<dst> app="..." <decision>` (per unique 5-tuple+decision,
  deduped via `sync.Map`; decisions: `route-via-…`, `drop`, `no-rule`,
  `no-owner`, `no-peer-for-…`)

These are scaffolding, not load-bearing — a rebase dropping them is fine.
`adb logcat -d -s gojni | grep perAppExitNode` surfaces them.

## Known issues / future work

- **Passthrough leaks the device's real public IP.** In per-app-only mode,
  non-configured apps egress via the underlying network — the real public
  IP is visible to the destination. Intentional (the requirement), but
  per-app routing is *not* a privacy boundary for non-configured apps when
  no global exit is set.
- **Passthrough is TCP/UDP only.** `connowner.Lookup` needs a 5-tuple, so
  ICMP from non-configured apps to public destinations still drops at
  wireguard. Acceptable.
- **Lookup failure means drop, not passthrough.** If
  `getConnectionOwnerUid` returns `INVALID_UID` (flow already closed),
  `ShouldPassthroughForApp` returns false and the packet falls through to
  wireguard, which drops it — erring against accidentally leaking traffic
  we *would* have per-app-routed if resolved in time.
- **Verbose logging.** `perAppExitNode:` fires per unique flow. Easy
  cleanup: drop the `no-rule` default case, keep hits/drops/no-owner.
- **`getConnectionOwnerUid` is API 29+.** `lookupPackageByFlow` returns ""
  on Android < 10, making the override a silent no-op there.
- **LruCache caches negatives forever.** Failed UID lookups (`""`) are
  cached in the same 1024-entry cache; bounded, but suboptimal.
- **MDM / multi-user / iOS** are not handled.
- **The "Disable Exit Node" worker is a persisted `OneTimeWorkRequest`.** A
  stale one can fire on app restart and clear a fresh exit-node selection.
  Consider making it non-persisted or adding a freshness guard.
