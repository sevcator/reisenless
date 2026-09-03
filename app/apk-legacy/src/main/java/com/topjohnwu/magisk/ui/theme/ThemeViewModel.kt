package com.topjohnwu.magisk.ui.theme

import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.ktx.activity
import com.topjohnwu.magisk.core.utils.TextHolder
import com.topjohnwu.magisk.core.utils.asText
import com.topjohnwu.magisk.events.RecreateEvent
import com.topjohnwu.magisk.ui.settings.BaseSettingsItem
import com.topjohnwu.magisk.view.MagiskDialog
import com.topjohnwu.magisk.core.R as CoreR

object ThemeModeSetting : BaseSettingsItem.Selector() {
    override val title = CoreR.string.settings_theme_mode.asText()
    override val entryRes = CoreR.array.theme_mode
    override var value
        get() = if (Config.darkTheme == Config.Value.THEME_DARK) 1 else 0
        set(value) {
            Config.darkTheme = if (value == 1) {
                Config.Value.THEME_DARK
            } else {
                Config.Value.THEME_LIGHT
            }
        }
}

private object AccentColorSetting : BaseSettingsItem.Blank() {

    private var value: Int
        get() = Config.accentColor
        set(value) { Config.accentColor = value }

    override val title = CoreR.string.settings_accent_color.asText()

    override val description = object : TextHolder() {
        override fun getText(resources: Resources): String {
            val color = value
            return "r: ${Color.red(color)}   g: ${Color.green(color)}   b: ${Color.blue(color)}"
        }
    }

    override fun onPressed(view: View, handler: Handler) {
        handler.onItemPressed(view, this) {
            val content = LayoutInflater.from(view.context).inflate(R.layout.dialog_rgb_color, null)
            val inputs = listOf<EditText>(
                content.findViewById(R.id.color_red),
                content.findViewById(R.id.color_green),
                content.findViewById(R.id.color_blue),
            )
            val preview = content.findViewById<View>(R.id.color_preview)
            val selected = value
            inputs[0].setText(Color.red(selected).toString())
            inputs[1].setText(Color.green(selected).toString())
            inputs[2].setText(Color.blue(selected).toString())

            fun inputColor(): Int? {
                val channels = inputs.map { it.text.toString().toIntOrNull() ?: return null }
                if (channels.any { it !in 0..255 }) return null
                return Color.rgb(channels[0], channels[1], channels[2])
            }

            fun updatePreview() {
                val color = inputColor() ?: return
                preview.backgroundTintList = ColorStateList.valueOf(color)
            }

            inputs.forEach { it.doAfterTextChanged { updatePreview() } }
            updatePreview()

            MagiskDialog(view.activity).apply {
                setTitle(title.getText(view.resources))
                setView(content)
                setButton(MagiskDialog.ButtonType.POSITIVE) {
                    text = android.R.string.ok
                    onClick {
                        val color = inputColor()
                        if (color == null) {
                            doNotDismiss = true
                            return@onClick
                        }
                        doNotDismiss = false
                        if (value != color) {
                            value = color
                            notifyPropertyChanged(BR.description)
                            handler.onItemAction(view, AccentColorSetting)
                        }
                    }
                }
                setButton(MagiskDialog.ButtonType.NEGATIVE) {
                    text = android.R.string.cancel
                }
            }.show()
        }
    }
}

class ThemeViewModel : BaseViewModel(), BaseSettingsItem.Handler {

    val themeMode: BaseSettingsItem = ThemeModeSetting
    val accentColor: BaseSettingsItem = AccentColorSetting

    override fun onItemPressed(
        view: View,
        item: BaseSettingsItem,
        doAction: () -> Unit,
    ) {
        doAction()
    }

    override fun onItemAction(view: View, item: BaseSettingsItem) {
        RecreateEvent().publish()
    }
}
