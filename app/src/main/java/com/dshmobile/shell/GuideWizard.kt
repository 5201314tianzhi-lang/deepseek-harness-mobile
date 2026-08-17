package com.dshmobile.shell

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Boot wizard UI: full-screen guide (brand block, three-step indicator
 * runtime → container → launch, status card, action row) plus the thin
 * cold-start bar overlaying the Harness. Pure presentation — all flow
 * decisions live in the caller through the injected callbacks.
 */
/** Cold-start bar states: STARTING breathes, FAILED persists (I-26: a failed
 *  boot must always leave an exit), SUCCESS fades away after a delay. */
enum class BarState { STARTING, FAILED, SUCCESS }

class GuideWizard(
  private val activity: ComponentActivity,
  private val webView: android.webkit.WebView,
  private val onPrimaryAction: () -> Unit,
  private val onCheckUpdate: (status: (String) -> Unit) -> Unit,
  private val onCopyLog: () -> Unit,
  private val onBackToHarness: () -> Unit,
  private val onKeepAlive: () -> Unit,
) {
  val guideView: LinearLayout = buildGuideView()
  val topStatusBar: LinearLayout = buildTopStatusBar()

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

  private val d: Float get() = activity.resources.displayMetrics.density

  private val INTERPOLATOR = android.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)

  private var currentBarState: BarState? = null

  // ---- Public state API -------------------------------------------------

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

  /** Public so failure paths can stop the pulse and dismiss the bar. */
  fun hideTopBar() {
    stopTopBarPulse()
    topStatusBar
      .animate()
      .alpha(0f)
      .setDuration(250)
      .withEndAction {
        topStatusBar.visibility = View.GONE
        topStatusBar.alpha = 1f
      }.start()
  }

  private var topBarHidePending: java.lang.Runnable? = null

  private fun scheduleTopBarHide(delayMs: Long) {
    topBarHidePending?.let { webView.removeCallbacks(it) }
    val r = java.lang.Runnable { hideTopBar() }
    topBarHidePending = r
    webView.postDelayed(r, delayMs)
  }

  private fun cancelScheduledTopBarHide() {
    topBarHidePending?.let { webView.removeCallbacks(it) }
    topBarHidePending = null
  }

  /** Guide entry from the cold-start bar: show actions, keep Harness in back. */
  fun showGuideFromTopBar() {
    primaryButton?.visibility = View.GONE
    backButton?.visibility = View.VISIBLE
    showGuide()
  }

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

  fun onDestroy() {
    cancelScheduledTopBarHide()
    stopTopBarPulse()
    stopStepPulse()
  }

  // ---- Construction -----------------------------------------------------

  /** Tactile press feedback: a light scale-down while pressed. */
  private fun attachPressFeedback(view: View) {
    view.setOnTouchListener { v, event ->
      when (event.actionMasked) {
        android.view.MotionEvent.ACTION_DOWN -> {
          v
            .animate()
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(80)
            .start()
        }

        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
          v
            .animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(120)
            .start()
        }
      }
      false
    }
  }

  /** Pill button with the accent fill (primary action). */
  private fun accentButton(text: String): Button =
    Button(activity).apply {
      this.text = text
      setTextColor(0xFFFFFFFF.toInt())
      textSize = 14f
      typeface = android.graphics.Typeface.DEFAULT_BOLD
      background = activity.getDrawable(com.dshmobile.shell.R.drawable.pill_accent)
      minHeight = (48 * d).toInt()
      isAllCaps = false
      stateListAnimator = null
      attachPressFeedback(this)
    }

  /** Pill button with a hairline border (secondary action). */
  private fun ghostButton(text: String): Button =
    Button(activity).apply {
      this.text = text
      setTextColor(activity.resources.getColor(com.dshmobile.shell.R.color.text_primary, null))
      textSize = 14f
      background = activity.getDrawable(com.dshmobile.shell.R.drawable.pill_ghost)
      minHeight = (48 * d).toInt()
      isAllCaps = false
      stateListAnimator = null
      attachPressFeedback(this)
    }

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

  private fun buildKeepAliveCard(): LinearLayout {
    val title =
      TextView(activity).apply {
        setText(activity.getString(R.string.keep_alive_title))
        setTextColor(activity.resources.getColor(com.dshmobile.shell.R.color.text_primary, null))
        textSize = 17f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
      }
    val text =
      TextView(activity).apply {
        setTextColor(activity.resources.getColor(com.dshmobile.shell.R.color.text_secondary, null))
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
        bottomMargin = sep
      }
    shizuku.layoutParams =
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = sep
      }
    close.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    val buttons =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
        addView(battery)
        addView(shizuku)
        addView(close)
      }
    val body =
      LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), 0)
        addView(title)
        addView(text)
      }
    return LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      background = activity.getDrawable(com.dshmobile.shell.R.drawable.card_bg)
      layoutParams =
        LinearLayout
          .LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
          ).apply {
            weight = 1f
            topMargin = (40 * d).toInt()
            leftMargin = (8 * d).toInt()
            rightMargin = (8 * d).toInt()
          }
      // Status text scrolls; the action buttons stay pinned to the bottom.
      val scroll =
        ScrollView(activity).apply {
          isFillViewport = true
          layoutParams =
            LinearLayout
              .LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
              ).apply {
                weight = 1f
              }
        }
      scroll.addView(body)
      addView(scroll)
      addView(buttons)
    }
  }

  /** Show the keep-alive panel with a status text and the two action buttons
   *  wired by the caller (battery-optimization page / Shizuku boost). */
  fun showKeepAlivePanel(
    statusText: String,
    onBattery: () -> Unit,
    onShizuku: () -> Unit,
  ) {
    keepAliveText?.text = statusText
    keepAliveBattery?.setOnClickListener { onBattery() }
    keepAliveShizuku?.setOnClickListener { onShizuku() }
    keepAliveBlock?.visibility = View.VISIBLE
    statusCard?.visibility = View.GONE
    actionRow?.visibility = View.GONE
  }

  fun updateKeepAliveStatus(text: String) {
    keepAliveText?.text = text
  }

  fun hideKeepAlivePanel() {
    keepAliveBlock?.visibility = View.GONE
    statusCard?.visibility = View.VISIBLE
    actionRow?.visibility = View.VISIBLE
  }

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

  /** Breathing alpha on the status-bar dot (engine working). */
  private fun startTopBarPulse() {
    val dot = topPulseDot ?: return
    // Idempotent: any prior animator must be cancelled or repeated starts
    // would drive the dot with several animators at once (visible jitter).
    topPulseAnimator?.cancel()
    val animator = android.animation.ValueAnimator.ofFloat(1f, 0.25f)
    animator.duration = 900
    animator.repeatMode = android.animation.ValueAnimator.REVERSE
    animator.repeatCount = android.animation.ValueAnimator.INFINITE
    animator.addUpdateListener { dot.alpha = it.animatedValue as Float }
    animator.start()
    topPulseAnimator = animator
  }

  private fun stopTopBarPulse() {
    topPulseAnimator?.cancel()
    topPulseAnimator = null
    topPulseDot?.alpha = 1f
  }
}
