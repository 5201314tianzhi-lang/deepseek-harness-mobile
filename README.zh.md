# deepseek-harness-mobile

[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (dsh) 的 Android 壳，
应用名 **深度编码**：**内嵌 Termux 运行时快照**（解压即跑，无需安装 Termux）+ WebView UI，
附带 **proot Ubuntu 容器**（agent 的 shell 环境）、SAF 目录桥、保活前台服务、引擎看门狗
与 manifest 驱动的运行时在线更新。一个 APK 即可装出一个真正能执行 bash 的 dsh web 智能体。

## 特性

- **内嵌运行时** — ~79MB APK 内嵌 `snapshot.tar.xz`，首次启动解压出约 484MB
  （node + bash + coreutils + dsh + 插件），解压后完全离线可用。
- **Ubuntu 容器** — proot 驱动的 Ubuntu 24.04 rootfs（约 35MB，首次运行时从
  Ubuntu 官方源下载并做 SHA-256 校验），让 agent 获得标准 Linux 环境：
  `apt`、系统软件包、类 root 权限。agent 的 `bash` 经生成的包装脚本路由进容器。
- **移动 UI** — 白色三步引导向导（运行时 → 容器 → 启动）访问
  `http://127.0.0.1:3080`；外部链接交给系统浏览器，仅引擎同源页面留在 WebView 内。
- **保活** — 前台服务（`dataSync` 类型，带常驻通知）+ 5s 看门狗，引擎进程死亡
  自动重启。引擎生命周期归服务所有（Activity 从不杀引擎）。
- **运行时在线更新** — HTTPS manifest 驱动的快照替换（下载 → SHA-256 校验 →
  暂存解压 → 原子切换带回滚 → 看门狗自动重启）；运行中的运行时可自我更新，
  无需升级 APK。
- **通用执行层** — 内置 `libexec-hook.so`（LD_PRELOAD）把引擎进程树中**同架构**
  的 ELF 执行重路由到 `/system/bin/linker64`（Android 各版本/厂商一致允许的
  native 库加载机制），覆盖 Android 15+ exec 禁令、华为/EMUI W^X 等场景。
  主进程采用 direct exec → linker64 回退；解压出的可执行文件剥离写位
  （rwx→r-x）满足 W^X。内置 `libunwind-patch.so` 补足 `_Unwind_Resume`，
  使 node-pty 可加载。
- **SAF 目录桥** — `pickDirectory` 把用户选择的目录映射为容器 bash 可直接访问的
  真实路径。
- **公共用户数据** — 设置、会话、存储、附件落在 `/storage/emulated/0/Documents/dshdata`
  （文件管理器可见、可备份、卸载重装不丢；API key 留在私有域）。

## 架构

| 组件 | 文件 | 职责 |
|---|---|---|
| `MainActivity` | `app/src/main/java/com/dshmobile/shell/MainActivity.kt` | 编排：启动流程、引擎启动、导出、更新触发 |
| `GuideWizard` | `.../GuideWizard.kt` | 白色向导 UI：三步流程、状态卡、冷启动顶部条（呼吸点） |
| `HarnessWebView` | `.../HarnessWebView.kt` | WebView 配置、引擎源导航门、兼容层注入、按需重载策略 |
| `AndroidBridge` | `.../AndroidBridge.kt` | `window.androidBridge` JS 接口（协议 v1） |
| `PickerBridge` | `.../PickerBridge.kt` | SAF 目录/文件选择；待决回调跨 Activity 重建保留 |
| `ExportFlow` | `.../ExportFlow.kt` | 应用内下载到 MediaStore Downloads（不跟随重定向） |
| `NotificationHelper` | `.../NotificationHelper.kt` | 通知通道 + 测试通知 |
| `AppLog` | `.../AppLog.kt` | 面向用户的诊断日志（有界环形缓冲 + 日志文件，剪贴板复制） |
| `EngineManager` | `.../EngineManager.kt` | 快照解压、dshdata 迁移/重连、引擎进程环境与生命周期 |
| `EngineService` | `.../EngineService.kt` | 前台服务：拥有引擎生命周期 + 5s 看门狗 |
| `EngineProbe` | `.../EngineProbe.kt` | 对 `127.0.0.1:3080` 的 HTTP 可达性探测 |
| `EngineSource` | `.../EngineSource.kt` | 引擎源 URL / 会话导出匹配 |
| `ProotRuntime` | `.../ProotRuntime.kt` | proot + libtalloc + libandroid-shmem 资产、bash 包装生成、环境注入 |
| `RootfsDownloader` | `.../RootfsDownloader.kt` | Ubuntu rootfs 下载、SHA-256 校验、暂存原子安装 |
| `ContainerProbe` | `.../ContainerProbe.kt` | 容器冒烟测试（node → 包装 → proot → 容器 bash） |
| `SnapshotExtractor` | `.../SnapshotExtractor.kt` | xz-tar 解压：穿越防护、符号/硬链接、W^X 剥离写位、exec 属性打标 |
| `UnwindResolver` | `.../UnwindResolver.kt` | `_Unwind_Resume` 提供库解析 + pty.node 加载诊断 |
| `UpdateManager` | `.../UpdateManager.kt` | 运行时快照下载/校验/切换（单飞、唯一暂存名） |
| `Downloader` | `.../Downloader.kt` | 公共 HTTP 下载 + SHA-256 |
| `DshPaths` | `.../DshPaths.kt` | 应用相对路径集中注册表（无硬编码包路径） |
| `ShizukuSupport` | `.../ShizukuSupport.kt` | Shizuku 服务/权限检测 + appops 后台豁免增强流程 |
| `KeepAliveUserService` | `.../KeepAliveUserService.kt` | Shizuku 用户服务（shell 身份）执行 appops 保活豁免 |
| `KeepAliveAlarm` | `.../KeepAliveAlarm.kt` | 30 分钟自续心跳闹钟（+ `KeepAliveAlarmReceiver`） |
| `BootReceiver` | `.../BootReceiver.kt` | 开机 / 升级 / 电源 / 解锁重拉起前台服务 |

### 首次启动流程（`MainActivity.onCreate`）

1. **第 1 步 — 运行时**：解压内嵌快照到 `filesDir/usr`（显示进度），然后
2. **第 2 步 — 容器**（强制）：缺失时下载 Ubuntu rootfs（对 `SHA256SUMS` 校验），
   安装 proot + libtalloc + libandroid-shmem，生成 `usr/bin/bash` 包装，并对
   整条链路做容器内冒烟测试（`echo CONTAINER_OK; id`）。容器失败 = 引擎启动失败。
3. **第 3 步 — 启动**：用户按"启动引擎"；启动
   `node --expose-internals <usr>/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web --port 3080`，
   轮询探测最多 60s。
4. **快速路径** — 快照与容器都已就绪时，应用冷启动直接进入 Harness，上方覆盖
   细状态条（呼吸点，引擎应答后 6s 淡出）。

流程受 in-flight CAS 标志保护（`onCreate` 与 `onResume` 都会触发；双线程并发
解压/启动会杀死引擎进程）。

### 引擎生命周期（归 `EngineService`）

- 前台服务启动后看门狗**必定武装**（每 5s 轮询；探测失败且快照就绪则重启引擎）。
  任务体全程 try/catch——一次异常不会让看门狗永久停摆。
- 进程级 `STARTING` CAS + 90s 冷却窗口防止双启动（冷启动 node 需 20–45s）。
  进程已死立即清冷却；冷却过期仍存活视为挂死，先杀再起（否则旧进程占着端口，
  每次新启动都以 `EADDRINUSE` 死亡，形成死循环）。
- `MainActivity.onDestroy` 从不杀引擎——退出后台不得毁掉健康进程再让看门狗
  冷启动一遍；引擎只在服务自身停止时被停止。
- 直接 exec 被拒（`Permission denied`，Android 15+）时改经 `/system/bin/linker64`
  拉起进程。
- pick token（`DSH_PICK_TOKEN`）为进程级单例：看门狗重启的引擎与 WebView 桥
  持有同一 token。

### 容器集成

- 集成点：agent 的 `dsh-bash-local` 从 `PATH` 解析 `bash`；`usr/bin/bash` 是
  运行时生成的 shebang 包装（路由进 proot），原 bash 保留为 `bash.termux`。
- 包装注入 `LD_LIBRARY_PATH`（proot 的 libtalloc/libandroid-shmem）、
  `PROOT_TMP_DIR`（proot 需要可写临时目录；Termux 默认路径不可访问）与
  `TMPDIR`（容器内临时目录）。
- `ContainerProbe` 以受限 30s 超时跑完整 agent 链路（node → 包装 → proot →
  容器 bash）；失败 = 引擎启动失败。
- **预置工作区**：`/root/projects`（agent 工作目录，宿主侧对应
  `Documents/dshdata/projects`）随容器创建。
- **国内镜像源预置**（一次性写入，可改）：apt → 清华 TUNA（备选阿里注释）、
  pip → TUNA PyPI、npm → npmmirror、cargo → TUNA sparse、Go → goproxy.cn、
  RubyGems → TUNA、Composer → 阿里、conda → TUNA。全部写入各包管理器标准
  配置位置，装好后立即生效——无需任何额外设置。

### 存储布局

| 路径 | 用途 |
|---|---|
| `filesDir/usr` | 解压后的运行时快照（node、bash、coreutils、dsh、插件） |
| `filesDir/rootfs` | Ubuntu 24.04 容器 rootfs（原子切换经 `rootfs-staging`/`rootfs-old`） |
| `filesDir/home` | 引擎 `HOME`；`filesDir/home/.dsh` 即 `DSH_HOME`（私有，存放 `.credentials.yaml`） |
| `filesDir/engine.log` | 引擎 stdout/stderr（合并重定向） |
| `filesDir/update-<uuid>.tar.xz`、`update-stage-<uuid>`、`usr-old` | 运行时更新暂存/回滚（唯一命名，总是清理） |
| `filesDir/libexec-hook.so`、`unwind` 资产 | exec 重路由钩子 + `_Unwind_Resume` 补丁库 |
| `/storage/emulated/0/Documents/dshdata` | 用户数据：`settings.yaml`、`sessions/`、`storages/`、`attachments/`、`profiles/{web,headless}/` |

用户数据按数据项从私有 `DSH_HOME` 迁往公共目录（issue apk#8 的约束）：
`DSH_HOME` 本身必须留在私有域，因为公共 FUSE 禁止创建 dsh 在
`$DSH_HOME/profiles/node_modules` 维护的 symlink。目录整体搬移后在原位留私有
symlink 指向公共副本；`.credentials.yaml` 绝不迁移（公共 FUSE 强制 660 权限，
credentials-local 权限校验会拒绝加载，且 key 会暴露给其他应用）。卸载重装后
幂等重建私有 symlink，公共数据重新可见。

## 桥协议 v1（`window.androidBridge`）

| 方法 | 签名 | 说明 |
|---|---|---|
| `version` | getter → string | 桥协议版本（`"1.0"`），feature-detect 用 |
| `checkEngine` | () → string | 探测 127.0.0.1:3080；JSON `{running, latencyMs, error?}` |
| `keepScreenOn` | (enable: boolean) | 屏幕常亮（单个共享 wakelock；Activity 销毁时释放） |
| `showNotification` | (title, text) | 测试通知通道（POST_NOTIFICATIONS 运行时请求；授权后补发排队通知） |
| `pickDirectory` | (callbackId: string) | SAF 目录选择（ACTION_OPEN_DOCUMENT_TREE）；结果异步回传 |
| `hasAllFilesAccess` | () → boolean | 是否持有 All Files Access（API 30+） |
| `requestAllFilesAccess` | () → void | 打开系统 All Files Access 授权页 |
| `getPickToken` | () → string/null | 目录选择端点的进程级会话 token（引擎重启后保持稳定） |

异步结果回传页面：

- `window.__dshBridge.onDirectoryPicked(callbackId, path|null)` — 选择结果；
  `null` 表示取消或不可用（API < 30、权限流程中、或已有选择在途）。
- `window.__dshBridge.onPermissionRequired()` — 缺少 All Files Access；
  页面应引导用户授权后重试。
- `window.__dshExportResult(ok, title, detail)` — 会话日志导出结果。
- `window.__dshThemeBridge.setDark(boolean)` — 系统深色状态推送（部分厂商
  WebView 不跟随 `uiMode`；由 matchMedia hook 消费）。

桥协议使 APK 与 dsh 版本解耦：页面按 `androidBridge.version` 做能力探测。

### 目录选择与 All Files Access

外部工作区要求容器 bash 能直接访问所选真实路径：引擎 env 携带
`DSH_PICK_TOKEN`，web-compat 插件以 `x-dsh-pick-token` 校验。API 30+ 且未
授予 All Files Access 时，应用打开系统授权页并回调 `onPermissionRequired`；
API < 30 时选择以取消结算（无该权限模型，外部工作区不可用）。`primary` 卷
映射为运行时推导的外部存储路径（无硬编码 `/storage/emulated/0`）。

### 会话日志导出与下载

引擎同源下载（`/api/session.export` 及 127.0.0.1:3080 的一切）由应用内
`HttpURLConnection` 执行（不跟随重定向——重定向目标不可信），流式写入
MediaStore Downloads（API 29+，免权限），上限 200MB。原因：浏览器导航携带
`Origin: null` / `sec-fetch-site` 标记，会被 dsh 的 `/api` browser-trust
fence 拒绝（403）；应用内连接无浏览器标记，可放行。两个入口
（`shouldOverrideUrlLoading` + 下载监听）经 in-flight 守卫去重。

### WebView 安全边界

- 仅引擎同源 URL（scheme/host/port 精确匹配）留在 WebView；其余一律交系统
  浏览器，不可信页面永远无法触达特权桥。
- 会话导出路径精确匹配（`/api/session.export`），非前缀匹配。
- `allowFileAccess=false`、禁止混合内容、`FORCE_DARK_AUTO` 跟随系统主题。
- 页面仅在之前加载失败（引擎未就绪时的错误页）时重载；健康页面回到前台
  保留状态。
- 旧 WebView 上注入 JS 兼容层（`assets/js/compat-polyfills.js`：AbortSignal.any、
  Promise.any、structuredClone、groupBy 等，全部 feature-detect）。
- `network_security_config.xml` 仅对 `127.0.0.1`/`localhost` 放行明文，
  其余必须 TLS。

## 运行时在线更新协议

1. 应用以 **HTTPS** 拉取 `manifest.json`：`{url, sha256, size}`。manifest 与
   快照 URL 均强制 HTTPS；缺少 `sha256` 直接拒绝更新（否则无完整性保护）。
2. 快照流式下载（上限 500MB），与 manifest 的 SHA-256 比对。
3. 解压到**唯一**暂存目录（`update-stage-<uuid>`，绝不触碰运行中的目录树；
   并发运行单飞互斥），校验新 `usr`（必须含 `bin/node`）后切换：
   `usr → usr-old → new usr`，切换失败自动回滚。暂存与压缩包总是清理。
4. 杀死旧引擎进程（`pkill -f bin.js`）；杀死失败时提示用户重启应用（看门狗
   只重启已死的引擎）。否则 EngineService 看门狗数秒内从新运行时重启引擎。

测试触发：`adb shell am start -n com.dshmobile.shell/.MainActivity -a com.dshmobile.shell.action.UPDATE`
（仅 debug 构建有效——activity 因 LAUNCHER 是 exported，release 忽略该
intent，防止外部应用触发下载+执行链路）。状态写入 `files/update-status.txt`。
默认 manifest URL（`https://10.0.2.2:8899/manifest.json`）指向模拟器宿主
回环；生产构建通过 `UpdateManager.manifestUrl` 覆盖。

## 构建

环境要求：JDK 17+、Android SDK（compileSdk 36）、Gradle 9.7.0（wrapper）。

```sh
# 1. 准备运行时快照（必需，作为 CI 资产分发）
#    release workflow 从上游 Releases 下载 snapshot-arm64/x86_64.tar.xz 打进 assets/
mkdir -p app/src/main/assets
cp snapshot/snapshot.tar.xz app/src/main/assets/snapshot.tar.xz

# 2. 构建（快照缺失时 loud fail）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

Release 构建（CI）额外传入：

```sh
./gradlew assembleRelease \
  -PversionName=0.1.0 -PversionCode=100 \   # 由发布 tag 推导
  -PabiFilter=arm64-v8a                      # 每个矩阵腿只编一个 ABI
```

### 质量门（CI）

每次 push 到 `main` 及每个 PR 都会跑 `.github/workflows/ci.yml`：
`./gradlew assembleDebug lintDebug ktlintCheck testDebugUnitTest` 外加
`./tests/run-local.sh`——编译、Android lint（`abortOnError`，debug 变体）、
ktlint（`.editorconfig` 的 android 规则集）、JVM 单元测试（JUnit4 +
Robolectric + mockk）与 JS/C 本地测试全部通过才算通过。ktlint 从 Maven
Central（`com.pinterest.ktlint:ktlint-cli`）拉取（非插件门户，国内网络可用）；
提交前用 `./gradlew ktlintFormat` 自动格式化。release workflow 额外产出
jacoco 覆盖率报告（尽力而为，非门禁）。

本地跑测试：

```sh
./gradlew testDebugUnitTest   # JVM 单元测试（JUnit4 + Robolectric + mockk）
./tests/run-local.sh          # JS polyfill 测试 + exec-hook C 测试（node + gcc）
```

构建配置：AGP 9.3.1、Kotlin 2.4.10、minSdk 26、targetSdk 34（Android 15+
的 app-data ELF exec 限制由 linker64 回退兜底）。解压器会去除可执行文件的
写权限（W^X：华为/EMUI 拒绝执行可写文件）。`snapshot.tar.xz` 排除资源压缩
（`noCompress += "xz"`）；Android lint 错误阻断构建（`abortOnError`）。
**签名 keystore 只存在于仓库 secret `RELEASE_KEYSTORE_B64`**——workflow
缺失即拒绝构建（绝不发布或现生成密钥）。

## 权限

| 权限 | 用途 |
|---|---|
| `INTERNET` | WebView + 引擎探测 + rootfs/更新下载 |
| `MANAGE_EXTERNAL_STORAGE` | 外部工作区：容器 bash 访问用户选择的目录。Android 11+ 安装即授权（All Files Access）；Android 10 及以下无此模型，外部工作区不可用 |
| `POST_NOTIFICATIONS` | 通知通道（API 33+ 运行时请求） |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | 保活服务（`dataSync` 类型） |

SAF 目录选择无需权限（用户经系统选择器授权 tree URI）。

## ABI 与页大小

Release 按 ABI 各发布一个 APK（内嵌快照与架构绑定）：

- **arm64-v8a** — ARM64 手机/平板（绝大多数真机）
- **x86_64** — x86_64 模拟器（MuMu、雷电等）

x86_64 快照已端到端验证；arm64 快照由官方 Termux aarch64 源组装（见
`docs/design.md` §ABI）。16KB 页构建必须在 16KB 设备上产出。请选择与设备
ABI 匹配的 APK——装错架构的包解压正常，但引擎无法执行。

## 已知限制

- 保活为尽力而为：激进省电的厂商电池管理仍可能杀掉服务；Shizuku 增强
  （appops `RUN_IN_BACKGROUND` / `RUN_ANY_IN_BACKGROUND`）需要安装并授权
  Shizuku，且电池优化豁免最终仍取决于厂商是否遵守。
- 目录选择仅把 `primary` 卷映射为真实路径；其他卷回退为不透明的
  `content://` tree URI。
- 引擎切换后由看门狗下轮探测重启（切换后最多 ~5s），且仅当旧进程被杀成功；
  杀失败时提示重启应用。
- Ubuntu 容器首次运行需要网络（cdimage.ubuntu.com 约 35MB）；校验和获取失败
  时拒绝安装（绝不装未校验的 rootfs）。

## 相关项目

- [dsh-shell-termux](https://github.com/kelai141/dsh-shell-termux) — shell
- [dsh-client-ui-responsive](https://github.com/kelai141/dsh-client-ui-responsive) — 移动 UI
- [dsh-host-web-compat](https://github.com/kelai141/dsh-host-web-compat) — 浏览器兼容

## License

MIT。Copyright (c) 2026 kelai141（上游）、Copyright (c) 2026 lemonhub-io。
内含第三方组件按各自许可证（见依赖声明）。设计依据：`docs/design.md`；
审查记录：`docs/issues.md`。
