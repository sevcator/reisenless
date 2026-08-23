package com.topjohnwu.magisk.ui.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.di.ServiceLocator
import com.topjohnwu.magisk.core.ktx.activity
import com.topjohnwu.magisk.core.repository.ModuleRepository
import com.topjohnwu.magisk.core.repository.TrustedRepository
import com.topjohnwu.magisk.view.MagiskDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.topjohnwu.magisk.core.R as CoreR

internal class RepositorySettingsDialog(private val anchor: View) {
    private val context = anchor.context
    private val activity = anchor.activity as AppCompatActivity
    private val density = context.resources.displayMetrics.density
    private val repository = ModuleRepository(ServiceLocator.networkService)
    private val configuredContainer = verticalLayout()
    private val trustedContainer = verticalLayout().apply { visibility = View.GONE }
    private var configured = readConfigured().toMutableList()
    private var trusted = emptyList<TrustedRepository>()
    private var showTrusted = false
    private var loadJob: Job? = null

    fun show() {
        val customUrl = EditText(context).apply {
            hint = context.getString(CoreR.string.repository_mmrl_url_hint)
            maxLines = 2
        }
        val add = actionButton(CoreR.string.repository_add).apply {
            setOnClickListener {
                val value = customUrl.text.toString().trim()
                if (ModuleRepository.normalizeRepositoryUrl(value) == null) {
                    customUrl.error = context.getString(CoreR.string.repository_mmrl_url_invalid)
                } else {
                    updateConfigured(configured + value)
                    customUrl.text.clear()
                }
            }
        }
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(customUrl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(add)
        }
        val trustedButton = actionButton(CoreR.string.repository_add_trusted).apply {
            setOnClickListener {
                showTrusted = !showTrusted
                trustedContainer.visibility = if (showTrusted) View.VISIBLE else View.GONE
                if (showTrusted) renderTrusted()
            }
        }
        val content = verticalLayout().apply {
            val padding = dp(16)
            setPadding(padding)
            addView(inputRow)
            addView(trustedButton, matchWidthParams(top = dp(8)))
            addView(trustedContainer, matchWidthParams(top = dp(8)))
            addView(sectionTitle(CoreR.string.repository_configured), matchWidthParams(top = dp(16)))
            addView(configuredContainer)
        }
        val scroll = ScrollView(context).apply {
            minimumHeight = (context.resources.displayMetrics.heightPixels * 0.68f).toInt()
            addView(content)
        }

        renderConfigured()
        loadJob = activity.lifecycleScope.launch {
            trusted = repository.loadTrustedRepositories()
            renderConfigured()
            if (showTrusted) renderTrusted()
        }

        MagiskDialog(activity).apply {
            setTitle(CoreR.string.repository_searcher)
            setView(scroll)
            setButton(MagiskDialog.ButtonType.NEGATIVE) { text = android.R.string.cancel }
            setOnDismissListener { loadJob?.cancel() }
        }.show()
    }

    private fun renderConfigured() {
        configuredContainer.removeAllViews()
        if (configured.isEmpty()) {
            configuredContainer.addView(bodyText(context.getString(CoreR.string.repository_none_configured)))
            return
        }
        configured.forEach { source ->
            val known = trusted.firstOrNull { sameRepository(source, it.url) }
            configuredContainer.addView(
                repositoryCard(
                    name = known?.name ?: source.substringAfter("://").substringBefore('/'),
                    url = source,
                    description = known?.description.orEmpty(),
                    trusted = known != null,
                    actionText = CoreR.string.module_state_remove,
                    removeAction = true,
                    onAction = { updateConfigured(configured - source) },
                ),
                matchWidthParams(top = dp(8)),
            )
        }
    }

    private fun renderTrusted() {
        trustedContainer.removeAllViews()
        trusted.forEach { item ->
            val installed = configured.any { sameRepository(it, item.url) }
            val description = item.description.ifBlank {
                item.modulesCount?.let { context.getString(CoreR.string.repository_module_count, it) }.orEmpty()
            }
            trustedContainer.addView(
                repositoryCard(
                    name = item.name,
                    url = item.url,
                    description = description,
                    trusted = true,
                    actionText = if (installed) {
                        CoreR.string.module_state_remove
                    } else {
                        CoreR.string.repository_add
                    },
                    removeAction = installed,
                    onAction = {
                        if (installed) {
                            updateConfigured(configured.filterNot { sameRepository(it, item.url) })
                        } else {
                            updateConfigured(configured + item.url)
                        }
                    },
                ),
                matchWidthParams(top = dp(8)),
            )
        }
    }

    private fun repositoryCard(
        name: String,
        url: String,
        description: String,
        trusted: Boolean,
        actionText: Int,
        removeAction: Boolean,
        onAction: () -> Unit,
    ): View {
        val card = MaterialCardView(context).apply {
            radius = dp(14).toFloat()
            strokeWidth = dp(1)
            strokeColor = MaterialColors.getColor(
                this,
                MaterialR.attr.colorOutline,
                Color.GRAY,
            )
        }
        val body = verticalLayout().apply { setPadding(dp(16)) }
        val title = TextView(context).apply {
            setTextAppearance(R.style.AppearanceFoundation_Body)
            text = if (trusted) "$name  ✓" else name
        }
        val address = bodyText(url).apply { maxLines = 2 }
        body.addView(title)
        body.addView(address)
        if (description.isNotBlank()) {
            body.addView(bodyText(description).apply { maxLines = 3 }, matchWidthParams(top = dp(4)))
        }
        body.addView(View(context).apply {
            setBackgroundColor(
                MaterialColors.getColor(this, MaterialR.attr.colorOutline, Color.GRAY)
            )
        }, matchWidthParams(top = dp(10), height = dp(1)))

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(actionButton(CoreR.string.repository_web).apply {
            setOnClickListener {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        })
        buttons.addView(actionButton(actionText, removeAction).apply {
            setOnClickListener { onAction() }
        })
        body.addView(buttons, matchWidthParams(top = dp(8)))
        card.addView(body)
        return card
    }

    private fun actionButton(textRes: Int, destructive: Boolean = false) =
        MaterialButton(context).apply {
            setText(textRes)
            isAllCaps = false
            val colorAttr = if (destructive) MaterialR.attr.colorErrorContainer else MaterialR.attr.colorSecondaryContainer
            backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(this, colorAttr, Color.LTGRAY)
            )
        }

    private fun sectionTitle(textRes: Int) = TextView(context).apply {
        setText(textRes)
        setTextAppearance(R.style.AppearanceFoundation_Body)
        setTextColor(MaterialColors.getColor(this, R.attr.colorPrimary, Color.MAGENTA))
    }

    private fun bodyText(value: String) = TextView(context).apply {
        text = value
        setTextAppearance(R.style.AppearanceFoundation_Caption_Variant)
    }

    private fun verticalLayout() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun matchWidthParams(top: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
            topMargin = top
        }

    private fun updateConfigured(values: List<String>) {
        configured = values.distinctBy {
            ModuleRepository.normalizeRepositoryUrl(it)?.lowercase() ?: it.lowercase()
        }.toMutableList()
        Config.moduleRepositoryUrls = configured.joinToString("\n")
        renderConfigured()
        if (showTrusted) renderTrusted()
    }

    private fun readConfigured() = Config.moduleRepositoryUrls.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()

    private fun sameRepository(first: String, second: String): Boolean =
        ModuleRepository.normalizeRepositoryUrl(first)
            ?.equals(ModuleRepository.normalizeRepositoryUrl(second), ignoreCase = true) == true

    private fun dp(value: Int) = (value * density).toInt()
}
