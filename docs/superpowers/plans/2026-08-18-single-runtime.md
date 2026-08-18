# Single-runtime Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse dsh-apk's two runtimes (Termux snapshot engine + online Ubuntu container) into one embedded Debian glibc rootfs produced by dsh-arm64, deleting the whole exec-reroute/wrapper layer.

**Architecture:** dsh-io/dsh-arm64 gains a complete rootfs artifact (debootstrap bookworm + glibc Node 22 + the existing dsh overlay under `/root/.dsh-arm64`). dsh-apk embeds that artifact, launches the engine *inside* the rootfs via proot (`node --expose-internals ... web --port 3080`), keeps the shell and engine in the same container, and reuses its mature shell (wizard, foreground service + watchdog, WebView, bridge, SAF, updates, Shizuku). Mirrors move from runtime-writing to rootfs-build-time.

**Tech Stack:** bash (debootstrap/proot scripts), Kotlin (Android), Gradle/AGP 9, GitHub Actions (ubuntu-24.04-arm + ubuntu-latest), xz/commons-compress.

## Global Constraints

- arm64-v8a ONLY. x86_64 support is deleted (build.gradle abiFilters, CI matrix, assets, docs).
- Rootfs artifact name: `dsh-arm64-rootfs-<version>.tar.xz`; APK asset name: `assets/rootfs.tar.xz`; DshPaths.ROOTFS_ASSET = `"rootfs.tar.xz"`.
- Overlay location inside rootfs: `/root/.dsh-arm64` (identical to dsh-arm64 `install.sh` semantics); engine entry `<rootfs>/root/.dsh-arm64/node_modules/@deepseek-ai/dsh/lib/bin.js`; node at `<rootfs>/usr/local/bin/node` (official Node 22 binary tarball, not apt).
- Engine start command (single source of truth, used by EngineManager and ContainerProbe):
  ```
  proot -0 -r <rootfsDir> -b /dev:/dev -b /proc:/proc -b /sys:/sys \
    -b <filesDir>/etc/resolv.conf:/etc/resolv.conf \
    -b <dshdata>/projects:/root/projects -w /root --kill-on-exit -- \
    /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    TERM=xterm-256color DSH_HOME=/root/.dsh DSH_PICK_TOKEN=<token> \
    node --expose-internals /root/.dsh-arm64/node_modules/@deepseek-ai/dsh/lib/bin.js web --port 3080
  ```
- Update manifest URL: `https://github.com/dsh-io/dsh-arm64/releases/latest/download/manifest.json` (HTTPS + mandatory sha256, per I-01).
- Quality gate (dsh-apk AGENTS.md) must stay green: `./gradlew assembleDebug lintDebug ktlintCheck testDebugUnitTest` + `./tests/run-local.sh`.
- No new third-party dependencies. Delete obsolete paths (AGENTS.md: no compatibility layers).
- signing secrets untouched: `RELEASE_KEYSTORE_B64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.

---

### Task 1: dsh-arm64 rootfs artifact (build-rootfs.sh + verify-rootfs.sh + CI)

**Files:**
- Create: `/root/projects/dsh-arm64/build/build-rootfs.sh`
- Create: `/root/projects/dsh-arm64/build/verify-rootfs.sh`
- Modify: `/root/projects/dsh-arm64/.github/workflows/build.yml`

**Interfaces:**
- Produces: `dsh-arm64-rootfs-<version>.tar.xz` — tar.xz whose root IS the rootfs (no `usr/` prefix; archive top-level entries are `bin/ etc/ lib/ root/ usr/ ...`), containing `/usr/local/bin/node`, `/root/.dsh-arm64/node_modules/@deepseek-ai/dsh/lib/bin.js`, pre-provisioned mirrors and `/root/projects`.
- Produces: `manifest.json` `{"url": <https release asset url>, "sha256": <hex>, "size": <bytes>}` (no version prefix — the version is in the URL/tag).
- Produces: `rootfs.sha256sums` with the rootfs tarball checksum.

- [ ] **Step 1: Write `build/build-rootfs.sh`**

```bash
#!/usr/bin/env bash
set -euo pipefail

BASELINE="${1:-0.1.0-rc.6}"
STAGE="${2:-/tmp/dsh-arm64-rootfs-stage}"
NODE_VERSION="${3:-22.14.0}"
NODE_MIRROR="${NODE_MIRROR:-https://npmmirror.com/mirrors/node}"

if [ "$(uname -m)" != "aarch64" ] && [ "$(uname -m)" != "arm64" ]; then
  echo "!! build-rootfs.sh must run on an arm64 host (got $(uname -m))" >&2
  exit 2
fi
if [ "$(id -u)" != "0" ]; then
  echo "!! build-rootfs.sh needs root (debootstrap)" >&2
  exit 2
fi

echo "==> rootfs build: Debian bookworm + node ${NODE_VERSION} + dsh@${BASELINE}"
rm -rf "${STAGE}"
mkdir -p "${STAGE}/rootfs" "${STAGE}/work"
cd "${STAGE}"

# ---- 1. debootstrap bookworm minbase (arm64) ----
debootstrap --variant=minbase --arch=arm64 bookworm rootfs \
  http://deb.debian.org/debian/ >/dev/null

# ---- 2. trim: docs, man, locales, apt caches ----
rm -rf rootfs/usr/share/doc rootfs/usr/share/man rootfs/usr/share/locale \
  rootfs/usr/share/info rootfs/var/lib/apt/lists rootfs/var/cache/apt/archives/*

# ---- 3. glibc Node 22 (official binary tarball -> /usr/local) ----
curl -fsSL --retry 3 --retry-all-errors \
  -o work/node.tar.xz "${NODE_MIRROR}/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-arm64.tar.xz"
tar -xJf work/node.tar.xz -C rootfs/usr/local --strip-components=1 \
  --exclude='*/CHANGELOG.md' --exclude='*/README.md' --exclude='*/LICENSE' \
  --exclude='*/include' --exclude='*/share/man' --exclude='*/share/doc'
rootfs/usr/local/bin/node --version | grep -q "v${NODE_VERSION}" \
  || { echo "!! node version mismatch" >&2; exit 1; }

# ---- 4. dsh overlay (the existing dsh-arm64 artifact) -> /root/.dsh-arm64 ----
curl -fsSL --retry 3 --retry-all-errors \
  -o work/overlay.tar.gz "https://github.com/dsh-io/dsh-arm64/releases/download/v${BASELINE}/dsh-arm64-${BASELINE}.tar.gz"
mkdir -p rootfs/root/.dsh-arm64
tar -xzf work/overlay.tar.gz -C rootfs/root/.dsh-arm64 --strip-components=1 \
  --exclude='install.sh'
[ -f rootfs/root/.dsh-arm64/node_modules/@deepseek-ai/dsh/lib/bin.js ] \
  || { echo "!! overlay missing dsh bin.js" >&2; exit 1; }

# ---- 5. mirrors + workspace (moved from the app's runtime applyMirrors) ----
cat > rootfs/etc/apt/sources.list <<'EOF'
deb https://mirrors.tuna.tsinghua.edu.cn/debian/ bookworm main contrib non-free non-free-firmware
deb https://mirrors.tuna.tsinghua.edu.cn/debian/ bookworm-updates main contrib non-free non-free-firmware
deb https://mirrors.tuna.tsinghua.edu.cn/debian-security bookworm-security main contrib non-free non-free-firmware
EOF
cat > rootfs/etc/pip.conf <<'EOF'
[global]
index-url = https://pypi.tuna.tsinghua.edu.cn/simple
trusted-host = pypi.tuna.tsinghua.edu.cn
EOF
cat > rootfs/etc/npmrc <<'EOF'
registry=https://registry.npmmirror.com
EOF
mkdir -p rootfs/root/.cargo
cat > rootfs/root/.cargo/config.toml <<'EOF'
[source.crates-io]
replace-with = 'tuna'
[source.tuna]
registry = "sparse+https://mirrors.tuna.tsinghua.edu.cn/crates.io-index/"
EOF
mkdir -p rootfs/etc/profile.d
cat > rootfs/etc/profile.d/dsh-mirrors.sh <<'EOF'
export GOPROXY=https://goproxy.cn,direct
export GO111MODULE=on
EOF
cat > rootfs/root/.bashrc <<'EOF'
export GOPROXY=https://goproxy.cn,direct
export GO111MODULE=on
EOF
cat > rootfs/root/.gemrc <<'EOF'
---
:sources:
- https://mirrors.tuna.tsinghua.edu.cn/rubygems/
EOF
mkdir -p rootfs/root/.config/composer
cat > rootfs/root/.config/composer/config.json <<'EOF'
{
  "repositories": [
    { "type": "composer", "url": "https://mirrors.aliyun.com/composer/" }
  ]
}
EOF
cat > rootfs/root/.condarc <<'EOF'
channels:
  - defaults
show_channel_urls: true
default_channels:
  - https://mirrors.tuna.tsinghua.edu.cn/anaconda/pkgs/main
  - https://mirrors.tuna.tsinghua.edu.cn/anaconda/pkgs/r
custom_channels:
  conda-forge: https://mirrors.tuna.tsinghua.edu.cn/anaconda/cloud
EOF
mkdir -p rootfs/root/projects

# ---- 6. package rootfs.tar.xz + sha256 ----
tar -cJf "dsh-arm64-rootfs-${BASELINE}.tar.xz" -C rootfs .
sha256sum "dsh-arm64-rootfs-${BASELINE}.tar.xz" > rootfs.sha256sums
sha256sum -c rootfs.sha256sums
du -h "dsh-arm64-rootfs-${BASELINE}.tar.xz"
echo "==> rootfs build OK"
```

- [ ] **Step 2: Write `build/verify-rootfs.sh`**

```bash
#!/usr/bin/env bash
set -euo pipefail

# Full-chain verification on the host: unpack the rootfs artifact, boot the
# dsh web engine inside proot (0-error window), smoke the agent shell.
STAGE="${1:?usage: verify-rootfs.sh <rootfs.tar.xz> [work-dir]}"
WORK="${2:-/tmp/dsh-arm64-rootfs-verify}"

if [ "$(uname -m)" != "aarch64" ] && [ "$(uname -m)" != "arm64" ]; then
  echo "!! verify-rootfs.sh must run on an arm64 host (got $(uname -m))" >&2
  exit 2
fi
command -v proot >/dev/null || apt-get install -y proot >/dev/null

rm -rf "${WORK}"
mkdir -p "${WORK}/rootfs"
tar -xJf "${STAGE}" -C "${WORK}/rootfs"

[ -x "${WORK}/rootfs/usr/local/bin/node" ] || { echo "!! node missing" >&2; exit 1; }
[ -f "${WORK}/rootfs/root/.dsh-arm64/node_modules/@deepseek-ai/dsh/lib/bin.js" ] \
  || { echo "!! dsh missing" >&2; exit 1; }

PROOT_ARGS=(-0 -r "${WORK}/rootfs" -b /dev:/dev -b /proc:/proc -b /sys:/sys -w /root --kill-on-exit)

echo "==> agent shell smoke:"
proot "${PROOT_ARGS[@]}" -- /usr/bin/env -i HOME=/root \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm-256color \
  bash -c 'echo SHELL_OK; id -u; node --version; which node bash' || { echo "!! shell smoke FAILED" >&2; exit 1; }

echo "==> booting dsh web (60s window)..."
timeout 60 proot "${PROOT_ARGS[@]}" -- /usr/bin/env -i HOME=/root \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm-256color \
  DSH_HOME=/root/.dsh \
  node --expose-internals /root/.dsh-arm64/node_modules/@deepseek-ai/dsh/lib/bin.js web \
  > "${WORK}/boot.log" 2>&1 || true
if grep -qi "error\|pty.node" "${WORK}/boot.log"; then
  echo "!! boot errors:" >&2
  grep -i "error\|pty.node" "${WORK}/boot.log" >&2
  exit 1
fi
echo "==> verify OK: shell smoke + dsh web boot clean"
```

- [ ] **Step 3: Run locally and confirm it works end-to-end**

Run: `sudo ./build/build-rootfs.sh 0.1.0-rc.6 /tmp/dsh-arm64-rootfs-stage`
Then: `./build/verify-rootfs.sh /tmp/dsh-arm64-rootfs-stage/dsh-arm64-rootfs-0.1.0-rc.6.tar.xz`
Expected: both scripts exit 0; verify prints `SHELL_OK`, `0`, the node version, and `verify OK: shell smoke + dsh web boot clean`. The stage tarball is a few tens of MB (`du -h` output). A boot.log with any `error`/`pty.node` line fails the run — that is the core single-runtime correctness proof.

- [ ] **Step 4: Extend `.github/workflows/build.yml` with a rootfs job**

```yaml
  rootfs:
    runs-on: ubuntu-24.04-arm
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 22
      - name: Install proot (host verify tool)
        run: sudo apt-get update && sudo apt-get install -y proot
      - name: Build rootfs artifact
        run: |
          sudo ./build/build-rootfs.sh "${GITHUB_REF_NAME#v}" /tmp/dsh-rootfs
          sudo chown -R "$(id -u)" /tmp/dsh-rootfs
      - name: Verify rootfs (shell smoke + dsh web boot)
        run: ./build/verify-rootfs.sh /tmp/dsh-rootfs/dsh-arm64-rootfs-"${GITHUB_REF_NAME#v}".tar.xz
      - name: Generate update manifest
        run: |
          SHA=$(cut -d' ' -f1 /tmp/dsh-rootfs/rootfs.sha256sums)
          SIZE=$(stat -c%s /tmp/dsh-rootfs/dsh-arm64-rootfs-"${GITHUB_REF_NAME#v}".tar.xz)
          URL="https://github.com/dsh-io/dsh-arm64/releases/download/${GITHUB_REF_NAME}/dsh-arm64-rootfs-${GITHUB_REF_NAME#v}.tar.xz"
          printf '{"url":"%s","sha256":"%s","size":%s}\n' "$URL" "$SHA" "$SIZE" > /tmp/dsh-rootfs/manifest.json
          cat /tmp/dsh-rootfs/manifest.json
      - name: Upload artifacts
        uses: actions/upload-artifact@v4
        with:
          name: dsh-arm64-rootfs
          path: /tmp/dsh-rootfs/
          retention-days: 7
```

- [ ] **Step 5: Commit**

```bash
cd /root/projects/dsh-arm64
git add build/build-rootfs.sh build/verify-rootfs.sh .github/workflows/build.yml
git commit -m "feat: rootfs artifact build + verify + CI job (single runtime)"
git push
```

---

### Task 2: dsh-apk asset + build-pipeline switch (CI downloads rootfs, drop x86_64/unwind)

**Files:**
- Modify: `/root/projects/dsh-apk/.github/workflows/ci.yml`
- Modify: `/root/projects/dsh-apk/.github/workflows/release.yml`
- Modify: `/root/projects/dsh-apk/app/build.gradle.kts`

**Interfaces:**
- Consumes: rootfs artifact URL `https://github.com/dsh-io/dsh-arm64/releases/latest/download/dsh-arm64-rootfs-<version>.tar.xz` (asset downloaded to `app/src/main/assets/rootfs.tar.xz`).
- Produces: assets layout `assets/rootfs.tar.xz` + `assets/proot/arm64-v8a/{proot,libtalloc.so.2,libandroid-shmem.so}`. NO `assets/unwind/`, NO `libexec-hook.so`, NO `bash-wrapper`.

- [ ] **Step 1: Switch `ci.yml` snapshot download to the rootfs artifact**

Replace the "Download runtime snapshot (arm64) from upstream releases" step with:

```yaml
      - name: Download single runtime rootfs (arm64) from dsh-arm64 releases
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          mkdir -p app/src/main/assets
          asset_url=$(gh api repos/dsh-io/dsh-arm64/releases/latest \
            --jq '.assets[] | select(.name | startswith("dsh-arm64-rootfs-")) | .url' | head -1)
          [ -n "$asset_url" ] || { echo "rootfs asset not found in dsh-arm64 latest release"; exit 1; }
          curl -sfL -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/octet-stream" \
            "$asset_url" -o app/src/main/assets/rootfs.tar.xz
          ls -lh app/src/main/assets/rootfs.tar.xz
```

- [ ] **Step 2: Slim `release.yml` — single ABI, no unwind, rootfs source**

In the build job:
- Change `on.push.tags` stays; replace the matrix with a single leg:

```yaml
    runs-on: ubuntu-latest
    steps:
```

Delete `matrix` block and all `${{ matrix.abi }}` references (single ABI arm64-v8a).
- Replace the "Download runtime snapshot" step with the same download step as ci.yml (Step 1 above).
- Delete the "Package unwind patch lib" job step entirely.
- In "Package proot runtime": delete the `if [ "${{ matrix.abi }}" = ... ]` ABI branch and the `else` branch; keep only the arm64/aarch64 branch:

```yaml
      - name: Package proot runtime
        run: |
          ABI=aarch64
          BASE="https://packages.termux.dev/apt/termux-main/pool/main"
          mkdir -p /tmp/proot && cd /tmp/proot
          curl -sfL -o proot.deb "$BASE/p/proot/proot_5.1.107.91_${ABI}.deb"
          curl -sfL -o talloc.deb "$BASE/libt/libtalloc/libtalloc_2.4.3_${ABI}.deb"
          curl -sfL -o shmem.deb "$BASE/liba/libandroid-shmem/libandroid-shmem_0.7_${ABI}.deb"
          mkdir -p x && for d in proot talloc shmem; do
            ar x $d.deb && tar -xf data.tar.xz -C x && rm -f data.tar.xz
          done
          P=x/data/data/com.termux/files
          cp "$P/usr/bin/proot" . && cp "$P/usr/lib/libtalloc.so.2" "$P/usr/lib/libandroid-shmem.so" .
          file proot | grep -q ELF || { echo "proot not ELF"; exit 1; }
          readelf --dyn-syms proot | grep -q talloc || { echo "proot missing talloc ref"; exit 1; }
          mkdir -p "$GITHUB_WORKSPACE/app/src/main/assets/proot/arm64-v8a"
          cp proot libtalloc.so.2 libandroid-shmem.so "$GITHUB_WORKSPACE/app/src/main/assets/proot/arm64-v8a/"
```

- Rename step "Rename APK" to drop `-${{ matrix.abi }}`:

```yaml
          cp app/build/outputs/apk/release/app-release.apk "dist/dsh-apk-${{ steps.tag.outputs.tag }}.apk"
```

- "Run local JS/C tests" → `./tests/run-local.sh` keeps running (Task 5 trims the C side of that script).
- "Build release APK" keeps `-PabiFilter=arm64-v8a` (hardcoded now).

- [ ] **Step 3: `app/build.gradle.kts` — asset check + ABI lock**

Replace the snapshot check task with:

```kotlin
tasks.whenTaskAdded {
  if (name == "mergeDebugAssets" || name == "mergeReleaseAssets") {
    doFirst {
      val rootfs = file("src/main/assets/rootfs.tar.xz")
      if (!rootfs.exists()) {
        throw GradleException(
          "缺少单运行时 rootfs assets/rootfs.tar.xz / Missing single-runtime rootfs " +
            "assets/rootfs.tar.xz — download dsh-arm64-rootfs-<version>.tar.xz from " +
            "dsh-io/dsh-arm64 releases into app/src/main/assets/rootfs.tar.xz (see README.md).",
        )
      }
    }
  }
}
```

Delete the `externalNativeBuild { cmake { ... } }` block and the `ndkVersion` line is kept (bash-wrapper/exec-hook are gone; no CMakeLists). Add the ABI lock so local builds also produce arm64 only:

```kotlin
  defaultConfig {
    ...
    ndk {
      abiFilters += "arm64-v8a"
    }
  }
```

Keep `noCompress += "xz"` (rootfs.tar.xz must not be recompressed — openFd/streaming extraction relies on it).

- [ ] **Step 4: Commit**

```bash
cd /root/projects/dsh-apk
git add .github/workflows app/build.gradle.kts
git commit -m "build: single-runtime assets (rootfs from dsh-arm64), drop x86_64 + unwind pipeline"
git push
```

Expected: CI runs the quality gate. Assets are downloaded from dsh-io/dsh-arm64 latest release; the build fails only because Kotlin sources still reference the old files (fixed in Task 3) — that is acceptable mid-refactor; the workflow files themselves are the deliverable.

---

### Task 3: Kotlin runtime layer — engine inside the rootfs

**Files:**
- Modify: `/root/projects/dsh-apk/app/src/main/java/com/dshmobile/shell/DshPaths.kt`
- Rewrite: `/root/projects/dsh-apk/app/src/main/java/com/dshmobile/shell/EngineManager.kt` (keep dshdata migration; new engine start)
- Rewrite: `/root/projects/dsh-apk/app/src/main/java/com/dshmobile/shell/ProotRuntime.kt` (extract proot + resolv.conf only)
- Rewrite: `/root/projects/dsh-apk/app/src/main/java/com/dshmobile/shell/ContainerProbe.kt` (rootfs-internal smoke)
- Delete: `/root/projects/dsh-apk/app/src/main/java/com/dshmobile/shell/UnwindResolver.kt`
- Delete: `/root/projects/dsh-apk/app/src/main/java/com/dshmobile/shell/RootfsDownloader.kt`
- Modify: `/root/projects/dsh-apk/app/src/main/java/com/dshmobile/shell/SnapshotVersion.kt`
- Delete: `/root/projects/dsh-apk/app/src/main/cpp/` (CMakeLists.txt, exec-hook.c, bash-wrapper.c)

**Interfaces:**
- Consumes: `DshPaths.ROOTFS_ASSET` (`"rootfs.tar.xz"`), rootfs archive layout (top-level entries, no prefix).
- Produces: `EngineManager.startEngine(port): Boolean` (same signature), `EngineManager.engineReady: Boolean`, `EngineManager.extractRootfs(onProgress): Boolean`, `ProotRuntime.ensureProot(): Boolean`, `ProotRuntime.buildEngineArgs(port, token): Pair<Array<String>, Map<String,String>>`, `ContainerProbe.smokeTest(): String?`, `DshPaths.ROOTFS_DIR`, `DshPaths.DSH_ENTRY` (`"root/.dsh-arm64/node_modules/@deepseek-ai/dsh/lib/bin.js"`), `DshPaths.ROOTFS_NODE` (`"usr/local/bin/node"`).

- [ ] **Step 1: Update `DshPaths.kt`**

```kotlin
  /** Single runtime rootfs under filesDir (rootfs/). */
  const val ROOTFS_DIR = "rootfs"

  /** Rootfs archive (assets). */
  const val ROOTFS_ASSET = "rootfs.tar.xz"

  /** Node inside the rootfs. */
  const val ROOTFS_NODE = "usr/local/bin/node"

  /** dsh entry inside the rootfs (overlay at /root/.dsh-arm64). */
  const val DSH_ENTRY = "root/.dsh-arm64/node_modules/@deepseek-ai/dsh/lib/bin.js"

  /** Rootfs bash (container /bin/bash). */
  const val ROOTFS_BASH = "bin/bash"

  /** Container dsh home (DSH_HOME, private inside the rootfs). */
  const val CONTAINER_DSH_HOME = "root/.dsh"

  /** Container workspace bind target: /root/projects. */
  const val CONTAINER_PROJECTS = "root/projects"

  /** Host-side projects directory (inside the public dshdata). */
  const val PROJECTS_DIR = "projects"
```

Delete `USR_DIR`, `NODE_BIN`, `BASH_BIN`, `PTY_NODE`, `SNAPSHOT_ASSET` (no usages remain after Task 3/4).

- [ ] **Step 2: Rewrite `ProotRuntime.kt` (proot extraction + resolv.conf + engine argv)**

```kotlin
package com.dshmobile.shell

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Proot runtime: extracts the proot binary + libtalloc + libandroid-shmem
 * from APK assets and builds the single-runtime engine argv (engine and
 * agent shell share one Debian glibc rootfs). No wrapper, no env injection —
 * the container is the engine's environment.
 */
class ProotRuntime(private val context: Context) {
  val prootDir: File get() = File(context.filesDir, "proot")
  val prootBin: File get() = File(prootDir, "proot")

  /** AliDNS first (reachable in CN networks); Google DNS secondary. */
  fun resolvConf(): File {
    val f = File(context.filesDir, "etc/resolv.conf")
    if (!f.isFile) {
      f.parentFile?.mkdirs()
      f.writeText("nameserver 223.5.5.5\nnameserver 8.8.8.8\n")
    }
    return f
  }

  private fun extractAsset(
    name: String,
    target: File,
    exec: Boolean,
  ): Boolean {
    if (target.isFile && target.length() > 0L) return true
    return try {
      target.parentFile?.mkdirs()
      context.assets.open("proot/arm64-v8a/$name").use { input ->
        target.outputStream().use { out -> input.copyTo(out) }
      }
      target.setExecutable(exec, true)
      // W^X: proot AND its shared libs must not stay writable (EMUI refuses
      // to exec / mmap PROT_EXEC a writable file).
      target.setWritable(false, false)
      true
    } catch (t: Throwable) {
      AppLog.log("proot", "extract failed: $name", t)
      false
    }
  }

  fun ensureProot(): Boolean {
    val talloc = File(prootDir, "libtalloc.so.2")
    val shmem = File(prootDir, "libandroid-shmem.so")
    if (prootBin.isFile && prootBin.length() > 0L &&
      talloc.isFile && talloc.length() > 0L &&
      shmem.isFile && shmem.length() > 0L
    ) {
      return true
    }
    val ok = extractAsset("proot", prootBin, exec = true)
    val tallocOk = extractAsset("libtalloc.so.2", talloc, exec = false)
    val shmemOk = extractAsset("libandroid-shmem.so", shmem, exec = false)
    AppLog.log("proot", "ensureProot executable=$ok libtalloc=$tallocOk shmem=$shmemOk")
    return ok && tallocOk && shmemOk
  }

  /**
   * Build the engine argv + env: proot with the single rootfs, engine node
   * booted inside the container. Mirrors/workspace are pre-provisioned in
   * the rootfs artifact (build-rootfs.sh) — nothing is written at runtime.
   */
  fun buildEngineArgs(
    rootfsDir: File,
    projectsDir: File,
    port: Int,
    pickToken: String,
  ): Pair<Array<String>, Map<String, String>> {
    resolvConf()
    val args =
      arrayOf(
        prootBin.absolutePath,
        "-0",
        "-r", rootfsDir.absolutePath,
        "-b", "/dev:/dev",
        "-b", "/proc:/proc",
        "-b", "/sys:/sys",
        "-b", resolvConf().absolutePath + ":/etc/resolv.conf",
        "-b", projectsDir.absolutePath + ":/root/projects",
        "-w", "/root",
        "--kill-on-exit",
        "--",
        "/usr/bin/env",
        "-i",
        "HOME=/root",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm-256color",
        "DSH_HOME=/root/.dsh",
        "DSH_PICK_TOKEN=" + pickToken,
        "node",
        "--expose-internals",
        "/root/.dsh-arm64/node_modules/@deepseek-ai/dsh/lib/bin.js",
        "web",
        "--port",
        port.toString(),
      )
    // LD_LIBRARY_PATH only reaches proot itself (bionic deps in its own
    // dir); the container env is rebuilt by env -i.
    val env = mapOf("LD_LIBRARY_PATH" to prootDir.absolutePath)
    return args to env
  }
}
```

- [ ] **Step 3: Rewrite `EngineManager.kt` (extract rootfs; engine start; keep dshdata migration verbatim)**

Keep everything through `relocateDir`/`copyTree` unchanged. Replace `usrDir`/`nodeBin`/`dshBin`/`unwindResolver`/`execHook`/`opensslConfEnv`/`extractSnapshot`/`startEngine` with:

```kotlin
class EngineManager(
  private val context: Context,
) {
  val rootfsDir = File(context.filesDir, DshPaths.ROOTFS_DIR)
  val homeDir = File(context.filesDir, "home")

  val dshDataDir: File
    get() { /* unchanged */ }

  private val prootRuntime by lazy { ProotRuntime(context) }

  /** Engine entry binary inside the rootfs. */
  private val dshEntry = File(rootfsDir, DshPaths.DSH_ENTRY)

  /** True once the rootfs is extracted and holds the dsh entry. */
  val engineReady: Boolean get() = dshEntry.isFile

  /** Extract the bundled rootfs archive into filesDir. */
  fun extractRootfs(onProgress: (Long, Long) -> Unit): Boolean =
    try {
      context.assets.openFd(DshPaths.ROOTFS_ASSET).use { fd ->
        AppLog.log("extract", "archive size=" + fd.length + " bytes, dest=" + context.filesDir)
        SnapshotExtractor.extract(
          context.assets.open(DshPaths.ROOTFS_ASSET),
          fd.length,
          context.filesDir,
          onProgress,
        )
      }
      homeDir.mkdirs()
      AppLog.log("extract", "done, engineReady=" + engineReady)
      true
    } catch (t: Throwable) {
      Log.e(TAG, "rootfs extract failed", t)
      AppLog.log("extract", "FAILED", t)
      false
    }

  /** ensureDshDataHome(): UNCHANGED except the private base path — DSH_HOME
   *  now lives inside the rootfs (/root/.dsh), not filesDir/home/.dsh. */
  fun ensureDshDataHome(): File {
    val dshData = dshDataDir
    val privateDsh = File(rootfsDir, DshPaths.CONTAINER_DSH_HOME)
    // ... the rest of the migration/relink logic is byte-for-byte identical
  }

  /** Start the dsh web engine inside the single runtime rootfs. */
  fun startEngine(port: Int = 3080): Boolean {
    if (!prootRuntime.ensureProot()) {
      AppLog.log("engine", "start refused: proot runtime unavailable")
      return false
    }
    val now = System.currentTimeMillis()
    if (!STARTING.compareAndSet(false, true)) return true
    if (engineProcess?.isAlive != true) EngineManager.lastStartAttemptAt = 0
    if (now - EngineManager.lastStartAttemptAt < START_COOLDOWN_MS) {
      STARTING.set(false)
      return true
    }
    EngineManager.engineProcess?.let { p ->
      if (p.isAlive) {
        AppLog.log("engine", "previous engine process alive past cooldown, killing hung process")
        p.destroyForcibly()
        try {
          p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
      }
    }
    EngineManager.engineProcess = null
    return try {
      val projectsDir = File(ensureDshDataHome(), DshPaths.PROJECTS_DIR).apply { mkdirs() }
      val (args, env) = prootRuntime.buildEngineArgs(rootfsDir, projectsDir, port, pickToken)
      engineProcess = startWithArgs(args, env)
      EngineManager.lastStartAttemptAt = now
      AppLog.log(
        "engine",
        "started port=" + port + " proot=" + prootRuntime.prootBin.absolutePath +
          " rootfs=" + rootfsDir.absolutePath + " arch=" +
          android.os.Build.SUPPORTED_ABIS.joinToString(","),
      )
      true
    } catch (t: Throwable) {
      Log.e(TAG, "engine start failed", t)
      AppLog.log("engine", "start FAILED", t)
      AppLog.includeFile(File(context.filesDir, DshPaths.ENGINE_LOG), DshPaths.ENGINE_LOG)
      false
    } finally {
      STARTING.set(false)
    }
  }

  /**
   * Spawn the engine, falling back to the system linker when direct exec is
   * denied. proot is NDK/bionic-linked, so /system/bin/linker64 can load it
   * (the container's glibc binaries are never exec'd from app data).
   */
  private fun startWithArgs(
    args: Array<String>,
    env: Map<String, String>,
  ): Process {
    val log = File(context.filesDir, "engine.log")
    fun build(argv: List<String>): ProcessBuilder =
      ProcessBuilder(argv).also { b ->
        b.environment().putAll(env)
        b.redirectErrorStream(true)
        b.redirectOutput(log)
      }
    return try {
      build(args.toList()).start()
    } catch (e: java.io.IOException) {
      if (e.message?.contains("Permission denied") != true) throw e
      Log.w(TAG, "direct exec denied, falling back to linker64: " + e.message)
      AppLog.log("engine", "direct exec denied (" + e.message + "), falling back to linker64")
      build(listOf("/system/bin/linker64") + args.toList()).start()
    }
  }

  fun stopEngine() { /* unchanged */ }

  companion object {
    const val START_COOLDOWN_MS = 90_000L
    val pickToken = java.util.UUID.randomUUID().toString()
    val STARTING = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile var lastStartAttemptAt: Long = 0
    @Volatile var engineProcess: Process? = null
  }
}
```

Note: the ENTIRE dshdata migration body (`relink`, `relinkFile`, `copyFileIfExists`, `relocateDir`, `copyTree`) stays byte-for-byte identical — only the `privateDsh` declaration changes to `File(rootfsDir, DshPaths.CONTAINER_DSH_HOME)`.

- [ ] **Step 4: Rewrite `ContainerProbe.kt` (rootfs-internal smoke, no wrapper chain)**

```kotlin
package com.dshmobile.shell

import java.io.File

/**
 * Container smoke test: runs a real command inside the single rootfs via the
 * exact proot argv the engine uses, so a failure here means the container is
 * genuinely unusable — proot binary, its shared libs and the rootfs node are
 * all exercised. Runs outside the engine process (fresh ProcessBuilder).
 */
class ContainerProbe(
  private val prootRuntime: ProotRuntime,
  private val rootfsDir: File,
  private val projectsDir: File,
  private val pickToken: String,
) {
  /** Returns null on success, or the combined output tail on failure. */
  fun smokeTest(): String? =
    try {
      val (args, env) = prootRuntime.buildEngineArgs(rootfsDir, projectsDir, 3080, pickToken)
      // Replace the engine argv with a bounded smoke command: keep the proot
      // prefix, swap everything after "--" for `bash -c 'echo CONTAINER_OK; id'`.
      val sep = args.indexOf("--")
      val smokeArgs = args.take(sep + 1).toMutableList() + listOf("/bin/bash", "-c", "echo CONTAINER_OK; id -u")
      val pb = ProcessBuilder(smokeArgs).also { b ->
        b.environment().putAll(env)
        b.redirectErrorStream(true)
      }
      val proc = pb.start()
      if (!proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
        AppLog.log("boot", "container smoke probe hung, killing")
        proc.destroyForcibly()
      }
      val out = proc.inputStream.bufferedReader().readText()
      if (out.contains("CONTAINER_OK")) null else out.trim().take(600)
    } catch (t: Throwable) {
      (t.message ?: t.javaClass.simpleName).take(600)
    }
}
```

- [ ] **Step 5: Delete obsolete files and update `SnapshotVersion.kt`**

Delete: `UnwindResolver.kt`, `RootfsDownloader.kt`, `app/src/main/cpp/` (CMakeLists.txt, exec-hook.c, bash-wrapper.c).

SnapshotVersion — change the path:

```kotlin
    val file =
      File(
        context.filesDir,
        DshPaths.ROOTFS_DIR + "/root/.dsh-arm64/node_modules/@deepseek-ai/dsh/package.json",
      )
```

(Use the same constant expression as `DshPaths.DSH_ENTRY` minus `lib/bin.js`; hardcode the overlay path with a comment pointing at the overlay layout.)

- [ ] **Step 6: Update unit tests for the new runtime layer**

`app/src/test/java/com/dshmobile/shell/EngineManagerTest.kt` — the old test asserted snapshot/env/hook behavior. Replace with tests that:
- `engineReady` is false when the rootfs is missing (Robolectric fresh filesDir).
- `startEngine()` returns false when proot assets are absent (no `assets/proot/arm64-v8a/proot`).

```kotlin
package com.dshmobile.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EngineManagerTest {
  @Test
  fun engineReadyFalseWhenRootfsMissing() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val mgr = EngineManager(ctx)
    assertFalse(mgr.engineReady)
  }

  @Test
  fun startEngineFailsWithoutProotAssets() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val mgr = EngineManager(ctx)
    assertFalse(mgr.startEngine())
  }
}
```

`ProotRuntimeTest.kt` — assert the argv shape:

```kotlin
package com.dshmobile.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ProotRuntimeTest {
  @Test
  fun engineArgsBootNodeInsideRootfs() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val rt = ProotRuntime(ctx)
    val (args, env) = rt.buildEngineArgs(
      File("/data/user/0/x/rootfs"),
      File("/data/user/0/x/projects"),
      3080,
      "tok",
    )
    assertTrue(args.joinToString(" ").contains("--kill-on-exit"))
    assertTrue(args.joinToString(" ").contains("-r /data/user/0/x/rootfs"))
    assertEquals("/root/.dsh-arm64/node_modules/@deepseek-ai/dsh/lib/bin.js", args.last { it.endsWith("bin.js") })
    assertTrue(args.joinToString(" ").contains("--expose-internals"))
    assertTrue(env.getValue("LD_LIBRARY_PATH").isNotEmpty())
  }
}
```

Delete `RootfsDownloaderTest.kt` (class deleted). `SnapshotExtractorTest.kt`, `UpdateManagerTest.kt` pass unchanged (Task 4 updates UpdateManagerTest for the swap semantics).

- [ ] **Step 7: Update `tests/run-local.sh` (C tests removed; JS stays)**

```bash
#!/usr/bin/env bash
set -euo pipefail
# JS polyfill tests (node required). The exec-hook / bash-wrapper C tests are
# gone with the single-runtime refactor (no forwarding layer).
cd "$(dirname "$0")"
command -v node >/dev/null || { echo "node required for JS tests"; exit 1; }
node js/polyfills.test.js
echo "==> JS tests OK"
```

Delete `tests/c/` (exec-hook-test.c, bash-wrapper-test.c, argv0safe.c, bash-fix-test.c).

- [ ] **Step 8: Commit**

```bash
cd /root/projects/dsh-apk
git rm -r app/src/main/cpp app/src/main/java/com/dshmobile/shell/UnwindResolver.kt \
  app/src/main/java/com/dshmobile/shell/RootfsDownloader.kt app/src/test/java/com/dshmobile/shell/RootfsDownloaderTest.kt
git add -A
git commit -m "refactor: single runtime — engine inside the embedded Debian rootfs"
git push
```

Expected: CI quality gate green (assembleDebug + lintDebug + ktlintCheck + unit tests + JS tests). The app no longer references usr/, exec-hook, unwind, or RootfsDownloader.

---

### Task 4: Boot flow, update mechanism, strings

**Files:**
- Modify: `/root/projects/dsh-apk/app/src/main/java/com/dshmobile/shell/MainActivity.kt`
- Modify: `/root/projects/dsh-apk/app/src/main/java/com/dshmobile/shell/UpdateManager.kt`
- Modify: `/root/projects/dsh-apk/app/src/main/java/com/dshmobile/shell/EngineService.kt`
- Modify: `/root/projects/dsh-apk/app/src/main/res/values/strings.xml`
- Modify: `/root/projects/dsh-apk/app/src/main/res/values-zh/strings.xml`

**Interfaces:**
- Consumes: `EngineManager.extractRootfs(onProgress)`, `EngineManager.ensureDshDataHome()`, `ContainerProbe(prootRuntime, rootfsDir, projectsDir, pickToken)`.

- [ ] **Step 1: `MainActivity.kt` — flow: extract rootfs → container smoke → launch**

Replace `startEngineFlow()`'s Step 1/Step 2 with:

```kotlin
        var setupRan = false
        val prootRuntime = ProotRuntime(this)
        val rootfsDir = engineManager.rootfsDir
        if (!engineManager.engineReady) {
          runOnUiThread {
            showGuide()
            wizard.renderSteps(0, 0)
            wizard.showGuideStatus(getString(R.string.status_first_extract), null, true)
          }
          AppLog.log("boot", "extracting rootfs to " + rootfsDir)
          val ok = engineManager.extractRootfs { done, _ ->
            runOnUiThread {
              wizard.showGuideStatus(
                getString(R.string.status_extracting, done / 1024 / 1024),
                null,
                true,
              )
            }
          }
          if (!ok) {
            runOnUiThread { wizard.showGuideError(getString(R.string.status_extract_failed)) }
            AppLog.log("boot", "extract FAILED")
            return@Thread
          }
          setupRan = true
          AppLog.log("boot", "extract ok, engineReady=" + engineManager.engineReady)
        }
        // Step 2 — container is mandatory: proot runtime must be present and
        // a real in-container command must pass (rootfs bash + node). A
        // failing container counts as an engine start failure.
        if (!prootRuntime.ensureProot()) {
          runOnUiThread {
            wizard.renderSteps(1, 1)
            wizard.showGuideError(getString(R.string.status_container_init_failed))
          }
          AppLog.log("boot", "container init FAILED: proot runtime unavailable")
          return@Thread
        }
        val projectsDir = File(engineManager.ensureDshDataHome(), DshPaths.PROJECTS_DIR)
        val probe = ContainerProbe(prootRuntime, rootfsDir, projectsDir, EngineManager.pickToken)
        val smoke = probe.smokeTest()
        if (smoke != null) {
          AppLog.log("boot", "container init FAILED: " + smoke)
          runOnUiThread {
            wizard.renderSteps(1, 1)
            wizard.showGuideError(getString(R.string.status_container_init_failed))
          }
          return@Thread
        }
        AppLog.log("boot", "container init: smoke test pass")
```

And replace the containerProbe lazy field at the top of MainActivity:

```kotlin
  /** Container smoke probe: runs a real command inside the proot rootfs. */
  private val containerProbe by lazy {
    ContainerProbe(
      ProotRuntime(this),
      engineManager.rootfsDir,
      File(engineManager.ensureDshDataHome(), DshPaths.PROJECTS_DIR),
      EngineManager.pickToken,
    )
  }
```

The engine probe/poll loop (Step 3 / `launchEngineInternal`) is unchanged: `engineManager.startEngine()` now starts proot.

- [ ] **Step 2: `EngineService.kt` — nothing to change**

Verify the service only calls `engineManager.engineReady` / `startEngine()` (it does; grep confirmed lines 70/92). No edits needed.

- [ ] **Step 3: `UpdateManager.kt` — rootfs swap**

Replace the extract/validate/swap block:

```kotlin
        onStatus(context.getString(R.string.update_extracting))
        // The archive IS the rootfs (no usr/ prefix); stage it OUTSIDE the
        // live tree and swap rootfs → rootfs-old → new rootfs.
        SnapshotExtractor.extract(
          tmp.inputStream(),
          manifest.optLong("size", 0),
          stage,
          { _, _ -> },
        )
        if (!File(stage, DshPaths.ROOTFS_NODE).exists()) {
          throw IllegalStateException(context.getString(R.string.err_new_snapshot_no_node))
        }

        onStatus(context.getString(R.string.update_switching))
        val rootfs = File(context.filesDir, DshPaths.ROOTFS_DIR)
        val old = File(context.filesDir, "rootfs-old")
        deleteRecursively(old)
        if (rootfs.exists() && !rootfs.renameTo(old)) {
          throw IllegalStateException(context.getString(R.string.err_old_runtime_switch))
        }
        if (!stage.renameTo(rootfs)) {
          if (old.exists() && !old.renameTo(rootfs)) {
            throw IllegalStateException(context.getString(R.string.err_switch_failed_rollback))
          }
          throw IllegalStateException(context.getString(R.string.err_switch_failed_rolled_back))
        }
```

And the manifest default:

```kotlin
    /** Single-runtime rootfs updates come from dsh-io/dsh-arm64 releases. */
    const val DEFAULT_MANIFEST_URL =
      "https://github.com/dsh-io/dsh-arm64/releases/latest/download/manifest.json"

    /** Total-size cap for rootfs downloads: the artifact is ~80MB; 500MB
     *  leaves ample headroom while preventing storage exhaustion (I-09). */
    const val MAX_SNAPSHOT_BYTES = 500L * 1024 * 1024
```

(The `manifestUrl` HTTPS setter stays; the update test entry point / `pkill -f bin.js` stays.)

- [ ] **Step 4: `strings.xml` + `values-zh/strings.xml` — container wording**

English (`values/strings.xml`):

```xml
  <string name="container_install_done">Runtime ready</string>
  <string name="container_install_failed">Runtime install failed</string>
  <string name="status_container_installing">Preparing runtime…</string>
  <string name="status_container_installing_detail">One-time local setup</string>
  <string name="status_container_init_failed">Runtime failed to initialize. Please retry.</string>
  <string name="err_new_snapshot_no_node">The new runtime is missing node</string>
  <string name="update_downloading">Downloading runtime (%1$d MB)…</string>
  <string name="update_extracting">Extracting new runtime…</string>
```

Chinese (`values-zh/strings.xml`): update the same keys — `安装运行时…` / `首次本地初始化` / `运行时初始化失败,请重试。` / `新运行时缺少 node` / `正在下载运行时(%1$d MB)…` / `正在解压新运行时…`。(Match the existing translation style in the file.)

- [ ] **Step 5: Update `UpdateManagerTest.kt` swap assertions**

The existing test drives `checkAndApply` with a fake fetcher and a stub manifest pointing at a local file URL — adjust the manifest `url`/`sha256` to a rootfs-shaped archive (top-level `bin/`, `usr/local/bin/node` present after extraction) and assert the swap moved `rootfs → rootfs-old` and the new `rootfs/usr/local/bin/node` exists. Keep the HTTPS enforcement and missing-sha256 refusal tests unchanged.

- [ ] **Step 6: Commit**

```bash
cd /root/projects/dsh-apk
git add -A
git commit -m "feat: single-runtime boot flow, rootfs update swap, wording"
git push
```

Expected: CI green.

---

### Task 5: Docs + acceptance checklist

**Files:**
- Modify: `/root/projects/dsh-apk/README.md`
- Modify: `/root/projects/dsh-apk/README.zh.md`
- Modify: `/root/projects/dsh-apk/AGENTS.md`
- Modify: `/root/projects/dsh-apk/docs/design.md` (runtime sections)
- Modify: `/root/projects/dsh-apk/docs/verification/container-acceptance.md`

- [ ] **Step 1: Rewrite README runtime sections**

Replace the "Embedded runtime" / "Ubuntu container" / "Universal exec layer" feature bullets with a single-runtime description: one embedded Debian bookworm glibc rootfs (from dsh-io/dsh-arm64, `dsh-arm64-rootfs-<v>.tar.xz`) holding node 22 + dsh + bash + coreutils + pre-provisioned China mirrors; proot is the only native asset; no exec hook, no wrapper, no `_Unwind_Resume` patch. Update:
- "Build" section: asset is `app/src/main/assets/rootfs.tar.xz` from dsh-io/dsh-arm64 releases.
- "Architecture" table: drop `UnwindResolver`, `RootfsDownloader`; note `ProotRuntime` = proot extraction + argv builder; `SnapshotExtractor` extracts the rootfs; `UpdateManager` swaps the rootfs.
- "First-run flow": extract rootfs → container smoke → launch.
- "Storage layout": `filesDir/rootfs` (was usr + rootfs), `filesDir/proot`, `filesDir/etc/resolv.conf`; engine log unchanged.
- "ABI & pagesize": arm64-only paragraph.
- "Known limitations": remove the Ubuntu first-run network item; add the glibc-exec-on-Android-15+ note (risk 1 from the spec).
- "Related projects": add `dsh-io/dsh-arm64`.

- [ ] **Step 2: Update `README.zh.md`** with the same changes (mirror the English edits in Chinese).

- [ ] **Step 3: Update `AGENTS.md`** — build section: "the runtime rootfs `app/src/main/assets/rootfs.tar.xz` must exist (CI downloads it from dsh-io/dsh-arm64 releases)".

- [ ] **Step 4: Update `docs/design.md`** — replace the Termux-snapshot/Ubuntu-container/wrapper/exec-hook sections with the single-runtime design; keep the historical issue log in `docs/issues.md` untouched (it records what was fixed).

- [ ] **Step 5: Rewrite `docs/verification/container-acceptance.md`**

```markdown
# Single-runtime acceptance (on device)

Setup: fresh install of the APK from the v0.2.0-rc.1 release (arm64-v8a).

1. First open goes straight into the install flow: wizard shows "Preparing
   your workspace…" / "Extracting runtime…" (no download step).
2. Step 1: rootfs extracts (progress on the wizard).
3. Step 2 (mandatory): "Preparing runtime…" → container smoke test runs
   (`echo CONTAINER_OK; id -u` inside proot); AppLog shows
   `container init: smoke test pass`.
4. Step 3: press "Launch engine"; the web UI opens. Cold start on an already
   provisioned install runs under the thin status bar.
5. In the agent UI, ask for a shell command: `cat /etc/os-release`
   → expect `Debian GNU/Linux 12 (bookworm)` (proot runs, container bash
   answers).
6. `id` → expect `uid=0(root)` (fake root via -0).
7. `apt-get update && apt-get install -y git` → expect success (TUNA mirror).
8. `node --version && git --version` → versions printed (same node as the
   engine: /usr/local/bin/node).
9. `pwd` → `/root/projects` (pre-created workspace); create a file there,
   verify it appears in host `Documents/dshdata/projects`.
10. **Android 15+ device**: confirm the engine boots (glibc node exec inside
    app data). If the start fails with `Permission denied`, the linker64
    fallback applies to proot only — record the failure and report back.
11. Container failure path: delete `files/rootfs` (adb run-as), reopen the app
    → reinstall flow runs, engine does NOT start until the smoke test passes.
12. Regression: directory pick, session export, check update (manifest from
    dsh-io/dsh-arm64 latest release).
```

- [ ] **Step 6: Commit**

```bash
cd /root/projects/dsh-apk
git add -A
git commit -m "docs: single-runtime README/design/acceptance; arm64 only"
git push
```

---

### Task 6: Release v0.2.0-rc.1

**Files:**
- `/root/projects/dsh-apk` tag + `/root/projects/dsh-arm64` tag (rootfs artifact must exist first)

- [ ] **Step 1: Publish the rootfs artifact from dsh-arm64**

```bash
cd /root/projects/dsh-arm64
# wait for the rootfs CI job to pass on an existing tag, then tag the commit
git tag v0.1.0-rc.6-rootfs1 && git push origin v0.1.0-rc.6-rootfs1
```

(Or run `workflow_dispatch`/a normal `v*` tag on the dsh-arm64 commit containing Task 1 — the tag name is free-form; the release must carry `dsh-arm64-rootfs-<version>.tar.xz`, `rootfs.sha256sums`, `manifest.json`, plus the existing overlay artifacts.)

- [ ] **Step 2: Tag and publish the APK**

```bash
cd /root/projects/dsh-apk
git tag v0.2.0-rc.1 && git push origin v0.2.0-rc.1
```

Expected: the Release workflow downloads the rootfs artifact from dsh-arm64's latest release, builds the signed arm64 APK, and publishes `dsh-apk-v0.2.0-rc.1.apk`. Check the release assets exist and the APK installs on the device.

- [ ] **Step 3: Verify release artifacts**

```bash
gh release view v0.2.0-rc.1 -R deepcode-lab/deepseek-harness-mobile
gh run list -R deepcode-lab/deepseek-harness-mobile -L 3 --json workflowName,conclusion,headBranch
```

Expected: release lists the APK; Release workflow green.

---

## Self-review notes

- Spec coverage: rootfs artifact (Task 1), shell changes delete/modify/keep matrix (Tasks 2-4), update mechanism (Task 4), testing layers (Task 1 host verify + CI, Task 3 unit tests, Task 5 device checklist), risks (glibc exec → Task 5 acceptance item 10, node-pty → same, APK size → noted, reproducibility → CI job), out-of-scope (x86_64 deleted in Task 2).
- Placeholder scan: no TBD/TODO; every code block is complete.
- Type consistency: `extractRootfs`, `buildEngineArgs`, `smokeTest`, `DshPaths.ROOTFS_DIR/DSH_ENTRY/ROOTFS_NODE/ROOTFS_ASSET/CONTAINER_DSH_HOME/PROJECTS_DIR` are defined once (Task 3) and consumed with identical names (Tasks 3-5). `startEngine(port)/engineReady/ensureDshDataHome/pickToken` signatures unchanged. ContainerProbe constructor matches MainActivity's `containerProbe` lazy (Task 4). `DEFAULT_MANIFEST_URL` points at the artifact produced by Task 1's CI step.