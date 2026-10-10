package com.topjohnwu.magisk.ui.hideapps

import com.topjohnwu.magisk.hideapps.HideAppsConfig
import com.topjohnwu.magisk.hideapps.HideAppsRule

internal data class HideAppsTargetState(val checked: Boolean, val enabled: Boolean)

internal fun hideAppsTargetState(
    config: HideAppsConfig,
    caller: String?,
    target: String,
    isSystemApp: Boolean,
    rule: HideAppsRule?,
): HideAppsTargetState {
    val inherited = caller != HideAppsViewModel.ALL_APPS_CALLER && config.enabled &&
        config.shouldHide(caller, target, isSystemApp)
    return HideAppsTargetState(
        checked = inherited || target in rule?.packages.orEmpty(),
        enabled = rule != null && !inherited,
    )
}
