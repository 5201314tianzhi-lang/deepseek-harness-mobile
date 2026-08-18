# Proot 容器业务逻辑深度排查报告

> 2026-08-18 排查产出。范围：proot 容器全链路（wrapper 安装、proot 运行时、
> rootfs 下载/校验/安装、容器冒烟探针、引擎启动集成、exec-hook 重路由）。
> 按 P1 = 高（会导致容器不可用或数据分叉）、P2 = 正确性、P3 = 健壮性/低优先级。
> 状态：🟥 待处理 / 🟨 处理中 / 🟩 已修复
> 已排查确认无问题的部分见文末「排查通过」清单（完整审计留痕）。

---

## 0. 架构链路（排查基准）

```
dsh agent（node 进程，LD_PRELOAD=exec-hook[:unwind]）
  → bash-wrapper ELF（usr/bin/bash，NDK 构建，经 linker64 重路由加载）
      · 路径从 DSH_FILES_DIR / DSH_WORKSPACE 环境变量解析（引擎注入）
  → proot（filesDir/proot/，含 libtalloc.so.2 / libandroid-shmem.so）
      · PROOT_TMP_DIR = filesDir/home/tmp
      · 绑定：resolv.conf、DSH_WORKSPACE → 容器 /root/projects
  → 容器 bash（rootfs/bin/bash，Ubuntu base 24.04 x86_64/arm64）
      · rootfs 提取时贴 security.android.exec + 去写位（W^X）
exec-hook（app/src/main/cpp/exec-hook.c）
  · /bin/bash → usr/bin/bash 改写；子进程 env 被 dsh scrub 时重注入 DSH_*
  · ELF 一律经 /system/bin/linker64 重路由，失败回退原生 exec
```

---

## P1 高优先级

### P1-01 硬编码 Ubuntu point release（24.04.3），上游更替后安装必 404 🟥

**位置**：`RootfsDownloader.kt:18`（`TARBALL_PREFIX = "ubuntu-base-24.04.3-base-"`）、
`:54`（tarballName 拼接）

**问题**：
- tarball 名和 SHA256SUMS 匹配都钉死在 `24.04.3`。cdimage 的
  `releases/24.04/release/` 目录随点版本滚动更新，旧 point release 会被清理。
- 实测上游现状：目录中已有 `24.04.3` 和 `24.04.4` 两个点版本 —— 当 `24.04.3`
  被清理（上游惯例）后，**新装用户（含换机/清数据）永久卡在下载失败**，且由于
  SHA256SUMS 匹配不上，会被「无校验拒绝安装」正确拦截（不装坏东西，但永远装不上）。
- 顺带：容器将永远停留在最老的可用版本，无法随上游滚动。

**修复**：安装时拉取 SHA256SUMS，解析当前存在的 `24.04.N`（数值排序取最新）
+ 当前 arch 的条目，校验和必选（维持现有安全策略）。

### P1-02 容器工作区路径分叉：冒烟探针 ≠ 引擎实际挂载 🟥

**位置**：`EngineManager.kt:320`（字面量 `"workspace"`）vs
`MainActivity.kt:57,337` + `DshPaths.kt:22`（`PROJECTS_DIR = "projects"`）

**问题**：
- 引擎把 `dshdata/workspace` 绑定进容器 `/root/projects`；而容器冒烟探针和
  容器初始化用的是 `dshdata/projects`（文档化路径）。
- 后果：冒烟测试验证的是一套挂载，引擎实际跑的又是另一套 —— 探针通过
  **不代表**容器的真实工作区可用；用户数据也会在两个目录间漂移（引擎侧
  写入 `workspace`，用户/文件管理器看到 `projects`）。

**修复**：统一为 `DshPaths.PROJECTS_DIR`（删除 `"workspace"` 字面量）。
注意：若真机 `dshdata/workspace` 已有旧数据，升级后不再挂载（pre-release，
按 AGENTS.md 不做兼容迁移）。

### P1-03 exec-hook 不重注入 LD_PRELOAD，容器 exec 链在硬编码设备失守 🟥

**位置**：`exec-hook.c:118-152`（`env_with_bash_paths`）

**问题**：
- 该函数在子进程 envp 被 dsh scrub（已证实会剥掉 DSH_*）时，只重注入
  `DSH_FILES_DIR` / `DSH_WORKSPACE`，不补 `LD_PRELOAD`。
- 链路推演：scrub 后的子进程 exec wrapper → wrapper 环境里没有 hook →
  proot → 容器 bash 的 exec 全部走**原生内核路径**（app 数据域 ELF）→
  Android 15+ / EMUI W^X 设备直接 EACCES，容器整体不可用。
- 冒烟探针同样测不出来：它自己也依赖 LD_PRELOAD 注入（`ContainerProbe.kt:45`），
  无法覆盖「子进程 env 被剥」这一分支。

**修复**：重注入 DSH_* 的同时，缺失则注入 hook 自身环境的 `getenv("LD_PRELOAD")`
（引擎进程必有）。扩展 `tests/c/bash-fix-test.c` 断言三种变量都被补回。

### P1-04 并发提取竞争：watchdog 与启动流双实例无锁写文件 🟥

**位置**：`ProotRuntime.kt:42-58,137-154`（`ensureWrapper` / `ensureProot`，实例方法无锁）

**问题**：
- `MainActivity` 容器初始化（启动线程）与 `EngineManager.startEngine`
  （主 UI 线程 + `EngineService` watchdog 线程）各建一个 `ProotRuntime`
  实例，同时提取 `usr/bin/bash` 与 `filesDir/proot/*`。
- 两个线程交错写同一文件 → 撕裂的 ELF；marker 写入也有竞态（一侧写完
  另一侧又覆盖）。实例级 `synchronized` 救不了（不同实例），需要共享锁。

**修复**：companion object 共享锁，`ensureProot` / `ensureWrapper` /
`ensureInitialized` 全部走同一把锁（Java 监视器可重入，嵌套调用无碍）。

### P1-05 wrapper 标记陈旧：应用更新后永不替换新 wrapper 🟥

**位置**：`ProotRuntime.kt:47`（marker 短路）

**问题**：
- 短路条件是 `bash.isFile && marker.isFile && length > 0`，marker 内容
  恒定 `"1"`，**与 APK 里的 wrapper 无关**。
- usr/ 树只在快照 swap（在线更新）时整体替换；普通应用升级（APK 重装）
  不动 usr/。于是新 APK 携带修复后的 wrapper ELF 时，已装用户**永远跑旧
  wrapper**（marker 一直在）。

**修复**：marker 记录 APK 内 `lib/<abi>/bash-wrapper` 条目大小，大小不匹配
即重提取；APK 无条目（测试环境）时信任已装文件。

---

## P2 正确性

### P2-01 startEngine 忽略 ensureWrapper() 返回值，容器损坏也照常启动 🟥

**位置**：`EngineManager.kt:320-322`

**问题**：
- `ensureWrapper()` 返回 false（proot 资产缺失/提取失败）时引擎照常启动，
  进入「引擎活着、容器死了」的降级状态；watchdog 重启路径没有任何容器
  检查（冒烟测试只在首次启动流跑）。
- 与设计文档相悖：「容器失败 = 引擎启动失败」。

**修复**：`ensureWrapper()` 失败时拒绝启动并记录日志。

### P2-02 校验和请求无 readTimeout，启动线程可无限挂起 🟥

**位置**：`RootfsDownloader.kt:112`（仅 `connectTimeout = 30_000`）

**问题**：
- `install()` 运行在启动线程（引导流程）。SHA256SUMS 连接建立后服务端
  挂起不再响应 → `read` 永久阻塞 → 引导 UI 永远卡在「容器安装中」。

**修复**：补 `readTimeout = 30_000`（与 `Downloader` 的读超时策略一致）。

---

## P3 健壮性 / 低优先级

### P3-01 applyMirrors 创建的 rootfs/root/projects 被运行时 bind 遮蔽 🟥

**位置**：`ProotRuntime.kt:179`

**问题**：容器启动时 `DSH_WORKSPACE` 会 bind 挂载覆盖
`rootfs/root/projects`，该 mkdirs 永远不可见 —— 无害死代码（保留也
无碍，proot 对缺失 guest 路径的处理依赖它兜底）。

**处理**：不改（或顺手移除）。

### P3-02 bash-wrapper env 分配失败路径泄漏 🟥

**位置**：`app/src/main/cpp/bash-wrapper.c`

**问题**：`setenv` 前 malloc 的 env 副本在部分失败分支未 free。exec 语义下
进程将不复存在，实际影响可忽略。

**处理**：随 P1-03 一并修掉（同为 C 侧）。

### P3-03 ContainerProbe 先 waitFor 再读管道 🟥

**位置**：`ContainerProbe.kt:51-55`

**问题**：`waitFor(30s)` 期间不排空 stdout；子进程输出超管道容量（64KB）
时阻塞，直到 `destroyForcibly` 才解除。有界（30s 上限 + kill 后管道关闭），
风险低。

**处理**：改为后台线程排空或重定向到文件（对齐 `startWithArgs` 的做法）。

### P3-04 wrapper 丢弃 argv[0] 🟥

**位置**：`app/src/main/cpp/bash-wrapper.c`

**问题**：wrapper 用 argv[1..] 拼接 proot 参数，argv[0]（"bash"）丢失。
当前无调用方依赖，功能无碍。

**处理**：不改。

---

## 已知局限（本次不修，需真机验证的长期项）

### L-01 容器内 apt 安装的二进制失去 exec 属性，W^X 强制设备上不可执行

- 基础 rootfs 提取时已整体贴 `security.android.exec` + 去写位
  （`SnapshotExtractor.kt:15-18,201-218`，`RootfsDownloader.kt:124-138`）。
- 但容器内后续 `apt install` / `apt upgrade` 由 dpkg 写入的**新二进制**
  是普通 755 文件：无 exec 属性且可写可执行 → Android 15+ / EMUI W^X
  强制设备上原生 exec 被内核拒绝（容器内 glibc ELF 无法经 linker64 加载，
  必然落到原生 exec 路径）。
- 即：**rootfs 装好后第一次 `apt install gcc` 之后，新装命令在强制设备
  上跑不起来**（基础包不受影响）。
- 修复方向：在 rootfs 内装 dpkg/apt Post-Invoke 钩子，操作后调用宿主
  `/system/bin/setfattr` 贴标 + chmod 555。侵入面大、需真机验证，暂记为
  已知局限。

---

## 修复顺序建议

1. **C 侧（本地可验证）**：P1-03 → 扩展 `tests/c/bash-fix-test.c` + fake-linker
   断言 → `./tests/run-local.sh` 全绿；顺手 P3-02。
2. **Kotlin 侧核心**：P1-01（含 resolveFromSums 单测）、P1-02、P2-02。
3. **一致性/并发**：P1-04（共享锁）、P1-05（marker 指纹）、P2-01（拒绝启动）。
4. **低优先级**：P3-01 / P3-03 按需。
5. 验证：本地 ktlint + `./tests/run-local.sh` → 提交推送 → CI 质量门
   （assembleDebug + lintDebug + ktlintCheck + testDebugUnitTest）。

---

## 排查通过（已确认无问题，留痕）

| 组件 | 结论 |
|------|------|
| `Downloader.kt` 超时 | connect 30s / read 60s，齐全 ✅ |
| `UpdateManager.kt` 快照 swap | marker 随 usr/ 树整体替换；proot/、rootfs 不受影响 ✅ |
| `ProotRuntime.resolvConf` | 幂等创建，AliDNS 优先（CN 友好）✅ |
| TMPDIR 语义 | 宿主 `filesDir/home/tmp`（wrapper）；容器内 `/tmp` 在 rootfs 中真实存在 ✅ |
| exec-hook `is_elf` | O_NONBLOCK 防 FIFO 阻塞 ✅ |
| `harden` W^X 幂等 | marker 命中时重加硬（`ProotRuntime.kt:50`）✅ |
| RootfsDownloader 状态机 | missing/ready/failed 三态 + 单飞 RUNNING ✅ |
| exec-hook 重路由 fallthrough | linker 拒绝（glibc 容器 ELF 等）时回退原生 exec，容器由此可跑 ✅ |
| rootfs 安装 stage/swap | 旧 rootfs 保留可回滚，中断不毁树 ✅ |
| exec 属性贴标（提取时） | 快照与 rootfs 同路径处理，批处理 setfattr ✅ |
