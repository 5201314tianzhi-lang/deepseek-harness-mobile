# Boot UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rework the boot guide + cold-start top bar into a restrained Linear/Vercel-style visual system with dark mode, a persistent failure bar, an in-app log viewer (copy/share), and a version line — keeping every existing public API contract and callback.

**Architecture:** Pure presentation rework. `GuideWizard` keeps its public API (plus `showTopBar(state: BarState)` and `openLogPanel()`), but its view tree is rebuilt: page glow + brand block + vertical step cards + engine status card + 2×2 action grid + version line inside a `ScrollView`. The thin top bar becomes a floating pill with three states (`STARTING`/`FAILED`/`SUCCESS`); `FAILED` persists. Testable logic is extracted into `StepModel`, `GuidePalette`, `SnapshotVersion`, `VersionLine`, `LogPanelText` with JUnit/Robolectric tests. All colors resolve through `values/colors.xml` + new `values-night/colors.xml` (zero branching); `MainActivity.applyTheme()` follows system dark mode for the window bars.

**Tech Stack:** Kotlin, platform views only (no XML layouts), `GradientDrawable` programmatic drawing, `PathInterpolator(0.16, 1, 0.3, 1)` motion, `androidx.activity:activity-ktx` (existing), JUnit4 + Robolectric + mockk (existing test stack).

## Global Constraints

- 零新依赖（无 appcompat）；零新资产（无字体/图标文件）— 字体用平台 Roboto/Noto，`sans-serif-medium` 平台字体族
- 纯代码构建延续（无 XML 布局）
- 浅色板:底 `#FAFAFC` 卡片 `#FFFFFF` 内嵌 `#F2F4F8` 描边 `#E7EAF0` 主文 `#171A21` 次文 `#6E7684` 渐变 `#4D6BFE → #8B5CF6` 成功 `#17A26A` 错误 `#D64545`
- 深色板:底 `#0E1116` 卡片 `#161B22` 内嵌 `#1C222C` 描边 `#272E3A` 主文 `#ECEEF3` 次文 `#98A1B0` 渐变 `#5B7CFF → #9D6BFF` 成功 `#2FBF85` 错误 `#E85D5D`
- 渐变仅三处:品牌徽标、主按钮、页面顶部光晕(径向,约 14% 透明度);状态一律纯色
- 卡片:外壳 1dp 发丝描边 + 16dp 圆角;内芯内嵌面 + 12dp 圆角(复用 `card_bg`/`inset_bg` drawable)
- 动效仅 transform/alpha,统一 `PathInterpolator(0.16, 1, 0.3, 1)`;入场错落(淡入+上移 12dp,stagger 80ms,~400ms);`ViewPropertyAnimator` 自动遵循系统动画缩放;无无限循环动效(仅状态点呼吸)
- 失败态细条常驻不自动隐藏(I-26);成功态 6s 淡出(保留现有行为)
- 字体层级:标题 22sp Bold / 卡片标题 15sp Medium(`sans-serif-medium`)/ 次级 13sp / 日志与版本 12sp Monospace
- 保留全部现有回调与 `showGuideStatus/showGuideError/showLaunchReady/showWeb/showGuide/showGuideFromTopBar/hideTopBar/renderSteps/showKeepAlivePanel/updateKeepAliveStatus/hideKeepAlivePanel/onDestroy`
- 版本行 dsh 版本:读快照内 `usr/lib/node_modules/@deepseek-ai/dsh/package.json` 并缓存(不影响引导速度)
- 质量门禁:CI 跑 `./gradlew assembleDebug lintDebug ktlintCheck testDebugUnitTest` + `./tests/run-local.sh`;本地无 JDK/SDK,只能跑 `./tests/run-local.sh`
- 工作树当前有未提交的 exec-hook bugfix(`git status` 显示 `app/src/main/cpp/exec-hook.c`、`tests/run-local.sh`、`tests/c/bash-fix-test.c`)— 先单独提交它,UI 提交不得混入
- 提交风格参照仓库:`fix:` / `feat:` / `style:` / `docs:` 前缀 + 长描述

---

### Task 1: Palette resources + strings + GuidePalette

**Files:**
- Modify: `app/src/main/res/values/colors.xml` (update values + add `accent_end`)
- Create: `app/src/main/res/values-night/colors.xml`
- Create: `app/src/main/java/com/dshmobile/shell/GuidePalette.kt`
- Create: `app/src/test/java/com/dshmobile/shell/GuidePaletteTest.kt`
- Modify: `app/src/main/res/values/strings.xml` (append new strings)
- Modify: `app/src/main/res/values-zh/strings.xml` (append zh translations)

**Interfaces:**
- Produces: `class GuidePalette(context: Context)` with `val dark: Boolean` and resolved `Int` colors: `background, card, inset, hairline, textPrimary, textSecondary, accent, accentEnd, success, error, accentDim, errorDim`; plus `fun colors()` helper used by tasks 5-7. All later tasks construct `GuidePalette(activity)`.
- Produces string resources consumed by tasks 4-9: `bar_failed, bar_success, button_reload, button_view_log, log_panel_title, button_copy_panel, button_share, button_close, log_share_title, step_status_done, step_status_active, step_status_pending`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/dshmobile/shell/GuidePaletteTest.kt`:

```kotlin
package com.dshmobile.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuidePaletteTest {
  @Test
  fun lightThemeByDefault() {
    val p = GuidePalette(RuntimeEnvironment.getApplication())
    assertFalse(p.dark)
    assertEquals(0xFFFAFAFC.toInt(), p.background)
    assertEquals(0xFF171A21.toInt(), p.textPrimary)
    assertEquals(0xFF4D6BFE.toInt(), p.accent)
    assertEquals(0xFF8B5CF6.toInt(), p.accentEnd)
    assertEquals(0xFF17A26A.toInt(), p.success)
    assertEquals(0xFFD64545.toInt(), p.error)
  }

  @Test
  @Config(qualifiers = "night")
  fun darkThemeFollowsConfiguration() {
    val p = GuidePalette(RuntimeEnvironment.getApplication())
    assertTrue(p.dark)
    assertEquals(0xFF0E1116.toInt(), p.background)
    assertEquals(0xFF161B22.toInt(), p.card)
    assertEquals(0xFFECEEF3.toInt(), p.textPrimary)
    assertEquals(0xFF5B7CFF.toInt(), p.accent)
    assertEquals(0xFF9D6BFF.toInt(), p.accentEnd)
    assertEquals(0xFF2FBF85.toInt(), p.success)
    assertEquals(0xFFE85D5D.toInt(), p.error)
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.dshmobile.shell.GuidePaletteTest"` (CI only — local has no JDK/SDK; run in CI or on a machine with the toolchain)
Expected: FAIL — `GuidePalette` unresolved, colors not yet present.

- [ ] **Step 3: Update the light palette**

Replace the whole content of `app/src/main/res/values/colors.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
  <color name="ic_launcher_background">#ffffff</color>
  <color name="bg_guide">#FAFAFC</color>
  <color name="surface_card">#FFFFFF</color>
  <color name="surface_inset">#F2F4F8</color>
  <color name="line_border">#E7EAF0</color>
  <color name="text_primary">#171A21</color>
  <color name="text_secondary">#6E7684</color>
  <color name="accent">#4D6BFE</color>
  <color name="accent_end">#8B5CF6</color>
  <color name="accent_dim">#E8EDFF</color>
  <color name="success">#17A26A</color>
  <color name="error">#D64545</color>
  <color name="error_dim">#FDEEEE</color>
</resources>
```

- [ ] **Step 4: Create the dark palette**

`app/src/main/res/values-night/colors.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
  <color name="ic_launcher_background">#0E1116</color>
  <color name="bg_guide">#0E1116</color>
  <color name="surface_card">#161B22</color>
  <color name="surface_inset">#1C222C</color>
  <color name="line_border">#272E3A</color>
  <color name="text_primary">#ECEEF3</color>
  <color name="text_secondary">#98A1B0</color>
  <color name="accent">#5B7CFF</color>
  <color name="accent_end">#9D6BFF</color>
  <color name="accent_dim">#26304F</color>
  <color name="success">#2FBF85</color>
  <color name="error">#E85D5D</color>
  <color name="error_dim">#332225</color>
</resources>
```

- [ ] **Step 5: Implement GuidePalette**

`app/src/main/java/com/dshmobile/shell/GuidePalette.kt`:

```kotlin
package com.dshmobile.shell

import android.content.Context

/**
 * Resolved boot-guide palette. Light/dark values live in values/colors.xml
 * and values-night/colors.xml under the same names, so every color follows
 * the system theme through the configuration qualifiers — zero branching.
 */
class GuidePalette(context: Context) {
  val dark: Boolean =
    (context.resources.configuration.uiMode and
      android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
      android.content.res.Configuration.UI_MODE_NIGHT_YES

  val background: Int = context.getColor(R.color.bg_guide)
  val card: Int = context.getColor(R.color.surface_card)
  val inset: Int = context.getColor(R.color.surface_inset)
  val hairline: Int = context.getColor(R.color.line_border)
  val textPrimary: Int = context.getColor(R.color.text_primary)
  val textSecondary: Int = context.getColor(R.color.text_secondary)
  val accent: Int = context.getColor(R.color.accent)
  val accentEnd: Int = context.getColor(R.color.accent_end)
  val accentDim: Int = context.getColor(R.color.accent_dim)
  val success: Int = context.getColor(R.color.success)
  val error: Int = context.getColor(R.color.error)
  val errorDim: Int = context.getColor(R.color.error_dim)
}
```

- [ ] **Step 6: Add the new strings (English)**

Append to `app/src/main/res/values/strings.xml` (before the closing `</resources>`):

```xml
  <!-- Top bar (floating pill) -->
  <string name="bar_failed">Connection failed · tap for details</string>
  <string name="bar_success">Engine ready</string>

  <!-- Guide actions -->
  <string name="button_reload">Reload</string>
  <string name="button_view_log">View log</string>

  <!-- Step cards -->
  <string name="step_status_done">Done</string>
  <string name="step_status_active">In progress…</string>
  <string name="step_status_pending">Pending</string>

  <!-- Log viewer panel -->
  <string name="log_panel_title">Diagnostic log</string>
  <string name="button_copy_panel">Copy</string>
  <string name="button_share">Share</string>
  <string name="button_close">Close</string>
  <string name="log_share_title">Share diagnostic log</string>
```

- [ ] **Step 7: Add the zh translations**

Append to `app/src/main/res/values-zh/strings.xml` (before the closing `</resources>`):

```xml
  <!-- Top bar (floating pill) -->
  <string name="bar_failed">连接失败 · 点按查看</string>
  <string name="bar_success">引擎已就绪</string>

  <!-- Guide actions -->
  <string name="button_reload">重新加载</string>
  <string name="button_view_log">查看日志</string>

  <!-- Step cards -->
  <string name="step_status_done">完成</string>
  <string name="step_status_active">进行中…</string>
  <string name="step_status_pending">待执行</string>

  <!-- Log viewer panel -->
  <string name="log_panel_title">诊断日志</string>
  <string name="button_copy_panel">复制</string>
  <string name="button_share">分享</string>
  <string name="button_close">关闭</string>
  <string name="log_share_title">分享诊断日志</string>
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.dshmobile.shell.GuidePaletteTest"`
Expected: PASS (2 tests)

- [ ] **Step 9: Commit**

```bash
git add app/src/main/res/values/colors.xml app/src/main/res/values-night/colors.xml \
  app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml \
  app/src/main/java/com/dshmobile/shell/GuidePalette.kt \
  app/src/test/java/com/dshmobile/shell/GuidePaletteTest.kt
git commit -m "feat(ui): palette resources — light/dark colors + GuidePalette resolution with tests"
```

---

### Task 2: StepModel step state machine

**Files:**
- Create: `app/src/main/java/com/dshmobile/shell/StepState.kt`
- Create: `app/src/test/java/com/dshmobile/shell/StepModelTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `enum class StepState { PENDING, ACTIVE, DONE }` and `class StepModel(done: Int, active: Int)` with `fun state(index: Int): StepState` — Task 6's `renderSteps` uses it.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/dshmobile/shell/StepModelTest.kt`:

```kotlin
package com.dshmobile.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class StepModelTest {
  @Test
  fun allPendingAtStart() {
    val m = StepModel(0, 0)
    assertEquals(StepState.PENDING, m.state(0))
    assertEquals(StepState.PENDING, m.state(1))
    assertEquals(StepState.PENDING, m.state(2))
  }

  @Test
  fun firstDoneSecondActive() {
    val m = StepModel(1, 1)
    assertEquals(StepState.DONE, m.state(0))
    assertEquals(StepState.ACTIVE, m.state(1))
    assertEquals(StepState.PENDING, m.state(2))
  }

  @Test
  fun allDone() {
    val m = StepModel(3, 3)
    assertEquals(StepState.DONE, m.state(0))
    assertEquals(StepState.DONE, m.state(1))
    assertEquals(StepState.DONE, m.state(2))
  }

  @Test
  fun activeCannotPrecedeDone() {
    val m = StepModel(2, 2)
    assertEquals(StepState.DONE, m.state(1))
    assertEquals(StepState.ACTIVE, m.state(2))
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.dshmobile.shell.StepModelTest"`
Expected: FAIL — `StepModel` unresolved.

- [ ] **Step 3: Implement StepState + StepModel**

`app/src/main/java/com/dshmobile/shell/StepState.kt`:

```kotlin
package com.dshmobile.shell

/** Render state of a boot-guide step card. */
enum class StepState { PENDING, ACTIVE, DONE }

/**
 * Maps the guide's (done, active) counters onto per-step states: steps
 * before [done] are done, the step at [active] is in progress, the rest
 * are pending. Pure logic — UI rendering lives in GuideWizard.renderSteps.
 */
class StepModel(
  private val done: Int,
  private val active: Int,
) {
  fun state(index: Int): StepState =
    when {
      index < done -> StepState.DONE
      index == active -> StepState.ACTIVE
      else -> StepState.PENDING
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.dshmobile.shell.StepModelTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/dshmobile/shell/StepState.kt \
  app/src/test/java/com/dshmobile/shell/StepModelTest.kt
git commit -m "feat(ui): StepModel step state machine — (done, active) to per-step state mapping with tests"
```

---

### Task 3: Version line — SnapshotVersion + VersionLine

**Files:**
- Create: `app/src/main/java/com/dshmobile/shell/SnapshotVersion.kt`
- Create: `app/src/main/java/com/dshmobile/shell/VersionLine.kt`
- Create: `app/src/test/java/com/dshmobile/shell/SnapshotVersionTest.kt`
- Create: `app/src/test/java/com/dshmobile/shell/VersionLineTest.kt`

**Interfaces:**
- Consumes: `DshPaths.USR_DIR` (existing)
- Produces: `object SnapshotVersion` with `fun read(context: Context): String` (cached; `"?"` when unreadable) and `fun clearCache()` (test hook); `object VersionLine` with `fun format(appVersion: String, abi: String, dshVersion: String): String` — Task 5 builds the guide version row, Task 9 shares logs with it.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/dshmobile/shell/VersionLineTest.kt`:

```kotlin
package com.dshmobile.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionLineTest {
  @Test
  fun formatAssemblesAllParts() {
    assertEquals(
      "v0.1.5 · arm64-v8a · dsh 0.1.0-rc.6",
      VersionLine.format("0.1.5", "arm64-v8a", "0.1.0-rc.6"),
    )
  }
}
```

`app/src/test/java/com/dshmobile/shell/SnapshotVersionTest.kt`:

```kotlin
package com.dshmobile.shell

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnapshotVersionTest {
  private val context get() = RuntimeEnvironment.getApplication()

  private fun snapshotPackageJson(): File =
    File(context.filesDir, DshPaths.USR_DIR + "/lib/node_modules/@deepseek-ai/dsh/package.json")

  @Test
  fun readsVersionFromSnapshotPackageJson() {
    snapshotPackageJson().apply {
      parentFile.mkdirs()
      writeText("{\"name\":\"@deepseek-ai/dsh\",\"version\":\"0.1.0-rc.6\"}")
    }
    SnapshotVersion.clearCache()
    assertEquals("0.1.0-rc.6", SnapshotVersion.read(context))
  }

  @Test
  fun missingSnapshotYieldsQuestionMark() {
    SnapshotVersion.clearCache()
    assertEquals("?", SnapshotVersion.read(context))
  }

  @Test
  fun cachedValueNotReRead() {
    snapshotPackageJson().apply {
      parentFile.mkdirs()
      writeText("{\"version\":\"1.2.3\"}")
    }
    SnapshotVersion.clearCache()
    assertEquals("1.2.3", SnapshotVersion.read(context))
    snapshotPackageJson().delete()
    assertEquals("1.2.3", SnapshotVersion.read(context))
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.dshmobile.shell.VersionLineTest" --tests "com.dshmobile.shell.SnapshotVersionTest"`
Expected: FAIL — classes unresolved.

- [ ] **Step 3: Implement VersionLine**

`app/src/main/java/com/dshmobile/shell/VersionLine.kt`:

```kotlin
package com.dshmobile.shell

/** Bottom-of-guide version line: app version + ABI + snapshot dsh version. */
object VersionLine {
  fun format(
    appVersion: String,
    abi: String,
    dshVersion: String,
  ): String = "v" + appVersion + " · " + abi + " · dsh " + dshVersion
}
```

- [ ] **Step 4: Implement SnapshotVersion**

`app/src/main/java/com/dshmobile/shell/SnapshotVersion.kt`:

```kotlin
package com.dshmobile.shell

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * Version of the @deepseek-ai/dsh package inside the runtime snapshot,
 * read once and cached — the guide rebuilds must never re-read the
 * filesystem (boot speed), and the value is stable for the app's lifetime.
 */
object SnapshotVersion {
  @Volatile
  private var cached: String? = null

  fun read(context: Context): String {
    cached?.let { return it }
    val file =
      File(
        context.filesDir,
        DshPaths.USR_DIR + "/lib/node_modules/@deepseek-ai/dsh/package.json",
      )
    val version =
      try {
        JSONObject(file.readText()).optString("version", "")
      } catch (_: Throwable) {
        ""
      }
    cached = version.ifEmpty { "?" }
    return cached!!
  }

  /** Test hook: drop the cached value so the next read hits the filesystem. */
  fun clearCache() {
    cached = null
  }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.dshmobile.shell.VersionLineTest" --tests "com.dshmobile.shell.SnapshotVersionTest"`
Expected: PASS (4 tests)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/dshmobile/shell/SnapshotVersion.kt \
  app/src/main/java/com/dshmobile/shell/VersionLine.kt \
  app/src/test/java/com/dshmobile/shell/SnapshotVersionTest.kt \
  app/src/test/java/com/dshmobile/shell/VersionLineTest.kt
git commit -m "feat(ui): version line — snapshot dsh version (cached read) + app/ABI/dsh format with tests"
```

---

### Task 4: Floating pill top bar with BarState

**Files:**
- Modify: `app/src/main/java/com/dshmobile/shell/GuideWizard.kt` (add `BarState`, `INTERPOLATOR`, rework `buildTopStatusBar`, replace `showTopBar(title)` with `showTopBar(state)`, keep `hideTopBar`/`showGuideFromTopBar`/`showGuide`; `showWeb` consults `currentBarState`)

**Interfaces:**
- Consumes: `GuidePalette` (Task 1), string `bar_failed`, `bar_success`, existing `status_engine_starting`
- Produces: `enum class BarState { STARTING, FAILED, SUCCESS }` at the top of `GuideWizard.kt`; `fun showTopBar(state: BarState)` (slide-in translateY -32dp→0, dot tinted accent/error/success, STARTING breathes, FAILED persists, SUCCESS auto-hides after 6s); `showWeb()` keeps the failure bar visible; Task 10 consumes `BarState` and the new `showTopBar` signature.
- Contract change: callers of the old `showTopBar(title: String)` are only in `MainActivity` — updated in Task 10, so the app does not compile until then.

- [ ] **Step 1: Add BarState + interpolator constant**

In `GuideWizard.kt`, before the `class GuideWizard` declaration:

```kotlin
/** Cold-start bar states: STARTING breathes, FAILED persists (I-26: a failed
 *  boot must always leave an exit), SUCCESS fades away after a delay. */
enum class BarState { STARTING, FAILED, SUCCESS }
```

In the class body, next to the `d` property:

```kotlin
private val INTERPOLATOR = android.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)
```

- [ ] **Step 2: Add the state field**

Next to the other fields:

```kotlin
private var currentBarState: BarState? = null
```

- [ ] **Step 3: Replace showTopBar(title) with showTopBar(state)**

Replace the whole old function:

```kotlin
  /** Slide the thin status bar in (cold start over the Harness). */
  fun showTopBar(title: String) {
    cancelScheduledTopBarHide()
    guideView.visibility = View.GONE
    webView.visibility = View.VISIBLE
    webView
      .animate()
      .alpha(1f)
      .setDuration(150)
      .start()
    topStatusLabel?.text = title
    topStatusBar.visibility = View.VISIBLE
    topStatusBar
      .animate()
      .alpha(1f)
      .setDuration(200)
      .start()
    startTopBarPulse()
  }
```

with:

```kotlin
  /** Show the cold-start pill: STARTING breathes, FAILED persists (I-26),
   *  SUCCESS fades after 6s. Slide-in from -32dp. */
  fun showTopBar(state: BarState) {
    cancelScheduledTopBarHide()
    currentBarState = state
    guideView.visibility = View.GONE
    webView.visibility = View.VISIBLE
    webView
      .animate()
      .alpha(1f)
      .setDuration(150)
      .start()
    val palette = GuidePalette(activity)
    val dotColor =
      when (state) {
        BarState.STARTING -> palette.accent
        BarState.FAILED -> palette.error
        BarState.SUCCESS -> palette.success
      }
    topPulseDot?.backgroundTintList = android.content.res.ColorStateList.valueOf(dotColor)
    topStatusLabel?.text =
      when (state) {
        BarState.STARTING -> activity.getString(R.string.status_engine_starting)
        BarState.FAILED -> activity.getString(R.string.bar_failed)
        BarState.SUCCESS -> activity.getString(R.string.bar_success)
      }
    topStatusBar.visibility = View.VISIBLE
    topStatusBar.alpha = 0f
    topStatusBar.translationY = (-32 * d)
    topStatusBar
      .animate()
      .alpha(1f)
      .translationY(0f)
      .setDuration(250)
      .setInterpolator(INTERPOLATOR)
      .start()
    if (state == BarState.STARTING) startTopBarPulse() else stopTopBarPulse()
    if (state == BarState.SUCCESS) scheduleTopBarHide(6000L)
  }
```

- [ ] **Step 4: Make showWeb respect the failure bar**

Replace the tail of `showWeb()`:

```kotlin
    // NOTE: no reload here — MainActivity reloads only when the page had
    // failed to load; a blanket reload on every show would discard the page
    // state (and race picker callbacks) on each return to foreground.
    // Engine is up — keep the breathing dot visible a few seconds longer
    // (cold-start transition) before fading the bar away, so the pulse
    // animation is actually seen instead of vanishing immediately.
    scheduleTopBarHide(6000L)
```

with:

```kotlin
    // NOTE: no reload here — MainActivity reloads only when the page had
    // failed to load; a blanket reload on every show would discard the page
    // state (and race picker callbacks) on each return to foreground.
    // SUCCESS keeps the 6s fade (pulse visible during the cold-start
    // transition); FAILED must persist — a failed boot always leaves an
    // exit (I-26). When no bar was shown, nothing to hide.
    val state = currentBarState
    if (state != null && state != BarState.FAILED) scheduleTopBarHide(6000L)
  }
```

- [ ] **Step 5: Rework buildTopStatusBar into the floating pill**

Replace the whole old `buildTopStatusBar` function:

```kotlin
  /** Thin cold-start bar overlaying the Harness: pulse dot + status, taps to
   *  open the full-screen guide. */
  private lateinit var topStatusLabel: TextView

  private fun buildTopStatusBar(): LinearLayout {
    val dot =
      View(activity).apply {
        background = activity.getDrawable(com.dshmobile.shell.R.drawable.status_dot)
        val size = (8 * d).toInt()
        layoutParams = LinearLayout.LayoutParams(size, size)
      }
    topPulseDot = dot
    val label =
      TextView(activity).apply {
        setTextColor(activity.resources.getColor(com.dshmobile.shell.R.color.text_primary, null))
        textSize = 13f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding((10 * d).toInt(), 0, 0, 0)
      }
    topStatusLabel = label
    val bar =
      LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding((20 * d).toInt(), (10 * d).toInt(), (20 * d).toInt(), (10 * d).toInt())
        setBackgroundColor(0xE6FFFFFF.toInt())
        visibility = View.GONE
        setOnClickListener { showGuideFromTopBar() }
      }
    bar.addView(dot)
    bar.addView(label)
    bar.elevation = (6 * d)
    return bar
  }
```

with:

```kotlin
  /** Floating cold-start pill overlaying the Harness: tinted pulse dot +
   *  status + trailing chevron; taps open the full-screen guide. */
  private lateinit var topStatusLabel: TextView

  private fun buildTopStatusBar(): LinearLayout {
    val palette = GuidePalette(activity)
    val dot =
      View(activity).apply {
        background = activity.getDrawable(com.dshmobile.shell.R.drawable.status_dot)
        val size = (8 * d).toInt()
        layoutParams = LinearLayout.LayoutParams(size, size)
      }
    topPulseDot = dot
    val label =
      TextView(activity).apply {
        setTextColor(palette.textPrimary)
        textSize = 13f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding((10 * d).toInt(), 0, (6 * d).toInt(), 0)
      }
    topStatusLabel = label
    val chevron =
      TextView(activity).apply {
        text = "›"
        setTextColor(palette.textSecondary)
        textSize = 16f
      }
    val bar =
      LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding((16 * d).toInt(), (9 * d).toInt(), (14 * d).toInt(), (9 * d).toInt())
        background =
          android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = (22 * d)
            setColor(palette.card)
            alpha = 230
            setStroke((1 * d).toInt(), palette.hairline)
          }
        visibility = View.GONE
        setOnClickListener { showGuideFromTopBar() }
      }
    bar.addView(dot)
    bar.addView(label)
    bar.addView(chevron)
    bar.elevation = (6 * d)
    return bar
  }
```

- [ ] **Step 6: Self-review against the spec**

Check: pill = 圆角 22dp、半透明底+发丝描边、呼吸点+状态文字+尾部 `›`、滑入 translateY -32→0、三态着色。`showGuideFromTopBar`/`hideTopBar`/`startTopBarPulse`/`stopTopBarPulse`/`scheduleTopBarHide` unchanged.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/dshmobile/shell/GuideWizard.kt
git commit -m "feat(ui): floating pill top bar — BarState (STARTING/FAILED/SUCCESS), slide-in, persistent failure state (I-26)"
```

---

### Task 5: Guide container — scroll view, glow, brand block, version line, entry stagger

**Files:**
- Modify: `app/src/main/java/com/dshmobile/shell/GuideWizard.kt` (fields, `buildGuideView` → ScrollView root, `buildBrandBlock`, `glowDrawable`, `buildVersionLine`, `animateGuideEntry`, updated `showGuide`; remove `spacer`)

**Interfaces:**
- Consumes: `GuidePalette` (Task 1), `VersionLine.format` + `SnapshotVersion.read` (Task 3), strings `app_name`, `guide_brand_subtitle` (existing)
- Produces: `val guideView: ScrollView` (was `LinearLayout` — MainActivity only sets `visibility` + layout params, both unchanged), internal `guideContent: LinearLayout?`, `buildStepCards()` (implemented in Task 6), `buildStatusCard()` (Task 7), `buildActionArea()` (Task 7), `buildKeepAliveCard()` (Task 8), `animateGuideEntry()`. The constructor calls `buildGuideView()` which references the later-task builders — the file does not compile until Task 8.

- [ ] **Step 1: Update the field list**

Replace the fields block:

```kotlin
  private var engineStatus: TextView? = null
  private var statusDetail: TextView? = null
  private var progressBar: android.widget.ProgressBar? = null
  private var primaryButton: Button? = null
  private var backButton: Button? = null
  private var errorBlock: LinearLayout? = null
  private var errorText: TextView? = null
  private var statusCard: LinearLayout? = null
  private var actionRow: LinearLayout? = null
  private var keepAliveBlock: LinearLayout? = null
  private var keepAliveText: TextView? = null
  private var spacer: View? = null
  private var keepAliveBattery: Button? = null
  private var keepAliveShizuku: Button? = null
  private var stepDots: Array<TextView> = emptyArray()
  private var stepLabels: Array<TextView> = emptyArray()
  private var topPulseDot: View? = null
  private var topPulseAnimator: android.animation.ValueAnimator? = null
```

with:

```kotlin
  private var engineStatus: TextView? = null
  private var statusDetail: TextView? = null
  private var progressBar: android.widget.ProgressBar? = null
  private var primaryButton: LinearLayout? = null
  private var primaryLabel: TextView? = null
  private var backButton: Button? = null
  private var errorBlock: LinearLayout? = null
  private var errorText: TextView? = null
  private var statusCard: LinearLayout? = null
  private var actionRow: LinearLayout? = null
  private var keepAliveBlock: LinearLayout? = null
  private var keepAliveText: TextView? = null
  private var keepAliveBattery: Button? = null
  private var keepAliveShizuku: Button? = null
  private var guideContent: LinearLayout? = null
  private var stepCircles: Array<TextView> = emptyArray()
  private var stepGlyphs: Array<TextView> = emptyArray()
  private var stepStatusTexts: Array<TextView> = emptyArray()
  private var stepCards: Array<LinearLayout> = emptyArray()
  private var stepActiveGlyph: View? = null
  private var stepPulseAnimator: android.animation.ValueAnimator? = null
  private var firstStepRender = true
  private var prevDone = 0
  private var topPulseDot: View? = null
  private var topPulseAnimator: android.animation.ValueAnimator? = null
```

- [ ] **Step 2: Rework buildGuideView**

Replace the whole old `buildGuideView` function (from `private fun buildGuideView(): LinearLayout {` through its closing brace before `private fun buildKeepAliveCard`) with:

```kotlin
  private fun buildGuideView(): ScrollView {
    val pad = (24 * d).toInt()
    val palette = GuidePalette(activity)
    val content =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, 0, pad, pad)
      }
    guideContent = content

    // Page glow: radial brand-gradient wash behind the brand block (~14%
    // opacity), the third and last allowed gradient after logo and primary.
    val glow =
      View(activity).apply {
        background = glowDrawable(palette)
        layoutParams =
          LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (220 * d).toInt(),
          )
      }
    content.addView(glow)
    content.addView(buildBrandBlock())
    content.addView(buildStepCards())
    content.addView(buildStatusCard())
    // Keep-alive panel stays hidden until requested.
    keepAliveBlock = buildKeepAliveCard().also { it.visibility = View.GONE }
    content.addView(keepAliveBlock)
    content.addView(buildActionArea())
    content.addView(buildVersionLine())

    return ScrollView(activity).apply {
      isFillViewport = true
      visibility = View.GONE
      setBackgroundColor(palette.background)
      addView(content)
    }
  }

  /** Radial glow behind the brand block: the accent at ~14% alpha. */
  private fun glowDrawable(palette: GuidePalette): android.graphics.drawable.GradientDrawable {
    val center = palette.accent and 0x00FFFFFF.toInt() or (0x24 shl 24)
    return android.graphics.drawable.GradientDrawable().apply {
      shape = android.graphics.drawable.GradientDrawable.RECTANGLE
      gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
      colors = intArrayOf(center, palette.background)
      gradientRadius = (400 * d)
    }
  }

  /** Brand block: programmatic gradient logo + app name + subtitle. */
  private fun buildBrandBlock(): LinearLayout {
    val palette = GuidePalette(activity)
    val logo =
      TextView(activity).apply {
        text = "D"
        textSize = 22f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = android.view.Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        background =
          android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            colors = intArrayOf(palette.accent, palette.accentEnd)
            gradientType = android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT
            gradientAngle = 135
          }
        val size = (52 * d).toInt()
        layoutParams = LinearLayout.LayoutParams(size, size)
      }
    val brandTitle =
      TextView(activity).apply {
        text = activity.getString(R.string.app_name)
        setTextColor(palette.textPrimary)
        textSize = 22f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, (16 * d).toInt(), 0, 0)
      }
    val brandSub =
      TextView(activity).apply {
        text = activity.getString(R.string.guide_brand_subtitle)
        setTextColor(palette.textSecondary)
        textSize = 13f
        setPadding(0, (4 * d).toInt(), 0, 0)
      }
    return LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      gravity = android.view.Gravity.CENTER_HORIZONTAL
      setPadding(0, (16 * d).toInt(), 0, 0)
      addView(logo)
      addView(brandTitle)
      addView(brandSub)
    }
  }

  /** Bottom version row: app version · ABI · snapshot dsh version. */
  private fun buildVersionLine(): TextView {
    val palette = GuidePalette(activity)
    val appVersion =
      try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"
      } catch (_: Throwable) {
        "?"
      }
    val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
    val line = VersionLine.format(appVersion, abi, SnapshotVersion.read(activity))
    return TextView(activity).apply {
      text = line
      setTextColor(palette.textSecondary)
      textSize = 12f
      typeface = android.graphics.Typeface.MONOSPACE
      gravity = android.view.Gravity.CENTER_HORIZONTAL
      layoutParams =
        LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
          topMargin = (24 * d).toInt()
        }
    }
  }
```

- [ ] **Step 3: Update showGuide with the entry stagger**

Replace the old `showGuide()`:

```kotlin
  /** Cross-fade between the guide surface and the Harness web view. */
  fun showGuide() {
    cancelScheduledTopBarHide()
    webView
      .animate()
      .alpha(0f)
      .setDuration(150)
      .start()
    webView.visibility = View.GONE
    stopTopBarPulse()
    topStatusBar.visibility = View.GONE
    guideView.visibility = View.VISIBLE
    guideView
      .animate()
      .alpha(1f)
      .setDuration(200)
      .start()
  }
```

with:

```kotlin
  /** Cross-fade between the guide surface and the Harness web view. */
  fun showGuide() {
    cancelScheduledTopBarHide()
    webView
      .animate()
      .alpha(0f)
      .setDuration(150)
      .start()
    webView.visibility = View.GONE
    stopTopBarPulse()
    stopStepPulse()
    topStatusBar.visibility = View.GONE
    guideView.visibility = View.VISIBLE
    guideView.alpha = 1f
    animateGuideEntry()
  }

  /** Staggered entry: children fade in + rise 12dp, 80ms apart. */
  private fun animateGuideEntry() {
    val content = guideContent ?: return
    var delay = 0L
    for (i in 0 until content.childCount) {
      val child = content.getChildAt(i)
      child.alpha = 0f
      child.translationY = (12 * d)
      child
        .animate()
        .alpha(1f)
        .translationY(0f)
        .setStartDelay(delay)
        .setDuration(400)
        .setInterpolator(INTERPOLATOR)
        .start()
      delay += 80
    }
  }
```

- [ ] **Step 4: Update showWeb to stop the step pulse**

In the old `showWeb()`, right after `backButton?.visibility = View.GONE`, add `stopStepPulse()`. The function becomes:

```kotlin
  fun showWeb() {
    backButton?.visibility = View.GONE
    stopStepPulse()
    guideView
      .animate()
      .alpha(0f)
      .setDuration(150)
      .withEndAction {
        guideView.visibility = View.GONE
      }.start()
    webView.visibility = View.VISIBLE
    webView
      .animate()
      .alpha(1f)
      .setDuration(200)
      .start()
    // NOTE: no reload here — MainActivity reloads only when the page had
    // failed to load; a blanket reload on every show would discard the page
    // state (and race picker callbacks) on each return to foreground.
    // SUCCESS keeps the 6s fade (pulse visible during the cold-start
    // transition); FAILED must persist — a failed boot always leaves an
    // exit (I-26). When no bar was shown, nothing to hide.
    val state = currentBarState
    if (state != null && state != BarState.FAILED) scheduleTopBarHide(6000L)
  }
```

- [ ] **Step 5: Drop the spacer references in the keep-alive panel toggles**

In `showKeepAlivePanel()` remove the line `spacer?.visibility = View.GONE`; in `hideKeepAlivePanel()` remove `spacer?.visibility = View.VISIBLE`. The two functions keep their remaining lines.

- [ ] **Step 6: Self-review against the spec**

Check: ScrollView 包整体（防小屏溢出）、光晕 14% 径向、徽标渐变、标题 22sp、版本行 12sp 等宽、入场错落 80ms/400ms/PathInterpolator、只用 transform/alpha。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/dshmobile/shell/GuideWizard.kt
git commit -m "feat(ui): guide container — scroll layout, radial glow, gradient brand block, version line, staggered entry"
```

---

### Task 6: Step cards + renderSteps rework

**Files:**
- Modify: `app/src/main/java/com/dshmobile/shell/GuideWizard.kt` (replace `buildStepIndicator` with `buildStepCards`; rewrite `renderSteps`; add `startStepPulse`/`stopStepPulse`; update `onDestroy`)

**Interfaces:**
- Consumes: `StepModel`/`StepState` (Task 2), `GuidePalette` (Task 1), strings `step_runtime/step_container/step_launch` (existing) + `step_status_done/step_status_active/step_status_pending` (Task 1), `INTERPOLATOR`
- Produces: `fun renderSteps(done: Int, active: Int)` — same signature as before, now driving three card rows (numbered circle, label, status text, state glyph). Done cards animate in with a scale 0.9→1 pop; the active card's glyph breathes. Task 5's `buildGuideView` calls `buildStepCards()`.

- [ ] **Step 1: Replace buildStepIndicator with buildStepCards**

Delete the whole old `buildStepIndicator` function and replace it with:

```kotlin
  /** Vertical three-step card list (runtime → container → launch). Each row
   *  is a hairline shell card with an inset inner: numbered circle + title +
   *  status text + trailing state glyph (✓ done / breathing dot active). */
  private fun buildStepCards(): LinearLayout {
    val palette = GuidePalette(activity)
    val names =
      listOf(
        activity.getString(R.string.step_runtime),
        activity.getString(R.string.step_container),
        activity.getString(R.string.step_launch),
      )
    val circles = arrayOfNulls<TextView>(3)
    val glyphs = arrayOfNulls<TextView>(3)
    val statusTexts = arrayOfNulls<TextView>(3)
    val cards = arrayOfNulls<LinearLayout>(3)
    val list =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams =
          LinearLayout
            .LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
              topMargin = (32 * d).toInt()
            }
      }
    for (i in 0..2) {
      val circle =
        TextView(activity).apply {
          textSize = 12f
          typeface = android.graphics.Typeface.DEFAULT_BOLD
          gravity = android.view.Gravity.CENTER
          setTextColor(0xFFFFFFFF.toInt())
          val size = (24 * d).toInt()
          layoutParams = LinearLayout.LayoutParams(size, size)
        }
      circles[i] = circle
      val title =
        TextView(activity).apply {
          text = names[i]
          textSize = 15f
          typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
          setTextColor(palette.textPrimary)
          setPadding((12 * d).toInt(), 0, 0, 0)
        }
      val status =
        TextView(activity).apply {
          textSize = 13f
          setPadding((12 * d).toInt(), (2 * d).toInt(), 0, 0)
        }
      statusTexts[i] = status
      val titleColumn =
        LinearLayout(activity).apply {
          orientation = LinearLayout.VERTICAL
          layoutParams =
            LinearLayout
              .LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
              ).apply {
                weight = 1f
              }
        }
      titleColumn.addView(title)
      titleColumn.addView(status)
      val glyph =
        TextView(activity).apply {
          textSize = 11f
          typeface = android.graphics.Typeface.DEFAULT_BOLD
          gravity = android.view.Gravity.CENTER
          val size = (22 * d).toInt()
          layoutParams = LinearLayout.LayoutParams(size, size)
        }
      glyphs[i] = glyph
      val body =
        LinearLayout(activity).apply {
          orientation = LinearLayout.HORIZONTAL
          gravity = android.view.Gravity.CENTER_VERTICAL
          setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
          addView(circle)
          addView(titleColumn)
          addView(glyph)
        }
      val inset =
        LinearLayout(activity).apply {
          orientation = LinearLayout.VERTICAL
          background = activity.getDrawable(com.dshmobile.shell.R.drawable.inset_bg)
          addView(body)
        }
      val card =
        LinearLayout(activity).apply {
          orientation = LinearLayout.VERTICAL
          background = activity.getDrawable(com.dshmobile.shell.R.drawable.card_bg)
          setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
          layoutParams =
            LinearLayout
              .LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
              ).apply {
                bottomMargin = (12 * d).toInt()
              }
        }
      card.addView(inset)
      cards[i] = card
      list.addView(card)
    }
    stepCircles = circles.map { it!! }.toTypedArray()
    stepGlyphs = glyphs.map { it!! }.toTypedArray()
    stepStatusTexts = statusTexts.map { it!! }.toTypedArray()
    stepCards = cards.map { it!! }.toTypedArray()
    return list
  }
```

- [ ] **Step 2: Rewrite renderSteps**

Replace the whole old `renderSteps` function with:

```kotlin
  /** Render the step cards from the (done, active) counters: done rows show a
   *  green check (first appearance pops in at scale 0.9), the active row
   *  breathes, pending rows stay quiet. */
  fun renderSteps(
    done: Int,
    active: Int,
  ) {
    val model = StepModel(done, active)
    val palette = GuidePalette(activity)
    stopStepPulse()
    stepActiveGlyph = null
    for (i in stepCircles.indices) {
      val state = model.state(i)
      val circle = stepCircles[i]
      val glyph = stepGlyphs[i]
      val statusText = stepStatusTexts[i]
      val circleColor =
        when (state) {
          StepState.DONE -> palette.success
          StepState.ACTIVE -> palette.accent
          StepState.PENDING -> palette.hairline
        }
      circle.background =
        android.graphics.drawable.GradientDrawable().apply {
          shape = android.graphics.drawable.GradientDrawable.OVAL
          setColor(circleColor)
        }
      when (state) {
        StepState.DONE -> {
          circle.text = "✓"
          glyph.text = "✓"
          glyph.background = null
          glyph.setTextColor(palette.success)
          statusText.setTextColor(palette.textSecondary)
          statusText.text = activity.getString(R.string.step_status_done)
        }

        StepState.ACTIVE -> {
          circle.text = (i + 1).toString()
          glyph.text = ""
          glyph.background = null
          statusText.setTextColor(palette.accent)
          statusText.text = activity.getString(R.string.step_status_active)
          stepActiveGlyph = glyph
        }

        StepState.PENDING -> {
          circle.text = (i + 1).toString()
          glyph.text = ""
          glyph.background = null
          statusText.setTextColor(palette.textSecondary)
          statusText.text = activity.getString(R.string.step_status_pending)
        }
      }
    }
    // Newly-done rows pop in (scale 0.9 → 1); the first render stays static.
    if (done > prevDone && !firstStepRender) {
      for (i in prevDone until done) {
        val card = stepCards.getOrNull(i) ?: continue
        card.alpha = 0f
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card
          .animate()
          .alpha(1f)
          .scaleX(1f)
          .scaleY(1f)
          .setDuration(200)
          .setInterpolator(INTERPOLATOR)
          .start()
      }
    }
    prevDone = done
    firstStepRender = false
    if (stepActiveGlyph != null) startStepPulse()
  }

  /** Breathing alpha on the active step-card glyph. */
  private fun startStepPulse() {
    val glyph = stepActiveGlyph ?: return
    stepPulseAnimator?.cancel()
    val animator = android.animation.ValueAnimator.ofFloat(1f, 0.25f)
    animator.duration = 900
    animator.repeatMode = android.animation.ValueAnimator.REVERSE
    animator.repeatCount = android.animation.ValueAnimator.INFINITE
    animator.addUpdateListener { glyph.alpha = it.animatedValue as Float }
    animator.start()
    stepPulseAnimator = animator
  }

  private fun stopStepPulse() {
    stepPulseAnimator?.cancel()
    stepPulseAnimator = null
    stepActiveGlyph?.alpha = 1f
  }
```

- [ ] **Step 3: Update onDestroy**

Replace:

```kotlin
  fun onDestroy() {
    cancelScheduledTopBarHide()
    stopTopBarPulse()
  }
```

with:

```kotlin
  fun onDestroy() {
    cancelScheduledTopBarHide()
    stopTopBarPulse()
    stopStepPulse()
  }
```

- [ ] **Step 4: Self-review against the spec**

Check: 步骤卡纵向列表、状态用图标+状态色点/勾、激活卡呼吸点、完成卡勾+缩放进入、pending 灰圈编号。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/dshmobile/shell/GuideWizard.kt
git commit -m "feat(ui): vertical step cards — StepModel-driven states, breathing active dot, done check pop-in"
```

---

### Task 7: Status card + action area

**Files:**
- Modify: `app/src/main/java/com/dshmobile/shell/GuideWizard.kt` (replace status-card construction inside the old `buildGuideView` — the new `buildStatusCard`; add `buildActionArea` + `primaryButton`; rework `showGuideStatus`/`showLaunchReady`/`showGuideError` to the new primary button)

**Interfaces:**
- Consumes: `GuidePalette`, strings `button_view_log`, `button_reload` (Task 1), existing callbacks `onCheckUpdate/onKeepAlive/onCopyLog/onBackToHarness`, new constructor params `onOpenLog: () -> Unit`, `onReload: () -> Unit` (added in this task), `EngineProbe.ENGINE_URL`
- Produces: `buildStatusCard(): LinearLayout` (engine status row + detail + progress + URL row with "查看日志" ghost entry + inline error block), `buildActionArea(): LinearLayout` (gradient primary pill + 2×2 ghost grid + back button), `primaryLabel` field. Task 5's `buildGuideView` calls both. Constructor gains two params — Task 10 passes them.
- Contract change: `GuideWizard` constructor signature grows by `onOpenLog: () -> Unit, onReload: () -> Unit` (inserted after `onBackToHarness`); `MainActivity` is updated in Task 10.

- [ ] **Step 1: Extend the constructor**

Replace the constructor:

```kotlin
class GuideWizard(
  private val activity: ComponentActivity,
  private val webView: android.webkit.WebView,
  private val onPrimaryAction: () -> Unit,
  private val onCheckUpdate: (status: (String) -> Unit) -> Unit,
  private val onCopyLog: () -> Unit,
  private val onBackToHarness: () -> Unit,
  private val onKeepAlive: () -> Unit,
) {
```

with:

```kotlin
class GuideWizard(
  private val activity: ComponentActivity,
  private val webView: android.webkit.WebView,
  private val onPrimaryAction: () -> Unit,
  private val onCheckUpdate: (status: (String) -> Unit) -> Unit,
  private val onCopyLog: () -> Unit,
  private val onBackToHarness: () -> Unit,
  private val onKeepAlive: () -> Unit,
  private val onOpenLog: () -> Unit,
  private val onReload: () -> Unit,
) {
```

- [ ] **Step 2: Rework the status-row state setters for the new primary button**

Replace `showGuideStatus`:

```kotlin
  /** Set the guide's status row; shows the spinner when the status is
   *  indeterminate progress (extraction, container install, engine start). */
  fun showGuideStatus(
    title: String,
    detail: String?,
    busy: Boolean,
  ) {
    engineStatus?.text = title
    statusDetail?.text = detail
    statusDetail?.visibility = if (detail.isNullOrEmpty()) View.GONE else View.VISIBLE
    progressBar?.visibility = if (busy) View.VISIBLE else View.GONE
    if (busy) {
      errorBlock?.visibility = View.GONE
      primaryButton?.visibility = View.GONE
    }
  }
```

with:

```kotlin
  /** Set the guide's status row; shows the spinner when the status is
   *  indeterminate progress (extraction, container install, engine start). */
  fun showGuideStatus(
    title: String,
    detail: String?,
    busy: Boolean,
  ) {
    engineStatus?.text = title
    statusDetail?.text = detail
    statusDetail?.visibility = if (detail.isNullOrEmpty()) View.GONE else View.VISIBLE
    progressBar?.visibility = if (busy) View.VISIBLE else View.GONE
    if (busy) {
      errorBlock?.visibility = View.GONE
      primaryButton?.visibility = View.GONE
    }
  }
```

(unchanged — the rework touches only the primary button label. `primaryButton` is now a `LinearLayout`, so in `showLaunchReady` and `showGuideError` the `text`/`isEnabled` assignments must move to `primaryLabel`.)

Replace `showLaunchReady`:

```kotlin
  /** Ready state: everything installed → show the Launch engine button. */
  fun showLaunchReady() {
    showGuideStatus(
      activity.getString(R.string.status_ready_to_launch),
      activity.getString(R.string.status_ready_to_launch_detail),
      false,
    )
    renderSteps(3, 3)
    primaryButton?.apply {
      visibility = View.VISIBLE
      text = activity.getString(R.string.button_launch_engine)
      isEnabled = true
    }
  }
```

with:

```kotlin
  /** Ready state: everything installed → show the Launch engine button. */
  fun showLaunchReady() {
    showGuideStatus(
      activity.getString(R.string.status_ready_to_launch),
      activity.getString(R.string.status_ready_to_launch_detail),
      false,
    )
    renderSteps(3, 3)
    primaryLabel?.text = activity.getString(R.string.button_launch_engine)
    primaryButton?.visibility = View.VISIBLE
  }
```

Replace `showGuideError`:

```kotlin
  fun showGuideError(title: String) {
    showGuideStatus(title, null, false)
    primaryButton?.apply {
      visibility = View.VISIBLE
      text = activity.getString(R.string.button_retry)
      isEnabled = true
    }
    // Surface the tail of the diagnostic log as inline error context.
    val tail = AppLog.tail(1200)
    errorText?.text = tail
    errorBlock?.visibility = if (tail.isBlank()) View.GONE else View.VISIBLE
  }
```

with:

```kotlin
  fun showGuideError(title: String) {
    showGuideStatus(title, null, false)
    primaryLabel?.text = activity.getString(R.string.button_retry)
    primaryButton?.visibility = View.VISIBLE
    // Surface the tail of the diagnostic log as inline error context.
    val tail = AppLog.tail(1200)
    errorText?.text = tail
    errorBlock?.visibility = if (tail.isBlank()) View.GONE else View.VISIBLE
  }
```

- [ ] **Step 3: Build the status card**

Add a new function after `glowDrawable` (before `buildBrandBlock`):

```kotlin
  /** Engine status card: status row + detail + progress + engine URL with a
   *  "View log" entry + inline error block (log tail). */
  private fun buildStatusCard(): LinearLayout {
    val palette = GuidePalette(activity)
    val statusDot =
      View(activity).apply {
        background = activity.getDrawable(com.dshmobile.shell.R.drawable.status_dot)
        val size = (8 * d).toInt()
        layoutParams = LinearLayout.LayoutParams(size, size)
      }
    val statusTitle =
      TextView(activity).apply {
        setTextColor(palette.textPrimary)
        textSize = 15f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setPadding((8 * d).toInt(), 0, 0, 0)
      }
    engineStatus = statusTitle
    val statusRow =
      LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
      }
    statusRow.addView(statusDot)
    statusRow.addView(statusTitle)
    val detail =
      TextView(activity).apply {
        setTextColor(palette.textSecondary)
        textSize = 12f
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(0, (10 * d).toInt(), 0, 0)
        maxLines = 3
        ellipsize = android.text.TextUtils.TruncateAt.END
      }
    statusDetail = detail
    val progress =
      android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        progressTintList = android.content.res.ColorStateList.valueOf(palette.accent)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(palette.accentDim)
        visibility = View.GONE
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (4 * d).toInt())
        lp.topMargin = (16 * d).toInt()
        layoutParams = lp
      }
    progressBar = progress
    val urlText =
      TextView(activity).apply {
        text = EngineProbe.ENGINE_URL
        setTextColor(palette.textSecondary)
        textSize = 12f
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(0, (10 * d).toInt(), 0, 0)
      }
    val viewLog =
      ghostButton(activity.getString(R.string.button_view_log)).apply {
        minHeight = (36 * d).toInt()
        setOnClickListener { onOpenLog() }
      }
    val urlRow =
      LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
      }
    urlRow.addView(urlText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
    urlRow.addView(viewLog)
    val cardBody =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
      }
    cardBody.addView(statusRow)
    cardBody.addView(detail)
    cardBody.addView(progress)
    cardBody.addView(urlRow)
    val errorDetail =
      TextView(activity).apply {
        setTextColor(palette.error)
        textSize = 12f
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
        maxLines = 4
        ellipsize = android.text.TextUtils.TruncateAt.END
      }
    errorText = errorDetail
    val errorBlock =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background =
          android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = (12 * d)
            setColor(palette.errorDim)
          }
        visibility = View.GONE
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (16 * d).toInt()
        layoutParams = lp
      }
    errorBlock.addView(errorDetail)
    this.errorBlock = errorBlock
    val inner =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = activity.getDrawable(com.dshmobile.shell.R.drawable.inset_bg)
      }
    inner.addView(cardBody)
    inner.addView(errorBlock)
    val card =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = activity.getDrawable(com.dshmobile.shell.R.drawable.card_bg)
        setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        layoutParams =
          LinearLayout
            .LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
              topMargin = (20 * d).toInt()
            }
      }
    card.addView(inner)
    statusCard = card
    return card
  }
```

- [ ] **Step 4: Build the action area + gradient primary button**

Add a new function after `buildVersionLine` (or anywhere at class level):

```kotlin
  /** Action area: gradient primary pill + 2×2 ghost grid (update / reload /
   *  keep-alive / copy log) + back-to-harness (visible only when the guide
   *  was opened from the cold-start bar). */
  private fun buildActionArea(): LinearLayout {
    val sep = (10 * d).toInt()
    val primary =
      primaryPill(activity.getString(R.string.button_launch_engine)).apply {
        visibility = View.GONE
        setOnClickListener { onPrimaryAction() }
      }
    primaryButton = primary
    fun ghost(text: String, action: () -> Unit): Button =
      ghostButton(text).apply {
        setOnClickListener { action() }
      }
    val update =
      ghost(activity.getString(R.string.button_check_update)) {
        onCheckUpdate { status -> showGuideStatus(status, null, true) }
      }
    val reload = ghost(activity.getString(R.string.button_reload)) { onReload() }
    val keepAlive = ghost(activity.getString(R.string.button_keep_alive)) { onKeepAlive() }
    val copyLog = ghost(activity.getString(R.string.button_copy_log)) { onCopyLog() }
    val back =
      ghost(activity.getString(R.string.button_back_to_harness)).apply {
        visibility = View.GONE
        setOnClickListener { onBackToHarness() }
      }
    backButton = back
    (update.layoutParams as LinearLayout.LayoutParams).bottomMargin = sep
    (reload.layoutParams as LinearLayout.LayoutParams).bottomMargin = sep
    (keepAlive.layoutParams as LinearLayout.LayoutParams).bottomMargin = sep
    val left =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams =
          LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            weight = 1f
            rightMargin = (8 * d).toInt()
          }
      }
    val right =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams =
          LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            weight = 1f
            leftMargin = (8 * d).toInt()
          }
      }
    left.addView(update)
    left.addView(keepAlive)
    right.addView(reload)
    right.addView(copyLog)
    val grid =
      LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams =
          LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (24 * d).toInt()
          }
      }
    grid.addView(left)
    grid.addView(right)
    back.layoutParams =
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (16 * d).toInt()
      }
    val actions =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams =
          LinearLayout
            .LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
              topMargin = (32 * d).toInt()
            }
      }
    actions.addView(primary)
    actions.addView(grid)
    actions.addView(back)
    actionRow = actions
    return actions
  }

  /** Gradient pill primary: label + trailing circular chevron. */
  private fun primaryPill(text: String): LinearLayout {
    val palette = GuidePalette(activity)
    val label =
      TextView(activity).apply {
        textSize = 15f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(0xFFFFFFFF.toInt())
      }
    primaryLabel = label
    val chevron =
      TextView(activity).apply {
        text = "›"
        textSize = 14f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(0xFFFFFFFF.toInt())
        gravity = android.view.Gravity.CENTER
        background =
          android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0x33FFFFFF.toInt())
          }
        val size = (24 * d).toInt()
        layoutParams = LinearLayout.LayoutParams(size, size)
      }
    return LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = android.view.Gravity.CENTER
      background =
        android.graphics.drawable.GradientDrawable().apply {
          shape = android.graphics.drawable.GradientDrawable.RECTANGLE
          cornerRadius = (24 * d)
          colors = intArrayOf(palette.accent, palette.accentEnd)
          gradientType = android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT
          gradientAngle = 0
        }
      minHeight = (48 * d).toInt()
      setPadding((20 * d).toInt(), 0, (12 * d).toInt(), 0)
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      addView(label)
      addView(
        chevron,
        LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()).apply {
          leftMargin = (14 * d).toInt()
        },
      )
      setOnClickListener { onPrimaryAction() }
      attachPressFeedback(this)
    }
  }
```

- [ ] **Step 5: Adjust the press feedback to 0.98**

In `attachPressFeedback`, replace both `0.97f` with `0.98f` (spec: 按压缩放 0.98).

- [ ] **Step 6: Self-review against the spec**

Check: 主按钮渐变药丸+尾随圆形 chevron、按压缩放 0.98;2×2 幽灵按钮(检查更新/重新加载/后台运行/复制日志);返回 Harness 保留隐藏规则;状态卡含 URL + 查看日志入口。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/dshmobile/shell/GuideWizard.kt
git commit -m "feat(ui): engine status card (URL + view-log entry) and 2x2 action grid with gradient primary pill"
```

---

### Task 8: Keep-alive card restyle

**Files:**
- Modify: `app/src/main/java/com/dshmobile/shell/GuideWizard.kt` (rework `buildKeepAliveCard`)

**Interfaces:**
- Consumes: `GuidePalette`, existing strings `keep_alive_*`, `accentButton`/`ghostButton` (unchanged helpers)
- Produces: `buildKeepAliveCard(): LinearLayout` — same public behavior via `showKeepAlivePanel`/`updateKeepAliveStatus`/`hideKeepAlivePanel` (already de-spacered in Task 5), shell-card + inset styling, natural height inside the scroll view.

- [ ] **Step 1: Rework buildKeepAliveCard**

Replace the whole old `buildKeepAliveCard` function (from `private fun buildKeepAliveCard(): LinearLayout {` through its closing brace) with:

```kotlin
  private fun buildKeepAliveCard(): LinearLayout {
    val palette = GuidePalette(activity)
    val title =
      TextView(activity).apply {
        text = activity.getString(R.string.keep_alive_title)
        setTextColor(palette.textPrimary)
        textSize = 15f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
      }
    val text =
      TextView(activity).apply {
        setTextColor(palette.textSecondary)
        textSize = 13f
        setPadding(0, (12 * d).toInt(), 0, 0)
      }
    keepAliveText = text
    val battery = accentButton(activity.getString(R.string.keep_alive_battery))
    keepAliveBattery = battery
    val shizuku = ghostButton(activity.getString(R.string.keep_alive_shizuku))
    keepAliveShizuku = shizuku
    val close =
      ghostButton(activity.getString(R.string.keep_alive_close)).apply {
        setOnClickListener { hideKeepAlivePanel() }
      }
    val sep = (10 * d).toInt()
    battery.layoutParams =
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (16 * d).toInt()
        bottomMargin = sep
      }
    shizuku.layoutParams =
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = sep
      }
    close.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    val body =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
        addView(title)
        addView(text)
        addView(battery)
        addView(shizuku)
        addView(close)
      }
    val inner =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = activity.getDrawable(com.dshmobile.shell.R.drawable.inset_bg)
        addView(body)
      }
    return LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      background = activity.getDrawable(com.dshmobile.shell.R.drawable.card_bg)
      setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
      layoutParams =
        LinearLayout
          .LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
          ).apply {
            topMargin = (32 * d).toInt()
          }
      addView(inner)
    }
  }
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/dshmobile/shell/GuideWizard.kt
git commit -m "feat(ui): keep-alive card restyle — shell card + inset, natural height in the scroll view"
```

---

### Task 9: Log viewer panel (LogPanel + LogPanelText)

**Files:**
- Create: `app/src/main/java/com/dshmobile/shell/LogPanelText.kt`
- Create: `app/src/test/java/com/dshmobile/shell/LogPanelTextTest.kt`
- Create: `app/src/main/java/com/dshmobile/shell/LogPanel.kt`
- Modify: `app/src/main/java/com/dshmobile/shell/GuideWizard.kt` (own a `LogPanel`, expose `logPanelView` + `openLogPanel()`, share intent)

**Interfaces:**
- Consumes: `AppLog.dump()`/`AppLog.copyToClipboard` (existing), `LogPanelText` (this task), `VersionLine.format` (Task 3), strings `log_panel_title/button_copy_panel/button_share/button_close/log_share_title` (Task 1)
- Produces: `object LogPanelText` with `fun shareText(versionLine: String, logText: String): String`; `class LogPanel(activity, versionLine, onCopy, onShare, onClose)` with `val view: FrameLayout` and `fun open()`/`fun close()`; `GuideWizard.logPanelView: View` + `fun openLogPanel()` — Task 10 adds the panel to the root and wires `onOpenLog`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/dshmobile/shell/LogPanelTextTest.kt`:

```kotlin
package com.dshmobile.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class LogPanelTextTest {
  @Test
  fun shareTextCarriesVersionLineAndLog() {
    val text =
      LogPanelText.shareText(
        "v0.1.5 · arm64-v8a · dsh 0.1.0-rc.6",
        "boot: engine flow start",
      )
    assertEquals("dsh v0.1.5 · arm64-v8a · dsh 0.1.0-rc.6\n\nboot: engine flow start\n", text)
  }

  @Test
  fun trailingNewlineNotDuplicated() {
    assertEquals("dsh v1\n\nlog\n", LogPanelText.shareText("v1", "log\n"))
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.dshmobile.shell.LogPanelTextTest"`
Expected: FAIL — `LogPanelText` unresolved.

- [ ] **Step 3: Implement LogPanelText**

`app/src/main/java/com/dshmobile/shell/LogPanelText.kt`:

```kotlin
package com.dshmobile.shell

/** Text assembly for the diagnostic log panel (share payload). */
object LogPanelText {
  fun shareText(
    versionLine: String,
    logText: String,
  ): String =
    "dsh " + versionLine + "\n\n" + logText + if (logText.endsWith("\n")) "" else "\n"
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.dshmobile.shell.LogPanelTextTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Implement LogPanel**

`app/src/main/java/com/dshmobile/shell/LogPanel.kt`:

```kotlin
package com.dshmobile.shell

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Full-screen log viewer overlay for the boot guide: title bar (Copy /
 * Share / Close) over a monospace scroll of AppLog.dump(). Built once,
 * revealed on demand, auto-scrolled to the newest entry. Share goes through
 * ACTION_SEND (text/plain) so devices without adb/PC access can still
 * export diagnostics.
 */
class LogPanel(
  private val activity: ComponentActivity,
  private val versionLine: String,
  private val onCopy: () -> Unit,
  private val onShare: (String) -> Unit,
  private val onClose: () -> Unit,
) {
  private val d: Float get() = activity.resources.displayMetrics.density

  private var logText: TextView? = null
  private var scrollView: ScrollView? = null

  val view: FrameLayout = build()

  /** Reveal with a fresh dump and scroll to the newest entry. */
  fun open() {
    logText?.text = AppLog.dump()
    view.visibility = View.VISIBLE
    view.alpha = 0f
    view
      .animate()
      .alpha(1f)
      .setDuration(200)
      .start()
    scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
  }

  fun close() {
    view
      .animate()
      .alpha(0f)
      .setDuration(150)
      .withEndAction {
        view.visibility = View.GONE
        view.alpha = 1f
      }.start()
  }

  private fun share() {
    onShare(LogPanelText.shareText(versionLine, AppLog.dump()))
  }

  private fun ghostAction(
    text: String,
    action: () -> Unit,
  ): Button =
    Button(activity).apply {
      this.text = text
      setTextColor(activity.resources.getColor(com.dshmobile.shell.R.color.text_primary, null))
      textSize = 12f
      background = activity.getDrawable(com.dshmobile.shell.R.drawable.pill_ghost)
      minHeight = (32 * d).toInt()
      isAllCaps = false
      stateListAnimator = null
      setOnClickListener { action() }
    }

  private fun build(): FrameLayout {
    val title =
      TextView(activity).apply {
        text = activity.getString(R.string.log_panel_title)
        setTextColor(activity.resources.getColor(com.dshmobile.shell.R.color.text_primary, null))
        textSize = 16f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
      }
    val copy = ghostAction(activity.getString(R.string.button_copy_panel)) { onCopy() }
    val share = ghostAction(activity.getString(R.string.button_share)) { share() }
    val close = ghostAction(activity.getString(R.string.button_close)) { onClose() }
    fun gap(): View =
      View(activity).apply {
        layoutParams = LinearLayout.LayoutParams((8 * d).toInt(), (36 * d).toInt())
      }
    val bar =
      LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())
        background = activity.getDrawable(com.dshmobile.shell.R.drawable.card_bg)
      }
    bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
    bar.addView(copy)
    bar.addView(gap())
    bar.addView(share)
    bar.addView(gap())
    bar.addView(close)
    val body =
      TextView(activity).apply {
        setTextColor(activity.resources.getColor(com.dshmobile.shell.R.color.text_primary, null))
        textSize = 12f
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        setTextIsSelectable(true)
      }
    logText = body
    val scroll =
      ScrollView(activity).apply {
        isFillViewport = true
        background = activity.getDrawable(com.dshmobile.shell.R.drawable.inset_bg)
      }
    scroll.addView(body)
    scrollView = scroll
    val panel =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = activity.getDrawable(com.dshmobile.shell.R.drawable.card_bg)
        setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        layoutParams =
          FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.Gravity.CENTER,
          )
      }
    panel.addView(bar)
    panel.addView(
      scroll,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f },
    )
    return FrameLayout(activity).apply {
      visibility = View.GONE
      setBackgroundColor(0xCC000000.toInt())
      setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
      addView(panel)
    }
  }
}
```

- [ ] **Step 6: Wire LogPanel into GuideWizard**

In `GuideWizard.kt`:

Add a field next to `guideContent`:

```kotlin
  private lateinit var logPanel: LogPanel
```

In the constructor body (after `val topStatusBar: LinearLayout = buildTopStatusBar()` — but `logPanel` needs the version line first; build it inside `init` after `guideView` is built):

```kotlin
  init {
    logPanel =
      LogPanel(
        activity,
        buildVersionLineText(),
        onCopy = { onCopyLog() },
        onShare = { text -> shareLog(text) },
        onClose = { logPanel.close() },
      )
  }
```

Extract the version-line text from Task 5's `buildVersionLine` into a reusable function and make `buildVersionLine` use it:

```kotlin
  private fun buildVersionLineText(): String {
    val appVersion =
      try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"
      } catch (_: Throwable) {
        "?"
      }
    val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
    return VersionLine.format(appVersion, abi, SnapshotVersion.read(activity))
  }
```

and simplify `buildVersionLine` to:

```kotlin
  /** Bottom version row: app version · ABI · snapshot dsh version. */
  private fun buildVersionLine(): TextView {
    val palette = GuidePalette(activity)
    return TextView(activity).apply {
      text = buildVersionLineText()
      setTextColor(palette.textSecondary)
      textSize = 12f
      typeface = android.graphics.Typeface.MONOSPACE
      gravity = android.view.Gravity.CENTER_HORIZONTAL
      layoutParams =
        LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
          topMargin = (24 * d).toInt()
        }
    }
  }
```

Add the public API near `showKeepAlivePanel`:

```kotlin
  /** The log-viewer overlay view; the caller adds it on top of everything. */
  val logPanelView: View get() = logPanel.view

  /** Reveal the diagnostic log overlay (copy / share / close). */
  fun openLogPanel() {
    logPanel.open()
  }

  /** ACTION_SEND (text/plain) — no storage permission needed. */
  private fun shareLog(text: String) {
    val intent =
      android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
      }
    try {
      activity.startActivity(
        android.content.Intent.createChooser(intent, activity.getString(R.string.log_share_title)),
      )
    } catch (_: Throwable) {
    }
  }
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/dshmobile/shell/LogPanelText.kt \
  app/src/main/java/com/dshmobile/shell/LogPanel.kt \
  app/src/main/java/com/dshmobile/shell/GuideWizard.kt \
  app/src/test/java/com/dshmobile/shell/LogPanelTextTest.kt
git commit -m "feat(ui): in-app diagnostic log viewer — overlay panel with copy/share/close, ACTION_SEND export"
```

---

### Task 10: MainActivity wiring — applyTheme, new callbacks, bar states

**Files:**
- Modify: `app/src/main/java/com/dshmobile/shell/MainActivity.kt`

**Interfaces:**
- Consumes: `BarState` + `wizard.showTopBar(state)` (Task 4), `wizard.logPanelView`/`wizard.openLogPanel()` (Task 9), `GuidePalette` (Task 1), new `GuideWizard` constructor params `onOpenLog`/`onReload` (Task 7)
- Produces: `applyTheme()` (window bars follow system dark); `showWeb(barState: BarState?)` with `BarState.SUCCESS` default; `onEngineError` → guide + persistent `FAILED` bar; quick-path `showTopBar(BarState.STARTING)`; root background from palette; the pill bar centered with 12dp top margin; `logPanelView` added last; `onConfigurationChanged` calls `applyTheme`.

- [ ] **Step 1: applyTheme + root background**

Add the theme helper after `logDeviceInfo`:

```kotlin
  /** Follow the system theme for the window bars (light/dark auto). */
  private fun applyTheme() {
    val palette = GuidePalette(this)
    window.statusBarColor = palette.background
    window.navigationBarColor = palette.background
    val lightFlags =
      if (palette.dark) {
        window.decorView.systemUiVisibility and
          android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
          android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
      } else {
        window.decorView.systemUiVisibility or
          android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
          android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
      }
    window.decorView.systemUiVisibility = lightFlags
  }
```

In `onCreate`, replace:

```kotlin
    val root =
      FrameLayout(this).apply {
        setBackgroundColor(0xFFFFFFFF.toInt())
      }
```

with:

```kotlin
    val root =
      FrameLayout(this).apply {
        setBackgroundColor(GuidePalette(this).background)
      }
```

After `setContentView(root)`, add `applyTheme()`:

```kotlin
    setContentView(root)
    applyTheme()
    harness.configure()
```

- [ ] **Step 2: Rework the root children (pill bar params + log panel)**

Replace:

```kotlin
    root.addView(
      wizard.topStatusBar,
      FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP),
    )
    root.addView(wizard.guideView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    setContentView(root)
```

with:

```kotlin
    root.addView(
      wizard.topStatusBar,
      FrameLayout
        .LayoutParams(
          ViewGroup.LayoutParams.WRAP_CONTENT,
          ViewGroup.LayoutParams.WRAP_CONTENT,
          android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL,
        ).apply {
          topMargin = (12 * resources.displayMetrics.density).toInt()
        },
    )
    root.addView(wizard.guideView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    root.addView(wizard.logPanelView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    setContentView(root)
```

- [ ] **Step 3: Pass the new wizard callbacks**

Replace the `GuideWizard(...)` construction call — add after `onKeepAlive = { showKeepAlivePanel() },`:

```kotlin
        onOpenLog = { wizard.openLogPanel() },
        onReload = { harness.view.loadUrl(EngineProbe.ENGINE_URL) },
```

- [ ] **Step 4: Failure path — onEngineError shows guide + persistent FAILED bar**

Replace:

```kotlin
        onEngineError = { showGuide() },
```

with:

```kotlin
        onEngineError = {
          showGuide()
          wizard.showTopBar(BarState.FAILED)
        },
```

- [ ] **Step 5: Quick path shows STARTING bar**

In `startEngineFlow`, replace:

```kotlin
          wizard.showTopBar(getString(R.string.status_engine_starting))
```

with:

```kotlin
          wizard.showTopBar(BarState.STARTING)
```

- [ ] **Step 6: showWeb gains the bar state**

Replace:

```kotlin
  private fun showWeb() {
    // Reload only when the page actually failed to load (error page shown
    // before the engine answered); onResume/pick-return must NOT reload a
    // healthy page — that discards session UI and races in-flight pick
    // callbacks.
    harness.reloadIfFailed()
    wizard.showWeb()
  }
```

with:

```kotlin
  /** Show the Harness; a null [barState] keeps the current bar state (the
   *  FAILED bar must persist when returning from the error guide, I-26). */
  private fun showWeb(barState: BarState? = BarState.SUCCESS) {
    // Reload only when the page actually failed to load (error page shown
    // before the engine answered); onResume/pick-return must NOT reload a
    // healthy page — that discards session UI and races in-flight pick
    // callbacks.
    harness.reloadIfFailed()
    if (barState != null) wizard.showTopBar(barState)
    wizard.showWeb()
  }
```

- [ ] **Step 7: Back-to-harness keeps the current bar state**

Replace:

```kotlin
        onBackToHarness = { showWeb() },
```

with:

```kotlin
        onBackToHarness = { showWeb(null) },
```

- [ ] **Step 8: applyTheme on configuration change**

Replace:

```kotlin
  override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
    super.onConfigurationChanged(newConfig)
    harness.pushSystemDark()
  }
```

with:

```kotlin
  override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
    super.onConfigurationChanged(newConfig)
    harness.pushSystemDark()
    applyTheme()
  }
```

- [ ] **Step 9: Self-review against the spec**

Check: `onEngineError` → 引导页 + 失败态细条常驻;`showWeb` 成功 → 成功态 6s 淡出;`applyTheme()` 在 `onCreate` 与 `onConfigurationChanged(uiMode)` 调用;`onOpenLog` 接线(状态卡"查看日志");日志面板置于 root 顶层。

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/dshmobile/shell/MainActivity.kt
git commit -m "feat(ui): MainActivity wiring — applyTheme (system dark), FAILED/STARTING/SUCCESS bar states, log panel + reload entries"
```

---

### Task 11: Cleanup + full quality gate

**Files:**
- Delete: `app/src/main/res/drawable/step_active.xml`, `app/src/main/res/drawable/step_done.xml`, `app/src/main/res/drawable/step_pending.xml`, `app/src/main/res/drawable/pill_accent.xml` (obsolete — code-drawn circles/gradient replace them)
- Run: full CI gate + local test gate

**Interfaces:**
- Consumes: all tasks above
- Produces: green CI (`./gradlew assembleDebug lintDebug ktlintCheck testDebugUnitTest` + `./tests/run-local.sh`)

- [ ] **Step 1: Verify no remaining references**

Grep the repo for `step_active`, `step_done`, `step_pending`, `pill_accent` — they may still be referenced in Kotlin code:

```bash
rg -n "step_active|step_done|step_pending|pill_accent" app/src
```

Expected: no matches (all usages were replaced in Tasks 5-8; if any remain, fix them first).

- [ ] **Step 2: Delete the obsolete drawables**

```bash
git rm app/src/main/res/drawable/step_active.xml \
  app/src/main/res/drawable/step_done.xml \
  app/src/main/res/drawable/step_pending.xml \
  app/src/main/res/drawable/pill_accent.xml
```

- [ ] **Step 3: Commit the cleanup**

```bash
git commit -m "style(ui): drop obsolete drawables — code-drawn circles and gradient pill replace step_*/pill_accent"
```

- [ ] **Step 4: Run the local gate**

Run: `./tests/run-local.sh`
Expected: `ALL LOCAL TESTS PASSED` (JS polyfills + C hook + bash wrapper tests — untouched by UI work, must stay green)

- [ ] **Step 5: Run the full CI gate**

Push to a branch and open a PR (or push to main if the repo flow is direct):

```bash
git push
```

Expected (CI): `assembleDebug`, `lintDebug`, `ktlintCheck`, `testDebugUnitTest` (including the 5 new test classes: GuidePaletteTest 2, StepModelTest 4, VersionLineTest 1, SnapshotVersionTest 3, LogPanelTextTest 2), and `./tests/run-local.sh` all green.

- [ ] **Step 6: Fix any gate findings**

`ktlintFormat` fixes style; lint errors must be fixed by hand. Re-run the gate until green.

- [ ] **Step 7: Commit the gate fixes (if any)**

```bash
git add -A
git commit -m "style(ui): ktlint/lint fixes from the CI gate"
```

---

### Task 12: Device verification checklist (user)

**Files:** none — manual verification on the Huawei ban-device.

- [ ] **Step 1: Fresh first-boot flow**

Install the CI debug APK, cold start, and walk the full first boot: extraction → container install → launch → Harness. Check: glow + brand block, three step cards (pending → active with breathing dot → done with check pop-in), status card (URL + "查看日志"), gradient primary pill with chevron, 2×2 ghost grid, version line (app · ABI · dsh version from snapshot package.json), entry stagger on every guide show.

- [ ] **Step 2: Kill-and-reopen failure persistence**

Kill the app (swipe away), reopen: the engine is cold — the FAILED pill must persist on the web view and the error guide must still offer Retry + 查看日志 (I-26 exit). Tap the pill → guide opens; tap 返回 Harness → red pill still there.

- [ ] **Step 3: Success fade**

After a healthy start, the green SUCCESS pill fades out after ~6s.

- [ ] **Step 4: Log viewer**

Open 查看日志: full dump, auto-scrolled to bottom; Copy works; Share opens the ACTION_SEND chooser; Close dismisses.

- [ ] **Step 5: Dark mode**

Toggle system dark mode while on the guide: colors swap (no restart needed beyond configuration change), bars follow.

- [ ] **Step 6: Report findings**

Report anything off-spec back; tag `v0.1.6` and release once green.
