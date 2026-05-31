# Fork Maintenance

How the forks are wired together, what to do after cloning, and how to pull
upstream changes without breaking the per-app-exit-node feature.

## Topology

Two forks, coupled by one submodule pin:

```
github.com/tailscale/tailscale-android   ← upstream Android client
        │
        │ (forked)
        ▼
github.com/halilozercan/tailscale-android   ← this repo
        │
        │ go.mod: replace tailscale.com => ./third_party/tailscale
        │ .gitmodules: third_party/tailscale → halilozercan/tailscale
        ▼
   third_party/tailscale (git submodule, pinned commit)
        │
        ▼
github.com/halilozercan/tailscale   ← Go fork (branch: per-app-exit-node)
        │
        │ (forked, periodically rebased)
        ▼
github.com/tailscale/tailscale   ← upstream Go core
```

The submodule pins a specific commit of the Go fork. Bumping the Android
client to a newer Go-fork commit is an explicit step (see below).

## Remotes

The two repos have **different** remote layouts, because the submodule is
cloned from a personal-fork URL (see `.gitmodules`) while the parent is a
plain clone of upstream. Don't assume they match.

### Parent (`tailscale-android`)

| Remote   | URL                                                | Purpose                    |
|----------|----------------------------------------------------|----------------------------|
| `origin` | `https://github.com/tailscale/tailscale-android/`  | Pull from upstream         |
| `halil`  | `git@github.com:halilozercan/tailscale-android.git`| Push personal feature work |

Here `origin` *is* upstream, so `git fetch origin` / `git rebase
origin/main` sync against upstream with no extra remote. There is no
separate `upstream` remote.

### Submodule (`third_party/tailscale`)

| Remote     | URL                                          | Purpose                    |
|------------|----------------------------------------------|----------------------------|
| `origin`   | `git@github.com:halilozercan/tailscale.git`  | Personal fork (clone URL)  |
| `halil`    | `git@github.com:halilozercan/tailscale.git`  | Personal fork (push alias) |
| `upstream` | `https://github.com/tailscale/tailscale.git` | Pull from upstream core    |

Here `origin` points at the **personal fork**, not upstream — that's the
URL in `.gitmodules`, and `git submodule update` names it `origin`. So
upstream lives under a separate `upstream` remote, and you rebase against
`upstream/main` (not `origin/main`). `halil` is a convenience alias for
the same fork as `origin`, so the push commands below read consistently
across both repos.

In both repos, **pushes always go to `halil`** and upstream syncs pull
from whichever remote points at upstream (parent: `origin`; submodule:
`upstream`).

If a clone is missing these remotes, add them:

```bash
# In the tailscale-android repo (origin already = upstream)
git remote add halil git@github.com:halilozercan/tailscale-android.git

# In the submodule (origin = personal fork from .gitmodules)
cd third_party/tailscale
git remote add halil    git@github.com:halilozercan/tailscale.git
git remote add upstream https://github.com/tailscale/tailscale.git
```

## Initial setup (fresh clone)

```bash
git clone git@github.com:halilozercan/tailscale-android.git
cd tailscale-android
git checkout per-app-exit-node          # the feature work lives here, not main
git submodule update --init --recursive
```

The feature work lives on the **`per-app-exit-node`** branch in both the
parent and the submodule; `main` in each is a clean mirror of upstream.
A fresh clone lands on `main`, so check out the feature branch before
building. (The submodule is pinned by commit, so it checks out the right
Go-fork revision regardless of branch — see "Submodule HEAD detached"
under Common pitfalls if you want to develop inside it.)

`--init` populates the submodule's working tree from the URL in
`.gitmodules`; `--recursive` is defensive in case the Go fork ever gains
nested submodules. Without this step `third_party/tailscale` is an empty
directory and the build will fail at the gomobile step.

To always pull submodules on `git pull` without remembering the flag:

```bash
git config submodule.recurse true   # repo-local
```

Verify the build works end-to-end:

```bash
ANDROID_HOME=/path/to/Android/Sdk make tailscale-debug
```

## Routine sync: pulling upstream into the Go fork

Tailscale's Go core moves fast. Rebase the feature branch onto upstream
every few weeks; longer than that and conflicts in `ipn/prefs.go` /
`ipn/ipnlocal/local.go` get painful.

```bash
cd third_party/tailscale

# Add upstream once (idempotent)
git remote get-url upstream >/dev/null 2>&1 || \
  git remote add upstream https://github.com/tailscale/tailscale.git

git fetch upstream
git checkout per-app-exit-node
git rebase upstream/main
```

### Expected conflict zones

1. `ipn/prefs.go` — `Prefs` / `MaskedPrefs` struct fields churn often.
   Re-add `PerAppExitNode` and `PerAppExitNodeSet` if upstream removed
   them during a merge resolution.
2. `ipn/ipnlocal/local.go` — large file with many active hands. The
   conflict-prone sites are `routerConfigLocked` (the exit-node /0 +
   per-app-exit-node block), the registration of
   `lookupPerAppExitNodePeer` near where `lookupPeerByIP` is set, and
   the `ShouldPassthroughForApp` method.
3. `wgengine/wgcfg/nmcfg/nmcfg.go` — `WGCfg`'s signature takes our
   variadic per-app stable IDs and sets `cpeer.IsExitNode`. If upstream
   changes the signature, reapply both.
4. `wgengine/wgcfg/config.go`, `wgengine/wgcfg/wgcfg_clone.go` — the
   `IsExitNode bool` field on `Peer` plus the corresponding clone
   regeneration sentinel. Also update `wgengine/wgcfg/config_test.go`:
   `TestPeerEqual` reflects over `Peer`'s fields against a hardcoded
   allowlist, so `"IsExitNode"` must be in that `case` list or the test
   fails with "Have you added field … to Peer.Equal?".
5. `wgengine/userspace.go` — the BART trie /0 filter and the
   `perAppPeerOverride` atomic + closure.
6. `wgengine/wgengine.go` — `Engine` interface addition for
   `SetPerAppPeerOverrideFunc`. **Note:** upstream removed the Engine
   watchdog (commit `2b338dd6a`), so `wgengine/watchdog.go` is gone. On
   rebase you'll get a modify/delete conflict there — resolve it with
   `git rm wgengine/watchdog.go`; the real method lives on
   `userspaceEngine` (userspace.go) and the interface, not a wrapper.
7. `wgengine/netstack/netstack.go` — `passthroughDecider` field +
   wiring, `netns.NewDialer`/`netns.Listener` in `forwardTCP` /
   `forwardUDP`, and the `shouldSendToHost` block that returns true for
   `dst=local-tailnet-IP` with a **genuine public source**
   (`!ns.isLocalIP(src) && !tsaddr.IsTailscaleIP(src)`). The
   `!tsaddr.IsTailscaleIP` guard is load-bearing: without it the
   `TestShouldSendToHost/other_4via6_to_local` case fails, because a
   4via6 source (`fd7a:115c:a1e0::/48`) would be wrongly diverted to the
   host instead of staying on WireGuard.
8. `ipn/ipnlocal/state_test.go` — `mockEngine` must stub the new
   `SetPerAppPeerOverrideFunc`.
9. Generated files `ipn/ipn_clone.go` and `ipn/ipn_view.go` — **never
   hand-resolve.** Take either side during rebase, then regenerate:

   ```bash
   git checkout --theirs ipn/ipn_clone.go ipn/ipn_view.go
   git add ipn/ipn_clone.go ipn/ipn_view.go
   ```

   After the rebase finishes, run `go generate ./ipn/` to overwrite
   them from the rebased `prefs.go`.

### Post-rebase verification (Go fork)

```bash
cd third_party/tailscale

# Codegen, if prefs.go was touched
go generate ./ipn/

# Smoke build everything
go build ./...

# Tests that exercise the touched packages
go test ./ipn/... ./wgengine/... -count=1
```

If `TestPrefsEqual` fails complaining the handled list is out of sync,
add `"PerAppExitNode"` back to the `prefsHandles` slice in
`ipn/prefs_test.go` — a resolve probably dropped it.

### Push the rebased Go fork

```bash
cd third_party/tailscale
git push --force-with-lease halil per-app-exit-node
```

`--force-with-lease` is mandatory because rebase rewrites history.
`--force-with-lease` (over plain `--force`) refuses the push if `halil`
has moved unexpectedly — cheap insurance against clobbering a parallel
push.

## Routine sync: pulling upstream into the Android client

In the parent repo `origin` already points at upstream, so sync against
`origin/main` directly — no `upstream` remote needed here (unlike the
submodule).

```bash
cd /path/to/tailscale-android   # back out of any submodule

git fetch origin
git checkout per-app-exit-node
git rebase origin/main
```

After the rebase, push the feature branch (history was rewritten):

```bash
git push --force-with-lease halil per-app-exit-node
```

`main` stays a clean mirror of upstream — don't put feature commits on
it. If it has drifted, reset it: `git fetch origin && git branch -f main
origin/main`.

### Expected conflict zones

| File | What's ours that needs to survive |
|---|---|
| `go.mod` | The `replace tailscale.com => ./third_party/tailscale` line — keep it. Upstream may bump the `tailscale.com vX.Y.Z-pre.0...` pin; take theirs. |
| `libtailscale/interfaces.go` | Our `LookupPackageByFlow(protocol, srcAddr, srcPort, dstAddr, dstPort) string` method on the `IPNService` interface. |
| `libtailscale/backend.go` | The two `connowner.SetLookupFunc(...)` calls in the VPN-start and VPN-stop blocks, alongside the existing `netns.SetAndroidProtectFunc` calls. |
| `android/.../IPNService.kt` | `lookupPackageByFlow` implementation + its `LruCache` cache + imports. |
| `android/.../ui/model/Ipn.kt` | `PerAppExitNode` fields on `Prefs` and `MaskedPrefs`; the `deepCopy` clone. |
| `android/.../ui/view/PerAppExitNodeView.kt`, `PerAppExitNodePickerView.kt`, `viewModel/PerAppExitNodeViewModel.kt` | Whole files — generally won't conflict unless upstream restructures the Compose viewmodel layer. |
| `android/.../ui/view/SettingsView.kt`, `viewModel/SettingsViewModel.kt`, `MainActivity.kt` | The per-app-exit-node nav entry. Upstream touches these whenever a new setting is added. |
| `android/.../IPNReceiver.java`, `AndroidManifest.xml`, `App.kt` | The Disable Exit Node notification action wiring. |
| `android/.../DisableExitNodeWorker.kt` | Whole file. |
| `android/src/main/res/values/strings.xml` | Append-only conflicts; trivial. |

## Bumping the submodule pin

The submodule points at a specific commit. Pulling new upstream commits
into the Go fork is **not** automatically reflected in the Android repo —
you have to advance the pin explicitly.

```bash
cd third_party/tailscale
git fetch halil
git checkout per-app-exit-node
git pull halil per-app-exit-node     # fast-forward to the new HEAD
cd ../..

# At this point `git status` in the parent shows the submodule as modified.
git add third_party/tailscale
git commit -m "Bump tailscale fork to <short-sha>"
```

This commit records the new gitlink (a 20-byte SHA pointing into the
submodule). Anyone who pulls and runs `git submodule update` will
fast-forward their submodule worktree to match.

Also bump the `tailscale.com v…` pin in `go.mod` so external tooling
sees a consistent version string. The `replace` directive overrides it
during local builds, but `go mod tidy` and CI care about it:

```bash
cd third_party/tailscale
COMMIT=$(git rev-parse HEAD)
DATE=$(git show -s --format=%cd --date=format:%Y%m%d%H%M%S HEAD)
echo "tailscale.com v1.97.0-pre.0.${DATE}-${COMMIT:0:12}"

# Edit go.mod's `tailscale.com v...` line by hand to that version string.

cd ..
go mod tidy
```

## Post-bump verification (Android)

Always do a full clean rebuild after touching the submodule or `go.mod`
— gomobile caches the previous AAR aggressively:

```bash
cd /path/to/tailscale-android

rm -f libgojni.so.stripped libgojni.so.unstripped libgojni.so.debug \
      android/libs/libtailscale.aar android/libs/libtailscale_unstripped.aar

ANDROID_HOME=/path/to/Android/Sdk make tailscale-debug
adb install -r tailscale-debug.apk
```

Then run the testing flow from `PER_APP_EXIT_NODE.md` end-to-end so
you're sure the feature still works after the upstream sync.

## Common pitfalls

- **"PerAppExitNode is undefined" at build time after a rebase**: You
  skipped `go generate ./ipn/`. Run it inside `third_party/tailscale`.
- **`fatal: No url found for submodule path 'third_party/tailscale'`**:
  Someone cloned the repo without `--recurse-submodules` and never ran
  `git submodule update --init`. Fix with that command.
- **`go: errors parsing go.mod: ... tailscale.com: is replaced … but
  not marked as replaced in vendor/modules.txt`**: This means a top-level
  `vendor/` directory exists. Go interprets that as vendor mode and
  expects `modules.txt`. Remove the directory (we deliberately use
  `third_party/`, not `vendor/`, to avoid this).
- **Build picks up the wrong tailscale code**: Check `go env GOFLAGS`
  isn't forcing `-mod=vendor`, and that `replace tailscale.com =>
  ./third_party/tailscale` is the last line of the require block in
  `go.mod`.
- **Submodule HEAD detached after `git submodule update`**: Normal —
  submodules check out the pinned commit, not a branch. If you want to
  develop in the submodule, `cd` in and `git checkout
  per-app-exit-node` before making changes.
- **`git push halil per-app-exit-node` rejected**: After a rebase you
  need `--force-with-lease`. Plain `--force` works but is dangerous; the
  lease variant refuses if the remote has moved.
