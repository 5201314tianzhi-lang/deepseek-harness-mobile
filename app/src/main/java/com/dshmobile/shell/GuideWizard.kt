package com.dshmobile.shell

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Boot wizard UI: full-screen guide (brand block, three-step indicator
 * runtime → container → launch, status card, action row) plus the thin
 * cold-start bar overlaying the Harness. Pure presentation — all flow
 * decisions live in the caller through the injected callbacks.
 */
class GuideWizard(
  private val activity: ComponentActivity,
  private val webView: android.webkit.WebView,
  private val onPrimaryAction: () -> Unit,
  private val onCheckUpdate: (status: (String) -> Unit) -> Unit,
  private val onCopyLog: () -> Unit,
  private val onBackToHarness: () -> Unit,
) {

  val guideView: LinearLayout = buildGuideView()
  val topStatusBar: LinearLayout = buildTopStatusBar()

  private var engineStatus: TextView? = null
  private var statusDetail: TextView? = null
  private var progressBar: android.widget.ProgressBar? = null
  private var primaryButton: Button? = null
  private var backButton: Button? = null
  private var errorBlock: LinearLayout? = null
  private var errorText: TextView? = null
  private var stepDots: Array<TextView> = emptyArray()
  private var stepLabels: Array<TextView> = emptyArray()
  private var topPulseDot: View? = null
  private var topPulseAnimator: android.animation.ValueAnimator? = null

  private val d: Float get() = activity.resources.displayMetrics.density

  // ---- Public state API -------------------------------------------------

  /** Set the guide's status row; shows the spinner when the status is
   *  indeterminate progress (extraction, container install, engine start). */
  fun showGuideStatus(title: String, detail: String?, busy: Boolean) {
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
    webView.animate().alpha(0f).setDuration(150).start()
    webView.visibility = View.GONE
    stopTopBarPulse()
    topStatusBar.visibility = View.GONE
    guideView.visibility = View.VISIBLE
    guideView.animate().alpha(1f).setDuration(200).start()
  }

  fun showWeb() {
    backButton?.visibility = View.GONE
    guideView.animate().alpha(0f).setDuration(150).withEndAction {
      guideView.visibility = View.GONE
    }.start()
    webView.visibility = View.VISIBLE
    webView.animate().alpha(1f).setDuration(200).start()
    // The WebView may have rendered an error page before the engine was
    // ready (engine boot takes seconds); reload now that it answers.
    webView.reload()
    // Engine is up — keep the breathing dot visible a few seconds longer
    // (cold-start transition) before fading the bar away, so the pulse
    // animation is actually seen instead of vanishing immediately.
    scheduleTopBarHide(6000L)
  }

  /** Slide the thin status bar in (cold start over the Harness). */
  fun showTopBar(title: String) {
    cancelScheduledTopBarHide()
    guideView.visibility = View.GONE
    webView.visibility = View.VISIBLE
    webView.animate().alpha(1f).setDuration(150).start()
    topStatusLabel?.text = title
    topStatusBar.visibility = View.VISIBLE
    topStatusBar.animate().alpha(1f).setDuration(200).start()
    startTopBarPulse()
  }

  /** Public so failure paths can stop the pulse and dismiss the bar. */
  fun hideTopBar() {
    stopTopBarPulse()
    topStatusBar.animate().alpha(0f).setDuration(250).withEndAction {
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

  /** Render the wizard steps: done (filled + check), active (ring), pending (outline). */
  fun renderSteps(done: Int, active: Int) {
    for (i in stepDots.indices) {
      val dot = stepDots[i]
      dot.background = activity.getDrawable(
        when {
          i < done -> com.dshmobile.shell.R.drawable.step_done
          i == active -> com.dshmobile.shell.R.drawable.step_active
          else -> com.dshmobile.shell.R.drawable.step_pending
        },
      )
      val label = stepLabels[i]
      label.setTextColor(activity.resources.getColor(
        if (i <= done) com.dshmobile.shell.R.color.text_primary
        else com.dshmobile.shell.R.color.text_secondary, null,
      ))
      if (i < done) {
        dot.text = "✓"
        dot.setTextColor(0xFFFFFFFF.toInt())
      } else {
        dot.text = ""
      }
    }
  }

  fun onDestroy() {
    cancelScheduledTopBarHide()
    stopTopBarPulse()
  }

  // ---- Construction -----------------------------------------------------

  /** Tactile press feedback: a light scale-down while pressed. */
  private fun attachPressFeedback(view: View) {
    view.setOnTouchListener { v, event ->
      when (event.actionMasked) {
        android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
          v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
      }
      false
    }
  }

  /** Pill button with the accent fill (primary action). */
  private fun accentButton(text: String): Button {
    return Button(activity).apply {
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
  }

  /** Pill button with a hairline border (secondary action). */
  private fun ghostButton(text: String): Button {
    return Button(activity).apply {
      this.text = text
      setTextColor(activity.resources.getColor(com.dshmobile.shell.R.color.text_primary, null))
      textSize = 14f
      background = activity.getDrawable(com.dshmobile.shell.R.drawable.pill_ghost)
      minHeight = (48 * d).toInt()
      isAllCaps = false
      stateListAnimator = null
      attachPressFeedback(this)
    }
  }

  private fun buildGuideView(): LinearLayout {
    val pad = (24 * d).toInt()
    val guide = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad, pad, pad, pad)
      gravity = android.view.Gravity.CENTER_HORIZONTAL
      visibility = View.GONE
      setBackgroundColor(activity.resources.getColor(com.dshmobile.shell.R.color.bg_guide, null))
    }

    // Brand block.
    val logo = android.widget.ImageView(activity).apply {
      setImageResource(com.dshmobile.shell.R.mipmap.ic_launcher)
      val size = (56 * d).toInt()
      layoutParams = LinearLayout.LayoutParams(size, size)
    }
    val brandTitle = TextView(activity).apply {
      text = activity.getString(R.string.app_name)
      setTextColor(activity.resources.getColor(com.dshmobile.shell.R.color.text_primary, null))
      textSize = 26f
      typeface = android.graphics.Typeface.DEFAULT_BOLD
      setPadding(0, (16 * d).toInt(), 0, 0)
    }
    val brandSub = TextView(activity).apply {
      text = activity.getString(R.string.guide_brand_subtitle)
      setTextColor(activity.resources.getColor(com.dshmobile.shell.R.color.text_secondary, null))
      textSize = 13f
      setPadding(0, (4 * d).toInt(), 0, 0)
    }
    val brand = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      gravity = android.view.Gravity.CENTER_HORIZONTAL
      setPadding(0, (72 * d).toInt(), 0, 0)
    }
    brand.addView(logo)
    brand.addView(brandTitle)
    brand.addView(brandSub)

    // Status card.
    val statusDot = View(activity).apply {
      background = activity.getDrawable(com.dshmobile.shell.R.drawable.status_dot)
      val size = (8 * d).toInt()
      layoutParams = LinearLayout.LayoutParams(size, size)
    }
    val statusTitle = TextView(activity).apply {
      setTextColor(activity.resources.getColor(com.dshmobile.shell.R.color.text_primary, null))
      textSize = 15f
      typeface = android.graphics.Typeface.DEFAULT_BOLD
      setPadding((8 * d).toInt(), 0, 0, 0)
    }
    engineStatus = statusTitle
    val statusRow = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = android.view.Gravity.CENTER_VERTICAL
    }
    statusRow.addView(statusDot)
    statusRow.addView(statusTitle)
    val detail = TextView(activity).apply {
      setTextColor(activity.resources.getColor(com.dshmobile.shell.R.color.text_secondary, null))
      textSize = 12f
      typeface = android.graphics.Typeface.MONOSPACE
      setPadding(0, (10 * d).toInt(), 0, 0)
      maxLines = 3
      ellipsize = android.text.TextUtils.TruncateAt.END
    }
    statusDetail = detail
    val progress = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
      isIndeterminate = true
      progressTintList = android.content.res.ColorStateList.valueOf(0xFF4D6BFE.toInt())
      progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE8EDFF.toInt())
      visibility = View.GONE
      val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (4 * d).toInt())
      lp.topMargin = (16 * d).toInt()
      layoutParams = lp
    }
    progressBar = progress
    val cardBody = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
    }
    cardBody.addView(statusRow)
    cardBody.addView(detail)
    cardBody.addView(progress)
    val errorDetail = TextView(activity).apply {
      setTextColor(activity.resources.getColor(com.dshmobile.shell.R.color.error, null))
      textSize = 12f
      typeface = android.graphics.Typeface.MONOSPACE
      setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
      maxLines = 4
      ellipsize = android.text.TextUtils.TruncateAt.END
    }
    errorText = errorDetail
    val errorBlock = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      background = activity.getDrawable(com.dshmobile.shell.R.drawable.inset_bg)
      visibility = View.GONE
      val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      lp.topMargin = (16 * d).toInt()
      layoutParams = lp
    }
    errorBlock.addView(errorDetail)
    this.errorBlock = errorBlock
    val card = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      background = activity.getDrawable(com.dshmobile.shell.R.drawable.card_bg)
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      ).apply {
        topMargin = (40 * d).toInt()
        leftMargin = (8 * d).toInt()
        rightMargin = (8 * d).toInt()
      }
    }
    card.addView(cardBody)
    card.addView(errorBlock)

    // Actions: one primary (launch / retry) + secondary utilities.
    val primary = accentButton(activity.getString(R.string.button_launch_engine)).apply {
      visibility = View.GONE
      setOnClickListener { onPrimaryAction() }
    }
    primaryButton = primary
    val update = ghostButton(activity.getString(R.string.button_check_update)).apply {
      setOnClickListener { onCheckUpdate { status -> showGuideStatus(status, null, true) } }
    }
    val copyLog = ghostButton(activity.getString(R.string.button_copy_log)).apply {
      setOnClickListener { onCopyLog() }
    }
    val back = ghostButton(activity.getString(R.string.button_back_to_harness)).apply {
      visibility = View.GONE
      setOnClickListener { onBackToHarness() }
    }
    backButton = back
    val actions = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (32 * d).toInt()
        leftMargin = (8 * d).toInt()
        rightMargin = (8 * d).toInt()
      }
    }
    actions.addView(primary)
    actions.addView(update)
    actions.addView(copyLog)
    actions.addView(back)
    repeat(actions.childCount - 1) { i ->
      (actions.getChildAt(i).layoutParams as LinearLayout.LayoutParams).bottomMargin = (10 * d).toInt()
    }

    val spacer = View(activity).apply {
      layoutParams = LinearLayout.LayoutParams(0, 0).apply { weight = 1f }
    }
    guide.addView(brand)
    guide.addView(buildStepIndicator())
    guide.addView(spacer)
    guide.addView(card)
    guide.addView(actions)
    return guide
  }

  /** Horizontal three-step indicator (runtime → container → launch). */
  private fun buildStepIndicator(): LinearLayout {
    val names = listOf(
      activity.getString(R.string.step_runtime),
      activity.getString(R.string.step_container),
      activity.getString(R.string.step_launch),
    )
    val dots = arrayOfNulls<TextView>(3)
    val labels = arrayOfNulls<TextView>(3)
    val row = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = android.view.Gravity.CENTER_HORIZONTAL
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (36 * d).toInt()
        leftMargin = (24 * d).toInt()
        rightMargin = (24 * d).toInt()
      }
    }
    val circleSize = (28 * d).toInt()
    for (i in 0..2) {
      val dot = TextView(activity).apply {
        textSize = 14f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = android.view.Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
      }
      dots[i] = dot
      val label = TextView(activity).apply {
        text = names[i]
        textSize = 11f
        setTextColor(activity.resources.getColor(com.dshmobile.shell.R.color.text_secondary, null))
        gravity = android.view.Gravity.CENTER
        setPadding(0, (6 * d).toInt(), 0, 0)
      }
      labels[i] = label
      val cell = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
      }
      cell.addView(dot)
      cell.addView(label)
      row.addView(cell)
      if (i < 2) {
        val line = View(activity).apply {
          background = activity.getDrawable(com.dshmobile.shell.R.drawable.divider)
          val lp = LinearLayout.LayoutParams(0, (1 * d).toInt())
          lp.weight = 1f
          lp.topMargin = (circleSize / 2 - (1 * d).toInt()).toInt()
          layoutParams = lp
        }
        row.addView(line)
      }
    }
    stepDots = dots.map { it!! }.toTypedArray()
    stepLabels = labels.map { it!! }.toTypedArray()
    return row
  }

  /** Thin cold-start bar overlaying the Harness: pulse dot + status, taps to
   *  open the full-screen guide. */
  private lateinit var topStatusLabel: TextView

  private fun buildTopStatusBar(): LinearLayout {
    val dot = View(activity).apply {
      background = activity.getDrawable(com.dshmobile.shell.R.drawable.status_dot)
      val size = (8 * d).toInt()
      layoutParams = LinearLayout.LayoutParams(size, size)
    }
    topPulseDot = dot
    val label = TextView(activity).apply {
      setTextColor(activity.resources.getColor(com.dshmobile.shell.R.color.text_primary, null))
      textSize = 13f
      typeface = android.graphics.Typeface.DEFAULT_BOLD
      setPadding((10 * d).toInt(), 0, 0, 0)
    }
    topStatusLabel = label
    val bar = LinearLayout(activity).apply {
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
