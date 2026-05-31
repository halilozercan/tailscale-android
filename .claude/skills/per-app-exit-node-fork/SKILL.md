---
name: per-app-exit-node-fork
description: Architecture, build/test, and upstream-sync procedures for this per-app-exit-node fork of tailscale-android (Android client + a forked Go core vendored at third_party/tailscale). Use when working on the per-app exit node feature, rebasing or syncing either fork onto upstream tailscale, resolving rebase conflicts, bumping the third_party/tailscale submodule pin, building or installing the APK, or debugging per-app routing.
---

# Per-app exit node fork

This repo is a fork of `tailscale-android` carrying a **per-app exit node**
feature: route each Android app through its own exit node (e.g. YouTube via
Mullvad, everything else direct or via the global exit). The feature needs
source-based routing that doesn't exist in stock Tailscale, so it also
forks the Go core, vendored here as a git submodule at
`third_party/tailscale`.

- Feature design, the five cross-repo layers, files changed, and packet
  walkthrough: **[reference/design.md](reference/design.md)**.
- Fork topology, remotes, rebase/conflict details, pin bumps, pitfalls:
  **[reference/rebase.md](reference/rebase.md)**.

Read the relevant reference file before editing across the repo boundary
or doing a rebase — the conflict and watch-list detail there is what keeps
the feature from silently breaking on upstream sync.

## Branch and submodule model

- Feature work lives on branch **`per-app-exit-node`** in *both* the parent
  repo and the submodule. `main` in each is a clean mirror of upstream.
- `go.mod` has `replace tailscale.com => ./third_party/tailscale`, so the
  Android build always compiles the vendored Go fork.
- The submodule is pinned by commit. Advancing the Android client to a
  newer Go-fork commit is an explicit step (see "Bump the submodule pin").
- Pushes go to the `halil` remote in both repos. A fresh clone lands on
  `main` — `git checkout per-app-exit-node` before building.

## Build & install

Prereqs: `zip`, Android NDK 23, Android SDK.

```bash
ANDROID_HOME=/home/halilibo/Android/Sdk make tailscale-debug
/home/halilibo/Android/Sdk/platform-tools/adb install -r tailscale-debug.apk
```

gomobile caches the AAR aggressively. After **any** change to Go source or
the submodule pin, force a clean rebuild by deleting the cached artifacts
first (the Makefile doesn't track Go source timestamps for the `.so`):

```bash
rm -f libgojni.so.stripped libgojni.so.unstripped libgojni.so.debug \
      android/libs/libtailscale.aar android/libs/libtailscale_unstripped.aar
```

If the device shows more than one entry (e.g. an offline wireless adb),
target the USB device explicitly: `adb -s <serial> install -r ...`.

## Test the feature

1. Connect to your tailnet, leave the global exit node **off**.
2. Settings → Per-app exit nodes → tap an app → pick an exit node.
3. Toggle the VPN off and back on so the new `wgcfg` and routes install.
4. In that app open a fresh URL (`http://neverssl.com` avoids caching) and
   check a "what's my IP" page shows the chosen exit node's IP.

Cover all four quadrants of (global exit on/off) × (per-app rule set/unset).
The fragile one is **global off + per-app set**: non-configured apps must
still reach the public internet *and* keep tailnet access (e.g. Termux SSH
to a tailnet VPS). That path runs the netstack passthrough — see the
`shouldSendToHost` notes in reference/design.md.

`adb logcat -d -s gojni | grep perAppExitNode` shows the per-flow routing
decisions (`route-via-…`, `drop`, `no-rule`, `no-owner`).

> Note: the "Disable Exit Node" action is a persisted `OneTimeWorkRequest`.
> A stale one from a prior session can fire on app restart and clear a
> fresh selection — that's a drained-backlog artifact, not a routing bug.

## Sync a fork onto upstream

Both forks rebase the same way; conflict-zone detail is in
[reference/rebase.md](reference/rebase.md). Always pull the **same** upstream
tailscale revision into the Go fork that the Android client's `go.mod`
pins, so the two stay in lockstep.

### Go fork (`third_party/tailscale`)

```bash
cd third_party/tailscale
git remote get-url upstream >/dev/null 2>&1 || \
  git remote add upstream https://github.com/tailscale/tailscale.git
git fetch upstream
git checkout per-app-exit-node
git rebase upstream/main          # or: git rebase --onto <pin> <oldbase> per-app-exit-node
```

Resolve conflicts per reference/rebase.md, then verify and push:

```bash
go generate ./ipn/                # if prefs.go was touched
go build ./...
go test ./ipn/... ./wgengine/... ./net/connowner/ -count=1
git push --force-with-lease halil per-app-exit-node
```

### Android client (parent repo)

Here `origin` already is upstream, so rebase onto `origin/main` directly:

```bash
git fetch origin
git checkout per-app-exit-node
git rebase origin/main
git push --force-with-lease halil per-app-exit-node
```

Keep `main` a clean mirror — never put feature commits on it. If it
drifted: `git fetch origin && git branch -f main origin/main`.

## Bump the submodule pin

After the Go fork has new commits, advance the Android client's pin
explicitly (it is not automatic):

```bash
cd third_party/tailscale
git fetch halil && git checkout per-app-exit-node && git pull --ff-only halil per-app-exit-node
cd ../..
git add third_party/tailscale
git commit -m "Bump tailscale fork to <short-sha>"
```

Also update the `tailscale.com v…` line in `go.mod` to match the new
commit's pseudo-version (the `replace` overrides it for local builds, but
`go mod tidy` and CI read it), then `go mod tidy`. Ideally pin the Go fork
on the exact upstream commit the parent already references, so `go mod
tidy` reports no dependency churn. Finish with a clean rebuild (see "Build
& install") and the four-quadrant test.
