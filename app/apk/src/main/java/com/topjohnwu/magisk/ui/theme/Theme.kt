package com.topjohnwu.magisk.ui.theme

import android.app.Activity
import android.graphics.Color
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Config

object Theme {
    const val COLOR_COUNT = 8

    private val colors = intArrayOf(
        Color.rgb(201, 91, 200),
        Color.rgb(126, 87, 194),
        Color.rgb(78, 175, 245),
        Color.rgb(104, 161, 127),
        Color.rgb(242, 185, 13),
        Color.rgb(219, 115, 102),
        Color.rgb(0, 150, 136),
        Color.rgb(96, 125, 139),
    )

    private val accentStyles = intArrayOf(
        R.style.ThemeOverlay_Reisenless_Accent_Pink,
        R.style.ThemeOverlay_Reisenless_Accent_Purple,
        R.style.ThemeOverlay_Reisenless_Accent_Blue,
        R.style.ThemeOverlay_Reisenless_Accent_Green,
        R.style.ThemeOverlay_Reisenless_Accent_Amber,
        R.style.ThemeOverlay_Reisenless_Accent_Red,
        R.style.ThemeOverlay_Reisenless_Accent_Teal,
        R.style.ThemeOverlay_Reisenless_Accent_Gray,
    )

    fun apply(activity: Activity) {
        activity.setTheme(R.style.ThemeFoundationMD2)
        applyOverlays(activity)
    }

    fun applyOverlays(activity: Activity) {
        activity.theme.applyStyle(accentStyles[nearestColorIndex(Config.accentColor)], true)
    }

    fun nearestColorIndex(color: Int): Int {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return colors.indices.minBy { index ->
            val candidate = colors[index]
            val dr = red - Color.red(candidate)
            val dg = green - Color.green(candidate)
            val db = blue - Color.blue(candidate)
            dr * dr + dg * dg + db * db
        }
    }
}
