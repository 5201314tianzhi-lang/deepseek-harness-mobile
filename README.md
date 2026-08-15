# dsh-mobile-apk

Android shell for [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (dsh):
a WebView UI over an **embedded Termux runtime snapshot** (extract-and-run, no
Termux app required), with a SAF directory bridge, a keep-alive foreground
service, an engine watchdog, and manifest-driven online runtime updates. One
APK installs a full dsh web agent that can actually execute bash.

## Features

- **Embedded runtime** — ships a ~70MB xz snapshot (node + bash + coreutils +
  dsh + plugins); first launch extracts it in ~10s and starts the engine from
  the app's own files. Fully offline.
- **Mobile UI** — system WebView over `http://127.0.0.1:3080`; external links
  are routed to the system browser, only engine-same-origin pages stay inside
  the WebView.
- **Keep-alive** — foreground service (`dataSync` type) with a user-visible
  notification plus a 5s watchdog that restarts a dead engine process.
- **Online runtime updates** — HTTPS manifest-driven snapshot swap (download →
  SHA-256 verify → staged extraction → atomic switch with rollback →
  auto-restart via the watchdog); the running runtime can update itself
  without an APK update.
- **SAF directory bridge** — `pickDirectory` maps a user-picked tree to a
  real path (`/storage/emulated/0/…`) the bash process can access directly.
- **Public user data** — settings, sessions, storages and attachments live in
  `/storage/emulated/0/Documents/dshdata` (visible to file managers, backed up,
  survives reinstall; API keys stay private).

## Architecture

| Component | File | Responsibility |
|---|---|---|
| `MainActivity` | `app/src/main/java/com/dshmobile/shell/MainActivity.kt` | WebView shell, JS bridge wiring, engine-first boot flow, in-app downloads, directory picking, notifications |
| `AndroidBridge` | `.../AndroidBridge.kt` | `window.androidBridge` JS interface (protocol v1) |
| `EngineManager` | `.../EngineManager.kt` | Snapshot extraction, dshdata migration/relinking, engine process lifecycle and env |
| `EngineService` | `.../EngineService.kt` | Foreground service: keep-alive + 5s watchdog |
| `EngineProbe` | `.../EngineProbe.kt` | HTTP reachability probe of `127.0.0.1:3080` |
| `SnapshotExtractor` | `.../SnapshotExtractor.kt` | xz-tar extraction with path-traversal guard and Android exec-attribute stamping |
| `UpdateManager` | `.../UpdateManager.kt` | Runtime snapshot download/verify/swap |
| `ShizukuSupport` | `.../ShizukuSupport.kt` | Optional Shizuku presence/status detection (keep-alive boost) |

### Engine-first boot flow (`MainActivity.startEngineFlow`)

1. Probe `127.0.0.1:3080`; if already running (Termux or a previous embedded
   engine), show the WebView immediately.
2. Otherwise extract the embedded snapshot on first launch (`filesDir/usr`),
   then start the engine:
   `node --expose-internals <usr>/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web --port 3080`.
3. Poll the probe for up to 30s; on success start the foreground service and
   the optional Shizuku keep-alive boost.
4. Any failure falls back to the guide view (status + retry + Termux +
   update button).

The flow is guarded by an in-flight CAS flag: `onCreate` and `onResume` both
trigger it, and a double-threaded extract/start would kill the engine process.

### Engine lifecycle (`EngineManager`)

- Process-wide `STARTING` CAS plus a 90s cooldown window prevent double starts
  (cold node boot takes 20–45s; a faster watchdog would race a healthy boot
  and produce `EADDRINUSE`).
- The cooldown is cleared when the tracked engine process is dead — the 5s
  watchdog can then restart a crashed engine immediately.
- `startEngine` asserts the termux-exec `LD_PRELOAD` library before starting
  (missing it silently breaks every child exec).
- If direct exec is denied (`Permission denied`, Android 15+), the process is
  spawned through `/system/bin/linker64` instead.

### Storage layout

| Path | Purpose |
|---|---|
| `filesDir/usr` | Extracted runtime snapshot (node, bash, coreutils, dsh, plugins) |
| `filesDir/home` | `HOME` for the engine; `filesDir/home/.dsh` is `DSH_HOME` (private, holds `.credentials.yaml`) |
| `filesDir/engine.log` | Engine stdout/stderr (redirected, merged) |
| `filesDir/update.tar.xz`, `update-stage`, `usr-old` | Runtime-update staging/rollback |
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
| `keepScreenOn` | (enable: boolean) | Screen-on wake lock (single shared instance) |
| `showNotification` | (title, text) | Test notification channel (POST_NOTIFICATIONS requested at runtime; queued and re-sent after grant) |
| `pickDirectory` | (callbackId: string) | SAF tree picker (ACTION_OPEN_DOCUMENT_TREE); result delivered async |
| `hasAllFilesAccess` | () → boolean | True when the app holds All Files Access (API 30+) |
| `requestAllFilesAccess` | () → void | Opens the system All Files Access screen |
| `getPickToken` | () → string/null | One-shot session token for the engine-side pick endpoint |

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

External workspaces require the bash process to reach the picked real path:
the engine env carries `DSH_PICK_TOKEN` and the web-compat plugin validates it
as `x-dsh-pick-token`. On API 30+ without All Files Access the app opens the
system grant screen and signals `onPermissionRequired`; on API < 30 the pick
settles as cancelled (no such permission model).

### Session-log export and downloads

Engine-same-origin downloads (`/api/session.export` and everything else from
127.0.0.1:3080) are performed in-app over `HttpURLConnection` and written
streaming to MediaStore Downloads (API 29+, no permission needed) with a
200MB cap. Rationale: browser navigations carry `Origin: null` /
`sec-fetch-site` markers and are rejected (403) by dsh's `/api`
browser-trust fence; the in-app connection carries no browser markers and
passes. Downloads are deduplicated across the two entry points
(`shouldOverrideUrlLoading` + download listener) by an in-flight guard.

### WebView security boundary

- Only engine-same-origin URLs (exact scheme/host/port match) stay in the
  WebView; everything else opens in the system browser, so untrusted pages can
  never reach the privileged bridge.
- The session-export path is matched exactly (`/api/session.export`), not by
  prefix.
- `allowFileAccess=false`, mixed content never allowed, `FORCE_DARK_AUTO` for
  system theme following.
- Cleartext traffic is restricted to `127.0.0.1`/`localhost` via
  `network_security_config.xml`; everything else requires TLS.

## Online runtime update protocol

1. The app fetches `manifest.json` over **HTTPS**: `{url, sha256, size}`.
   The manifest URL and the snapshot URL are both enforced HTTPS; a missing
   `sha256` rejects the update (no integrity protection otherwise).
2. The snapshot is downloaded streaming with a 500MB cap, SHA-256 is verified
   against the manifest.
3. It is extracted to a staging directory (never touching the live tree), the
   new `usr` is validated (must contain `bin/node`), then swapped:
   `usr → usr-old → new usr`, with rollback if the swap fails.
4. The old engine process is killed; the EngineService watchdog restarts it
   from the new runtime within seconds.

Test trigger: `adb shell am start -n com.dshmobile.shell/.MainActivity -a com.dshmobile.shell.action.UPDATE`
(debug builds only — the activity is exported as the LAUNCHER, so release
builds ignore the intent to prevent external download+execute triggers).
Status is written to `files/update-status.txt`. The default manifest URL
(`https://10.0.2.2:8899/manifest.json`) targets the emulator host loopback;
production builds override it via `UpdateManager.manifestUrl`.

## Build

Requirements: JDK 17+, Android SDK (compileSdk 36), Gradle 9.7.0 via wrapper.

```sh
# 1. Prepare the runtime snapshot (required, ~70MB, distributed as a Release asset)
#    Option A: download snapshot-x86_64.tar.xz from GitHub Releases
#    Option B: build on a Termux device (scripts/make-snapshot.sh) and pull it
mkdir -p app/src/main/assets
cp snapshot/snapshot.tar.xz app/src/main/assets/snapshot.tar.xz

# 2. Build (fails loudly when the snapshot is missing)
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

Build config: AGP 9.3.1, Kotlin 2.4.10, minSdk 26, targetSdk 34 (deliberate —
Android 15+ forbids exec of app-data ELF for targetSdk 35+, and 34 keeps
native exec working on Android 15/16 devices). `snapshot.tar.xz` is excluded
from resource compression (`noCompress += "xz"`); lint is non-blocking for
offline environments.

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | WebView + engine probe |
| `MANAGE_EXTERNAL_STORAGE` | External workspace: bash reaches user-picked directories (All Files Access, requested at runtime) |
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
  (up to ~5s after the swap).

## Related projects

- [dsh-shell-termux](https://github.com/kelai141/dsh-shell-termux) — shell
- [dsh-client-ui-responsive](https://github.com/kelai141/dsh-client-ui-responsive) — mobile UI
- [dsh-host-web-compat](https://github.com/kelai141/dsh-host-web-compat) — browser compatibility

## License

MIT. Copyright (c) 2026 kelai141 (upstream), Copyright (c) 2026 lemonhub-io.
Contains third-party components under their own licenses (see dependency
declarations). Design rationale: `docs/design.md`; review log: `docs/issues.md`.
