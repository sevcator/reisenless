package com.topjohnwu.magisk.ui.home

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.accompanist.drawablepainter.rememberDrawablePainter
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.topjohnwu.magisk.core.R as CoreR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    installVm: InstallViewModel,
    modifier: Modifier = Modifier,
    isCurrentPage: Boolean = true,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
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

    val fumos = remember { mutableStateListOf<RandomFumo>() }
    var nextId by remember { mutableLongStateOf(0L) }
    var player by remember(context) { mutableStateOf<MediaPlayer?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                player?.release()
                player = null
                fumos.clear()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player?.release()
            player = null
        }
    }
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            fumos.clear()
            player?.release()
            player = null
        }
    }

    Scaffold(
        modifier = modifier,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val configuration = LocalConfiguration.current
            val maxFumoSize = remember(configuration.screenWidthDp, configuration.screenHeightDp) {
                (minOf(configuration.screenWidthDp, configuration.screenHeightDp) * 0.9f).coerceAtLeast(240f).toInt()
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (Info.isRooted) {
                            RebootButton()
                        }
                        if (Info.env.isActive) {
                            IconButton(onClick = { viewModel.onDeletePressed() }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(CoreR.string.uninstall_magisk_title),
                                )
                            }
                        }
                        IconButton(onClick = { showInstallSheet = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_download),
                                contentDescription = stringResource(CoreR.string.install),
                            )
                        }
                    }
                }

                if (uiState.magiskState == HomeViewModel.State.OUTDATED) {
                    OutdatedRootCard(onReinstall = { showInstallSheet = true })
                }
                StatusCard()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(CoreR.drawable.ic_reisen_white),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(Color.Gray.copy(alpha = 0.22f)),
                        modifier = Modifier
                            .size(160.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                if (player == null) {
                                    player = runCatching { MediaPlayer.create(context, R.raw.fumo) }.getOrNull()
                                }
                                player?.let { audio ->
                                    runCatching {
                                        if (audio.isPlaying) audio.pause()
                                        audio.seekTo(0)
                                        audio.start()
                                    }
                                }
                                val randomSize = (80..maxFumoSize).random().dp
                                val randomX = (0..100).random() / 100f
                                val randomY = (0..100).random() / 100f
                                fumos.add(
                                    RandomFumo(
                                        id = nextId++,
                                        xRatio = randomX,
                                        yRatio = randomY,
                                        sizeDp = randomSize,
                                    )
                                )
                            }
                    )
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 88.dp)
            ) {
                for (fumo in fumos) {
                    key(fumo.id) {
                        RandomFumoItem(
                            fumo = fumo,
                            parentWidth = maxWidth,
                            parentHeight = maxHeight,
                            onFinished = {
                                fumos.remove(fumo)
                            }
                        )
                    }
                }
            }
        }
    }

    InstallBottomSheet(
        show = showInstallSheet,
        onDismiss = { showInstallSheet = false },
        installVm = installVm,
    )
}

private data class RandomFumo(
    val id: Long,
    val xRatio: Float,
    val yRatio: Float,
    val sizeDp: Dp,
)

@Composable
private fun RandomFumoItem(
    fumo: RandomFumo,
    parentWidth: Dp,
    parentHeight: Dp,
    onFinished: () -> Unit
) {
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(fumo.id) {
        delay(5000)
        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 5000, easing = LinearEasing)
        )
        onFinished()
    }

    val maxOffsetX = (parentWidth - fumo.sizeDp).coerceAtLeast(0.dp)
    val maxOffsetY = (parentHeight - fumo.sizeDp).coerceAtLeast(0.dp)
    val offsetX = maxOffsetX * fumo.xRatio
    val offsetY = maxOffsetY * fumo.yRatio

    Image(
        painter = painterResource(R.drawable.fumo_reisen),
        contentDescription = null,
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .size(fumo.sizeDp)
            .graphicsLayer {
                this.alpha = alpha.value
            }
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
    val isZygiskActive = Config.zygisk && Info.isZygiskEnabled
    val statuses = listOf(
        StatusInfo(
            label = stringResource(CoreR.string.ramdisk),
            status = stringResource(if (Info.ramdisk) CoreR.string.yes else CoreR.string.no)
        ),
        StatusInfo(
            label = stringResource(CoreR.string.zygisk),
            status = stringResource(
                if (isZygiskActive) CoreR.string.on
                else CoreR.string.off
            )
        ),
        StatusInfo(
            label = stringResource(CoreR.string.udonge),
            status = stringResource(
                if (Config.udongeEnabled) CoreR.string.on
                else CoreR.string.off
            )
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
