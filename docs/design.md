# 壳 APK 设计（dsh-mobile-apk）

> v3.0 ｜ 2026-08-16 更新：与当前代码逐项对应（首次同意门、proot Ubuntu 容器、
> 引擎生命周期归服务、进程级 token、解压/更新幂等）。审查/修复记录见 `docs/issues.md`。

---

## 1. 形态与边界

- **纯壳**：WebView 只消费 `http://127.0.0.1:3080`（内嵌快照的 dsh web 服务）；
  壳与引擎版本解耦（桥协议版本化 `androidBridge.version`）。
- **自足运行时**：APK 内嵌 ~79MB xz 快照，首次安装解压出约 484MB
  （node + bash + coreutils + dsh + 插件）到 `filesDir/usr`，无需安装 Termux。
- **首次知情同意门**：全新安装首次打开先展示存储/耗时/须知（≈600MB、2-5 分钟、
  网络需求等），用户显式"同意并开始"后才进入安装流程；同意记录于
  SharedPreferences，覆盖升级不重复。
- **proot Ubuntu 容器（强制）**：agent 的 shell 走容器（Ubuntu 24.04 rootfs
  约 35MB，首次从官方源下载 + SHA256 校验）；容器冒烟失败 = 引擎启动失败。
- **零侵入**：页面侧不改动；桥能力全部经 `@JavascriptInterface` 注入。
- **引导向导**：白色三步向导（运行时 → 容器 → 启动），就绪后由用户手动启动
  引擎；全部就绪时冷启动直进 Harness（顶部呼吸状态条）。

## 2. 组件架构

```
MainActivity（编排）
 ├─ onCreate：同意门（consent_seen）→ startEngineFlow 或同意卡
 ├─ startEngineFlow()：CAS 防并发
 │   ├─ EngineProbe.check() —— 127.0.0.1:3080 可达性
 │   ├─ EngineManager.extractSnapshot() —— 首次解压（第 1 步）
 │   ├─ ProotRuntime + RootfsDownloader —— 容器安装（第 2 步，强制）
 │   ├─ ContainerProbe.smokeTest() —— 容器链路冒烟（失败=引擎启动失败）
 │   └─ launchEngineInternal() —— 引擎启动 + 60s 轮询 + 前台服务
 ├─ showWeb() —— reloadIfFailed() 策略 + 冷启动顶部条 6s 淡出
 ├─ ExportFlow —— 引擎同源下载 → MediaStore（禁重定向、200MB 上限）
 └─ pushSystemDark() —— 系统深色 → __dshThemeBridge.setDark

GuideWizard（纯 UI）—— 同意卡、三步状态卡、动作行、顶部条（呼吸点）
HarnessWebView —— WebView 配置、引擎源导航门、兼容层注入、loadFailed 标志
PickerBridge —— SAF 目录/文件选择（主线程 launch、跨重建保留待决回调）
AndroidBridge —— window.androidBridge JS 接口（协议 v1）

EngineManager（引擎进程与数据）
 ├─ ensureDshDataHome() —— dshdata 迁移/重连（公共用户数据）
 ├─ resolveBundledHook()/UnwindResolver —— exec-hook + libunwind-patch.so
 └─ startWithArgs() —— exec 被拒时回退 /system/bin/linker64

ProotRuntime —— proot/libtalloc/libandroid-shmem 资产 + bash 包装生成
RootfsDownloader —— rootfs 下载（SHA256 硬校验）+ staging 原子切换
ContainerProbe —— 容器冒烟（30s 受限等待）
UpdateManager —— manifest 驱动在线更新（单飞 + 唯一暂存名 + 回滚）
SnapshotExtractor —— xz-tar 解压（穿越防护 + symlink/hardlink + W^X + exec 打标）
EngineService —— 前台服务：拥有引擎生命周期 + 5s 看门狗（总 arm、异常保护）
EngineProbe / EngineSource / Downloader / NotificationHelper / ShizukuSupport
```

## 3. 桥协议 v1（window.androidBridge）

| 方法 | 签名 | 说明 |
|---|---|---|
| version | getter → string | 桥协议版本 `"1.0"`（feature-detect） |
| checkEngine | () → string | 探测 127.0.0.1:3080，返回 `{running:bool, latencyMs:int, error?:string}` JSON |
| keepScreenOn | (enable: boolean) | 屏幕常亮开关（单个共享 wakelock 实例，可重复开关） |
| showNotification | (title, text) | 测试通知通道（API 33+ 运行时请求权限；授权回调后补发排队通知） |
| pickDirectory | (callbackId: string) | SAF 目录选择；结果异步经 `onDirectoryPicked(callbackId, path\|null)` 回传 |
| hasAllFilesAccess | () → boolean | 是否持有 All Files Access（API 30+ 才存在该模型） |
| requestAllFilesAccess | () → void | 打开系统授权页（逐应用页优先，厂商缺失时回退全局页） |
| getPickToken | () → string/null | 目录选择桥**进程级**会话 token（引擎侧 pick 端点校验，env `DSH_PICK_TOKEN`；看门狗重启的引擎与 WebView 桥持有同一值，重启后不失配） |

**Kotlin → JS 异步回传通道**：

| 通道 | 载荷 | 语义 |
|---|---|---|
| `window.__dshBridge.onDirectoryPicked(callbackId, path)` | path 为真实路径或 `content://` URI；`null` 表示取消/不可用 | 选择结果 |
| `window.__dshBridge.onPermissionRequired()` | — | 缺 All Files Access，引导后重试 |
| `window.__dshExportResult(ok, title, detail)` | — | 会话日志导出结果（应用内弹框） |
| `window.__dshThemeBridge.setDark(boolean)` | — | 系统深色状态（厂商 WebView 不跟随 uiMode 的补丁） |

**目录选择并发模型**：单槽 `pendingPickCallback`，在途时新请求立即以取消结算
（防止覆盖导致旧 pick 永不结算）。API < 30 无该权限模型，直接取消并提示。
待决回调随 `onSaveInstanceState` 保存、重建后恢复（否则引擎侧 promise 永不
结算、页面反复重开选择器）。`ActivityResultRegistry.launch` 一律回到主线程
（JS 桥可能从 WebKit 线程调用）。

## 4. 引擎生命周期与并发控制

### 4.0 通用执行层（exec 重路由）

**问题**：Android 在多种场景拒绝直接 exec app-data ELF：
- Android 15+（targetSdk 35+ 策略）；
- 厂商 W^X 强化（华为/EMUI 拒绝"可写+可执行"文件，Android 10 实测 EACCES）；
- 各版本/厂商差异无法逐一兼容。

**方案**：APK 内置自研 `libexec-hook.so`（NDK，双 ABI），`LD_PRELOAD` 注入
引擎进程树，拦截 `execve/execv/execvp/execvpe`：

- 目标为**同架构 ELF** 时（e_machine 与构建 ABI 匹配），重路由为
  `execve(/system/bin/linker64, [linker64, path, argv...])` ——系统链接器以
  "加载 native 库"的机制加载目标（全版本、全厂商一致允许）；跨架构 ELF 与
  非 ELF（脚本/shebang）直接放行原生 exec；
- linker 自身精确匹配放行（前缀比较会误伤 `/system/bin/linker64-*`）；
- 重路由**任何失败都回退原始 syscall**（linker 可能拒绝无 PT_INTERP 等
  内核本可执行的二进制）；
- ELF 探测 `open` 带 `O_NONBLOCK`（PATH 搜索命中 FIFO 时不挂死 exec 链）；
  `argv == NULL`（POSIX 允许）按空 argv 处理；
- `LD_PRELOAD` 随 linker64 加载的进程树继承，重路由覆盖全部子进程
  （node 插件、bash、工具链）；
- 不依赖 SELinux 域判断（区别于快照 termux-exec 仅豁免 untrusted_app_25/27）。

**`_Unwind_Resume` 补丁**：Termux 的 libc++ 不导出 `_Unwind_Resume`
（libunwind 静态链入各二进制），node-pty 等 dlopen 的原生模块加载失败。
CI 内把快照自带 `libunwind.a` 归档链接为 `libunwind-patch.so`（符号
st_other 由 GLOBAL HIDDEN 补丁为 DEFAULT），随 APK 发布，探针找到后并入
`LD_PRELOAD`（exec-hook:libunwind-patch）。

**三层防护**（通用，无版本/厂商特判）：

| 层 | 机制 | 覆盖 |
|---|---|---|
| 主进程 | direct exec → EACCES 时 linker64 fallback（startWithArgs） | 全部 |
| 子进程 | LD_PRELOAD exec-hook 重路由 → linker64 | 全部 |
| 文件级 | 解压时剥离可执行文件写位（rwx→r-x） | 华为类 W^X |

**已知限制**：内核解析 shebang 后的解释器 exec 不经 libc，hook 无法拦截——
Android 15/16 上 `.sh` 脚本类工具仍受限（引擎核心为 node ELF，不受影响）。

### 4.1 启动流程（MainActivity.startEngineFlow）

1. 探测引擎；运行中 → `showWeb()`（仅当页面之前加载失败才 reload）。
2. **同意门**（仅全新安装首次，`consent_seen` 未置位）：显示同意卡
   （存储 ≈600MB、耗时 2-5 分钟、须知 3 条），"同意并开始" → 写标记 →
   进入本流程；"退出应用" → finish。
3. 第 1 步：未解压（`usr/bin/node` 不存在）→ 解压 + 进度反馈。
4. 第 2 步（强制）：rootfs 缺失 → `RootfsDownloader.install()`（下载 + SHA256
   硬校验 + staging 原子切换）；`ProotRuntime.ensureInitialized()`（proot 三
   件套 + bash 包装）；`ContainerProbe.smokeTest()`（node → 包装 → proot →
   容器 bash，30s 受限等待）——失败即引擎启动失败。
5. 第 3 步：就绪后由用户手动按"启动引擎"（`launchInFlight` CAS 防连点）；
   冷启动快速路径（快照+容器都已就绪）直进 Harness，顶部条覆盖（呼吸点，
   引擎应答后 6s 淡出）。
6. `startEngine()`：注入 env 后 spawn；轮询探测最多 60s（冷启动 20–45s，
   冷却/并发让位的启动可越过 30s）→ 成功后拉起前台服务 + Shizuku 增强。
7. 任一失败回落到引导页（错误可见：showGuide + showGuideError）。

`onCreate` 与 `onResume` 都触发本流程，`engineFlowRunning` CAS 防双线程
解压/启动（设备实证：双启动导致引擎进程死亡）；`onResume` 另受同意门约束。

### 4.2 进程级并发控制（EngineManager companion）

| 状态 | 机制 | 目的 |
|---|---|---|
| `STARTING` | AtomicBoolean CAS | 跨 EngineManager 实例（MainActivity 与 EngineService 各 new 一个）防双启动 |
| `lastStartAttemptAt` | @Volatile Long | 冷却窗口基准 |
| `engineProcess` | @Volatile Process? | 进程级共享，双实例可见同一进程 |
| `START_COOLDOWN_MS = 90s` | — | 冷启动 node 20–45s，防看门狗与健康启动竞争（EADDRINUSE） |

冷却规则：进程已死（`isAlive != true`）时立即清零冷却——崩溃后看门狗可在
下一轮（5s）立刻重启，无需等 90s。冷却只在真实启动后写入，失败路径不占窗口。
**挂死恢复**：冷却过期而进程仍存活（正常 boot ≤45s ≪ 90s）判定为挂死——
`destroyForcibly()` + 3s 等待后再启动（否则旧进程占端口，每次新启动
EADDRINUSE 死亡、形成 5s 循环）。`stopEngine` 同样受 CAS 保护（与启动互斥）。

### 4.3 环境注入（startEngine）

| 变量 | 值 | 理由 |
|---|---|---|
| `PATH` | `usr/bin:/system/bin` | 快照自带工具链优先 |
| `LD_LIBRARY_PATH` | `usr/lib` | Termux 库 |
| `HOME` | `filesDir/home` | 私有域 |
| `DSH_HOME` | `filesDir/home/.dsh` | 必须私有（见 §5.2） |
| `TMPDIR` | `filesDir/home/tmp` | 系统 tmp 在 app 域不可写 |
| `LD_PRELOAD` | `libexec-hook.so[:libunwind-patch.so]` | exec 重路由 + `_Unwind_Resume` 提供库（探针按需并入） |
| `OPENSSL_CONF` | 快照 `etc/tls|etc/ssl/openssl.cnf` | Termux 编译期路径不可读，node 缺配置直接退出（实测 exit 13） |
| `TERMUX__ROOTFS` / `TERMUX__PREFIX` | filesDir / usr | Termux 环境锚点 |
| `TERMUX_APP__DATA_DIR` / `LEGACY` | `context.filesDir.parentFile` | Termux 兼容（运行时推导，无硬编码包路径） |
| `TERMUX_VERSION` | 0.118.3 | 快照配套版本 |
| `DSH_PICK_TOKEN` | 进程级 UUID（EngineManager companion 单例） | 目录桥端点鉴权（web-compat 插件校验 `x-dsh-pick-token`）；服务重启的引擎持有同一值 |

exec 拒绝回退：`startWithArgs` 捕获 `Permission denied`，改经
`/system/bin/linker64` 拉起（与 JNI 库同机制，app 数据恒允许）。

### 4.4 保活（EngineService —— 引擎生命周期的唯一 owner）

- 前台服务（`dataSync` 类型，`FOREGROUND_SERVICE_DATA_SYNC`），常驻通知
  "dsh engine running"。
- `ensureEngine()` 整体在**后台线程**执行（`EngineProbe.check()` 是 HTTP I/O，
  主线程必抛 NetworkOnMainThreadException 且被吞 → 守卫恒失效 → 双启动；
  整套启动 I/O 也移出主线程防 ANR）；任务体全 try/catch。
- 看门狗**总 arm**（一旦服务运行，无论引擎当前是否在跑）：
  `scheduleWithFixedDelay` 5s，任务体全程 try/catch（一次异常 = 调度被静默
  抑制 = 看门狗永久死亡）；探测失败且快照就绪 → `startEngine()`。
- `START_STICKY`：进程被杀后系统重建服务。
- **生命周期契约**：`MainActivity.onDestroy` 从不杀引擎（后台化不得毁掉健康
  进程再冷启动一遍）；引擎进程只在**服务自身停止**时停止（`onDestroy` →
  `stopEngine()`，防孤儿进程）。

## 5. 数据布局与迁移

### 5.1 布局

| 路径 | 内容 |
|---|---|
| `filesDir/usr` | 运行时快照（引擎本体，可整体替换） |
| `filesDir/rootfs` | Ubuntu 24.04 容器 rootfs（原子切换经 `rootfs-staging`/`rootfs-old`） |
| `filesDir/home` | 引擎 `HOME`；`.dsh` = `DSH_HOME` |
| `filesDir/engine.log` | 引擎输出（合并重定向） |
| `filesDir/update-<uuid>.tar.xz` / `update-stage-<uuid>` / `usr-old` | 更新暂存与回滚（唯一命名，finally 清理） |
| `filesDir/libexec-hook.so` + assets `unwind/` | exec 重路由钩子 + `_Unwind_Resume` 补丁库 |
| `/storage/emulated/0/Documents/dshdata` | 公共用户数据 |

相对路径统一经 `DshPaths` 注册表（无硬编码包路径/存储路径）。

### 5.2 迁移策略（ensureDshDataHome，issue apk#8）

**约束**：`DSH_HOME` 必须留在私有域——dsh 每次启动在
`$DSH_HOME/profiles/node_modules` 维护 flat-module 回退（每依赖包一个
symlink 指向引擎安装位置），公共 FUSE 禁止创建 symlink（实测 Permission
denied），整体迁移必然崩溃。

**数据项级迁移**（私有原位建 symlink，dsh 读写经 symlink 落到公共）：

| 数据项 | 方式 | 说明 |
|---|---|---|
| `settings.yaml` | 拷贝到公共 | settings-file 经 cordis.patch.yml 的 config.path 直指公共文件，规避原子写替换 symlink |
| `sessions/` `storages/` `attachments/` | 整体搬移 + 私有 symlink | 目录内写文件不替换目录 symlink |
| `profiles/{web,headless}/cordis.yml` + `cordis.patch.yml` | 拷贝到公共 + 私有替换为 symlink | dsh 启动只读 |
| `.credentials.yaml` | **不迁移** | 公共 FUSE 强制 660，credentials-local 权限校验拒绝；key 留私有，由 patch 的 credentials path 指向 |

**幂等重连（卸载重装场景）**：迁移标记 `.migrated-from` 在公共目录（持久），
但私有 symlink 随卸载删除。重连分支在标记存在时幂等重建：公共目标存在且私有
为空壳（重装后 dsh 新建的空目录）→ 替换为 symlink；私有非空（可能有新数据）
→ 保守跳过。

## 6. 运行时在线更新

### 6.1 协议

1. **HTTPS** 拉取 `manifest.json`：`{url, sha256, size}`。manifest 与快照 URL
   均强制 HTTPS；`sha256` 缺失即拒绝（无完整性保护则等于无校验）。
2. 流式下载（64KB 缓冲，总上限 500MB，防填满存储）。
3. SHA-256 比对（忽略大小写），不匹配删除并失败。
4. 解压到**唯一**暂存目录 `update-stage-<uuid>`（不在运行树内；两个触发入口
   （按钮 + adb）经进程级 CAS 单飞互斥——共用固定路径的并发运行会互相删掉
   对方的暂存目录，把空心目录换进活树），校验新 `usr/bin/node` 存在。
5. 切换（带回滚）：
   - `usr` 存在且挪不动 → 保持现状，放弃切换；
   - `usr → usr-old` 成功但 `new usr → usr` 失败 → 回滚 `usr-old → usr`；
     回滚也失败 → 保留 `usr-old` 供手动恢复。
   - 暂存目录与下载的 tarball 在 finally 中总是清理（失败不留 ~500MB 垃圾）。
6. `pkill -f bin.js` 杀旧引擎 → 看门狗下轮（≤5s）从新运行时重启；pkill
   失败（不可用/没杀掉）不再吞掉——提示用户重启应用（看门狗只重启已死进程，
   活着的旧引擎会继续跑旧快照的 inode）。

### 6.2 触发与测试

- 引导页按钮：`MainActivity.runUpdate()`，状态写入 `files/update-status.txt`。
- adb 触发：`am start -n com.dshmobile.shell/.MainActivity -a com.dshmobile.shell.action.UPDATE`。
- **仅 debug 构建接受该 intent**：MainActivity 因 LAUNCHER 而 exported，
  release 忽略外部触发，防止任意应用触发下载+执行链路。
- 默认 manifest URL `https://10.0.2.2:8899/manifest.json`（模拟器宿主回环）；
  生产经 `UpdateManager.manifestUrl` 覆盖（setter 强制 HTTPS）。

## 7. 安全模型

| 面 | 措施 |
|---|---|
| 明文流量 | `network_security_config.xml`：base 禁明文，仅 127.0.0.1/localhost 放行 |
| 更新链路 | HTTPS 强制 + sha256 必填 + 大小上限（防 MITM 注入代码执行） |
| rootfs 下载 | `SHA256SUMS` 校验**硬性**——校验获取失败即拒绝安装（绝不装未校验 rootfs） |
| 签名 | keystore 仅存于仓库 secret，缺失即构建失败；绝不发布或现生成（否则可伪造同签名更新包） |
| 解压 | 每条目 canonical 路径校验，逃逸根目录即抛异常（tar slip）；symlink 判 `isSymbolicLink`（悬空链接不再永久卡死重试）；硬链接物化并同样校验目标 |
| WebView 边界 | 仅引擎同源（scheme/host/port 精确匹配）留 WebView；外部链接交系统浏览器 |
| 下载 | 仅引擎同源 URL（防本机 SSRF）；**禁跟随重定向**（重定向目标不可信）；in-flight 去重；MediaStore 流式 + 200MB 上限 |
| 导出路径匹配 | `/api/session.export` 精确匹配（非前缀） |
| 目录桥 | 进程级 token（DSH_PICK_TOKEN + `x-dsh-pick-token` 校验）；pick 需用户交互确认 |
| JS 注入面 | `allowFileAccess=false`、禁止混合内容、仅同源页面可触达桥 |
| 触发面 | ACTION_UPDATE 仅 debug；SAF 选择结果始终回传（取消/失败不挂起） |

## 8. 权限

| 权限 | 用途 | 时机 |
|---|---|---|
| INTERNET | WebView + 探测 + 更新/rootfs 下载 | 声明 |
| MANAGE_EXTERNAL_STORAGE | 外部工作区真实路径访问（All Files Access） | **Android 11+ 安装即授权**；10 及以下无此模型（外部工作区不可用） |
| POST_NOTIFICATIONS | 通知通道 | API 33+ 运行时请求 |
| FOREGROUND_SERVICE + FOREGROUND_SERVICE_DATA_SYNC | 保活服务（dataSync） | 声明 |

SAF 目录选择本身无需权限（用户经系统选择器授权 tree URI）。

## 9. 构建与发布

- JDK 17+、compileSdk 36、targetSdk 34（Android 15+ 的 app-data ELF exec
  限制由 linker64 回退兜底；Android 10-14 的 untrusted_app 域允许 exec
  app_data_file）、minSdk 26。
- W^X 兼容：华为/EMUI 拒绝执行"可写+可执行"文件（Android 10 实测 EACCES），
  解压器对可执行文件去除写位（rwx→r-x），引擎二进制运行时不写自身。
- AGP 9.3.1、Kotlin 2.4.10、Gradle 9.7.0（wrapper）。
- `snapshot.tar.xz` 不入库（CI 从上游 Releases 下载进 assets/）；缺失时构建
  loud fail 并给出获取指引（scripts/make-snapshot.sh 为 Termux 端打包脚本）。
- `noCompress += "xz"`：防二次压缩破坏 `openFd`。
- lint 不阻断（离线环境无 lint-gradle 缓存）。
- **发布矩阵**：每腿 `-PabiFilter=<abi>` 只编一个 ABI（否则两腿产出同一通用
  APK 仅后缀不同，且 32 位 ABI 会混入硬编码 linker64 的 hook）；
  `-PversionName/-PversionCode` 由发布 tag 推导（v0.1.0 → 100）；
  **keystore 只读 secret** `RELEASE_KEYSTORE_B64`（缺失 exit 1）。
- 引擎启动超时诊断：node.canExecute / 进程存活 / engine.log 全文入 AppLog；
- 依赖：androidx.activity-ktx 1.13.0、commons-compress 1.28.0、xz 1.12、
  shizuku api/provider 13.1.5（Manifest 声明 `ShizukuProvider`，否则保活桥
  静默失效）；NDK 27.2.12479018（exec-hook 编译）。
- AGP 9 兼容：`android.builtInKotlin=false` + `android.newDsl=false`（AGP 9
  默认启用内置 Kotlin 与新 DSL，与显式 KGP 不兼容；此组合为 flutter 生态
  同款过渡配置，见 flutter/flutter#183910）。

## 10. ABI 与页大小

- 快照按 ABI 分发：x86_64 已端到端验证；arm64 由官方 Termux aarch64 源组装。
- Android 16KB 页设备必须产出对应页大小的构建（快照内二进制与页大小绑定）。
- APK 与快照一一对应：不混用 ABI。

## 11. 已知限制

- 保活尽力而为：激进省电的厂商策略可能杀服务；Shizuku 增强停留在状态检测
  （appops-application 需 shell-exec API，Shizuku.newProcess 在 api 13.1.5
  非公开，已推迟）。
- 目录映射仅 `primary` 卷；其他卷回退 `content://` 不透明句柄（bash 不可直读）。
- 更新后引擎重启由看门狗轮询驱动（≤5s 延迟），且仅当旧进程被杀成功；杀失败
  时提示用户重启应用。
- 容器首次运行需要网络（~35MB，cdimage.ubuntu.com）；校验和获取失败拒绝安装。
- 系统深色依赖厂商 WebView 对 `FORCE_DARK_AUTO` 的支持，另以桥值补丁兜底。

## 12. 决策记录

| 决策 | 选择 | 原因 |
|---|---|---|
| D1 内嵌快照 vs 依赖 Termux | 内嵌 xz 快照，解压即跑 | 免安装、离线、版本自足 |
| D2 DSH_HOME 私有 + 数据项迁移 | 私有实体 + symlink 落公共 | FUSE 禁 symlink（apk#8） |
| D3 更新完整性 | HTTPS + sha256 必填 | 明文+可空摘要 = RCE（I-01） |
| D4 targetSdk | 34 | Android 15+ exec 限制由 linker64 兜底；华为 W^X（可写文件禁 exec）由解压去写位解决 |
| D5 引擎并发 | 进程级 CAS + 90s 冷却 + 进程死亡清冷却 + 挂死 kill | 防 EADDRINUSE 双启动；崩溃快速恢复；挂死不占端口 |
| D6 下载路径 | 应用内 HttpURLConnection → MediaStore（禁重定向） | 浏览器导航带 Origin:null 被 dsh fence 403；重定向目标不可信 |
| D7 ACTION_UPDATE | 仅 debug | exported LAUNCHER activity 的任意触发面 |
| D8 容器强制 | rootfs 缺失即安装，冒烟失败即引擎失败 | 保证 agent shell 环境一致性（标准发行版） |
| D9 引擎生命周期 | EngineService 唯一 owner；Activity 不杀引擎；看门狗总 arm | 消除"退出即杀 + 看门狗冷启动重拉"的双重浪费与死锁 |
| D10 pick token | 进程级单例 | 服务重启引擎后桥不失配（静默失效的目录选择） |
| D11 签名密钥 | 仅 secret，缺失即失败 | 公开 asset/现生成 = 任何人可伪造同签名更新包 |
| D12 首次同意门 | 仅全新安装首次，SharedPreferences 记录 | 用户必须知情（存储/耗时/网络）后同意，不得自动进入安装 |
