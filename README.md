# dsh-mobile-apk — DeepSeek Harness 安卓壳 APK

Android shell for [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness):
WebView UI over an **embedded Termux runtime snapshot** (extract-and-run, no Termux
app needed), SAF directory bridge, keep-alive foreground service, engine watchdog,
and online runtime updates.

## 源码仓库内容

完整 Gradle 工程源码（Kotlin）：MainActivity / AndroidBridge（SAF/通知/版本探测）/
EngineManager（快照解压+引擎进程）/ EngineService（保活+看门狗）/ UpdateManager（在线更新）/
ShizukuSupport / SnapshotExtractor。**构建产物与运行时快照不入库**（见下）。

## 构建（clone 之后）

要求：JDK 17+、Android SDK（compileSdk 36）；Gradle 8.11.1 由 wrapper 提供。

```sh
# 1. 准备运行时快照（必须，约 70MB，大文件走 GitHub Releases）
#    方式 A：从 GitHub Releases 下载 snapshot-x86_64.tar.xz
#    方式 B：按 scripts/make-snapshot.sh 在 Termux 设备自打后拉取到 snapshot/
#    然后放到 APK 资源：
mkdir -p app/src/main/assets
cp snapshot/snapshot.tar.xz app/src/main/assets/snapshot.tar.xz

# 2. 构建（缺快照会构建失败并提示）
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 运行时说明

- 首次启动自动解压快照（约 10s）→ 引擎自启 → WebView 显示 dsh UI；
- 引擎保活：Foreground Service + 5s 看门狗（崩溃自愈）；
- 在线更新：引导页「检查运行时更新」→ manifest {url, sha256} → 下载/校验/原子切换/自动重启
  （测试服务器 `node scripts/snapshot-server.mjs`，manifest URL 默认 10.0.2.2:8899）；
- 桥协议 v1：`window.androidBridge`（version/checkEngine/keepScreenOn/showNotification/pickDirectory）。

## License

MIT（见 LICENSE）。第三方依赖许可见各自包声明。
