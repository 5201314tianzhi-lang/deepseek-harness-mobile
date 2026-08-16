# 代码审查问题清单

> 2026-08-15 审查产出。按依赖分组，P1 = 安全（优先处理），P2 = 正确性 bug，P3 = 健壮性。
> 状态：🟥 待处理 / 🟨 处理中 / 🟩 已修复
> 全部 24 项已修复（I-01..I-14 于 2026-08-15，I-15..I-24 于 2026-08-16 全面审查）。
> 修复经 CI 质量门验证（assembleDebug + lintDebug + ktlintCheck + 单元测试 + JS/C 本地测试）。

---

## 依赖顺序

```
P1 安全组
 ├─ I-01 明文 HTTP 更新链路（manifest + network security config）
 ├─ I-02 Tar 解压路径穿越（SnapshotExtractor）
 └─ I-03 exported MainActivity 的 ACTION_UPDATE 门控
P2 MainActivity 组
 ├─ I-04 keepScreenOn wakelock 泄漏（引用丢失，永远关不掉）
 ├─ I-05 onResume 主线程网络探测（恒失败 → 每次回前台 reload）
 ├─ I-06 isSessionExport contains 前缀误匹配
 └─ I-07 showTestNotification 权限回调后不重发通知
P3 UpdateManager 组
 ├─ I-08 运行时切换非原子、失败无回滚
 └─ I-09 更新下载无总大小上限
P3 EngineManager 组
 ├─ I-10 卸载重装后 dshdata 链接丢失（数据不可见）
 └─ I-11 engineProcess 实例字段不共享 + 进程死亡后仍卡 90s 冷却
P3 收尾组
 ├─ I-12 EngineService.onDestroy 不停引擎（孤儿进程）
 └─ I-13 EngineProbe.check 异常分支不释放连接
```

---

## P1 安全

### I-01 明文 HTTP 运行时更新 = 远程代码执行 🟩

**位置**：`app/build.gradle.kts` / `AndroidManifest.xml:15` / `UpdateManager.kt:22,34,40,120`

**问题**：
- `AndroidManifest.xml` 全局 `usesCleartextTraffic="true"`，默认 manifest URL 是明文 `http://10.0.2.2:8899/manifest.json`。
- 更新流程下载快照并**执行其中的 node/bash 代码**，完整性仅靠 sha256，而 sha256 与快照同源（同一份明文 manifest，MITM 可同时替换）。
- `UpdateManager.kt:34` `optString("sha256", "")` 为空时**直接跳过校验**（I-01b）。

**修复**：network_security_config 仅对 `127.0.0.1` 放行明文；manifest 强制 HTTPS；sha256 必填。

### I-02 Tar 解压路径穿越（tar slip）🟩

**位置**：`SnapshotExtractor.kt:37,43`

**问题**：`File(dest, entry.name)` 不校验条目名是否含 `../`，symlink 的 `linkName` 也不校验。在线更新路径解压**外部下载的第三方快照**，恶意 tar 可越界写 app 沙箱内任意文件/建任意 symlink。

**修复**：条目名规范化后必须位于 dest 之下；拒绝 `..` 段；symlink 目标同样校验。

### I-03 exported MainActivity 触发远程更新 🟩

**位置**：`AndroidManifest.xml:24` / `MainActivity.kt:103`

**问题**：LAUNCHER activity `exported="true"`，任意 app 可发隐式 intent `com.dshmobile.shell.action.UPDATE` 触发网络下载+代码执行链路（配合 I-01 放大）。

**修复**：`ACTION_UPDATE` 仅 debuggable（debug）构建可用，release 禁用。

---

## P2 正确性 bug

### I-04 keepScreenOn 的 wakelock 永远关不掉 🟩

**位置**：`MainActivity.kt:443-448`

**问题**：每次调用 `power.newWakeLock` 新建对象，`enable=false` 时 `wakeLock.isHeld` 检查的是全新对象（恒 false），`release()` 永不执行；多次 `enable=true` 泄漏多把锁。

**修复**：wakelock 保存为字段，复用同一实例。

### I-05 onResume 主线程网络探测恒失败 🟩

**位置**：`MainActivity.kt:113`

**问题**：`EngineProbe.check()` 用 `HttpURLConnection`，主线程调用必抛 `NetworkOnMainThreadException`（被 catch 吞掉）→ 恒判"未运行"→ 每次回前台触发 `startEngineFlow` + `webView.reload()`，丢页面状态。

**修复**：探测移到后台线程。

### I-06 isSessionExport 前缀误匹配 🟩

**位置**：`MainActivity.kt:420`

**问题**：`url.contains("/api/session.export")` 误命中 `/api/session.export.evil` 等。

**修复**：用 Uri 精确比较 path。

### I-07 通知权限回调后不重发 🟩

**位置**：`MainActivity.kt:90-91,450-455`

**问题**：`showTestNotification` 在无权限时请求权限后直接 return，权限回调为空——首次点击只弹权限框，通知永远不发。

**修复**：回调中检查授权并重发待发通知。

---

## P3 健壮性

### I-08 运行时切换非原子、无回滚 🟩

**位置**：`UpdateManager.kt:61-65`

**问题**：`usr.renameTo(old)` 返回值被忽略；两步 rename 之间崩溃则 `usr` 缺失；`newUsr.renameTo(usr)` 失败时引擎无法启动。

**修复**：检查每步结果，失败时回滚。

### I-09 更新下载无大小上限 🟩

**位置**：`UpdateManager.kt:91-98`

**问题**：manifest 可指向超大文件，无总大小限制，可填满存储。

**修复**：流式写入并设上限（与导出路径一致的 200MB 策略）。

### I-10 卸载重装后 dshdata 数据不可见 🟩

**位置**：`EngineManager.kt:83-123`

**问题**：迁移标记 `.migrated-from` 写在公共目录（持久），但迁移产物（私有 symlink）随卸载删除。重装后 marker 仍在 → 跳过迁移 → symlink 不重建，公共目录旧数据（sessions/attachments）不可见，与注释"卸载重装不丢"相反。

**修复**：增加幂等重连——公共目录有数据/标记时，重建缺失的私有 symlink。

### I-11 engineProcess 不共享 + 进程死后卡 90s 冷却 🟩

**位置**：`EngineManager.kt:34,175` / `EngineService.kt:52`

**问题**：
- `engineProcess` 是实例字段，MainActivity 与 EngineService 各持一个 `EngineManager` 实例，进程状态互相不可见；
- watchdog 5s 轮询但冷却 90s：引擎启动后立即崩溃时，watchdog 持续撞冷却窗口，最长 90s 才重启。

**修复**：`engineProcess` 提到 companion（进程级共享，与 STARTING 一致）；startEngine 发现进程已死时重置冷却（进程已死即无双启动竞态）。

### I-12 EngineService 销毁不停引擎 🟩

**位置**：`EngineService.kt:38-42`

**问题**：onDestroy 只关 watchdog，引擎进程成孤儿。

**修复**：onDestroy 中停止引擎进程。

### I-13 EngineProbe 异常分支不释放连接 🟩

**位置**：`EngineProbe.kt:29`

**问题**：catch 分支未 `disconnect()`。

**修复**：finally 释放。

### I-14 签名密钥公开发布 + 密码硬编码 🟩（2026-08-16 全面审查修复）

**位置**：`.github/workflows/release.yml:96-117`（旧）

**问题**：keystore 缺失时回退到公开 Release asset 下载，再不行就 keytool 现生成并 `--clobber` 上传为公开资产，密码写死 `android`——任何人可拿到真签名伪造同签名更新包覆盖安装（继承 MANAGE_EXTERNAL_STORAGE 全盘权限）；双矩阵腿并发时各自生成不同密钥互相覆盖，arm64/x86_64 产物签名不一致。

**修复**：密钥只从 secret `RELEASE_KEYSTORE_B64` 读取并校验，缺失即 exit 1；删除 asset 回退与生成分支。

### I-15 引擎生命周期三方无仲裁（主线程探测/退出即杀/看门狗死锁）🟩

**位置**：`EngineService.kt` / `MainActivity.kt:185-188`

**问题**：
- `onStartCommand` 主线程跑 `EngineProbe.check()`（HTTP I/O）→ NetworkOnMainThreadException 被吞 → 守卫恒失效 → 真实双启动（90s 冷却过期后 EADDRINUSE）；
- `MainActivity.onDestroy` 无条件 stopEngine 杀掉健康引擎，5s 后看门狗又冷启动拉回（退出即杀、再必然拉起）；
- 看门狗 `scheduleWithFixedDelay` 任务体无 try/catch：一次异常 = 调度被静默抑制 = 引擎永远无人重启。

**修复**：ensureEngine 移后台线程；看门狗总 arm（无论引擎是否在跑）+ 任务体全 try/catch；引擎生命周期归 EngineService 唯一 owner，Activity 不杀引擎。

### I-16 pickToken 生命周期失配 → 目录选择静默失效 🟩

**位置**：`EngineManager.kt` / `EngineService.kt:27`

**问题**：token 是 Activity 实例随机值；引擎被服务（无 token 的 EngineManager）重启后 `DSH_PICK_TOKEN=""` 与 WebView 桥 token 失配 → 划掉任务重开后 pick 目录功能坏掉且永不重启（probe 显示 running）。

**修复**：token 提升为 EngineManager companion 进程级单例，所有实例（含服务）读取同一值。

### I-17 更新流程无单飞 + 固定路径并发交换 🟩

**位置**：`UpdateManager.kt`

**问题**：两个入口（按钮 + ACTION_UPDATE）共用固定 `update.tar.xz`/`update-stage` 且无 CAS：交错时序可删掉对方正在写入的 stage，把空心目录 rename 进活树；`pkill` 失败被吞（引擎继续跑旧 inode 且永不重启）；失败路径残留 ~500MB tarball。

**修复**：进程级 CAS 单飞 + UUID 唯一 tmp/stage 名 + finally 清理 + pkill 失败显式提示（新字符串 update_restart_hint）。

### I-18 解压中断后永久卡死（悬空 symlink + r-x 覆盖）🟩

**位置**：`SnapshotExtractor.kt`

**问题**：
- `target.exists()` 跟随 symlink：悬空链接不删除 → createSymbolicLink 抛异常 → 每次重试同样失败，唯一出路是清数据重装；
- 覆盖已剥离写位的 r-x 文件 → `outputStream()` EACCES → 同样永久卡死。

**修复**：改用 `Files.isSymbolicLink` 判断；开流前恢复写位（幂等可重入）；异常路径 finally 关流；硬链接条目物化复制（原实现写成 0 字节空文件）。

### I-19 rootfs 校验可旁路 + 破坏性失败顺序 🟩

**位置**：`RootfsDownloader.kt`

**问题**：
- `expectedChecksum` 的 catch-all 吞掉解析错/磁盘错 → 校验服务器不可达 = 裸装未校验 rootfs；
- 先 `rootfs.deleteRecursively()` 再解压：中途失败即销毁上一版可用 rootfs，只剩半棵树；
- `if (running) return false; running = true` 检查-赋值非原子。

**修复**：checksum 硬性（want==null 即失败）；staging 目录解压 → rename 原子替换带 rollback（rootfs-staging/rootfs-old）；AtomicBoolean CAS。

### I-20 WakeLock 永不释放 + JS 桥非主线程 launch 🟩

**位置**：`MainActivity.kt:207-214` / `PickerBridge.kt`

**问题**：`keepScreenOn(true)` 后锁被永久持有（onDestroy 不 release，前台服务保进程）→ 屏幕常亮电池耗尽；`@JavascriptInterface` 在 WebKit 线程直接调 `directoryPicker.launch()`（androidx 新版本必抛 IllegalStateException）。

**修复**：onDestroy 无条件 release；所有 ActivityResultRegistry.launch 回主线程。

### I-21 onResume 无条件 reload + 待决 pick 回调跨重建丢失 🟩

**位置**：`HarnessWebView.kt` / `PickerBridge.kt` / `MainActivity.kt`

**问题**：每次回前台整页 reload（会话 UI 全丢、与 pick 结果竞态）；Activity 重建（locale/低内存回收）后 `pendingPickCallback` 丢失，引擎侧 promise 永不 settle、页面反复重开选择器。

**修复**：`loadFailed` 标志——仅错误页时 reload；pending callback 随 onSaveInstanceState 保存/恢复。

### I-22 exec-hook 重路由边界过宽 🟩

**位置**：`app/src/main/cpp/exec-hook.c`

**问题**：is_elf 只查 4 字节 magic——静态 ELF/跨架构 ELF 被误路由（linker 加载失败且不回落原生）；前缀比较误伤 `/system/bin/linker64-*`；FIFO open 可能挂死 exec 链；`argv==NULL` 段错误。

**修复**：e_machine 与构建 ABI 校验；重路由任何失败回落原生；精确 strcmp；O_NONBLOCK；argv NULL 按空 argv 处理。

### I-23 双 ABI 矩阵无效 + 版本元数据静态 🟩

**位置**：`release.yml` / `app/build.gradle.kts`

**问题**：gradle 无 abiFilters，两腿产出同一通用 APK 仅后缀不同（32 位 ABI 混入且 hook 硬编码 linker64）；versionCode 恒 1——发布 v0.2.0 用户无升级感知。

**修复**：`-PabiFilter` 每腿单 ABI；`-PversionName/-PversionCode` 由 tag 推导（v0.1.0 → 100）；ShizukuProvider 声明补齐；curl -f；update_release 允许重发。

### I-24 JS 桥/UI 余项 🟩（部分修复）

**位置**：`GuideWizard.kt` / `AndroidBridge.kt` / `ExportFlow.kt` / `MainActivity.kt` / `compat-polyfills.js`

**修复**：检查更新回调回主线程；导出下载禁跟随重定向；顶部条失败路径清理（hideTopBar 公开 + 失败统一 showGuide）；冷启动顶部条 6s 后淡出（呼吸动画可被看见）；Object.groupBy null 原型累加器（`__proto__` 键原型污染）；structuredClone 支持 DataView；AbortSignal.any 透传 reason；子进程探测 30s 受限等待；更新/解压/根目录轮询 60s。
