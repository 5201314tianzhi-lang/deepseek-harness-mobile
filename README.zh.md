# dsh-mobile-apk

[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (dsh) 的 Android 壳：
**内嵌 Termux 运行时快照**（解压即跑，无需安装 Termux）+ WebView UI，附 SAF 目录桥、
保活前台服务、引擎看门狗与 manifest 驱动的运行时在线更新。一个 APK 即可装出一个
真正能执行 bash 的 dsh web 智能体。

## 特性

- **内嵌运行时** — 打包 ~70MB xz 快照（node + bash + coreutils + dsh + 插件）；
  首次启动约 10s 解压，从应用自身目录启动引擎，完全离线可用。
- **移动 UI** — 系统 WebView 访问 `http://127.0.0.1:3080`；外部链接交给系统
  浏览器，仅引擎同源页面留在 WebView 内。
- **保活** — 前台服务（`dataSync` 类型，带常驻通知）+ 5s 看门狗，引擎进程
  死亡自动重启。
- **运行时在线更新** — HTTPS manifest 驱动的快照替换（下载 → SHA-256 校验 →
  暂存解压 → 原子切换带回滚 → 看门狗自动重启）；运行中的运行时可自我更新，
  无需升级 APK。
- **SAF 目录桥** — `pickDirectory` 把用户选择的目录映射为 bash 可直接访问的
  真实路径（`/storage/emulated/0/…`）。
- **公共用户数据** — 设置、会话、存储、附件落在 `/storage/emulated/0/Documents/dshdata`
  （文件管理器可见、可备份、卸载重装不丢；API key 留在私有域）。

## 架构

| 组件 | 文件 | 职责 |
|---|---|---|
| `MainActivity` | `app/src/main/java/com/dshmobile/shell/MainActivity.kt` | WebView 壳、JS 桥接线、引擎优先启动流程、应用内下载、目录选择、通知 |
| `AndroidBridge` | `.../AndroidBridge.kt` | `window.androidBridge` JS 接口（协议 v1） |
| `EngineManager` | `.../EngineManager.kt` | 快照解压、dshdata 迁移/重连、引擎进程生命周期与环境注入 |
| `EngineService` | `.../EngineService.kt` | 前台服务：保活 + 5s 看门狗 |
| `EngineProbe` | `.../EngineProbe.kt` | 对 `127.0.0.1:3080` 的 HTTP 可达性探测 |
| `SnapshotExtractor` | `.../SnapshotExtractor.kt` | xz-tar 解压（路径穿越防护 + Android exec 属性打标） |
| `UpdateManager` | `.../UpdateManager.kt` | 运行时快照下载/校验/切换 |
| `ShizukuSupport` | `.../ShizukuSupport.kt` | 可选 Shizuku 存在性/状态检测（保活增强） |

### 引擎优先启动流程（`MainActivity.startEngineFlow`）

1. 探测 `127.0.0.1:3080`；已在运行（Termux 或上次内嵌引擎）则直接显示 WebView。
2. 否则首次启动时解压内嵌快照（`filesDir/usr`），再启动引擎：
   `node --expose-internals <usr>/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web --port 3080`。
3. 轮询探测最多 30s；成功后拉起前台服务与可选 Shizuku 保活增强。
4. 任一失败回落到引导页（状态 + 重试 + Termux + 更新按钮）。

流程受 in-flight CAS 标志保护：`onCreate` 与 `onResume` 都会触发本流程，
双线程并发解压/启动会杀死引擎进程。

### 引擎生命周期（`EngineManager`）

- 进程级 `STARTING` CAS + 90s 冷却窗口防止双启动（冷启动 node 需 20–45s；
  更快的看门狗会与健康的启动过程竞争并产生 `EADDRINUSE`）。
- 跟踪的引擎进程已死时立即清除冷却——5s 看门狗可在崩溃后立刻重启。
- `startEngine` 启动前断言 termux-exec `LD_PRELOAD` 库存在（缺失会使所有
  子进程 exec 静默失败）。
- 直接 exec 被拒（`Permission denied`，Android 15+）时改经
  `/system/bin/linker64` 拉起进程。

### 存储布局

| 路径 | 用途 |
|---|---|
| `filesDir/usr` | 解压后的运行时快照（node、bash、coreutils、dsh、插件） |
| `filesDir/home` | 引擎 `HOME`；`filesDir/home/.dsh` 即 `DSH_HOME`（私有，存放 `.credentials.yaml`） |
| `filesDir/engine.log` | 引擎 stdout/stderr（合并重定向） |
| `filesDir/update.tar.xz`、`update-stage`、`usr-old` | 运行时更新的暂存/回滚目录 |
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
| `keepScreenOn` | (enable: boolean) | 屏幕常亮（单个共享 wakelock 实例） |
| `showNotification` | (title, text) | 测试通知通道（POST_NOTIFICATIONS 运行时请求；授权后补发排队通知） |
| `pickDirectory` | (callbackId: string) | SAF 目录选择（ACTION_OPEN_DOCUMENT_TREE）；结果异步回传 |
| `hasAllFilesAccess` | () → boolean | 是否持有 All Files Access（API 30+） |
| `requestAllFilesAccess` | () → void | 打开系统 All Files Access 授权页 |
| `getPickToken` | () → string/null | 目录选择端点的一次性会话 token |

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

外部工作区要求 bash 进程能直接访问所选真实路径：引擎 env 携带
`DSH_PICK_TOKEN`，web-compat 插件以 `x-dsh-pick-token` 校验。API 30+ 且未
授予 All Files Access 时，应用打开系统授权页并回调 `onPermissionRequired`；
API < 30 时选择以取消结算（无该权限模型）。

### 会话日志导出与下载

引擎同源下载（`/api/session.export` 及 127.0.0.1:3080 的一切）由应用内
`HttpURLConnection` 执行，流式写入 MediaStore Downloads（API 29+，免权限），
上限 200MB。原因：浏览器导航携带 `Origin: null` / `sec-fetch-site` 标记，
会被 dsh 的 `/api` browser-trust fence 拒绝（403）；应用内连接无浏览器
标记，可放行。两个入口（`shouldOverrideUrlLoading` + 下载监听）经 in-flight
守卫去重。

### WebView 安全边界

- 仅引擎同源 URL（scheme/host/port 精确匹配）留在 WebView；其余一律交系统
  浏览器，不可信页面永远无法触达特权桥。
- 会话导出路径精确匹配（`/api/session.export`），非前缀匹配。
- `allowFileAccess=false`、禁止混合内容、`FORCE_DARK_AUTO` 跟随系统主题。
- `network_security_config.xml` 仅对 `127.0.0.1`/`localhost` 放行明文，
  其余必须 TLS。

## 运行时在线更新协议

1. 应用以 **HTTPS** 拉取 `manifest.json`：`{url, sha256, size}`。manifest 与
   快照 URL 均强制 HTTPS；缺少 `sha256` 直接拒绝更新（否则无完整性保护）。
2. 快照流式下载（上限 500MB），与 manifest 的 SHA-256 比对。
3. 解压到暂存目录（绝不触碰运行中的目录树），校验新 `usr`（必须含
   `bin/node`）后切换：`usr → usr-old → new usr`，切换失败自动回滚。
4. 杀死旧引擎进程；EngineService 看门狗数秒内从新运行时重启引擎。

测试触发：`adb shell am start -n com.dshmobile.shell/.MainActivity -a com.dshmobile.shell.action.UPDATE`
（仅 debug 构建有效——activity 因 LAUNCHER 是 exported，release 忽略该
intent，防止外部应用触发下载+执行链路）。状态写入 `files/update-status.txt`。
默认 manifest URL（`https://10.0.2.2:8899/manifest.json`）指向模拟器宿主
回环；生产构建通过 `UpdateManager.manifestUrl` 覆盖。

## 构建

环境要求：JDK 17+、Android SDK（compileSdk 36）、Gradle 9.7.0（wrapper）。

```sh
# 1. 准备运行时快照（必需，~70MB，作为 Release 资源分发）
#    方式 A：从 GitHub Releases 下载 snapshot-x86_64.tar.xz
#    方式 B：在 Termux 设备上自打（scripts/make-snapshot.sh）后拉取
mkdir -p app/src/main/assets
cp snapshot/snapshot.tar.xz app/src/main/assets/snapshot.tar.xz

# 2. 构建（快照缺失时 loud fail）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

构建配置：AGP 9.3.1、Kotlin 2.4.10、minSdk 26、targetSdk 28（刻意为之——
Android 10+ 将 targetSdk 29+ 的应用归入 untrusted_app_29 SELinux 域，禁止
exec app-data ELF（内嵌引擎依赖它）；Android 15/16 由 linker64 回退兜底）。
`snapshot.tar.xz` 排除资源压缩
（`noCompress += "xz"`）；lint 对离线环境不阻断。

## 权限

| 权限 | 用途 |
|---|---|
| `INTERNET` | WebView + 引擎探测 |
| `MANAGE_EXTERNAL_STORAGE` | 外部工作区：bash 访问用户选择的目录（All Files Access，运行时请求） |
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

- 保活为尽力而为：激进省电的厂商电池管理仍可能杀掉服务；Shizuku 增强目前
  仅报告状态（appops-application 步骤需要 shell-exec API，已推迟）。
- 目录选择仅把 `primary` 卷映射为真实路径；其他卷回退为不透明的
  `content://` tree URI。
- 引擎切换后由看门狗下轮探测重启（切换后最多 ~5s）。

## 相关项目

- [dsh-shell-termux](https://github.com/kelai141/dsh-shell-termux) — shell
- [dsh-client-ui-responsive](https://github.com/kelai141/dsh-client-ui-responsive) — 移动 UI
- [dsh-host-web-compat](https://github.com/kelai141/dsh-host-web-compat) — 浏览器兼容

## License

MIT。Copyright (c) 2026 kelai141（上游）、Copyright (c) 2026 lemonhub-io。
内含第三方组件按各自许可证（见依赖声明）。设计依据：`docs/design.md`；
审查记录：`docs/issues.md`。
