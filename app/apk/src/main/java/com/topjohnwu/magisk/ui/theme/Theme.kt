package com.topjohnwu.magisk.ui.theme

import android.app.Activity
import android.graphics.Color
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Config

object Theme {
    private fun applyAccentResources(activity: Activity) {
        val accent = Config.accentColor or Color.BLACK
        DynamicColors.applyToActivityIfAvailable(
            activity,
            DynamicColorsOptions.Builder()
                .setContentBasedSource(accent)
                .build(),
        )
    }

    fun apply(activity: Activity) {
        activity.setTheme(R.style.ThemeFoundationMD2)
        activity.theme.applyStyle(R.style.ThemeOverlay_Reisenless_Accent, true)
        applyAccentResources(activity)
    }

    fun applyOverlays(activity: Activity) {
        activity.theme.applyStyle(R.style.ThemeOverlay_Reisenless_Accent, true)
        applyAccentResources(activity)
    }
}
