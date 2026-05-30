# Per-App Exit Node

A hobby fork of `tailscale-android` and `tailscale/tailscale` that lets you
pick a different exit node for each Android app — e.g. route YouTube through
a Mullvad node while everything else uses your home tailnet or no exit node.

Built 2026-05-14/15.

## Layout

- `/home/halilibo/StudioProjects/tailscale-android` — this repo. Has a
  `replace tailscale.com => ../tailscale` directive in `go.mod`.
- `/home/halilibo/StudioProjects/tailscale` — sibling clone of
  `tailscale/tailscale` on branch `per-app-exit-node`, based on commit
  `0cf899610` (the pinned version this repo was using before the fork).

## What the feature does

Settings → **Per-app exit nodes** lets you, for each installed Android app,
choose one of:

- **Use global exit node** (default — follows the existing `ExitNodeID` pref).
- **No exit node** (drop exit-bound traffic from this app — useful if you want
  one app fully on-tailnet only).
- **A specific exit node** (any peer with `isExitNode`, including Mullvad
  countries).

Apps without an override use the global exit node selection (or none, if no
global is set). The feature does not require a global exit node to be set —
it works standalone.

## Why this needed cross-repo changes

Tailscale's existing exit-node mechanism is *destination-based*: the chosen
exit peer gets `0.0.0.0/0` in its WireGuard AllowedIPs, and the rest of the
data plane just follows AllowedIPs. To override per-app, we need
*source-based* routing, which doesn't exist in any single layer. It turned
out to require fixes in **five** layers:

### 1. The wireguard-go peer-selection hook (the hook we noticed first)

The Tailscale fork of wireguard-go already exposes `SetPeerByIPPacketFunc` —
a callback wireguard-go consults before its own AllowedIPs trie. If the
callback returns a peer, that peer's tunnel encapsulates the packet
regardless of AllowedIPs. Tailscale's wgengine was only using the
destination-IP part of the API, throwing away the full packet.

**Change:** added `SetPerAppPeerOverrideFunc` to the `wgengine.Engine`
interface. `userspaceEngine` chains the new override → the existing
destination fast path → BART trie inside the single wireguard-go callback.
`LocalBackend.lookupPerAppExitNodePeer` is the override: parses the packet,
asks Android for the originating package (via `connowner.Lookup`), looks up
`Prefs.PerAppExitNode[pkg]`, resolves the StableNodeID to a peer.

### 2. The Android VPN routing table (the layer that almost defeated me)

On Android, `VpnService.Builder.addRoute(prefix)` decides which destinations
*enter the tun at all*. Without a global exit node, only tailnet ranges are
added, so public-internet traffic from Chrome bypasses the VPN entirely —
Android's kernel routes it on the default network. Our wireguard hook never
saw the packets because the packets never reached wireguard.

**Change:** in `LocalBackend.routerConfigLocked`, the condition that adds
`0.0.0.0/0` and `::/0` to `rcfg.Routes` now also fires when
`prefs.PerAppExitNode().Len() > 0`. In per-app-only mode (no global exit),
LAN access is auto-preserved (the `ExitNodeAllowLANAccess` defaulting is
overridden) so non-overridden apps and the OS itself can still reach the
LAN.

### 3. WireGuard receive-side AllowedIPs validation (the subtle one)

`nmcfg.WGCfg` strips `0.0.0.0/0` from any peer that isn't the *globally
selected* exit. AllowedIPs serves a dual role: outbound routing trie *and*
inbound source-IP validation. Without `/0`, when the per-app exit peer
returned a packet with a public source IP (e.g. the response to Chrome's
TCP SYN), our wireguard decryption path saw an "AllowedIP mismatch" and
silently dropped it.

This is what made the second test confusing: the override was firing, the
packets were going out, the peer was forwarding — but responses came back
and got dropped before reaching netstack/the app.

**Change:** `nmcfg.WGCfg` now takes a variadic list of "default-route-ok"
stable IDs in addition to the selected exit node. `LocalBackend` passes
both `prefs.ExitNodeID()` and the unique set of `prefs.PerAppExitNode()`
values. Peers in either set retain `/0` in their AllowedIPs.

### 4. BART destination trie /0 disambiguation (the silently-wrong one)

Layer 3 above keeps `/0` in WireGuard AllowedIPs for both the global exit
*and* every per-app target. That fixes inbound validation, but introduces a
new outbound bug: wgengine's BART trie (`peerByIPRoute`) — used as the
destination-routing fallback when our `PeerByIPPacketFunc` callback returns
`(_, false)` — sees multiple peers claiming `/0`. The trie can't represent
two peers at the same prefix, so it returns whichever was inserted last,
non-deterministically. Concretely: with global = Amsterdam and per-app
Firefox = Amsterdam, Chrome (no per-app rule) was routing through a
*different* per-app target like London instead of the configured global
Amsterdam.

**Change:** added an `IsExitNode bool` to `wgcfg.Peer`. `nmcfg.WGCfg`
sets it only for the peer matching the globally selected `ExitNodeID`
(per-app targets stay false). In `userspaceEngine.maybeReconfigWireguardLocked`,
when building the BART trie, any `/0` prefix from a peer with
`IsExitNode == false` is filtered out. AllowedIPs are untouched, so the
inbound-validation fix from layer 3 remains intact. The trie now has at
most one `/0` owner (the real global exit), and the per-app override
callback handles per-app destinations *before* the trie is ever consulted.

### 5. Netstack passthrough for non-configured apps in per-app-only mode (the longest one)

With layers 1–4, this works:

- global exit set, per-app set: per-app apps route via their target,
  non-configured apps fall through to the global exit. ✓
- global exit not set, per-app not set: ordinary Tailscale (tailnet IPs
  on the tunnel, public traffic via underlying network — the tun never
  captured `/0` so nothing changed). ✓

But this didn't:

- global exit *not* set, per-app set: tun captures `/0` (layer 2
  requirement), Firefox routes via its per-app peer (override callback),
  but Chrome has no per-app rule and no global exit — wireguard's BART
  trie has no `/0` owner (layer 4 filtered them out), so Chrome's packet
  is dropped. Chrome can't reach the public Internet.

The user's requirement: in per-app-only mode, non-configured apps should
still reach the public Internet via the underlying network (LTE/WiFi),
while keeping tailnet access for things like Termux SSH to a VPS. Vanilla
Android `VpnService` can't express this — its routes are global to the
tun interface, and `addAllowedApplication`/`addDisallowedApplication` is
all-or-nothing per UID. So the only way is to capture `/0` for everyone
and *userspace-forward* the non-configured apps' public traffic through a
protected socket that bypasses our own VPN tunnel.

Tailscale's netstack already has the subnet-router code path:
`acceptTCP`/`acceptUDP` accept inbound connections into gVisor, then
`forwardTCP`/`forwardUDP` dial the destination via stdlib and proxy bytes.
What was missing on Android: (a) the dial sockets weren't `protect()`-ed,
so they'd loop back into wireguard, and (b) nothing was routing
passthrough packets into netstack in the first place.

**Change** (all in `tailscale/tailscale` fork):

- `LocalBackend.ShouldPassthroughForApp(p *packet.Parsed) bool` — checks
  the four conditions for passthrough (per-app rules exist, no global
  exit, dst is non-tailnet/non-loopback/non-subnet-routed,
  `connowner.Lookup` resolves to an app with no per-app rule).
- `netstack.Impl.passthroughDecider` — new field, wired automatically
  from `Start` when LocalBackend is passed. Consulted in
  `handleLocalPackets` before the existing service-IP switch: if it
  returns true, the packet is injected into `linkEP.gro` and the tun
  outbound path stops with `filter.DropSilently`. wireguard never sees
  these packets.
- `netstack.forwardTCP` and `forwardUDP` default their egress dial /
  listen path to `netns.NewDialer` / `netns.Listener` instead of stdlib
  `net.Dialer` / `net.ListenUDP`. On Android these route through the
  `controlC` hook in `net/netns/netns_android.go`, which calls
  `VpnService.protect(fd)` — the socket bypasses our VPN tunnel and uses
  the underlying default network.
- `netstack.shouldSendToHost` — extended so that synthesized response
  packets (src = a public IP, dst = this node's tailnet IP) are sent via
  `InjectInboundPacketBuffer` to the kernel for local delivery, instead
  of being looped back into gVisor by `DeliverLoopback`. Without this,
  the SYN-ACK netstack generates on behalf of the public backend never
  reaches the originating local app, and connections never establish.

After these changes:

- Chrome (no per-app rule): SYN diverted → netstack accepts → dials
  `1.2.3.4:443` via protected socket → bytes proxied. Chrome thinks it's
  talking directly to `1.2.3.4`. Real source IP visible to remote is the
  device's underlying-network IP.
- Firefox (per-app = Amsterdam): per-app override callback returns the
  Amsterdam peer; packet routed via wireguard as before. Unchanged.
- Termux SSH to `100.x.y.z`: dst is tailnet → `ShouldPassthroughForApp`
  returns false → normal wireguard path. Unchanged.

## Files changed

### `tailscale/tailscale` fork

| File | What changed |
|---|---|
| `ipn/prefs.go` | New `PerAppExitNode map[string]tailcfg.StableNodeID` on `Prefs` and `PerAppExitNodeSet bool` on `MaskedPrefs`; Equals/Pretty updated; `maps` stdlib added |
| `ipn/ipn_clone.go`, `ipn/ipn_view.go` | Regenerated by `go generate ./ipn/` |
| `ipn/prefs_test.go` | Added `PerAppExitNode` to the field-coverage check |
| `ipn/ipnlocal/local.go` | `lookupPerAppExitNodePeer` override; registered via `e.SetPerAppPeerOverrideFunc`; `routerConfigLocked` extended to add /0 routes when per-app rules exist; `WGCfg` call passes per-app stable IDs |
| `wgengine/wgengine.go` | New `SetPerAppPeerOverrideFunc` method on the `Engine` interface |
| `wgengine/userspace.go` | `userspaceEngine.perAppPeerOverride` atomic; new method; the single wgdev callback runs override → fast path → BART |
| `wgengine/watchdog.go` | Pass-through for `SetPerAppPeerOverrideFunc` |
| `wgengine/wgcfg/nmcfg/nmcfg.go` | `WGCfg` accepts variadic per-app stable IDs; keeps /0 for any peer in either set; sets `cpeer.IsExitNode` only for the global exit |
| `wgengine/wgcfg/config.go`, `wgengine/wgcfg/wgcfg_clone.go` | `Peer.IsExitNode` field + clone-needs-regeneration check; `Peer.Equal` updated |
| `wgengine/userspace.go` | BART trie excludes `/0` prefixes from non-exit peers so the destination fallback can't pick a per-app target instead of the global exit |
| `wgengine/netstack/netstack.go` | `passthroughDecider` hook in `handleLocalPackets`; `forwardTCP`/`forwardUDP` default to `netns.NewDialer`/`netns.Listener` for protected egress; `shouldSendToHost` returns true for synthesized response packets (src=public, dst=local tailnet IP) so the kernel delivers them to the originating app |
| `net/connowner/connowner.go` | New tiny package: atomic-pointer registry for platforms (Android) to install a flow→app resolver |
| `ipn/ipnlocal/state_test.go` | `mockEngine` stub for the new interface method |

### `tailscale-android`

| File | What changed |
|---|---|
| `go.mod` | `replace tailscale.com => ../tailscale` |
| `libtailscale/interfaces.go` | New `LookupPackageByFlow(protocol, srcAddr, srcPort, dstAddr, dstPort) string` on the `IPNService` interface |
| `libtailscale/backend.go` | Registers/unregisters `connowner.SetLookupFunc` on VPN start/stop, alongside the existing `SetAndroidProtectFunc` |
| `android/.../IPNService.kt` | `lookupPackageByFlow` implementation using `ConnectivityManager.getConnectionOwnerUid` + `PackageManager.getNameForUid`; 1024-entry LruCache to keep per-packet JNI cost down |
| `android/.../ui/model/Ipn.kt` | Kotlin `Prefs.PerAppExitNode` and `MaskedPrefs.PerAppExitNode`; `deepCopy` handles the new field |
| `android/.../ui/viewModel/PerAppExitNodeViewModel.kt` | New: list viewmodel + per-app picker viewmodel |
| `android/.../ui/view/PerAppExitNodeView.kt`, `PerAppExitNodePickerView.kt` | New: Compose screens. Overridden apps surface first; picker offers "Use global", "No exit node", and the full tailnet+Mullvad list |
| `android/.../ui/view/SettingsView.kt`, `viewModel/SettingsViewModel.kt`, `MainActivity.kt` | New nav route and Settings entry |
| `android/src/main/res/values/strings.xml` | New string resources |
| `android/.../IPNReceiver.java`, `AndroidManifest.xml`, `App.kt` | "Disable Exit Node" notification action: registers `com.tailscale.ipn.DISABLE_EXIT_NODE` broadcast, surfaces a notification action when an exit node is active and MDM hasn't forced one, enqueues `DisableExitNodeWorker` |
| `android/.../DisableExitNodeWorker.kt` | New: mirrors the UI's "Disable Exit Node" toggle via `Client.setUseExitNode(false)` so the selected exit node ID is preserved (re-enableable) instead of cleared |

## How a flow goes through the system

When Chrome (UID 10228, package `com.android.chrome`) opens a TCP socket
to `8.8.8.8:443`:

1. Android kernel routes the SYN packet through Tailscale's tun fd (only
   because we added `/0` to `rcfg.Routes`).
2. `tstun.Wrapper.Read` pulls it; `handleLocalPackets` returns `Accept`
   (not destined for a special address); main ACL filter accepts; packet
   is handed to wireguard-go.
3. wireguard-go's `RoutineReadFromTUN` calls
   `AllowedIPs.LookupFromPacket(src, dst, ipPkt)`. Because we registered
   a `PeerByIPPacketFunc`, the callback runs.
4. The callback (defined in `wgengine/userspace.go`) consults the per-app
   override atomic pointer. It calls
   `LocalBackend.lookupPerAppExitNodePeer`.
5. The override checks `prefs.PerAppExitNode` (empty fast-path returns
   `false`). Non-empty: parses the packet, sees `proto=TCP src=100.x:42364
   dst=8.8.8.8:443`.
6. `connowner.Lookup` calls the function libtailscale registered: a JNI
   call to Java's `IPNService.lookupPackageByFlow`, which calls Android's
   `ConnectivityManager.getConnectionOwnerUid` → UID `10228` → package
   `com.android.chrome` (cached in `LruCache`).
7. `perApp.GetOk("com.android.chrome")` → `nNVZjJXF2611CNTRL` (amsterdam).
8. `b.currentNode().PeerByStableID(...)` → amsterdam peer view.
9. Override returns amsterdam's `NodePublic`.
10. wireguard-go calls `device.LookupPeer(pubkey)` and stages the packet
    for amsterdam's tunnel. Encryption + magicsock send.
11. Amsterdam receives the encrypted packet, decrypts, sees inner
    `src=100.x dst=8.8.8.8`, validates `src` is in our peer's AllowedIPs
    (it is), forwards via local NAT.
12. Response from `8.8.8.8` returns to amsterdam → SNAT'd back → encrypted
    → magicsock to us.
13. We decrypt; check the inner source against AllowedIPs of amsterdam in
    OUR config. **Because step (3 of cross-repo fix) restored `/0` for
    per-app peers**, the source `8.8.8.8` matches `/0` → accepted.
14. Packet delivered to netstack → up the TCP stack → Chrome.

## Diagnostic logging

Several `b.logf("perAppExitNode: ...")` lines are wired up in the override:

- `override hook installed` (one-shot at startup)
- `SetPerAppPeerOverrideFunc set, atomic now non-nil=<bool>` (one-shot)
- `wgdev callback fired first time src=... dst=...` (one-shot, first
  outbound packet to reach wireguard)
- `hook fired first time src=... dst=...` (one-shot, first call to
  `lookupPerAppExitNodePeer`)
- `TCP/UDP <src>→<dst> app="..." <decision>` (per unique 5-tuple + decision,
  deduped via `sync.Map`)

These were crucial during debugging. Two diagnostic counters
(`perAppCallCount`, `engineCBCallCount`) are still in place but unused
beyond the one-shot logs. Worth pruning before any merge.

## Known issues / future work

### Passthrough source IP leaks the device's real public IP

In per-app-only mode, traffic from non-configured apps egresses via the
device's underlying network — its real public IP is visible to the
destination. This is *intentional*: the user wanted exactly that ("Non
configured apps pass-through to global exit node, or none"). But it's
worth being explicit about: per-app routing is *not* a privacy boundary
for non-configured apps when no global exit is set.

### Passthrough is TCP/UDP only

`connowner.Lookup` needs a 5-tuple, so ICMP from non-configured apps to
public destinations (e.g. `ping 8.8.8.8` from Chrome's dev tools, not
that anything does this) still drops at wireguard. Acceptable for now.

### `connowner.Lookup` failure means drop, not passthrough

If `ConnectivityManager.getConnectionOwnerUid` returns `INVALID_UID`
(short-lived flow that already closed by the time the first packet
reaches our hook), `ShouldPassthroughForApp` returns false and the
packet falls through to wireguard, which drops it. Erring on the side of
"don't passthrough" avoids accidentally leaking traffic from a flow we
*would* have routed via a per-app rule if we'd resolved it in time.

### Logging is verbose

`perAppExitNode:` lines fire per unique flow. On a busy device this is many
lines. Easy cleanup: drop the `no-rule` decision (the default, normal case
for non-overridden apps) and keep only override hits + drops + no-owner
events.

### `getConnectionOwnerUid` is API 29+

`IPNService.lookupPackageByFlow` returns empty string on Android < 10,
which causes the override to fall through. The feature is silently a no-op
on those versions. Not worth handling unless you actually care.

### LruCache caches negatives forever

Failed UID lookups (returning `""`) are cached in the same LruCache.
Long-lived sessions with many transient flows will fill the cache with
useless entries. Bounded at 1024 so it's not a real leak, but suboptimal.

### MDM / multi-user / iOS

None of these are handled and we don't plan to.

## Build & install

Prereq: `zip` (Arch: `pacman -S zip`), Android NDK 23, Android SDK.

```bash
cd /home/halilibo/StudioProjects/tailscale-android
ANDROID_HOME=/home/halilibo/Android/Sdk make tailscale-debug
/home/halilibo/Android/Sdk/platform-tools/adb install -r tailscale-debug.apk
```

A clean rebuild after Go source changes requires deleting the cached
stripped artifacts (the Makefile doesn't track Go source timestamps for the
.so):

```bash
rm -f libgojni.so.stripped libgojni.so.unstripped libgojni.so.debug \
      android/libs/libtailscale.aar android/libs/libtailscale_unstripped.aar
```

## Testing flow

1. Connect to your tailnet, leave global exit node **off**.
2. Settings → Per-app exit nodes → tap an app → pick an exit node.
3. Toggle the VPN off and back on so the new `wgcfg` and routes get
   installed (the daemon should do this automatically on prefs change but a
   manual toggle removes ambiguity).
4. Open the app and hit a fresh URL (e.g. `http://neverssl.com` to avoid
   caching).
5. Verify externally (a "what's my IP" page in that app) that the
   reported IP is the chosen exit node's.

`adb logcat -d -s gojni` will show the `perAppExitNode:` decisions per
flow if anything goes wrong.

## Keeping the fork current with upstream

The full procedure (clone setup, remote layout, rebase steps for both
forks, submodule pin bumps, verification) lives in
[`FORK_MAINTENANCE.md`](FORK_MAINTENANCE.md). This section just calls
out the parts of the upstream surface that are most likely to break
this specific feature on rebase.

### Watch list for upstream changes that need extra attention

- **`SetPeerByIPPacketFunc` signature changes.** Our `userspaceEngine`
  closure depends on the underlying wireguard-go callback being
  `(src, dst, ipPkt) → (NoisePublicKey, bool)`. If Tailscale's fork of
  wireguard-go ever changes this, the closure in `userspace.go` and the
  callback chain in `local.go` will need to follow.
- **`WGCfg` signature changes.** Upstream may add or rename parameters
  (e.g. they recently took flags as `netmap.WGConfigFlags`). Our
  variadic `perAppExitNodes ...tailcfg.StableNodeID` should sit at the
  end of any new signature.
- **`routerConfigLocked` exit-node block restructure.** If upstream
  moves the "should we add /0 to routes?" logic into a helper or
  changes its condition shape, port the `|| prefs.PerAppExitNode().Len()
  > 0` clause to wherever it ends up.
- **`nmcfg.WGCfg` AllowedIPs filtering.** Same: if upstream changes how
  exit-node /0 stripping works, port our `defaultRouteOK` helper *and*
  the `cpeer.IsExitNode = peer.StableID() == exitNode` assignment.
- **`wgcfg.Peer` struct fields.** We added `IsExitNode bool`; the
  generated `wgcfg_clone.go` has a `_PeerCloneNeedsRegeneration`
  sentinel that must list it too. If the regeneration check fails the
  build, re-run `go generate ./wgengine/wgcfg/`.
- **`wgengine.maybeReconfigWireguardLocked` BART trie construction.**
  Our `/0`-skip-when-`!IsExitNode` filter has to survive any refactor
  that rebuilds the trie. If the rebuilt trie ever stops using a `for
  _, p := range full.Peers` loop directly, port the filter to the new
  iteration site.
- **netstack `handleLocalPackets`.** Two things need to survive:
  (1) the `passthroughDecider` early-exit at the top of the function,
  (2) the existing service-IP / 4via6 switch below it. If upstream
  refactors the function, both have to land in equivalent positions.
- **netstack `forwardTCP` / `forwardUDP` dial paths.** We default the
  dialer to `netns.NewDialer` (TCP) and listener to `netns.Listener`
  (UDP) so Android's `protect()` gets called on the egress socket. If
  upstream changes how these forwarders are constructed, reapply the
  netns wiring — otherwise per-app-only mode silently loops back into
  wireguard.
- **netstack `shouldSendToHost`.** Our IPv4 and IPv6 cases append a
  `if ns.isLocalIP(dstIP) && !ns.isLocalIP(srcIP) { return true }`
  block so passthrough response packets reach the kernel via
  `InjectInboundPacketBuffer` instead of being looped back into gVisor
  by `DeliverLoopback`. Without it, established TCP connections from
  non-configured apps will silently fail handshake. If upstream
  restructures `shouldSendToHost`, this is critical to reapply.

If a future rebase ever drops our diagnostic log lines or atomic
counters, that's fine — they're scaffolding, not load-bearing. The
load-bearing changes are all listed under "Files changed" near the top
of this doc.
