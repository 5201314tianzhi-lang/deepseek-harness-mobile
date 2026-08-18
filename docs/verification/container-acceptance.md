# Container acceptance (on device)

Setup: fresh install of the APK from the v0.2.0 release (arm64-v8a only).

1. First open goes straight into the install flow (no consent gate): the
   wizard shows "Preparing runtime…".
2. Step 1: rootfs extracts from the embedded asset (progress on the wizard) —
   no download happens at any point.
3. Step 2 (mandatory): `ContainerProbe` smoke-tests proot + container bash
   (`echo CONTAINER_OK; id -u`); failure = engine-start failure.
4. Step 3: press "Launch engine"; the web UI opens (cold start when already
   provisioned runs under the thin status bar whose pulse dot fades out ~6s
   after the engine answers).
5. In the agent UI, ask for a shell command: `cat /etc/os-release`
   → expect `Debian GNU/Linux 12 (bookworm)` (proot runs, container bash
   answers).
6. `id` → expect `uid=0(root)` (fake root via -0).
7. `apt-get update && apt-get install -y git python3` → expect success.
8. `git --version && python3 --version` → versions printed.
9. `pwd` → `/root/projects` (pre-created workspace); create a file there,
   verify it appears in host `Documents/dshdata/projects`.
10. Engine-in-container check: `ps -ef` shows the dsh engine running as a
    proot child; `curl -s http://127.0.0.1:3080/api/health` answers from
    inside the container.
11. Container failure path: delete `files/rootfs` (adb run-as), reopen the app
    → reinstall flow runs, engine does NOT start until the smoke test passes.
12. Update path: trigger `am start -n com.dshmobile.shell/.MainActivity
    -a com.dshmobile.shell.action.UPDATE` (debug build) with the default
    manifest (dsh-io/dsh-arm64 latest) → status file reports success, engine
    restarts from the new rootfs.

Android 15+ / W^X probe (must be run on a real Android 15+ or Huawei device):

13. Fresh install, first launch → if proot exec is denied, AppLog shows the
    linker64 fallback path and the engine still boots; if neither works, the
    wizard reports "Runtime failed to initialize" — do not ship this release
    for that device class until resolved.
