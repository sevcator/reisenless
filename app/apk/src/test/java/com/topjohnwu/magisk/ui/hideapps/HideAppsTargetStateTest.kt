package com.topjohnwu.magisk.ui.hideapps

import com.topjohnwu.magisk.hideapps.HideAppsConfig
import com.topjohnwu.magisk.hideapps.HideAppsRule
import org.junit.Assert.assertEquals
import org.junit.Test

class HideAppsTargetStateTest {
    private val target = "com.example.target"
    private val caller = "com.example.viewer"
    private val global = HideAppsConfig(enabled = true, hiddenPackages = setOf(target))

    @Test fun globallyHiddenTargetIsCheckedAndLockedWithoutAnIndividualRule() {
        assertEquals(HideAppsTargetState(true, false),
            hideAppsTargetState(global, caller, target, false, null))
    }

    @Test fun globalEditorCanStillUnhideTheTarget() {
        assertEquals(HideAppsTargetState(true, true),
            hideAppsTargetState(global, HideAppsViewModel.ALL_APPS_CALLER, target, false,
                HideAppsRule(packages = global.hiddenPackages)))
    }

    @Test fun disabledGlobalRuleDoesNotLockIndividualSelection() {
        assertEquals(HideAppsTargetState(false, true),
            hideAppsTargetState(global.copy(enabled = false), caller, target, false, HideAppsRule()))
    }

    @Test fun exemptViewerDoesNotInheritHiddenSelection() {
        assertEquals(HideAppsTargetState(false, true),
            hideAppsTargetState(global.copy(viewerWhitelist = setOf(caller)), caller,
                target, false, HideAppsRule()))
    }
}
