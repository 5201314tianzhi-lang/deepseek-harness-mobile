# 壳 APK 设计（dsh-mobile-apk）

> v2.0 ｜ 2026-08-15 重写：与当前代码逐项对应。审查/修复记录见 `docs/issues.md`。

---

## 1. 形态与边界

- **纯壳**：WebView 只消费 `http://127.0.0.1:3080`（内嵌快照或 Termux 内 dsh web 服务）；
  壳与引擎版本解耦（桥协议版本化 `androidBridge.version`）。
- **自足运行时**：APK 内嵌 ~70MB xz 快照（node + bash + coreutils + dsh + 插件），
  首次启动解压到 `filesDir/usr`，无需安装 Termux。
- **零侵入**：页面侧不改动；桥能力全部经 `@JavascriptInterface` 注入。
- **引擎引导页**：引擎不可达时显示引导视图（状态文本 + 重试 + 打开 Termux +
  检查运行时更新）。

## 2. 组件架构

```
MainActivity（WebView 壳）
 ├─ configureWebView()：WebSettings / WebViewClient / WebChromeClient / 下载监听 / 桥注入
 ├─ startEngineFlow()：引擎优先启动（CAS 防并发）
 │   ├─ EngineProbe.check() —— 127.0.0.1:3080 可达性
 │   ├─ EngineManager.extractSnapshot() —— 首次解压
 │   ├─ EngineManager.startEngine() —— 拉起 node 进程（90s 冷却 + 进程级 CAS）
 │   └─ startEngineService() —— EngineService 前台服务（保活 + 5s 看门狗）
 ├─ pickDirectoryWithPermissionCheck() —— SAF 目录选择 + All Files Access 引导
 ├─ downloadToDownloads() —— 引擎同源下载 → MediaStore（200MB 上限）
 └─ pushSystemDark() —— 系统深色 → __dshThemeBridge.setDark

EngineManager（引擎进程与数据）
 ├─ ensureDshDataHome() —— dshdata 迁移/重连（公共用户数据）
 └─ startWithArgs() —— exec 被拒时回退 /system/bin/linker64

UpdateManager —— manifest 驱动在线更新（HTTPS + SHA-256 + 原子切换回滚）
SnapshotExtractor —— xz-tar 解压（路径穿越防护 + exec 属性打标）
EngineService —— 前台服务 + 5s 看门狗
ShizukuSupport —— 可选 Shizuku 状态检测
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
| getPickToken | () → string/null | 目录选择桥一次性会话 token（引擎侧 pick 端点校验，env `DSH_PICK_TOKEN`） |

**Kotlin → JS 异步回传通道**：

| 通道 | 载荷 | 语义 |
|---|---|---|
| `window.__dshBridge.onDirectoryPicked(callbackId, path)` | path 为真实路径或 `content://` URI；`null` 表示取消/不可用 | 选择结果 |
| `window.__dshBridge.onPermissionRequired()` | — | 缺 All Files Access，引导后重试 |
| `window.__dshExportResult(ok, title, detail)` | — | 会话日志导出结果（应用内弹框） |
| `window.__dshThemeBridge.setDark(boolean)` | — | 系统深色状态（厂商 WebView 不跟随 uiMode 的补丁） |

**目录选择并发模型**：单槽 `pendingPickCallback`，在途时新请求立即以取消结算
（防止覆盖导致旧 pick 永不结算）。API < 30 无该权限模型，直接取消并提示。

## 4. 引擎生命周期与并发控制

### 4.0 通用执行层（exec 重路由）

**问题**：Android 在多种场景拒绝直接 exec app-data ELF：
- Android 15+（targetSdk 35+ 策略）；
- 厂商 W^X 强化（华为/EMUI 拒绝"可写+可执行"文件，Android 10 实测 EACCES）；
- 各版本/厂商差异无法逐一兼容。

**方案**：APK 内置自研 `libexec-hook.so`（NDK，双 ABI），`LD_PRELOAD` 注入
引擎进程树，拦截 `execve/execv/execvp/execvpe`：

- 目标为 ELF 时，重路由为 `execve(/system/bin/linker64, [linker64, path, argv...])`
  ——系统链接器以"加载 native 库"的机制加载目标（全版本、全厂商一致允许）；
- 非 ELF（脚本/shebang）与 linker 自身直接放行；重路由失败（ENOENT/EACCES）
  回退原始 syscall；
- `LD_PRELOAD` 随 linker64 加载的进程树继承，重路由覆盖全部子进程
  （node 插件、bash、工具链）；
- 不依赖 SELinux 域判断（区别于快照 termux-exec 仅豁免 untrusted_app_25/27）。

**三层防护**（通用，无版本/厂商特判）：

| 层 | 机制 | 覆盖 |
|---|---|---|
| 主进程 | direct exec → EACCES 时 linker64 fallback（startWithArgs） | 全部 |
| 子进程 | LD_PRELOAD exec-hook 重路由 → linker64 | 全部 |
| 文件级 | 解压时剥离可执行文件写位（rwx→r-x） | 华为类 W^X |

**已知限制**：内核解析 shebang 后的解释器 exec 不经 libc，hook 无法拦截——
Android 15/16 上 `.sh` 脚本类工具仍受限（引擎核心为 node ELF，不受影响）。

### 4.1 启动流程（MainActivity.startEngineFlow）

1. 探测引擎；运行中 → `showWeb()`。
2. 未解压（`usr/bin/node` 不存在）→ 解压 + 进度反馈。
3. `startEngine()`：断言 `libtermux-exec-ld-preload.so` 存在（缺失则所有子进程
   exec 失败 → loud fail）；注入 env 后 spawn。
4. 轮询探测最多 30s（1s 间隔）→ 成功后拉起前台服务 + Shizuku 增强。
5. 失败回落到引导页。

`onCreate` 与 `onResume` 都触发本流程，`engineFlowRunning` CAS 防双线程
解压/启动（设备实证：双启动导致引擎进程死亡）。

### 4.2 进程级并发控制（EngineManager companion）

| 状态 | 机制 | 目的 |
|---|---|---|
| `STARTING` | AtomicBoolean CAS | 跨 EngineManager 实例（MainActivity 与 EngineService 各 new 一个）防双启动 |
| `lastStartAttemptAt` | @Volatile Long | 冷却窗口基准 |
| `engineProcess` | @Volatile Process? | 进程级共享，双实例可见同一进程 |
| `START_COOLDOWN_MS = 90s` | — | 冷启动 node 20–45s，防看门狗与健康启动竞争（EADDRINUSE） |

冷却规则：进程已死（`isAlive != true`）时立即清零冷却——崩溃后看门狗可在
下一轮（5s）立刻重启，无需等 90s。冷却只在真实启动后写入，失败路径不占窗口。

### 4.3 环境注入（startEngine）

| 变量 | 值 | 理由 |
|---|---|---|
| `PATH` | `usr/bin:/system/bin` | 快照自带工具链优先 |
| `LD_LIBRARY_PATH` | `usr/lib` | Termux 库 |
| `HOME` | `filesDir/home` | 私有域 |
| `DSH_HOME` | `filesDir/home/.dsh` | 必须私有（见 §5.2） |
| `TMPDIR` | `filesDir/home/tmp` | 系统 tmp 在 app 域不可写 |
| `LD_PRELOAD` | `usr/lib/libtermux-exec-ld-preload.so` | Android 16 禁止 exec app-data ELF；经 execve hook 走 linker64 |
| `TERMUX_EXEC__*` | force/intercept | hook 仅重写 untrusted_app_25/27 SELinux 域，必须 force |
| `TERMUX__ROOTFS` / `TERMUX__PREFIX` | filesDir / usr | Termux 环境锚点 |
| `TERMUX_APP__*` | 数据目录 | Termux 兼容 |
| `TERMUX_VERSION` | 0.118.3 | 快照配套版本 |
| `DSH_PICK_TOKEN` | 随机 UUID（每次进程启动） | 目录桥端点鉴权（web-compat 插件校验 `x-dsh-pick-token`） |

exec 拒绝回退：`startWithArgs` 捕获 `Permission denied`，改经
`/system/bin/linker64` 拉起（与 JNI 库同机制，app 数据恒允许）。

### 4.4 保活（EngineService）

- 前台服务（`dataSync` 类型，`FOREGROUND_SERVICE_DATA_SYNC`），常驻通知
  "dsh engine running"。
- `ensureEngine()`：探测 → 未运行则启动 → arm 看门狗（`scheduleWithFixedDelay`
  5s）：探测失败且快照就绪 → `startEngine()`。
- `START_STICKY`：进程被杀后系统重建服务。
- `onDestroy`：关看门狗 + 停引擎进程（服务销毁后无人接管，防孤儿进程）。

## 5. 数据布局与迁移

### 5.1 布局

| 路径 | 内容 |
|---|---|
| `filesDir/usr` | 运行时快照（引擎本体，可整体替换） |
| `filesDir/home` | 引擎 `HOME`；`.dsh` = `DSH_HOME` |
| `filesDir/engine.log` | 引擎输出（合并重定向） |
| `filesDir/update.tar.xz` / `update-stage` / `usr-old` | 更新暂存与回滚 |
| `/storage/emulated/0/Documents/dshdata` | 公共用户数据 |

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
4. 解压到 `update-stage`（不在运行树内），校验新 `usr/bin/node` 存在。
5. 切换（带回滚）：
   - `usr` 存在且挪不动 → 保持现状，放弃切换；
   - `usr → usr-old` 成功但 `new usr → usr` 失败 → 回滚 `usr-old → usr`；
     回滚也失败 → 保留 `usr-old` 供手动恢复。
6. `pkill -f bin.js` 杀旧引擎 → 看门狗下轮（≤5s）从新运行时重启。

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
| 解压 | 每条目 canonical 路径校验，逃逸根目录即抛异常（tar slip） |
| WebView 边界 | 仅引擎同源（scheme/host/port 精确匹配）留 WebView；外部链接交系统浏览器 |
| 下载 | 仅引擎同源 URL（防本机 SSRF）；in-flight 去重；MediaStore 流式 + 200MB 上限 |
| 导出路径匹配 | `/api/session.export` 精确匹配（非前缀） |
| 目录桥 | 进程随机 token（DSH_PICK_TOKEN + `x-dsh-pick-token` 校验）；pick 需用户交互确认 |
| JS 注入面 | `allowFileAccess=false`、禁止混合内容、仅同源页面可触达桥 |
| 触发面 | ACTION_UPDATE 仅 debug；SAF 选择结果始终回传（取消/失败不挂起） |

## 8. 权限

| 权限 | 用途 | 时机 |
|---|---|---|
| INTERNET | WebView + 探测 + 更新 | 声明 |
| MANAGE_EXTERNAL_STORAGE | 外部工作区真实路径访问（All Files Access） | API 30+ 运行时引导授权 |
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
- `snapshot.tar.xz` 不入库（GitHub Releases 分发）；缺失时构建 loud fail 并
  给出获取指引（scripts/make-snapshot.sh 为 Termux 端打包脚本）。
- `noCompress += "xz"`：防二次压缩破坏 `openFd`。
- lint 不阻断（离线环境无 lint-gradle 缓存）。
- 引擎启动超时诊断：node.canExecute / 进程存活 / engine.log 全文入 AppLog；
- 依赖：androidx.activity-ktx 1.13.0、commons-compress 1.28.0、xz 1.12、
  shizuku api/provider 13.1.5；NDK 27.2.12479018（exec-hook 编译）。
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
- 更新后引擎重启由看门狗轮询驱动（≤5s 延迟）。
- 系统深色依赖厂商 WebView 对 `FORCE_DARK_AUTO` 的支持，另以桥值补丁兜底。

## 12. 决策记录

| 决策 | 选择 | 原因 |
|---|---|---|
| D1 内嵌快照 vs 依赖 Termux | 内嵌 xz 快照，解压即跑 | 免安装、离线、版本自足；保留 Termux 探测兼容 |
| D2 DSH_HOME 私有 + 数据项迁移 | 私有实体 + symlink 落公共 | FUSE 禁 symlink（apk#8） |
| D3 更新完整性 | HTTPS + sha256 必填 | 明文+可空摘要 = RCE（I-01） |
| D4 targetSdk | 34 | Android 15+ exec 限制由 linker64 兜底；华为 W^X（可写文件禁 exec）由解压去写位解决 |
| D5 引擎并发 | 进程级 CAS + 90s 冷却 + 进程死亡清冷却 | 防 EADDRINUSE 双启动；崩溃快速恢复 |
| D6 下载路径 | 应用内 HttpURLConnection → MediaStore | 浏览器导航带 Origin:null 被 dsh fence 403 |
| D7 ACTION_UPDATE | 仅 debug | exported LAUNCHER activity 的任意触发面 |
