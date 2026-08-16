# Ubuntu container acceptance (on device)

Setup: fresh install of the APK from the v0.1.0 release (arm64-v8a).

1. First open shows the **consent card** (storage ≈600MB / time 2-5 min /
   notes) — nothing installs before "Agree & start". Tap it.
2. Step 1: snapshot extracts (progress on the wizard).
3. Step 2 (mandatory): boot shows "Install Ubuntu container" → AppLog shows
   "rootfs downloading ..." then "rootfs installed"; the container chain is
   smoke-tested (`ContainerProbe`, logs under `boot: container init`).
4. Step 3: press "Launch engine"; the web UI opens (cold start when already
   provisioned runs under the thin status bar whose pulse dot fades out ~6s
   after the engine answers).
5. In the agent UI, ask for a shell command: `cat /etc/os-release`
   → expect `Ubuntu 24.04` (proot runs, container bash answers).
6. `id` → expect `uid=0(root)` (fake root via -0).
7. `apt-get update && apt-get install -y git nodejs python3` → expect success.
8. `git --version && node --version && python3 --version` → versions printed.
9. `pwd` → `/root/projects` (pre-created workspace); create a file there,
   verify it appears in host `Documents/dshdata/projects`.
10. Container failure path: delete `files/rootfs` (adb run-as), reopen the app
    → reinstall flow runs, engine does NOT start until the smoke test passes.
11. Regression: directory pick, session export, check update —
    after the 2026-08-16 hardening pass (lifecycle/pickToken/reload changes).
