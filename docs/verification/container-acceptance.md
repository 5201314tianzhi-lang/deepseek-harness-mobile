# Ubuntu container acceptance (on device)

Setup: fresh install of the APK from the v0.1.0 release (arm64-v8a).

1. Open the app → engine starts → open the web UI (normal flow unchanged).
2. Boot screen shows "Install Ubuntu container" → tap → AppLog shows
   "rootfs downloading ..." then "rootfs installed".
3. In the agent UI, ask for a shell command: `cat /etc/os-release`
   → expect `Ubuntu 24.04` (proot runs, container bash answers).
4. `id` → expect `uid=0(root)` (fake root via -0).
5. `apt-get update && apt-get install -y git nodejs python3` → expect success.
6. `git --version && node --version && python3 --version` → versions printed.
7. `pwd` → `/root/workspace`; create a file there,
   verify it appears in host `Documents/dshdata/workspace`.
8. Before install: command inside the app returns
   "Ubuntu container not installed" (no crash).
