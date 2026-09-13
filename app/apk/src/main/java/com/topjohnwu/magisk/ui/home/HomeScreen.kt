package com.topjohnwu.magisk.ui.home

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.BuildConfig
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.ktx.reboot
import com.topjohnwu.magisk.core.ktx.toast
import com.topjohnwu.magisk.core.tasks.MagiskInstaller
import com.topjohnwu.magisk.ui.component.rememberLoadingDialog
import com.topjohnwu.magisk.ui.component.verticalScrollbar
import com.topjohnwu.magisk.ui.flash.FlashUtils
import com.topjohnwu.magisk.ui.install.InstallBottomSheet
import com.topjohnwu.magisk.ui.install.InstallViewModel
import kotlinx.coroutines.launch
import com.topjohnwu.magisk.core.R as CoreR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    installVm: InstallViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val scope = rememberCoroutineScope()
    val loadingDialog = rememberLoadingDialog()

    var showUninstallDialog by rememberSaveable { mutableStateOf(false) }
    var showEnvFixDialog by rememberSaveable { mutableStateOf(false) }
    var showInstallSheet by rememberSaveable { mutableStateOf(false) }
    var envFixCode by remember { mutableIntStateOf(0) }

    LaunchedEffect(uiState.showUninstall) {
        if (uiState.showUninstall) {
            showUninstallDialog = true
            viewModel.onUninstallConsumed()
        }
    }
    LaunchedEffect(uiState.envFixCode) {
        if (uiState.envFixCode != 0) {
            envFixCode = uiState.envFixCode
            showEnvFixDialog = true
            viewModel.onEnvFixConsumed()
        }
    }
    if (showUninstallDialog) {
        UninstallComposableDialog(
            onDismiss = { showUninstallDialog = false },
            onCompleteUninstall = {
                showUninstallDialog = false
                val intent = Intent(context, context.javaClass).apply {
                    action = FlashUtils.INTENT_FLASH
                    putExtra(FlashUtils.EXTRA_FLASH_ACTION, Const.Value.UNINSTALL)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                context.startActivity(intent)
            },
            onRestoreImage = {
                showUninstallDialog = false
                scope.launch {
                    val success = loadingDialog.withLoading {
                        MagiskInstaller.Restore().exec()
                    }
                    context.toast(
                        if (success) CoreR.string.restore_done else CoreR.string.restore_fail,
                        Toast.LENGTH_SHORT
                    )
                }
            }
        )
    }

    if (showEnvFixDialog) {
        EnvFixComposableDialog(
            code = envFixCode,
            onDismiss = { showEnvFixDialog = false },
            onNavigateInstall = {
                showEnvFixDialog = false
                showInstallSheet = true
            },
            onFixEnv = {
                showEnvFixDialog = false
                scope.launch {
                    val success = loadingDialog.withLoading {
                        MagiskInstaller.FixEnv().exec()
                    }
                    context.toast(
                        if (success) CoreR.string.reboot_delay_toast else CoreR.string.setup_fail,
                        Toast.LENGTH_LONG
                    )
                    if (success) {
                        @Suppress("DEPRECATION")
                        Handler(Looper.getMainLooper())
                            .postDelayed({ reboot() }, 5000)
                    }
                }
            }
        )
    }

    val scrollState = rememberScrollState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreR.string.section_home)) },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { showInstallSheet = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = stringResource(CoreR.string.install),
                        )
                    }
                    if (Info.env.isActive) {
                        IconButton(onClick = { viewModel.onDeletePressed() }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(CoreR.string.uninstall_magisk_title),
                            )
                        }
                    }
                    if (Info.isRooted) {
                        RebootButton()
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(padding)
                .verticalScrollbar(scrollState, contentPadding = PaddingValues(top = 12.dp, bottom = 88.dp))
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uiState.magiskState == HomeViewModel.State.OUTDATED) {
                OutdatedRootCard(onReinstall = { showInstallSheet = true })
            }
            StatusCard()
        }
    }

    InstallBottomSheet(
        show = showInstallSheet,
        onDismiss = { showInstallSheet = false },
        installVm = installVm,
    )
}

@Composable
private fun OutdatedRootCard(
    onReinstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(CoreR.string.root_outdated_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(CoreR.string.root_outdated_msg),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onReinstall) {
                Text(stringResource(CoreR.string.root_reinstall))
            }
        }
    }
}

@Composable
private fun RebootButton(
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var safeModeEnabled by remember { mutableIntStateOf(Config.bootloop) }

    val showUserspace = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        context.getSystemService<PowerManager>()?.isRebootingUserspaceSupported == true
    val showSafeMode = Const.Version.atLeast_28_0()

    val items = buildList {
        add(RebootOption(CoreR.string.reboot) { reboot() })
        if (showUserspace) {
            add(RebootOption(CoreR.string.reboot_userspace) { reboot("userspace") })
        }
        add(RebootOption(CoreR.string.reboot_recovery) { reboot("recovery") })
        add(RebootOption(CoreR.string.reboot_bootloader) { reboot("bootloader") })
        add(RebootOption(CoreR.string.reboot_download) { reboot("download") })
        add(RebootOption(CoreR.string.reboot_edl) { reboot("edl") })
        if (showSafeMode) {
            add(RebootOption(CoreR.string.reboot_safe_mode) {
                val newVal = if (safeModeEnabled >= 2) 0 else 2
                Config.bootloop = newVal
                safeModeEnabled = newVal
            })
        }
    }

    Box(modifier = modifier) {
        IconButton(
            modifier = Modifier.padding(end = 16.dp),
            onClick = { showMenu = true },
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = stringResource(CoreR.string.reboot),
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            items.forEach { item ->
                val isSafeMode = item.labelRes == CoreR.string.reboot_safe_mode
                DropdownMenuItem(
                    text = { Text(stringResource(item.labelRes)) },
                    trailingIcon = if (isSafeMode && safeModeEnabled >= 2) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null,
                    onClick = {
                        item.action()
                        if (!isSafeMode) showMenu = false
                    }
                )
            }
        }
    }
}

private class RebootOption(val labelRes: Int, val action: () -> Unit)




private data class StatusInfo(val label: String, val status: String)

@Composable
private fun StatusCard(
    modifier: Modifier = Modifier
) {
    val zygiskMismatch = Config.zygisk != Info.isZygiskEnabled
    val statuses = listOf(
        StatusInfo(
            label = stringResource(CoreR.string.zygisk),
            status = stringResource(
                if (zygiskMismatch) CoreR.string.reboot_apply_change
                else if (Config.zygisk) CoreR.string.enabled
                else CoreR.string.disabled
            )
        ),
        StatusInfo(
            label = stringResource(CoreR.string.ramdisk),
            status = stringResource(if (Info.ramdisk) CoreR.string.yes else CoreR.string.no)
        )
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            statuses.forEachIndexed { index, info ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = info.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = info.status,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (index < statuses.lastIndex) {
                    VerticalDivider(
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun UninstallComposableDialog(
    onDismiss: () -> Unit,
    onCompleteUninstall: () -> Unit,
    onRestoreImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreR.string.uninstall_magisk_title)) },
        text = {
            Text(
                text = stringResource(CoreR.string.uninstall_magisk_msg),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = {
            TextButton(onClick = onCompleteUninstall) {
                Text(stringResource(CoreR.string.complete_uninstall))
            }
        },
        dismissButton = {
            TextButton(onClick = onRestoreImage) {
                Text(stringResource(CoreR.string.restore_img))
            }
        }
    )
}

@Composable
private fun EnvFixComposableDialog(
    code: Int,
    onDismiss: () -> Unit,
    onNavigateInstall: () -> Unit,
    onFixEnv: () -> Unit,
    modifier: Modifier = Modifier
) {
    val needsFullFix = code == 2 ||
        Info.env.versionCode != BuildConfig.APP_VERSION_CODE ||
        Info.env.versionString != BuildConfig.APP_VERSION_NAME

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreR.string.env_fix_title)) },
        text = {
            Text(
                text = stringResource(
                    if (needsFullFix) CoreR.string.env_full_fix_msg else CoreR.string.env_fix_msg
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (needsFullFix) onNavigateInstall() else onFixEnv()
                }
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
