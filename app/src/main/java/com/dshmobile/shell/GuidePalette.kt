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