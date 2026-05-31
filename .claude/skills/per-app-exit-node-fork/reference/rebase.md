# Fork maintenance — rebase reference

How the two forks are wired, what to do after cloning, and how to pull
upstream changes without breaking the per-app-exit-node feature. The
feature rationale and per-file change list are in [design.md](design.md);
this file is the rebase mechanics and the conflict/watch surface.

## Topology

Two forks, coupled by one submodule pin:

```
github.com/tailscale/tailscale-android   ← upstream Android client
        │ (forked)
        ▼
github.com/halilozercan/tailscale-android   ← this repo
        │ go.mod: replace tailscale.com => ./third_party/tailscale
        │ .gitmodules: third_party/tailscale → halilozercan/tailscale
        ▼
   third_party/tailscale (git submodule, pinned commit)
        │
        ▼
github.com/halilozercan/tailscale   ← Go fork (branch: per-app-exit-node)
        │ (forked, periodically rebased)
        ▼
github.com/tailscale/tailscale   ← upstream Go core
```

The submodule pins a specific commit of the Go fork. Bumping the Android
client to a newer Go-fork commit is explicit (see "Bumping the submodule
pin").

## Remotes

The two repos have **different** remote layouts — the submodule is cloned
from a personal-fork URL (`.gitmodules`) while the parent is a plain clone
of upstream. Don't assume they match.

### Parent (`tailscale-android`)

| Remote   | URL                                                | Purpose                    |
|----------|----------------------------------------------------|----------------------------|
| `origin` | `https://github.com/tailscale/tailscale-android/`  | Pull from upstream         |
| `halil`  | `git@github.com:halilozercan/tailscale-android.git`| Push personal feature work |

`origin` *is* upstream, so `git fetch origin` / `git rebase origin/main`
sync against upstream. No separate `upstream` remote.

### Submodule (`third_party/tailscale`)

| Remote     | URL                                          | Purpose                    |
|------------|----------------------------------------------|----------------------------|
| `origin`   | `git@github.com:halilozercan/tailscale.git`  | Personal fork (clone URL)  |
| `halil`    | `git@github.com:halilozercan/tailscale.git`  | Personal fork (push alias) |
| `upstream` | `https://github.com/tailscale/tailscale.git` | Pull from upstream core    |

`origin` points at the **personal fork** (the `.gitmodules` URL, which
`git submodule update` names `origin`), so upstream lives under a separate
`upstream` remote and you rebase against `upstream/main`. `halil` is an
alias for the same fork as `origin` so the push commands read the same
across both repos.

In both repos, **pushes go to `halil`**; upstream syncs pull from whichever
remote points at upstream (parent: `origin`; submodule: `upstream`).

Add missing remotes:

```bash
# Parent (origin already = upstream)
git remote add halil git@github.com:halilozercan/tailscale-android.git
# Submodule (origin = personal fork from .gitmodules)
cd third_party/tailscale
git remote add halil    git@github.com:halilozercan/tailscale.git
git remote add upstream https://github.com/tailscale/tailscale.git
```

## Fresh clone

```bash
git clone git@github.com:halilozercan/tailscale-android.git
cd tailscale-android
git checkout per-app-exit-node          # feature work lives here, not main
git submodule update --init --recursive
```

`main` in both repos is a clean upstream mirror; the feature lives on
`per-app-exit-node`. A fresh clone lands on `main`, so check out the
feature branch first. Without `git submodule update --init`,
`third_party/tailscale` is empty and the gomobile build fails.

To pull submodules automatically: `git config submodule.recurse true`.

## Conflict zones / upstream watch list

These are the sites where upstream churn either conflicts on rebase or —
worse — merges cleanly but silently breaks the feature. Check each after a
rebase.

### Go fork (`third_party/tailscale`)

1. **`ipn/prefs.go`** — `Prefs` / `MaskedPrefs` fields churn often. Re-add
   `PerAppExitNode` / `PerAppExitNodeSet` if a merge resolution dropped them.
2. **`ipn/ipnlocal/local.go`** — large, many hands. Conflict-prone sites:
   `routerConfigLocked` (the `|| prefs.PerAppExitNode().Len() > 0` clause
   that adds /0 routes), registration of `lookupPerAppExitNodePeer` near
   where `lookupPeerByIP` is set, and `ShouldPassthroughForApp`.
3. **`wgengine/wgcfg/nmcfg/nmcfg.go`** — `WGCfg`'s signature takes our
   variadic per-app stable IDs and sets `cpeer.IsExitNode`. Our variadic
   `perAppExitNodes ...tailcfg.StableNodeID` should sit at the end of any
   new signature; reapply both the variadic and the
   `cpeer.IsExitNode = peer.StableID() == exitNode` assignment.
4. **`wgengine/wgcfg/config.go`, `wgcfg_clone.go`, `config_test.go`** — the
   `IsExitNode bool` field on `Peer`, its `Peer.Equal` line, and the
   `_PeerCloneNeedsRegeneration` sentinel. `TestPeerEqual` reflects over
   `Peer`'s fields against a hardcoded allowlist, so `"IsExitNode"` must be
   in that `case` list or the test fails with "Have you added field … to
   Peer.Equal?". If the clone-regen check fails the build, run
   `go generate ./wgengine/wgcfg/`.
5. **`wgengine/userspace.go`** — the BART trie `/0`-skip-when-`!IsExitNode`
   filter and the `perAppPeerOverride` atomic + closure. The closure
   depends on the wireguard-go callback being
   `(src, dst, ipPkt) → (NoisePublicKey, bool)`; if Tailscale's
   wireguard-go fork changes that, the closure here and the chain in
   `local.go` must follow. If a refactor stops building the trie with a
   `for _, p := range full.Peers` loop, port the filter to the new site.
6. **`wgengine/wgengine.go`** — the `Engine` interface addition
   `SetPerAppPeerOverrideFunc`. **Upstream removed the Engine watchdog**
   (commit `2b338dd6a`), so `wgengine/watchdog.go` is gone; on rebase
   you'll get a modify/delete conflict there — resolve with
   `git rm wgengine/watchdog.go`. The real method lives on `userspaceEngine`
   and the interface, not a wrapper.
7. **`wgengine/netstack/netstack.go`** — three things must survive any
   refactor of `handleLocalPackets` / the forwarders:
   (a) the `passthroughDecider` early-exit at the top of
   `handleLocalPackets`, above the service-IP / 4via6 switch;
   (b) `forwardTCP`/`forwardUDP` defaulting to `netns.NewDialer` /
   `netns.Listener` (without it, per-app-only egress silently loops back
   into wireguard);
   (c) the `shouldSendToHost` block returning true for `dst=local-tailnet`
   with a genuine public source
   (`!ns.isLocalIP(src) && !tsaddr.IsTailscaleIP(src)`). The
   `!tsaddr.IsTailscaleIP` guard is load-bearing — without it
   `TestShouldSendToHost/other_4via6_to_local` fails; without the block at
   all, non-configured apps' TCP handshakes silently fail.
8. **`ipn/ipnlocal/state_test.go`** — `mockEngine` must stub
   `SetPerAppPeerOverrideFunc`.
9. **Generated `ipn/ipn_clone.go`, `ipn/ipn_view.go`** — **never
   hand-resolve.** Take either side during the rebase, then regenerate:

   ```bash
   git checkout --theirs ipn/ipn_clone.go ipn/ipn_view.go
   git add ipn/ipn_clone.go ipn/ipn_view.go
   # after the rebase finishes:
   go generate ./ipn/
   ```

If `TestPrefsEqual` fails saying the handled list is out of sync, re-add
`"PerAppExitNode"` to `prefsHandles` in `ipn/prefs_test.go`.

Diagnostic log lines and the unused atomic counters are scaffolding — a
rebase dropping them is fine. The load-bearing changes are the items above
and the "Files changed" tables in [design.md](design.md).

### Android client (parent repo)

| File | What's ours that needs to survive |
|---|---|
| `go.mod` | The `replace tailscale.com => ./third_party/tailscale` line. Upstream may bump the `tailscale.com vX.Y.Z-pre.0...` pin; take theirs. |
| `libtailscale/interfaces.go` | The `LookupPackageByFlow(protocol, srcAddr, srcPort, dstAddr, dstPort) string` method on `IPNService`. |
| `libtailscale/backend.go` | The two `connowner.SetLookupFunc(...)` calls in VPN-start/stop, alongside the existing `netns.SetAndroidProtectFunc` calls. |
| `android/.../IPNService.kt` | `lookupPackageByFlow` + its `LruCache` + imports. |
| `android/.../ui/model/Ipn.kt` | `PerAppExitNode` on `Prefs`/`MaskedPrefs`; the `deepCopy` clone. |
| `android/.../ui/view/PerAppExitNodeView.kt`, `PerAppExitNodePickerView.kt`, `viewModel/PerAppExitNodeViewModel.kt` | Whole files — generally won't conflict unless upstream restructures the Compose viewmodel layer. |
| `android/.../ui/view/SettingsView.kt`, `viewModel/SettingsViewModel.kt`, `MainActivity.kt` | The per-app-exit-node nav entry. Upstream touches these whenever a setting is added. |
| `android/.../IPNReceiver.java`, `AndroidManifest.xml`, `App.kt` | The Disable Exit Node notification action wiring. |
| `android/.../DisableExitNodeWorker.kt` | Whole file. |
| `android/src/main/res/values/strings.xml` | Append-only; trivial. |

## Post-rebase verification

### Go fork

```bash
cd third_party/tailscale
go generate ./ipn/                # if prefs.go was touched
go build ./...
go test ./ipn/... ./wgengine/... ./net/connowner/ -count=1
git push --force-with-lease halil per-app-exit-node
```

`--force-with-lease` is mandatory (rebase rewrites history) and safer than
`--force`: it refuses if `halil` moved unexpectedly. If the lease is
"stale" because you never fetched `halil`, run `git fetch halil` first so
the remote-tracking ref is accurate, then push.

### Android client

After bumping the submodule pin or `go.mod`, do a clean rebuild — gomobile
caches the previous AAR. See the "Build & install" section in SKILL.md, then
run the four-quadrant test from SKILL.md end-to-end.

## Bumping the submodule pin

Pulling new commits into the Go fork is **not** reflected in the Android
repo automatically — advance the pin explicitly:

```bash
cd third_party/tailscale
git fetch halil && git checkout per-app-exit-node && git pull --ff-only halil per-app-exit-node
cd ../..
git add third_party/tailscale
git commit -m "Bump tailscale fork to <short-sha>"
```

This records the new gitlink (a SHA pointing into the submodule). Also bump
the `tailscale.com v…` pin in `go.mod` so external tooling sees a
consistent version string (the `replace` overrides it for local builds, but
`go mod tidy`/CI read it):

```bash
cd third_party/tailscale
COMMIT=$(git rev-parse HEAD)
DATE=$(git show -s --format=%cd --date=format:%Y%m%d%H%M%S HEAD)
echo "tailscale.com v1.99.0-pre.0.${DATE}-${COMMIT:0:12}"
# edit go.mod's `tailscale.com v...` line to that string, then:
cd .. && go mod tidy
```

If you pinned the Go fork on the exact upstream commit the parent already
references, `go mod tidy` reports no dependency churn.

## Common pitfalls

- **"PerAppExitNode is undefined" after a rebase**: skipped
  `go generate ./ipn/`. Run it inside `third_party/tailscale`.
- **`fatal: No url found for submodule path 'third_party/tailscale'`**:
  cloned without `--recurse-submodules` and never ran
  `git submodule update --init`. Fix with that command.
- **`go: ... tailscale.com: is replaced … but not marked as replaced in
  vendor/modules.txt`**: a top-level `vendor/` directory exists; Go treats
  that as vendor mode. We deliberately use `third_party/`, not `vendor/`,
  to avoid this — remove any stray `vendor/`.
- **Build picks up the wrong tailscale code**: check `go env GOFLAGS` isn't
  forcing `-mod=vendor`, and that `replace tailscale.com =>
  ./third_party/tailscale` is present in `go.mod`.
- **Submodule HEAD detached after `git submodule update`**: normal —
  submodules check out the pinned commit, not a branch. To develop inside
  it, `cd third_party/tailscale && git checkout per-app-exit-node` first.
- **`git push halil per-app-exit-node` rejected / lease stale**: after a
  rebase use `--force-with-lease`; if it reports stale info, `git fetch
  halil` first so the tracking ref is current.
- **`undefined: ipn.NotifyNoNetMap` (or similar) building the Android
  client**: the parent was rebased onto an upstream that needs a symbol the
  pinned Go fork predates. Rebase the Go fork onto the same upstream commit
  the parent's `go.mod` pins, then bump the submodule pin.
