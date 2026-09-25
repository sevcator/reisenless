package com.topjohnwu.magisk.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.model.ColorMode
import com.topjohnwu.magisk.core.utils.LocaleSetting
import com.topjohnwu.magisk.ui.ThemeState
import com.topjohnwu.magisk.ui.component.SettingsArrow
import com.topjohnwu.magisk.ui.component.SettingsDropdown
import com.topjohnwu.magisk.ui.component.SettingsSwitch
import com.topjohnwu.magisk.ui.component.SettingsSwitchAction
import com.topjohnwu.magisk.ui.component.SmallTitle
import com.topjohnwu.magisk.ui.component.verticalScrollbar
import com.topjohnwu.magisk.ui.hideapps.HideAppsRootClient
import com.topjohnwu.magisk.core.R as CoreR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val scrollState = rememberScrollState()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreR.string.settings)) },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(padding)
                .verticalScrollbar(scrollState, contentPadding = PaddingValues(bottom = 88.dp))
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp)
                .padding(bottom = 88.dp)
        ) {
            CustomizationSection(viewModel = viewModel)
            if (Info.env.isActive) {
                Spacer(Modifier.height(12.dp))
                ReisenlessSection(viewModel = viewModel)
                Spacer(Modifier.height(12.dp))
                UdongeSection(viewModel = viewModel)
            }
            if (Info.showSuperUser) {
                Spacer(Modifier.height(12.dp))
                SuperuserSection(viewModel = viewModel)
            }
        }
    }
}

// --- Customization ---

@Composable
private fun CustomizationSection(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    SmallTitle(text = stringResource(CoreR.string.settings_customization))
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        if (LocaleSetting.useLocaleManager) {
            val locale = LocaleSetting.instance.appLocale
            val summary = locale?.getDisplayName(locale) ?: stringResource(CoreR.string.system_default)
            SettingsArrow(
                title = stringResource(CoreR.string.language),
                summary = summary,
                onClick = {
                    context.startActivity(LocaleSetting.localeSettingsIntent)
                }
            )
        } else {
            val names = remember { LocaleSetting.available.names }
            val tags = remember { LocaleSetting.available.tags }
            var selectedIndex by remember {
                mutableIntStateOf(tags.indexOf(Config.locale).coerceAtLeast(0))
            }
            SettingsDropdown(
                title = stringResource(CoreR.string.language),
                items = names.toList(),
                selectedIndex = selectedIndex,
                onSelectedIndexChange = { index ->
                    selectedIndex = index
                    Config.locale = tags[index]
                }
            )
        }

        // Color Mode
        val isDynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val resources = LocalResources.current
        val rawColorModeEntries = remember {
            resources.getStringArray(CoreR.array.color_mode).toList()
        }
        val colorModeEntries = remember(isDynamicColorSupported) {
            if (isDynamicColorSupported) {
                rawColorModeEntries
            } else {
                rawColorModeEntries.drop(3)
            }
        }
        var colorMode by remember { mutableIntStateOf(Config.colorMode) }
        val selectedIndex = ColorMode.fromValue(colorMode).toUiIndex(isDynamicColorSupported)

        SettingsDropdown(
            title = stringResource(CoreR.string.settings_color_mode),
            items = colorModeEntries,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = { index ->
                val newMode = ColorMode.fromUiIndex(index, isDynamicColorSupported)
                colorMode = newMode.value
                Config.colorMode = newMode.value
                ThemeState.colorMode = newMode.value
            }
        )
    }
}

// --- Reisenless ---

@Composable
private fun ReisenlessSection(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    SmallTitle(text = stringResource(CoreR.string.magisk))
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        // 1. Systemless Hosts (Button)
        SettingsArrow(
            title = stringResource(CoreR.string.settings_hosts_title),
            summary = stringResource(CoreR.string.settings_hosts_summary),
            onClick = { viewModel.createHosts() }
        )

        if (Const.Version.atLeast_24_0()) {
            // 2. Zygisk (Switch)
            var zygisk by remember { mutableStateOf(Config.zygisk) }
            SettingsSwitch(
                title = stringResource(CoreR.string.zygisk),
                summary = stringResource(
                    if (zygisk != Info.isZygiskEnabled) CoreR.string.reboot_apply_change
                    else CoreR.string.settings_zygisk_summary
                ),
                checked = zygisk,
                onCheckedChange = {
                    zygisk = it
                    Config.zygisk = it
                    viewModel.notifyZygiskChange()
                }
            )

            // 3. SuList (Switch)
            val suListEnabled by viewModel.suListEnabled.collectAsStateWithLifecycle()
            val suListBusy by viewModel.suListBusy.collectAsStateWithLifecycle()
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_sulist_title),
                summary = stringResource(CoreR.string.settings_sulist_summary),
                checked = suListEnabled,
                enabled = !suListBusy,
                onCheckedChange = { viewModel.toggleSuList(it) }
            )

            // 4. Configure SuList (Button)
            SettingsArrow(
                title = stringResource(CoreR.string.settings_sulist_config_title),
                summary = stringResource(CoreR.string.settings_sulist_config_summary),
                onClick = { viewModel.navigateToSuList() }
            )

            // 5. Hide Apps (Switch)
            val hideAppsEnabled by viewModel.hideAppsEnabled.collectAsStateWithLifecycle()
            SettingsSwitchAction(
                title = stringResource(CoreR.string.hide_apps_title),
                summary = stringResource(CoreR.string.hide_apps_summary),
                checked = hideAppsEnabled,
                onClick = viewModel::navigateToHideApps,
                onCheckedChange = viewModel::toggleHideApps,
            )
        }
    }
}

// --- Udonge ---

@Composable
private fun UdongeSection(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val udongeEnabled by viewModel.udongeEnabled.collectAsStateWithLifecycle()
    var eirinEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(udongeEnabled) {
        if (!udongeEnabled) eirinEnabled = false
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        SettingsSwitch(
            title = stringResource(CoreR.string.udonge_integrity_title),
            summary = stringResource(CoreR.string.udonge_version_label, remember { com.topjohnwu.magisk.core.Udonge.version() }) + " · " + stringResource(CoreR.string.udonge_integrity_summary),
            checked = udongeEnabled,
            onCheckedChange = { viewModel.toggleUdonge(it) },
        )
        SettingsSwitch(
            title = stringResource(CoreR.string.udonge_background_updates_title),
            summary = stringResource(CoreR.string.udonge_background_updates_summary),
            checked = eirinEnabled,
            enabled = udongeEnabled,
            onCheckedChange = { eirinEnabled = it },
        )
    }
}

// --- Superuser ---

@Composable
private fun SuperuserSection(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val resources = LocalResources.current

    SmallTitle(text = stringResource(CoreR.string.superuser))
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        // Tapjack (SDK < S)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            var tapjack by remember { mutableStateOf(Config.suTapjack) }
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_su_tapjack_title),
                summary = stringResource(CoreR.string.settings_su_tapjack_summary),
                checked = tapjack,
                onCheckedChange = {
                    tapjack = it
                    Config.suTapjack = it
                }
            )
        }

        // Authentication
        var suAuth by remember { mutableStateOf(Config.suAuth) }
        SettingsSwitch(
            title = stringResource(CoreR.string.settings_su_auth_title),
            summary = stringResource(
                if (Info.isDeviceSecure) CoreR.string.settings_su_auth_summary
                else CoreR.string.settings_su_auth_insecure
            ),
            checked = suAuth,
            enabled = Info.isDeviceSecure,
            onCheckedChange = { newValue ->
                viewModel.withAuth {
                    suAuth = newValue
                    Config.suAuth = newValue
                }
            }
        )

        // Access Mode
        val accessEntries = remember {
            resources.getStringArray(CoreR.array.su_access).toList()
        }
        var accessMode by remember { mutableIntStateOf(Config.rootMode) }
        SettingsDropdown(
            title = stringResource(CoreR.string.superuser_access),
            items = accessEntries,
            selectedIndex = accessMode,
            onSelectedIndexChange = {
                accessMode = it
                Config.rootMode = it
            }
        )

        // Multiuser Mode
        val multiuserEntries = remember {
            resources.getStringArray(CoreR.array.multiuser_mode).toList()
        }
        val multiuserDescriptions = remember {
            resources.getStringArray(CoreR.array.multiuser_summary).toList()
        }
        var multiuserMode by remember { mutableIntStateOf(Config.suMultiuserMode) }
        SettingsDropdown(
            title = stringResource(CoreR.string.multiuser_mode),
            summary = multiuserDescriptions.getOrElse(multiuserMode) { "" },
            items = multiuserEntries,
            selectedIndex = multiuserMode,
            enabled = Const.USER_ID == 0,
            onSelectedIndexChange = {
                multiuserMode = it
                Config.suMultiuserMode = it
            }
        )

        // Mount Namespace Mode
        val namespaceEntries = remember {
            resources.getStringArray(CoreR.array.namespace).toList()
        }
        val namespaceDescriptions = remember {
            resources.getStringArray(CoreR.array.namespace_summary).toList()
        }
        var mntNamespaceMode by remember { mutableIntStateOf(Config.suMntNamespaceMode) }
        SettingsDropdown(
            title = stringResource(CoreR.string.mount_namespace_mode),
            summary = namespaceDescriptions.getOrElse(mntNamespaceMode) { "" },
            items = namespaceEntries,
            selectedIndex = mntNamespaceMode,
            onSelectedIndexChange = {
                mntNamespaceMode = it
                Config.suMntNamespaceMode = it
            }
        )

        // Automatic Response
        val autoResponseEntries = remember {
            resources.getStringArray(CoreR.array.auto_response).toList()
        }
        var autoResponse by remember { mutableIntStateOf(Config.suAutoResponse) }
        SettingsDropdown(
            title = stringResource(CoreR.string.auto_response),
            items = autoResponseEntries,
            selectedIndex = autoResponse,
            onSelectedIndexChange = { newIndex ->
                val doIt = {
                    autoResponse = newIndex
                    Config.suAutoResponse = newIndex
                }
                if (Config.suAuth) viewModel.withAuth(doIt) else doIt()
            }
        )

        // Request Timeout
        val timeoutEntries = remember {
            resources.getStringArray(CoreR.array.request_timeout).toList()
        }
        val timeoutValues = remember { listOf(10, 15, 20, 30, 45, 60) }
        var timeoutIndex by remember {
            mutableIntStateOf(timeoutValues.indexOf(Config.suDefaultTimeout).coerceAtLeast(0))
        }
        SettingsDropdown(
            title = stringResource(CoreR.string.request_timeout),
            items = timeoutEntries,
            selectedIndex = timeoutIndex,
            onSelectedIndexChange = {
                timeoutIndex = it
                Config.suDefaultTimeout = timeoutValues[it]
            }
        )

        // SU Notification
        val notifEntries = remember {
            resources.getStringArray(CoreR.array.su_notification).toList()
        }
        var suNotification by remember { mutableIntStateOf(Config.suNotification) }
        SettingsDropdown(
            title = stringResource(CoreR.string.superuser_notification),
            items = notifEntries,
            selectedIndex = suNotification,
            onSelectedIndexChange = {
                suNotification = it
                Config.suNotification = it
            }
        )

        // Reauthenticate (SDK < O)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            var reAuth by remember { mutableStateOf(Config.suReAuth) }
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_su_reauth_title),
                summary = stringResource(CoreR.string.settings_su_reauth_summary),
                checked = reAuth,
                onCheckedChange = {
                    reAuth = it
                    Config.suReAuth = it
                }
            )
        }

        // Restrict (version >= 30.1)
        if (Const.Version.atLeast_30_1()) {
            var restrict by remember { mutableStateOf(Config.suRestrict) }
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_su_restrict_title),
                summary = stringResource(CoreR.string.settings_su_restrict_summary),
                checked = restrict,
                onCheckedChange = {
                    restrict = it
                    Config.suRestrict = it
                }
            )
        }
    }
}
