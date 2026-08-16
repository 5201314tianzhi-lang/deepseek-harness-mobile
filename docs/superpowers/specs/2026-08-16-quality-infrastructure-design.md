# 代码质量基础设施设计

> 日期:2026-08-16
> 状态:已批准(用户确认设计)

## 背景与目标

项目现状:

- Android lint 非阻塞(`abortOnError = false`, `checkReleaseBuilds = false`)
- 无 Kotlin 静态分析(ktlint/detekt)
- 零单元测试(本次范围外)
- release 工作流仅在打 tag 时触发,日常 push/PR 没有任何编译或质量验证
- 本机无 JDK,gradle 无法本地运行,门禁必须在 GitHub Actions 执行

目标:任何 push 到 `main` 或 PR 都自动跑 编译 + Android lint + ktlint,任一失败即拦截。同时解决"平时无人编译验证"的现状问题。

## 设计

### 1. Gradle 侧

**ktlint 插件接入**(`app/build.gradle.kts` 或根构建):

- 使用 `org.jlleitschuh.gradle.ktlint` 插件,与构建集成,本地有 JDK 时也可 `./gradlew ktlintCheck`
- 实施时验证与 Gradle 9.7 / AGP 9.3.1 / Kotlin 2.4.10 的兼容性;不兼容则回退 ktlint 官方 `com.pinterest.ktlint` 插件,再不行回退 CI 独立 CLI
- 版本选型时优先取与 Kotlin 2.4 兼容的最新稳定版

**lint 提升为阻塞**:

- `abortOnError = true`(lint error 即构建失败)
- 保留 `checkReleaseBuilds = false`:release 构建不被 lint 拖累;门禁在 CI 里显式跑 `lintDebug`
- `lint.ignoreTestSources` 等不做额外配置(无测试源码)

**.editorconfig**(仓库根):

- `root = true`
- `*.kt`/`*.kts`: 2-space 缩进、`utf-8`、`lf`、最终换行 — 与现状代码风格一致,避免 ktlintFormat 产生大规模无关改动
- ktlint 默认规则集 + 上述样式对齐

**存量全库格式化**:

- 接入插件后跑一次 `ktlintFormat` 自动修全库(~4000 行)
- 格式化结果独立提交,便于审查;人工 review 确认无语义变更

### 2. CI 侧(新文件 `.github/workflows/ci.yml`)

- 触发:`push` 到 `main` + `pull_request`(含 draft PR 不跳过,保持简单)
- 单 job(`quality-gate`),步骤:
  1. `actions/checkout@v7`
  2. JDK 17(`actions/setup-java`,temurin)
  3. Android SDK(`android-actions/setup-android`,platforms;android-36 + build-tools;36.0.0 + ndk;27.2.12479018 — 与 release.yml 相同)
  4. 下载运行时 snapshot 到 `app/src/main/assets/snapshot.tar.xz`(从上游 `kelai141/dsh-mobile-apk` v0.10.4 release 资产,按 ABI 矩阵取 arm64;`mergeDebugAssets` 缺文件即失败,必须提供)
  5. `./gradlew assembleDebug lintDebug ktlintCheck`
- 任一步骤失败 → job 红 → 合并拦截
- 不做 snapshot 缓存(每次 ~80MB,保持简单;后续可加)

### 3. 文档

- README `Build` 节补充:质量门禁说明(CI 跑什么、本地怎么跑)
- AGENTS.md 记录:`./gradlew ktlintCheck lintDebug` 为改代码后的必跑检查命令

## 验证策略

本机无 JDK,无法本地跑 gradle:

1. 优先检查本机是否有 docker;有则用 gradle 官方镜像在容器里验证 `ktlintCheck`(不含 Android 部分)
2. 完整验证(含 assembleDebug)依赖 CI 首次运行 — 需用户确认后 push 到 GitHub 触发

## 范围外(明确不做)

- 单元测试基础设施(用户未选)
- 本地 pre-commit hooks(用户未选)
- detekt 等额外静态分析
- lint 存量 warning 基线(门禁只拦 error)
