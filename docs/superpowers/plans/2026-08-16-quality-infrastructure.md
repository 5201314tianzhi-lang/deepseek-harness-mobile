# 代码质量基础设施实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 dsh-apk 建立 CI 质量门禁(compile + Android lint 阻塞 + ktlint),任何 push 到 main / PR 自动执行,失败即拦截。

**Architecture:** ktlint 不接插件门户(plugins.gradle.org 在本项目目标网络实测被拦截),改为 Maven Central 上的 ktlint-cli 1.8.0 jar + 根构建里两个 JavaExec 任务(`ktlintCheck` / `ktlintFormat`),CI 与本地同一入口。CI 门禁为独立 workflow `.github/workflows/ci.yml`。

**Tech Stack:** Gradle 9.7 / AGP 9.3.1 / Kotlin 2.4.10、ktlint-cli 1.8.0(`com.pinterest.ktlint:ktlint-cli`)、GitHub Actions(ubuntu-latest, JDK 17)。

**Spec:** `docs/superpowers/specs/2026-08-16-quality-infrastructure-design.md`

## Global Constraints

- 任务入口名必须是 `ktlintCheck` / `ktlintFormat`(CI 与本地同一命令)
- ktlint 来源只能走 Maven Central,不得引入 plugins.gradle.org 依赖
- ktlint 以 `--android` 标志运行;扫描仓库根,依赖默认规则尊重 `.gitignore`(`build/`、`.gradle/` 已忽略)
- Android lint:`abortOnError = true`;保留 `checkReleaseBuilds = false`(release 构建不被 lint 拖累)
- CI 只跑 `assembleDebug lintDebug ktlintCheck`(不跑 release;release 走现有 release.yml)
- 本机无法运行任何 gradle 任务(`maven.google.com` 超时,AGP 解析不了)——gradle 配置验证依赖 CI 首次运行;ktlint 真实逻辑用本机 JDK 21 + 同一 jar 独立验证
- 提交信息沿用仓库风格(`feat:` / `fix:` / `docs:` / `style:` / `build:` / `ci:` 小写前缀)
- 每个任务独立提交,全库格式化单独一个提交(便于审查无语义变更)

---

### Task 1: 全库 ktlint 格式化(.editorconfig + 格式化存量代码)

本任务用 JDK 21 + ktlint-cli jar 直接跑(与 Task 2 的 gradle 任务逻辑完全一致),产出一个 ktlint 零违规的仓库。这是门禁能全量生效的前提。

**Files:**
- Create: `.editorconfig`
- Modify: 仓库内全部 `.kt` / `.kts`(ktlintFormat 自动格式化)
- Test: 无(无测试设施,验证 = ktlintCheck 零违规输出)

- [ ] **Step 1: 准备 JDK 与 ktlint-cli**

```bash
export PATH=/usr/lib/jvm/java-21-openjdk-arm64/bin:$PATH
java -version
mkdir -p /tmp/opencode/ktlint
curl -sfSL --max-time 120 -o /tmp/opencode/ktlint/ktlint-cli.jar \
  "https://repo.maven.apache.org/maven2/com/pinterest/ktlint/ktlint-cli/1.8.0/ktlint-cli-1.8.0-all.jar"
ls -lh /tmp/opencode/ktlint/ktlint-cli.jar
```

Expected: `java` 输出 21.x;jar 约 40-60MB 下载成功。

- [ ] **Step 2: 创建 `.editorconfig`**

写入以下内容(仓库根):

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
indent_style = space
indent_size = 2

[*.{yml,yaml}]
indent_size = 2
```

注意:不设 `max_line_length`(ktlint 默认 140),避免人为收紧换行;若 Step 3 显示大量 max-line-length 违规,把 `max_line_length = 140` 显式补进 `[*.{kt,kts}]` 段再重跑。

- [ ] **Step 3: 预检——先跑 check,记录违规清单**

```bash
cd /root/projects/dsh-apk
java -jar /tmp/opencode/ktlint/ktlint-cli.jar --android --reporter=plain . 2>&1 | tee /tmp/opencode/ktlint/check-before.txt
wc -l /tmp/opencode/ktlint/check-before.txt
```

Expected: 输出为当前违规列表(数量不重要,只是基线记录)。**若输出是报错/异常堆栈**(例如不认识的规则名、解析失败),立即停止,把错误内容报告给用户,不要继续。

- [ ] **Step 4: 全库格式化**

```bash
java -jar /tmp/opencode/ktlint/ktlint-cli.jar --android --format . 2>&1 | tail -20
git status --short
git diff --stat
```

Expected: 输出被修改文件列表;`git diff --stat` 显示改动全部为 .kt/.kts。检查 diff 只有风格变化:
`git diff --word-diff=porcelain | grep -E '^[+-]' | grep -vE '^[+-]{3}' | grep -vE '^[+-]\s*(\*|//|/\*\*)' | head -50`
——确认无代码语义改动(只允许:缩进/空格/换行/import 排序/尾逗号/字符串内不可变)。

- [ ] **Step 5: 复查 check 已零违规**

```bash
java -jar /tmp/opencode/ktlint/ktlint-cli.jar --android --reporter=plain . 2>&1 | tee /tmp/opencode/ktlint/check-after.txt
wc -l /tmp/opencode/ktlint/check-after.txt
```

Expected: `0` 行(或只有空白行)。若有残留违规:**这些是 ktlintFormat 无法自动修的**,逐个手工修复(大多为 max-line-length 或 import 顺序),修复后重跑直到 0。

- [ ] **Step 6: 提交**

```bash
git add .editorconfig
git add -u '*.kt' '*.kts'
git commit -m "style: ktlint 1.8 format across the repo + .editorconfig"
```

---

### Task 2: 根构建加 ktlintCheck / ktlintFormat 任务

把 Task 1 验证过的 ktlint 逻辑包装成 gradle 任务,CI 与本地统一入口。**本机无法运行验证**(AGP 解析不了),任务体保持极简以降低风险;真实运行由 Task 4 的 CI 首次执行验证。

**Files:**
- Modify: `build.gradle.kts`(根)

- [ ] **Step 1: 修改根 `build.gradle.kts`**

在文件现有 `plugins {}` 块之后追加:

```kotlin
// ktlint via Maven Central CLI (CN-network friendly; plugins.gradle.org is
// unreachable there). Same engine as the standalone jar — CI runs
// `./gradlew ktlintCheck`, local devs with a JDK can run it too.
val ktlintConfig by configurations.creating

dependencies {
  ktlintConfig("com.pinterest.ktlint:ktlint-cli:1.8.0")
}

val ktlintCheck by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Check Kotlin code style with ktlint"
  classpath = ktlintConfig
  mainClass.set("com.pinterest.ktlint.Main")
  args("--android")
}

val ktlintFormat by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Format Kotlin code with ktlint"
  classpath = ktlintConfig
  mainClass.set("com.pinterest.ktlint.Main")
  args("--android", "--format")
}
```

完整文件追加后的形态:

```kotlin
plugins {
  id("com.android.application") version "9.3.1" apply false
  id("org.jetbrains.kotlin.android") version "2.4.10" apply false
}

// ktlint via Maven Central CLI (CN-network friendly; plugins.gradle.org is
// unreachable there). Same engine as the standalone jar — CI runs
// `./gradlew ktlintCheck`, local devs with a JDK can run it too.
val ktlintConfig by configurations.creating

dependencies {
  ktlintConfig("com.pinterest.ktlint:ktlint-cli:1.8.0")
}

val ktlintCheck by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Check Kotlin code style with ktlint"
  classpath = ktlintConfig
  mainClass.set("com.pinterest.ktlint.Main")
  args("--android")
}

val ktlintFormat by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Format Kotlin code with ktlint"
  classpath = ktlintConfig
  mainClass.set("com.pinterest.ktlint.Main")
  args("--android", "--format")
}
```

注意:两个 JavaExec 任务都从仓库根目录运行(默认 workingDir = root 项目目录),ktlint 默认扫描规则尊重 `.gitignore`(`build/`、`.gradle/` 被排除)。

- [ ] **Step 2: 自检(无法运行,做静态检查)**

```bash
cd /root/projects/dsh-apk
git diff build.gradle.kts
python3 - <<'EOF'
# 括号/字符串平衡粗检
s = open('build.gradle.kts').read()
assert s.count('{') == s.count('}'), 'brace mismatch'
assert s.count('(') == s.count(')'), 'paren mismatch'
print('syntax sanity OK')
EOF
```

Expected: diff 只含上述新增内容;`syntax sanity OK`。

- [ ] **Step 3: 提交**

```bash
git add build.gradle.kts
git commit -m "build: ktlintCheck/ktlintFormat tasks via Maven Central CLI"
```

---

### Task 3: Android lint 提升为阻塞门禁

**Files:**
- Modify: `app/build.gradle.kts:76-81`(lint 块)

- [ ] **Step 1: 修改 lint 块**

现有:

```kotlin
  lint {
    // Offline environments have no lint-gradle dependency cache (CN network);
    // lint is not on the release critical path.
    checkReleaseBuilds = false
    abortOnError = false
  }
```

改为:

```kotlin
  lint {
    // The CI quality gate runs `lintDebug` and fails on errors (abortOnError).
    // checkReleaseBuilds stays off: release builds are not blocked by lint —
    // the gate is explicit, not implicit on the release path.
    checkReleaseBuilds = false
    abortOnError = true
  }
```

- [ ] **Step 2: 提交**

```bash
git add app/build.gradle.kts
git commit -m "build: Android lint is a blocking gate (abortOnError)"
```

---

### Task 4: CI 质量门禁工作流

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: 创建 `.github/workflows/ci.yml`**

写入(步骤与 release.yml 已验证的步骤保持一致;snapshot 取 arm64 一个 ABI 即满足编译门禁):

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

permissions:
  contents: read

jobs:
  quality-gate:
    name: Quality gate (compile + lint + ktlint)
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      - name: Set up JDK 17
        uses: actions/setup-java@v5.7.0
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Android SDK
        uses: android-actions/setup-android@v4.0.1
        with:
          packages: 'platforms;android-36 platform-tools build-tools;36.0.0 ndk;27.2.12479018'
          accept-android-sdk-licenses: 'true'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v6.3.0

      - name: Download runtime snapshot (arm64) from upstream releases
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          mkdir -p app/src/main/assets
          asset_url=$(gh api repos/kelai141/dsh-mobile-apk/releases/tags/v0.10.4 \
            --jq '.assets[] | select(.name == "snapshot-arm64.tar.xz") | .url')
          [ -n "$asset_url" ] || { echo "snapshot-arm64.tar.xz not found upstream"; exit 1; }
          curl -sfL -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/octet-stream" \
            "$asset_url" -o app/src/main/assets/snapshot.tar.xz
          ls -lh app/src/main/assets/snapshot.tar.xz

      - name: Quality gate
        run: ./gradlew assembleDebug lintDebug ktlintCheck
```

- [ ] **Step 2: YAML 静态校验**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml')); print('yaml OK')" || python3 - <<'EOF'
import sys
try:
    import yaml
except ImportError:
    print('PyYAML missing — skip (manual review only)'); sys.exit(0)
yaml.safe_load(open('.github/workflows/ci.yml'))
print('yaml OK')
EOF
```

Expected: `yaml OK` 或 `PyYAML missing`(环境缺库时的降级说明;此时人工复查缩进即可)。

- [ ] **Step 3: 提交**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: quality gate workflow (compile + lint + ktlint) on push/PR"
```

---

### Task 5: 文档(README + AGENTS.md)

**Files:**
- Modify: `README.md`(Build 节)
- Create: `AGENTS.md`(仓库根,最小化)

- [ ] **Step 1: README `Build` 节追加门禁说明**

在 README 的 `## Build` 节末尾(现有 release 构建说明之后)追加:

```markdown
### Quality gate (CI)

Every push to `main` and every PR runs `.github/workflows/ci.yml`:
`./gradlew assembleDebug lintDebug ktlintCheck` — compile, Android lint
(`abortOnError`, debug variant) and ktlint (`--android` ruleset) must all
pass or the change is blocked. ktlint runs from Maven Central
(`com.pinterest.ktlint:ktlint-cli`), not the plugin portal, so it works in
CN networks too; auto-format with `./gradlew ktlintFormat` before committing.
```

- [ ] **Step 2: 创建仓库根 `AGENTS.md`**

写入(注意:创建/编辑 AGENTS.md 前先加载 `superpowers:writing-for-agents` 技能并遵循其规范):

```markdown
# AGENTS.md

## Build & quality commands

- Build: `./gradlew assembleDebug` (requires JDK 17+ and Android SDK; the
  runtime snapshot `app/src/main/assets/snapshot.tar.xz` must exist)
- After changing Kotlin code, run the CI quality gate locally:
  `./gradlew ktlintCheck` (style) — auto-fix with `./gradlew ktlintFormat`;
  `./gradlew lintDebug` (Android lint errors block)
- Do not run `assembleRelease`/lint release tasks locally — release is CI-only
  (signing key lives in the repo secret)
```

- [ ] **Step 3: 提交**

```bash
git add README.md AGENTS.md
git commit -m "docs: quality gate commands in README + AGENTS.md"
```

---

### Task 6: 收尾——本地可验证项复检 + 交付说明

- [ ] **Step 1: 复检最终状态**

```bash
cd /root/projects/dsh-apk
git log --oneline -6
git status --short
java -jar /tmp/opencode/ktlint/ktlint-cli.jar --android --reporter=plain . | wc -l
```

Expected: 5 个新提交(Task 1-5 各一个);working tree clean;ktlint 0 违规。

- [ ] **Step 2: 交付说明(写给用户,输出在对话里,不写文件)**

明确告知:
1. 本机已验证的部分:ktlint 全库零违规(JDK 21 + 同一 jar)、.editorconfig、ci.yml YAML 语法
2. **本机无法验证、需要用户 push 到 GitHub 后由 CI 首次运行验证的部分**:gradle 任务包装(ktlintCheck/ktlintFormat 能否解析)、`lintDebug` 是否暴露存量 lint error(若有,CI 日志会给出,修复或基线后再合)、`assembleDebug` 全链路
3. push 后把 CI 结果贴回来,若有红,继续修
