# deepseek-harness-mobile

Android shell for [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (dsh),
app name **深度编码**: a WebView UI over a **single embedded Debian glibc rootfs**
(extract-and-run, no Termux app required) in which the dsh **engine and the agent
shell share one proot container**, plus a SAF directory bridge, a keep-alive
foreground service, an engine watchdog, and manifest-driven online runtime
updates. One APK installs a full dsh web agent that can actually execute bash.

## Features

- **Single embedded runtime** — ships a ~80MB APK whose `rootfs.tar.xz` asset
  extracts to `filesDir/rootfs` on first launch: Debian bookworm with glibc
  Node 22, dsh + plugins, bash, coreutils, apt and pre-provisioned China
  mirrors. Fully offline after extraction — no download at any point.
- **Engine inside the container** — the dsh web engine boots *inside* the
  rootfs via proot (`node --expose-internals … web --port 3080`), so the
  agent's bash runs in the same glibc environment that executes the engine:
  one runtime, one container, no wrappers, no exec hooks.
- **Supply-chain controlled** — the rootfs and the dsh overlay are built in
  dsh-io/dsh-arm64 from vendored npm tarballs (integrity-pinned lockfile,
  offline install); updates come from our own release with mandatory SHA-256.
- **Mobile UI** — white, three-step boot wizard (runtime → container smoke →
  launch) over `http://127.0.0.1:3080`; external links are routed to the
  system browser, only engine-same-origin pages stay inside the WebView.
- **Keep-alive** — foreground service (`dataSync` type) with a user-visible
  notification plus a 5s watchdog that restarts a dead engine process. The
  engine lifecycle belongs to this service (the activity never kills it).
- **Online runtime updates** — HTTPS manifest-driven rootfs swap (download →
  SHA-256 verify → staged extraction → atomic switch with rollback →
  auto-restart via the watchdog); the running runtime can update itself
  without an APK update.
- **SAF directory bridge** — `pickDirectory` maps a user-picked tree to the
  real path the container's bash can access directly.
- **Public user data** — settings, sessions, storages and attachments live in
  `/storage/emulated/0/Documents/dshdata` (visible to file managers, backed
  up, survives reinstall; API keys stay private).

## Architecture

| Component | File | Responsibility |
|---|---|---|
| `MainActivity` | `app/src/main/java/com/dshmobile/shell/MainActivity.kt` | Orchestration: boot flow, engine start, exports, update trigger |
| `GuideWizard` | `.../GuideWizard.kt` | White wizard UI: three steps, status card, cold-start top bar with pulse dot |
| `HarnessWebView` | `.../HarnessWebView.kt` | WebView config, engine-source navigation gate, compat-polyfill injection, reload-if-failed policy |
| `AndroidBridge` | `.../AndroidBridge.kt` | `window.androidBridge` JS interface (protocol v1) |
| `PickerBridge` | `.../PickerBridge.kt` | SAF directory/file picking; pending callback survives activity recreation |
| `ExportFlow` | `.../ExportFlow.kt` | In-app downloads to MediaStore Downloads (no redirect following) |
| `NotificationHelper` | `.../NotificationHelper.kt` | Notification channel + test notifications |
| `AppLog` | `.../AppLog.kt` | Client-visible diagnostic log (bounded ring buffer + log file, clipboard copy) |
| `EngineManager` | `.../EngineManager.kt` | Rootfs extraction, dshdata migration/relinking, engine process env and lifecycle |
| `EngineService` | `.../EngineService.kt` | Foreground service: owns the engine lifecycle + 5s watchdog |
| `EngineProbe` | `.../EngineProbe.kt` | HTTP reachability probe of `127.0.0.1:3080` |
| `EngineSource` | `.../EngineSource.kt` | Engine-source URL/session-export matching |
| `ProotRuntime` | `.../ProotRuntime.kt` | Proot + libtalloc + libandroid-shmem assets, proot engine command builder |
| `ContainerProbe` | `.../ContainerProbe.kt` | Container smoke test (proot → container bash) |
| `SnapshotExtractor` | `.../SnapshotExtractor.kt` | xz-tar extraction: traversal guard, symlinks, hard links, W^X write-bit strip, exec-attribute stamp |
| `UpdateManager` | `.../UpdateManager.kt` | Runtime rootfs download/verify/swap (single-flight, unique staging) |
| `Downloader` | `.../Downloader.kt` | Shared HTTP download + SHA-256 |
| `DshPaths` | `.../DshPaths.kt` | Central registry of app-relative paths (no hardcoded package paths) |
| `ShizukuSupport` | `.../ShizukuSupport.kt` | Shizuku server/permission detection + appops background-exemption boost flow |
| `KeepAliveUserService` | `.../KeepAliveUserService.kt` | Shizuku user service (shell identity) that applies the appops keep-alive exemptions |
| `KeepAliveAlarm` | `.../KeepAliveAlarm.kt` | 30-min self-re-arming heartbeat alarm (+ `KeepAliveAlarmReceiver`) |
| `BootReceiver` | `.../BootReceiver.kt` | Boot / upgrade / power / unlock re-raise the foreground service |

### First-run flow (`MainActivity.onCreate`)

1. **Step 1 — runtime**: extract the embedded rootfs to `filesDir/rootfs`
   (progress shown; the rootfs already contains node, dsh, bash and apt), then
2. **Step 2 — container smoke**: `proot -0 -r <rootfs>` must answer
   `echo CONTAINER_OK; id` inside the container; this is the exact engine
   invocation chain minus the node command. A failing container counts as an
   engine-start failure.
3. **Step 3 — launch**: the user presses "Launch engine"; the engine starts
   *inside* the container (`proot … node --expose-internals
   /root/.dsh-arm64/node_modules/@deepseek-ai/dsh/lib/bin.js web --port 3080`)
   and the page is polled for up to 60s.
4. **Quick path** — when everything is already provisioned, the app cold-starts
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

- Single container: the engine runs under `proot -0 -r filesDir/rootfs` and
  the agent's `dsh-bash-local` spawns the very same container's `/usr/bin/bash`
  — one glibc world for both.
- `ProotRuntime` builds the proot command line: bind-mounts `/dev`, `/proc`,
  `/sys` and a host-backed resolv.conf; `/root/projects` is host-backed by
  `Documents/dshdata/projects` so user data survives and the container can
  reach picked directories.
- The container's bash needs no wrapper: the same `bash` binary that the
  engine's pty spawns is the container bash (`/root/.dsh-arm64` is bind-visible
  inside the rootfs via its own directory).
- **Pre-provisioned workspace**: `/root/projects` (the agent's working
  directory) is created with the container.
- **China mirror sources preconfigured** (once, editable): apt → Tsinghua
  TUNA (Aliyun alternative commented), pip → TUNA PyPI, npm → npmmirror,
  cargo → TUNA sparse registry, Go → goproxy.cn, RubyGems → TUNA, Composer →
  Aliyun, conda → TUNA. All written to each manager's standard config
  location, so they take effect immediately when the manager is installed —
  nothing needs setup after `apt install`.

### Storage layout

| Path | Purpose |
|---|---|
| `filesDir/rootfs` | Extracted Debian bookworm rootfs (glibc node, bash, coreutils, dsh, plugins, apt) |
| `filesDir/rootfs-old`, `rootfs-staging`, `update-<uuid>.tar.xz` | Rootfs-update staging/rollback (unique names, always cleaned) |
| `filesDir/proot` | Proot binary + libtalloc + libandroid-shmem assets |
| `filesDir/home` | `HOME` for the engine process; `filesDir/home/.dsh` is `DSH_HOME` (private, holds `.credentials.yaml`) |
| `filesDir/engine.log` | Engine stdout/stderr (redirected, merged) |
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

1. The app fetches `manifest.json` over **HTTPS** from the default URL
   `https://github.com/dsh-io/dsh-arm64/releases/latest/download/manifest.json`:
   `{url, sha256, size}`. The manifest URL and the rootfs URL are both enforced
   HTTPS; a missing `sha256` rejects the update (no integrity protection
   otherwise).
2. The rootfs is downloaded streaming with a 500MB cap, SHA-256 is verified
   against the manifest.
3. It is extracted to a **unique** staging directory (`update-stage-<uuid>`,
   never touching the live tree; concurrent runs are single-flighted), the new
   rootfs is validated (must contain `usr/local/bin/node`), then swapped:
   `rootfs → rootfs-old → new rootfs`, with rollback if the swap fails.
   Staging and tarball are always cleaned up.
4. The old engine process is killed (`pkill -f bin.js`); if the kill fails the
   user is told to restart the app (the watchdog only restarts a dead engine).
   Otherwise the EngineService watchdog restarts it from the new runtime within
   seconds.

Test trigger: `adb shell am start -n com.dshmobile.shell/.MainActivity -a com.dshmobile.shell.action.UPDATE`
(debug builds only — the activity is exported as the LAUNCHER, so release
builds ignore the intent to prevent external download+execute triggers).
Status is written to `files/update-status.txt`.

## Build

Requirements: JDK 17+, Android SDK (compileSdk 36), Gradle 9.7.0 via wrapper.

```sh
# 1. Prepare the runtime rootfs (required, distributed as a CI asset)
#    The CI/release workflow downloads dsh-arm64-rootfs-*.tar.xz from the
#    dsh-io/dsh-arm64 release and bundles it into assets/.
mkdir -p app/src/main/assets
cp rootfs/rootfs.tar.xz app/src/main/assets/rootfs.tar.xz

# 2. Build (fails loudly when the rootfs is missing)
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
`./gradlew assembleDebug lintDebug ktlintCheck testDebugUnitTest` plus
`./tests/run-local.sh` — compile, Android lint (`abortOnError`, debug variant),
ktlint (android ruleset via `.editorconfig`), the JVM unit tests (JUnit4 +
Robolectric + mockk) and the JS/C local tests must all pass or the change is
blocked. ktlint runs from Maven Central (`com.pinterest.ktlint:ktlint-cli`),
not the plugin portal, so it works in CN networks too; auto-format with
`./gradlew ktlintFormat` before committing. The release workflow additionally
produces a jacoco coverage report (best-effort, not a gate).

Run the tests locally:

```sh
./gradlew testDebugUnitTest   # JVM unit tests (JUnit4 + Robolectric + mockk)
./tests/run-local.sh          # JS polyfill tests (node)
```

Build config: AGP 9.3.1, Kotlin 2.4.10, minSdk 26, targetSdk 34 (Android 15+
app-data ELF exec restrictions affect the native proot binary, which is why
the engine runs only under glibc-in-container: the extracted rootfs + proot
stay fully offline and verifiable). A single ABI is built: **arm64-v8a**.
`rootfs.tar.xz` is excluded from resource compression (`noCompress += "xz"`);
Android lint errors block the build (`abortOnError`). **The signing keystore
lives only in the repo secret `RELEASE_KEYSTORE_B64`** — the workflow refuses
to build without it (never publishes or generates keys).

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | WebView + engine probe + runtime update downloads |
| `MANAGE_EXTERNAL_STORAGE` | External workspace: container bash reaches user-picked directories. On Android 11+ this is granted at install time (All Files Access); on Android 10 and below the model does not exist and the external workspace is unavailable |
| `POST_NOTIFICATIONS` | Notification channel (requested at runtime on API 33+) |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | Keep-alive service (`dataSync` type) |

SAF directory picking needs no permission (the user authorizes the tree URI
through the system picker).

## ABI

The runtime is built for **arm64-v8a only** (the Debian rootfs is aarch64; the
engine cannot run on x86_64 devices). A 16KB-page build must be produced on a
16KB device.

## Known limitations

- Keep-alive is best-effort: aggressive OEM battery managers may still kill
  the service; the Shizuku boost (appops `RUN_IN_BACKGROUND` /
  `RUN_ANY_IN_BACKGROUND`) needs Shizuku installed and authorized, and the
  battery-optimization exemption still depends on the vendor honoring it.
- Directory picking maps only the `primary` volume to a real path; other
  volumes fall back to the opaque `content://` tree URI.
- The engine restarts from the new runtime only after the watchdog's next poll
  (up to ~5s after the swap), and only if the old process was successfully
  killed; a missed kill surfaces a restart hint.
- Android 15+ app-data ELF exec restrictions and Huawei/EMUI W^X may block
  even the proot binary in app data; the linker64 spawn fallback covers the
  common cases — verify on real devices (see `docs/verification/container-acceptance.md`).
- The rootfs is ~64MB on first launch (APK asset); extraction takes a few
  minutes on slow devices.

## Related projects

- [dsh-arm64](https://github.com/dsh-io/dsh-arm64) — builds the embedded
  Debian rootfs (node, dsh overlay, vendored npm) and publishes it as a
  release asset
- [dsh-shell-termux](https://github.com/kelai141/dsh-shell-termux) — shell
- [dsh-client-ui-responsive](https://github.com/kelai141/dsh-client-ui-responsive) — mobile UI
- [dsh-host-web-compat](https://github.com/kelai141/dsh-host-web-compat) — browser compatibility

## License

MIT. Copyright (c) 2026 kelai141 (upstream), Copyright (c) 2026 lemonhub-io.
Contains third-party components under their own licenses (see dependency
declarations). Design rationale: `docs/design.md`; review log: `docs/issues.md`.
