# Single-runtime refactor — dsh-apk (deepseek-harness-mobile)

Date: 2026-08-18
Status: approved (brainstorming)

## Background

dsh-apk currently carries **two runtimes**:

1. an embedded **Termux snapshot** (~79MB APK → 484MB extracted) that runs the
   engine (`node --expose-internals ... dsh web`); Termux binaries are
   bionic/musl-linked and need the whole exec-reroute layer (exec-hook,
   linker64 fallback, `_Unwind_Resume` patch) to run inside app data;
2. an **online Ubuntu 24.04 rootfs** (~35MB, downloaded on first run) that
   only hosts the agent's shell, reached through a generated bash wrapper
   (LD_LIBRARY_PATH / PROOT_TMP_DIR / TMPDIR injection).

Two package managers, two libc worlds, one forwarding layer. The wrapper chain
(node → wrapper → proot → container bash) plus the exec-patch stack exist only
to keep these two worlds talking. This is bulky and hard to maintain.

The `dsh-io/dsh-arm64` project already produces the **single-runtime
alternative**: a Debian bookworm glibc rootfs in which node, dsh and bash all
live in one world. It was verified end-to-end on this Linux arm64 host:
`dsh web` boots with 0 errors inside proot, node-pty loads natively (glibc
provides `_Unwind_Resume`; no patches).

## Target architecture

One runtime, one rootfs, one libc. The engine and the agent shell run inside
the same Debian glibc rootfs under proot.

```
APK (com.dshmobile.shell, ~80-100MB)
├── assets/rootfs.tar.xz       ← dsh-arm64 rootfs artifact (single source of truth)
├── assets/proot/proot-arm64   ← existing proot binary (unchanged)
└── engine start:
    proot -0 -r <rootfs> -b /dev -b /proc -b /sys -w /root --kill-on-exit \
      /usr/bin/env -i HOME=/root \
      PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
      TERM=xterm-256color \
      node --expose-internals <rootfs>/root/.dsh-arm64/node_modules/@deepseek-ai/dsh/lib/bin.js web --port 3080
```

`<rootfs>/root/.dsh-arm64` is where the existing dsh-arm64 overlay (official
`@deepseek-ai/dsh@0.1.0-rc.6` + dependencies, cross-compiled pty.node) is
placed at rootfs build time — the same layout the dsh-arm64 `install.sh`
produces on a plain Linux host.

## 1. Rootfs artifact (dsh-io/dsh-arm64)

The dsh-arm64 repo currently publishes only the overlay
(`dsh-arm64-<v>.tar.gz` = node_modules + install.sh, requires a host glibc
Node 22). Extend it with a complete rootfs artifact:

- **New `build/build-rootfs.sh`**: debootstrap bookworm minbase (arm64),
  trimmed (docs/man/locale/apt caches), install glibc Node 22, unpack the
  current overlay into `/root/.dsh-arm64`, package
  `dsh-arm64-rootfs-<v>.tar.xz` (~50-80MB).
- **New `build/verify-rootfs.sh`**: host proot full-chain verification —
  `dsh web` boots with 0 errors (reuse the verify.sh approach), agent shell
  smoke (`bash`, coreutils, `node --version`, `apt-get`).
- **CI**: new rootfs job builds + verifies the artifact and uploads it to the
  same Release as the overlay, plus a `manifest.json` asset
  (`{url, sha256, size}` — HTTPS, sha256 mandatory) for the app's update
  mechanism.
- dsh-apk CI downloads the rootfs artifact from `dsh-io/dsh-arm64` releases
  (same pattern as today's upstream snapshot download, different URL).

## 2. dsh-apk shell changes

### Delete (the whole forwarding layer)

| File | Why |
|---|---|
| `app/src/main/cpp/exec-hook.c` | glibc binaries need no LD_PRELOAD exec reroute; `_Unwind_Resume` comes from glibc |
| `app/src/main/cpp/bash-wrapper.c` | bash already lives in the container; no wrapper to generate |
| `UnwindResolver.kt` | node-pty loads natively on glibc (host-verified) |
| `RootfsDownloader.kt` | rootfs is embedded, no online download |
| `ProotRuntime` wrapper/env-injection parts | keep only proot binary extraction + resolv.conf |

### Modify

| File | Change |
|---|---|
| `SnapshotExtractor.kt` | keep; target is the rootfs artifact; keep traversal guard + W^X write-bit strip (EMUI W^X applies to glibc ELF too) |
| `EngineManager.kt` | engine command becomes the proot launch above; env injection collapses into proot args |
| `ContainerProbe.kt` | simplified container smoke (`node --version`, `echo CONTAINER_OK`) before launch, same fail-closed semantics |
| `MainActivity.kt` | keep the 3-step wizard; step 2 text changes from "download Ubuntu container" to "install runtime" (local extract); failure/retry paths unchanged |
| `UpdateManager.kt` | manifest points at `dsh-io/dsh-arm64` releases (new `manifest.json` asset); download → verify → atomic swap (`rootfs → rootfs-old → new`) → watchdog restart, unchanged |
| CI / release pipeline | snapshot asset source switches from upstream `kelai141` to `dsh-io/dsh-arm64`; ABI locked to arm64-v8a only; quality gate unchanged (compile + lint + ktlint + unit tests + JS/C tests) |

### Keep (unchanged)

EngineService foreground service + 5s watchdog, HarnessWebView + bridge
protocol v1 + compat polyfills, SAF directory bridge (PickerBridge),
ExportFlow, Shizuku keep-alive, BootReceiver, dshdata public-data layout and
migration, AppLog, GuidePalette / StepState / VersionLine UI system, release
pipeline (signing / versioning).

## 3. Update mechanism

The app's manifest-driven runtime update stays, with a new target: the
`dsh-arm64-rootfs-<v>.tar.xz` asset. The manifest must be HTTPS with a
mandatory sha256 (the I-01 hardening requirements remain binding). The
rollback semantics (staged extraction → atomic swap → watchdog restart) are
unchanged.

## 4. Testing

| Layer | Scope |
|---|---|
| This host (linux-arm64) | after rootfs build: proot full chain — `dsh web` 0 errors, agent bash smoke, apt works (same path as the dsh-arm64 delivery) |
| CI (dsh-arm64) | rootfs job: build + verify-rootfs + artifact into Release |
| CI (dsh-apk) | quality gate unchanged; APK asset checks (rootfs + proot present, signed, arm64 only) |
| Real device | update `docs/verification/container-acceptance.md`: embedded install (no download), `id`/apt/workspace checks, failure path, **Android 15+ device mandatory** |

## 5. Risks

1. **glibc ELF direct exec (medium-high)** — Android 15+ bans app-data ELF
   exec only for targetSdk 35+; keeping targetSdk 34 preserves legality (same
   policy as today). But today's linker64 fallback does not apply to glibc
   binaries (bionic loader cannot load glibc), so a vendor that refuses exec
   at targetSdk 34 has no fallback. Mitigation: keep W^X write-bit strip;
   verdict = real device; Android 15+ testing is mandatory in the acceptance
   checklist.
2. **node-pty inside Android proot (medium)** — verified in host proot, but
   the Termux static proot on Android may differ; real-device checklist item.
3. **APK size ~80-100MB** — comparable to today's 79MB; acceptable.
4. **Rootfs reproducibility** — debootstrap + official npm registry, built on
   CI ubuntu-24.04-arm; low risk.

## Out of scope

- x86_64 support (dropped; arm64 only).
- Any changes to the bridge protocol, wizard UI design, or the keep-alive
  strategy.
- Replacing the dsh version source (still official `@deepseek-ai/dsh`
  releases, pinned by the dsh-arm64 build).