package com.topjohnwu.magisk.ui.settings

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat
import com.topjohnwu.magisk.core.BuildConfig
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.Udonge
import com.topjohnwu.magisk.ui.hideapps.HideAppsRootClient
import com.topjohnwu.magisk.core.isRunningAsStub
import com.topjohnwu.magisk.core.ktx.toast
import com.topjohnwu.magisk.core.tasks.AppMigration
import com.topjohnwu.magisk.core.utils.LocaleSetting
import com.topjohnwu.magisk.ui.ThemeState
import com.topjohnwu.magisk.ui.component.SettingsArrow
import com.topjohnwu.magisk.ui.component.SettingsDropdown
import com.topjohnwu.magisk.ui.component.SettingsSwitch
import com.topjohnwu.magisk.ui.component.SettingsSwitchAction
import com.topjohnwu.magisk.ui.component.SmallTitle
import com.topjohnwu.magisk.ui.component.rememberLoadingDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.topjohnwu.magisk.core.R as CoreR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
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
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 12.dp)
                .padding(bottom = 88.dp)
        ) {
            CustomizationSection(viewModel)
            Spacer(Modifier.height(12.dp))
            AppSettingsSection()
            if (Info.env.isActive) {
                Spacer(Modifier.height(12.dp))
                MagiskSection(viewModel)
                Spacer(Modifier.height(12.dp))
                UdongeSection()
            }
            if (Info.showSuperUser) {
                Spacer(Modifier.height(12.dp))
                SuperuserSection(viewModel)
            }
        }
    }
}



@Composable
private fun CustomizationSection(
    viewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadingDialog = rememberLoadingDialog()
    val isHidden = context.packageName != BuildConfig.APP_PACKAGE_NAME
    var showRestoreDialog by rememberSaveable { mutableStateOf(false) }

    if (showRestoreDialog) {
        RestoreAppDialog(
            onDismiss = { showRestoreDialog = false },
            onConfirm = {
                showRestoreDialog = false
                scope.launch {
                    val success = loadingDialog.withLoading {
                        AppMigration.restoreApp(context)
                    }
                    if (!success) context.toast(CoreR.string.failure, Toast.LENGTH_LONG)
                }
            }
        )
    }

    SmallTitle(text = stringResource(CoreR.string.settings_customization))
    Card(modifier = Modifier.fillMaxWidth()) {
        val resources = LocalResources.current
        val themeEntries = remember {
            resources.getStringArray(CoreR.array.theme_mode).toList()
        }
        var themeMode by remember {
            mutableIntStateOf(if (Config.darkTheme == Config.Value.THEME_DARK) 1 else 0)
        }
        SettingsDropdown(
            title = stringResource(CoreR.string.settings_theme_mode),
            items = themeEntries,
            selectedIndex = themeMode,
            onSelectedIndexChange = { index ->
                themeMode = index
                Config.darkTheme = if (index == 1) {
                    Config.Value.THEME_DARK
                } else {
                    Config.Value.THEME_LIGHT
                }
                ThemeState.darkTheme = Config.darkTheme
            }
        )

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

        var accentColor by remember { mutableIntStateOf(Config.accentColor) }
        RgbColorSetting(
            title = stringResource(CoreR.string.settings_accent_color),
            color = accentColor,
            onColorChange = { color ->
                accentColor = color
                Config.accentColor = color
                ThemeState.accentColor = color
            },
        )

        if (isRunningAsStub && ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            SettingsArrow(
                title = stringResource(CoreR.string.add_shortcut_title),
                summary = stringResource(CoreR.string.setting_add_shortcut_summary),
                onClick = { viewModel.requestAddShortcut() }
            )
        }

        var doh by remember { mutableStateOf(Config.doh) }
        SettingsSwitch(
            title = stringResource(CoreR.string.settings_doh_title),
            summary = stringResource(CoreR.string.settings_doh_description),
            checked = doh,
            onCheckedChange = {
                doh = it
                Config.doh = it
            }
        )

        if (isHidden) {
            SettingsArrow(
                title = stringResource(CoreR.string.settings_restore_app_title),
                summary = stringResource(CoreR.string.settings_restore_app_summary),
                onClick = { showRestoreDialog = true },
            )
        }
    }
}

@Composable
private fun RgbColorSetting(title: String, color: Int, onColorChange: (Int) -> Unit) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var red by rememberSaveable { mutableStateOf(android.graphics.Color.red(color).toString()) }
    var green by rememberSaveable { mutableStateOf(android.graphics.Color.green(color).toString()) }
    var blue by rememberSaveable { mutableStateOf(android.graphics.Color.blue(color).toString()) }

    fun parsedColor(): Int? {
        val channels = listOf(red, green, blue).map { it.toIntOrNull() ?: return null }
        if (channels.any { it !in 0..255 }) return null
        return android.graphics.Color.rgb(channels[0], channels[1], channels[2])
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("r" to red, "g" to green, "b" to blue).forEachIndexed { index, item ->
                            OutlinedTextField(
                                value = item.second,
                                onValueChange = { value ->
                                    when (index) {
                                        0 -> red = value.take(3)
                                        1 -> green = value.take(3)
                                        else -> blue = value.take(3)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                label = { Text(item.first) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                    }
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(Color(parsedColor() ?: color))
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    parsedColor()?.let(onColorChange)
                    if (parsedColor() != null) showDialog = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    SettingsArrow(
        title = title,
        summary = "r: ${android.graphics.Color.red(color)}   " +
            "g: ${android.graphics.Color.green(color)}   b: ${android.graphics.Color.blue(color)}",
        onClick = {
            red = android.graphics.Color.red(color).toString()
            green = android.graphics.Color.green(color).toString()
            blue = android.graphics.Color.blue(color).toString()
            showDialog = true
        },
    )
}



@Composable
private fun AppSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadingDialog = rememberLoadingDialog()
    val isHidden = context.packageName != BuildConfig.APP_PACKAGE_NAME
    var showHideDialog by rememberSaveable { mutableStateOf(false) }
    var backgroundUpdates by remember { mutableStateOf(Config.udongeBackgroundUpdates) }
    var backgroundModules by remember { mutableStateOf(Config.udongeBackgroundModules) }
    var backgroundKeyboxes by remember { mutableStateOf(Config.udongeBackgroundKeyboxes) }
    var draftBackgroundModules by remember { mutableStateOf(backgroundModules) }
    var draftBackgroundKeyboxes by remember { mutableStateOf(backgroundKeyboxes) }
    var showBackgroundTargets by rememberSaveable { mutableStateOf(false) }

    if (showBackgroundTargets) {
        AlertDialog(
            onDismissRequest = { showBackgroundTargets = false },
            title = { Text(stringResource(CoreR.string.udonge_background_updates_title)) },
            text = {
                Column {
                    BackgroundUpdateTarget(
                        title = stringResource(CoreR.string.udonge_background_updates_modules),
                        checked = draftBackgroundModules,
                        onCheckedChange = { draftBackgroundModules = it },
                    )
                    BackgroundUpdateTarget(
                        title = stringResource(CoreR.string.udonge_background_updates_keyboxes),
                        checked = draftBackgroundKeyboxes,
                        onCheckedChange = { draftBackgroundKeyboxes = it },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showBackgroundTargets = false
                    backgroundModules = draftBackgroundModules
                    backgroundKeyboxes = draftBackgroundKeyboxes
                    scope.launch(Dispatchers.IO) {
                        Udonge.setBackgroundUpdateTargets(
                            modules = draftBackgroundModules,
                            keyboxes = draftBackgroundKeyboxes,
                        )
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showBackgroundTargets = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showHideDialog) {
        HideAppDialog(
            onDismiss = { showHideDialog = false },
            onConfirm = {
                showHideDialog = false
                scope.launch {
                    val success = loadingDialog.withLoading {
                        AppMigration.patchAndHide(context)
                    }
                    if (!success) context.toast(CoreR.string.failure, Toast.LENGTH_LONG)
                }
            }
        )
    }

    SmallTitle(text = stringResource(CoreR.string.home_app_title))
    Card(modifier = Modifier.fillMaxWidth()) {
        if (!isHidden) {
            SettingsArrow(
                title = stringResource(CoreR.string.settings_hide_app_title),
                summary = stringResource(CoreR.string.settings_hide_app_summary),
                onClick = { showHideDialog = true },
            )
        }
        val selectedTargets = listOfNotNull(
            stringResource(CoreR.string.udonge_background_updates_modules)
                .takeIf { backgroundModules },
            stringResource(CoreR.string.udonge_background_updates_keyboxes)
                .takeIf { backgroundKeyboxes },
        ).joinToString(", ").ifEmpty {
            stringResource(CoreR.string.udonge_background_updates_none)
        }
        SettingsSwitchAction(
            title = stringResource(CoreR.string.udonge_background_updates_title),
            summary = selectedTargets,
            checked = backgroundUpdates,
            onClick = {
                draftBackgroundModules = backgroundModules
                draftBackgroundKeyboxes = backgroundKeyboxes
                showBackgroundTargets = true
            },
            onCheckedChange = { next ->
                backgroundUpdates = next
                scope.launch(Dispatchers.IO) {
                    if (!Udonge.setBackgroundUpdates(next)) backgroundUpdates = !next
                }
            },
        )
    }
}

@Composable
private fun BackgroundUpdateTarget(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = title, modifier = Modifier.padding(start = 12.dp, top = 12.dp))
    }
}



@Composable
private fun MagiskSection(viewModel: SettingsViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {

        SettingsArrow(
            title = stringResource(CoreR.string.settings_hosts_title),
            summary = stringResource(CoreR.string.settings_hosts_summary),
            onClick = { viewModel.createHosts() }
        )

        if (Const.Version.atLeast_24_0()) {

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

        }

        SettingsArrow(
            title = stringResource(CoreR.string.hide_apps_title),
            summary = stringResource(CoreR.string.hide_apps_summary),
            onClick = viewModel::navigateToHideApps,
        )

        val suListEnabled by viewModel.suListEnabled.collectAsState()
        SettingsSwitchAction(
            title = stringResource(CoreR.string.settings_sulist_title),
            summary = stringResource(CoreR.string.settings_sulist_summary),
            checked = suListEnabled,
            onClick = { viewModel.navigateToSuList() },
            onCheckedChange = { viewModel.toggleSuList(it) }
        )
    }
}

@Composable
private fun SuperuserSection(viewModel: SettingsViewModel) {
    val resources = LocalResources.current

    SmallTitle(text = stringResource(CoreR.string.superuser))
    Card(modifier = Modifier.fillMaxWidth()) {
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

        var suAuth by remember { mutableStateOf(Config.suAuth) }
        SettingsSwitch(
            title = stringResource(CoreR.string.settings_su_auth_title),
            summary = stringResource(
                if (Info.isDeviceSecure) CoreR.string.settings_su_auth_summary
                else CoreR.string.settings_su_auth_insecure
            ),
            checked = suAuth,
            enabled = Info.isDeviceSecure,
            onCheckedChange = { enabled ->
                viewModel.withAuth {
                    suAuth = enabled
                    Config.suAuth = enabled
                }
            }
        )

        val autoResponseEntries = remember {
            resources.getStringArray(CoreR.array.auto_response).toList()
        }
        var autoResponse by remember { mutableIntStateOf(Config.suAutoResponse) }
        SettingsDropdown(
            title = stringResource(CoreR.string.auto_response),
            items = autoResponseEntries,
            selectedIndex = autoResponse,
            onSelectedIndexChange = { selected ->
                val apply = {
                    autoResponse = selected
                    Config.suAutoResponse = selected
                }
                if (Config.suAuth) viewModel.withAuth(apply) else apply()
            }
        )

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

        val notificationEntries = remember {
            resources.getStringArray(CoreR.array.su_notification).toList()
        }
        var notification by remember { mutableIntStateOf(Config.suNotification) }
        SettingsDropdown(
            title = stringResource(CoreR.string.superuser_notification),
            items = notificationEntries,
            selectedIndex = notification,
            onSelectedIndexChange = {
                notification = it
                Config.suNotification = it
            }
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            var reauthenticate by remember { mutableStateOf(Config.suReAuth) }
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_su_reauth_title),
                summary = stringResource(CoreR.string.settings_su_reauth_summary),
                checked = reauthenticate,
                onCheckedChange = {
                    reauthenticate = it
                    Config.suReAuth = it
                }
            )
        }
    }
}

@Composable
private fun UdongeSection() {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(Config.udongeEnabled) }
    var showKeyboxes by rememberSaveable { mutableStateOf(false) }
    var keyboxUrls by rememberSaveable { mutableStateOf(Config.udongeKeyboxUrls) }
    var romHiding by remember { mutableStateOf(Config.udongeRomHidingEnabled) }

    if (showKeyboxes) {
        AlertDialog(
            onDismissRequest = { showKeyboxes = false },
            title = { Text(stringResource(CoreR.string.udonge_keybox_list_title)) },
            text = {
                OutlinedTextField(
                    value = keyboxUrls,
                    onValueChange = { keyboxUrls = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 10,
                    label = { Text(stringResource(CoreR.string.udonge_keybox_hint)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showKeyboxes = false
                    scope.launch(Dispatchers.IO) {
                        if (Udonge.setKeyboxUrls(keyboxUrls)) Udonge.refreshKeyboxes()
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showKeyboxes = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    SmallTitle(text = stringResource(CoreR.string.udonge))
    Card(modifier = Modifier.fillMaxWidth()) {
        SettingsSwitch(
            title = stringResource(CoreR.string.udonge_integrity_title),
            summary = stringResource(CoreR.string.udonge_integrity_summary),
            checked = enabled,
            onCheckedChange = { next ->
                enabled = next
                scope.launch(Dispatchers.IO) {
                    if (!Udonge.setEnabled(next)) enabled = !next
                }
            },
        )
        SettingsArrow(
            title = stringResource(CoreR.string.udonge_keybox_list_title),
            summary = stringResource(CoreR.string.udonge_keybox_list_summary),
            onClick = { showKeyboxes = true },
        )
        SettingsSwitch(
            title = stringResource(CoreR.string.udonge_rom_keywords_title),
            summary = stringResource(CoreR.string.udonge_rom_keywords_summary),
            checked = romHiding,
            onCheckedChange = { next ->
                romHiding = next
                scope.launch(Dispatchers.IO) {
                    if (Udonge.setRomHidingEnabled(next)) {
                        syncRomKeywordsHideApps(if (next) Udonge.DEFAULT_ROM_KEYWORDS else "")
                    } else {
                        romHiding = !next
                    }
                }
            },
        )
    }
}



private fun syncRomKeywordsHideApps(keywords: String) {
    HideAppsRootClient.syncRomKeywordsHideApps(keywords)
}



@Composable
private fun HideAppDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreR.string.settings_hide_app_title)) },
        text = { Text(stringResource(CoreR.string.hide_app_randomize_confirmation)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.size(4.dp))
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.size(4.dp))
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun RestoreAppDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreR.string.settings_restore_app_title)) },
        text = { Text(stringResource(CoreR.string.restore_app_confirmation)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.size(4.dp))
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.size(4.dp))
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
