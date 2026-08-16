# deepseek-harness-mobile

Android shell for [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (dsh),
app name **深度编码**: a WebView UI over an **embedded Termux runtime snapshot**
(extract-and-run, no Termux app required), with a **proot Ubuntu container** for the
agent's shell, a SAF directory bridge, a keep-alive foreground service, an engine
watchdog, and manifest-driven online runtime updates. One APK installs a full dsh
web agent that can actually execute bash.

## Features

- **Embedded runtime** — ships a ~79MB APK whose `snapshot.tar.xz` asset extracts
  to ~484MB (`node` + `bash` + coreutils + dsh + plugins) on first launch. Fully
  offline after extraction.
- **Ubuntu container** — a proot-based Ubuntu 24.04 rootfs (~35MB, downloaded from
  the official Ubuntu mirrors on first run with SHA-256 verification) gives the
  agent a standard Linux environment: `apt`, system packages, root-like access.
  The agent's `bash` is routed into the container through a generated wrapper.
- **First-run consent gate** — before anything installs, the app shows the
  storage/time footprint and what to expect; the user must explicitly agree
  (nothing downloads or extracts otherwise).
- **Mobile UI** — white, three-step boot wizard (runtime → container → launch)
  over `http://127.0.0.1:3080`; external links are routed to the system browser,
  only engine-same-origin pages stay inside the WebView.
- **Keep-alive** — foreground service (`dataSync` type) with a user-visible
  notification plus a 5s watchdog that restarts a dead engine process. The engine
  lifecycle belongs to this service (the activity never kills it).
- **Online runtime updates** — HTTPS manifest-driven snapshot swap (download →
  SHA-256 verify → staged extraction → atomic switch with rollback →
  auto-restart via the watchdog); the running runtime can update itself without
  an APK update.
- **Universal exec layer** — a bundled `libexec-hook.so` (LD_PRELOAD) reroutes
  every same-ABI ELF exec in the engine tree through `/system/bin/linker64`, the
  mechanism Android permits for app data on all versions and vendors (Android 15+
  exec bans, Huawei/EMUI W^X). The main process uses a direct-exec → linker64
  fallback; extracted executables get the write bit stripped (rwx→r-x) for W^X
  compliance. A bundled `libunwind-patch.so` supplies `_Unwind_Resume` so
  node-pty loads.
- **SAF directory bridge** — `pickDirectory` maps a user-picked tree to the real
  path the container's bash can access directly.
- **Public user data** — settings, sessions, storages and attachments live in
  `/storage/emulated/0/Documents/dshdata` (visible to file managers, backed up,
  survives reinstall; API keys stay private).

## Architecture

| Component | File | Responsibility |
|---|---|---|
| `MainActivity` | `app/src/main/java/com/dshmobile/shell/MainActivity.kt` | Orchestration: first-run consent, boot flow, engine start, exports, update trigger |
| `GuideWizard` | `.../GuideWizard.kt` | White wizard UI: consent card, three steps, status card, cold-start top bar with pulse dot |
| `HarnessWebView` | `.../HarnessWebView.kt` | WebView config, engine-source navigation gate, compat-polyfill injection, reload-if-failed policy |
| `AndroidBridge` | `.../AndroidBridge.kt` | `window.androidBridge` JS interface (protocol v1) |
| `PickerBridge` | `.../PickerBridge.kt` | SAF directory/file picking; pending callback survives activity recreation |
| `ExportFlow` | `.../ExportFlow.kt` | In-app downloads to MediaStore Downloads (no redirect following) |
| `NotificationHelper` | `.../NotificationHelper.kt` | Notification channel + test notifications |
| `EngineManager` | `.../EngineManager.kt` | Snapshot extraction, dshdata migration/relinking, engine process env and lifecycle |
| `EngineService` | `.../EngineService.kt` | Foreground service: owns the engine lifecycle + 5s watchdog |
| `EngineProbe` | `.../EngineProbe.kt` | HTTP reachability probe of `127.0.0.1:3080` |
| `EngineSource` | `.../EngineSource.kt` | Engine-source URL/session-export matching |
| `ProotRuntime` | `.../ProotRuntime.kt` | Proot + libtalloc + libandroid-shmem assets, generated bash wrapper, env injection |
| `RootfsDownloader` | `.../RootfsDownloader.kt` | Ubuntu rootfs download, SHA-256 verify, staged atomic install |
| `ContainerProbe` | `.../ContainerProbe.kt` | Container smoke test (node → wrapper → proot → container bash) |
| `SnapshotExtractor` | `.../SnapshotExtractor.kt` | xz-tar extraction: traversal guard, symlinks, hard links, W^X write-bit strip, exec-attribute stamp |
| `UnwindResolver` | `.../UnwindResolver.kt` | `_Unwind_Resume` provider resolution + pty.node load diagnostics |
| `UpdateManager` | `.../UpdateManager.kt` | Runtime snapshot download/verify/swap (single-flight, unique staging) |
| `Downloader` | `.../Downloader.kt` | Shared HTTP download + SHA-256 |
| `DshPaths` | `.../DshPaths.kt` | Central registry of app-relative paths (no hardcoded package paths) |
| `ShizukuSupport` | `.../ShizukuSupport.kt` | Optional Shizuku presence/status detection (keep-alive boost) |

### First-run flow (`MainActivity.onCreate`)

1. **Consent gate** — on a fresh install the app shows the consent card
   (storage ≈ 600MB: ~79MB APK + ~484MB runtime + ~35MB container; time 2-5 min;
   what to expect). Nothing installs until "Agree & start"; "Exit" leaves the
   page (the activity is finished only on explicit exit). Agreement is recorded
   in `SharedPreferences` and survives upgrades.
2. **Step 1 — runtime**: extract the embedded snapshot to `filesDir/usr`
   (progress shown), then
3. **Step 2 — container** (mandatory): download the Ubuntu rootfs if missing
   (SHA-256 verified against `SHA256SUMS`), install proot + libtalloc +
   libandroid-shmem, generate the `usr/bin/bash` wrapper, and smoke-test the
   whole chain inside the container (`echo CONTAINER_OK; id`). A failing
   container counts as an engine-start failure.
4. **Step 3 — launch**: the user presses "Launch engine"; the engine starts
   (`node --expose-internals <usr>/lib/node_modules/@deepseek-ai/dsh/lib/bin.js
   web --port 3080`) and the page is polled for up to 60s.
5. **Quick path** — when everything is already provisioned, the app cold-starts
   straight into the Harness under a thin status bar (breathing pulse dot,
   fades out 6s after the engine answers).

The flow is guarded by an in-flight CAS flag (`onCreate` and `onResume` both
trigger it; a double-threaded extract/start would kill the engine process).

### Engine lifecycle (`EngineService` owns it)

- `EngineService` is a foreground service; its watchdog **always arms** once the
  service runs (poll every 5s; restart the engine when the probe fails and the
  snapshot is ready). Task bodies are fully guarded — a throwing tick never
  kills the watchdog.
- Process-wide `STARTING` CAS + a 90s cooldown window prevent double starts
  (cold node boot takes 20–45s). The cooldown is cleared when the tracked
  process is dead; a process still alive past the cooldown is considered hung
  and killed before a respawn (it would otherwise hold the port and every new
  start would die with `EADDRINUSE`).
- `MainActivity.onDestroy` never stops the engine — backgrounding must not kill
  a healthy process that the watchdog would then cold-boot again. The engine is
  stopped only when the service itself stops.
- If direct exec is denied (`Permission denied`, Android 15+), the process is
  spawned through `/system/bin/linker64` instead.
- The pick token (`DSH_PICK_TOKEN`) is a process-level singleton, so a
  watchdog-restarted engine keeps the same token the WebView bridge holds.

### Container integration

- Integration point: the agent's `dsh-bash-local` spawns `bash` from `PATH`;
  `usr/bin/bash` is a runtime-generated shebang wrapper that routes into proot.
  The original bash stays as `bash.termux`.
- The wrapper injects `LD_LIBRARY_PATH` (proot's libtalloc/libandroid-shmem),
  `PROOT_TMP_DIR` (proot needs a writable temp dir; the Termux default is
  inaccessible) and `TMPDIR` (container temp).
- `ContainerProbe` runs the exact agent chain (node → wrapper → proot → container
  bash) with a bounded 30s wait; failure = engine start failure.
- **Pre-provisioned workspace**: `/root/projects` (the agent's working
  directory, host-backed by `Documents/dshdata/projects`) is created with the
  container.
- **China mirror sources preconfigured** (once, editable): apt → Tsinghua
  TUNA (Aliyun alternative commented), pip → TUNA PyPI, npm → npmmirror,
  cargo → TUNA sparse registry, Go → goproxy.cn, RubyGems → TUNA, Composer →
  Aliyun, conda → TUNA. All written to each manager's standard config
  location, so they take effect immediately when the manager is installed —
  nothing needs setup after `apt install`.

### Storage layout

| Path | Purpose |
|---|---|
| `filesDir/usr` | Extracted runtime snapshot (node, bash, coreutils, dsh, plugins) |
| `filesDir/rootfs` | Ubuntu 24.04 container rootfs (`rootfs-staging`/`rootfs-old` during atomic swap) |
| `filesDir/home` | `HOME` for the engine; `filesDir/home/.dsh` is `DSH_HOME` (private, holds `.credentials.yaml`) |
| `filesDir/engine.log` | Engine stdout/stderr (redirected, merged) |
| `filesDir/update-<uuid>.tar.xz`, `update-stage-<uuid>`, `usr-old` | Runtime-update staging/rollback (unique names, always cleaned) |
| `filesDir/libexec-hook.so`, `unwind` assets | Exec-reroute hook + `_Unwind_Resume` patch lib |
| `/storage/emulated/0/Documents/dshdata` | User data: `settings.yaml`, `sessions/`, `storages/`, `attachments/`, `profiles/{web,headless}/` |

User data is migrated item-by-item from the private `DSH_HOME` to the public
directory (issue apk#8 rationale): `DSH_HOME` itself must stay private because
public FUSE forbids the symlinks dsh maintains under
`$DSH_HOME/profiles/node_modules`. Directories are moved and replaced by
private symlinks pointing at the public copies; `.credentials.yaml` is never
migrated (public FUSE forces mode 660, which the credentials-local permission
check rejects, and the key would leak to other apps). After a reinstall the
private symlinks are rebuilt idempotently so the public data becomes visible
again.

## Bridge protocol v1 (`window.androidBridge`)

| Method | Signature | Description |
|---|---|---|
| `version` | getter → string | Bridge protocol version (`"1.0"`) for feature detection |
| `checkEngine` | () → string | Probes 127.0.0.1:3080; JSON `{running, latencyMs, error?}` |
| `keepScreenOn` | (enable: boolean) | Screen-on wake lock (single shared instance, released on activity destroy) |
| `showNotification` | (title, text) | Test notification channel (POST_NOTIFICATIONS requested at runtime; queued and re-sent after grant) |
| `pickDirectory` | (callbackId: string) | SAF tree picker (ACTION_OPEN_DOCUMENT_TREE); result delivered async |
| `hasAllFilesAccess` | () → boolean | True when the app holds All Files Access (API 30+) |
| `requestAllFilesAccess` | () → void | Opens the system All Files Access screen |
| `getPickToken` | () → string/null | Process-wide session token for the engine-side pick endpoint (stable across engine restarts) |

Async results are delivered back to the page:

- `window.__dshBridge.onDirectoryPicked(callbackId, path|null)` — pick
  result; `null` means cancelled or unavailable (API < 30, permission flow, or
  a pick already in flight).
- `window.__dshBridge.onPermissionRequired()` — the app lacks All Files
  Access; the page should prompt the user to grant it and retry.
- `window.__dshExportResult(ok, title, detail)` — session-log export result.
- `window.__dshThemeBridge.setDark(boolean)` — system dark-mode push (some OEM
  WebViews do not reflect `uiMode`; consumed by a matchMedia hook).

The bridge decouples the APK from the dsh version: pages feature-detect on
`androidBridge.version`.

### Directory picking and All Files Access

External workspaces require the container's bash to reach the picked real path:
the engine env carries `DSH_PICK_TOKEN` and the web-compat plugin validates it
as `x-dsh-pick-token`. On API 30+ without All Files Access the app opens the
system grant screen and signals `onPermissionRequired`; on API < 30 the pick
settles as cancelled (no such permission model, external workspace
unavailable). The primary volume maps to the runtime-derived external storage
path (no hardcoded `/storage/emulated/0`).

### Session-log export and downloads

Engine-same-origin downloads (`/api/session.export` and everything else from
127.0.0.1:3080) are performed in-app over `HttpURLConnection` (redirects
disabled — a redirect target is not trusted) and written streaming to MediaStore
Downloads (API 29+, no permission needed) with a 200MB cap. Rationale: browser
navigations carry `Origin: null` / `sec-fetch-site` markers and are rejected
(403) by dsh's `/api` browser-trust fence; the in-app connection carries no
browser markers and passes. Downloads are deduplicated across the two entry
points (`shouldOverrideUrlLoading` + download listener) by an in-flight guard.

### WebView security boundary

- Only engine-same-origin URLs (exact scheme/host/port match) stay in the
  WebView; everything else opens in the system browser, so untrusted pages can
  never reach the privileged bridge.
- The session-export path is matched exactly (`/api/session.export`), not by
  prefix.
- `allowFileAccess=false`, mixed content never allowed, `FORCE_DARK_AUTO` for
  system theme following.
- The page is reloaded only when it previously failed to load (error page shown
  before the engine answered); healthy pages keep their state across
  foreground returns.
- A JS compatibility layer (`assets/js/compat-polyfills.js`) is injected before
  page scripts on old WebViews (AbortSignal.any, Promise.any, structuredClone,
  groupBy, …), all feature-detected.
- Cleartext traffic is restricted to `127.0.0.1`/`localhost` via
  `network_security_config.xml`; everything else requires TLS.

## Online runtime update protocol

1. The app fetches `manifest.json` over **HTTPS**: `{url, sha256, size}`.
   The manifest URL and the snapshot URL are both enforced HTTPS; a missing
   `sha256` rejects the update (no integrity protection otherwise).
2. The snapshot is downloaded streaming with a 500MB cap, SHA-256 is verified
   against the manifest.
3. It is extracted to a **unique** staging directory (`update-stage-<uuid>`,
   never touching the live tree; concurrent runs are single-flighted), the new
   `usr` is validated (must contain `bin/node`), then swapped:
   `usr → usr-old → new usr`, with rollback if the swap fails. Staging and
   tarball are always cleaned up.
4. The old engine process is killed (`pkill -f bin.js`); if the kill fails the
   user is told to restart the app (the watchdog only restarts a dead engine).
   Otherwise the EngineService watchdog restarts it from the new runtime within
   seconds.

Test trigger: `adb shell am start -n com.dshmobile.shell/.MainActivity -a com.dshmobile.shell.action.UPDATE`
(debug builds only — the activity is exported as the LAUNCHER, so release
builds ignore the intent to prevent external download+execute triggers).
Status is written to `files/update-status.txt`. The default manifest URL
(`https://10.0.2.2:8899/manifest.json`) targets the emulator host loopback;
production builds override it via `UpdateManager.manifestUrl`.

## Build

Requirements: JDK 17+, Android SDK (compileSdk 36), Gradle 9.7.0 via wrapper.

```sh
# 1. Prepare the runtime snapshot (required, distributed as a CI asset)
#    The release workflow downloads snapshot-arm64.tar.xz / snapshot-x86_64.tar.xz
#    from the upstream releases and bundles it into assets/.
mkdir -p app/src/main/assets
cp snapshot/snapshot.tar.xz app/src/main/assets/snapshot.tar.xz

# 2. Build (fails loudly when the snapshot is missing)
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

Release builds (CI) additionally pass:

```sh
./gradlew assembleRelease \
  -PversionName=0.1.0 -PversionCode=100 \   # derived from the release tag
  -PabiFilter=arm64-v8a                      # one ABI per matrix leg
```

### Quality gate (CI)

Every push to `main` and every PR runs `.github/workflows/ci.yml`:
`./gradlew assembleDebug lintDebug ktlintCheck` — compile, Android lint
(`abortOnError`, debug variant) and ktlint (android ruleset via
`.editorconfig`) must all pass or the change is blocked. ktlint runs from
Maven Central (`com.pinterest.ktlint:ktlint-cli`), not the plugin portal, so
it works in CN networks too; auto-format with `./gradlew ktlintFormat`
before committing.

Build config: AGP 9.3.1, Kotlin 2.4.10, minSdk 26, targetSdk 34 (Android 15+
app-data ELF exec restrictions are covered by the linker64 fallback). The
extractor strips the write bit from executables (W^X: Huawei/EMUI refuse to
execute writable files). `snapshot.tar.xz` is excluded from resource
compression (`noCompress += "xz"`); lint is non-blocking for offline
environments. **The signing keystore lives only in the repo secret
`RELEASE_KEYSTORE_B64`** — the workflow refuses to build without it (never
publishes or generates keys).

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | WebView + engine probe + rootfs/update downloads |
| `MANAGE_EXTERNAL_STORAGE` | External workspace: container bash reaches user-picked directories. On Android 11+ this is granted at install time (All Files Access); on Android 10 and below the model does not exist and the external workspace is unavailable |
| `POST_NOTIFICATIONS` | Notification channel (requested at runtime on API 33+) |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | Keep-alive service (`dataSync` type) |

SAF directory picking needs no permission (the user authorizes the tree URI
through the system picker).

## ABI & pagesize

Releases publish one APK per ABI (the embedded snapshot is architecture-
specific):

- **arm64-v8a** — for ARM64 phones/tablets (most real devices)
- **x86_64** — for x86_64 emulators (MuMu, LDPlayer, etc.)

The x86_64 snapshot is verified end-to-end; arm64 snapshots are assembled from
the official Termux aarch64 repo (see `docs/design.md` §ABI). A 16KB-page build
must be produced on a 16KB device. Choose the APK matching your device's ABI —
installing the wrong one extracts fine but the engine cannot execute.

## Known limitations

- Keep-alive is best-effort: aggressive OEM battery managers may still kill
  the service; the Shizuku boost currently only reports status (the
  appops-application step needs the shell-exec API, deferred).
- Directory picking maps only the `primary` volume to a real path; other
  volumes fall back to the opaque `content://` tree URI.
- The engine restarts from the new runtime only after the watchdog's next poll
  (up to ~5s after the swap), and only if the old process was successfully
  killed; a missed kill surfaces a restart hint.
- The Ubuntu container needs network on first run (~35MB from
  cdimage.ubuntu.com); a failed checksum fetch refuses the install (no
  unverified rootfs).

## Related projects

- [dsh-shell-termux](https://github.com/kelai141/dsh-shell-termux) — shell
- [dsh-client-ui-responsive](https://github.com/kelai141/dsh-client-ui-responsive) — mobile UI
- [dsh-host-web-compat](https://github.com/kelai141/dsh-host-web-compat) — browser compatibility

## License

MIT. Copyright (c) 2026 kelai141 (upstream), Copyright (c) 2026 lemonhub-io.
Contains third-party components under their own licenses (see dependency
declarations). Design rationale: `docs/design.md`; review log: `docs/issues.md`.
