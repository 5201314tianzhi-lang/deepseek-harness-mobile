# 代码审查问题清单

> 2026-08-15 审查产出。按依赖分组，P1 = 安全（优先处理），P2 = 正确性 bug，P3 = 健壮性。
> 状态：🟥 待处理 / 🟨 处理中 / 🟩 已修复
> 全部 14 项已于 2026-08-15 修复。本机无 JDK/Android SDK 且网络受限，未做本地编译验证——
> 已做 XML 解析校验 + Kotlin 结构（括号/字符串/注释）平衡检查；请在装有 JDK 17+ 与
> Android SDK 的环境跑 `./gradlew assembleDebug`（注意先放入 snapshot.tar.xz）确认编译。

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
