package com.topjohnwu.magisk.ui.hideapps

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.topjohnwu.magisk.core.R as CoreR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HideAppsScreen(viewModel: HideAppsViewModel, onBack: () -> Unit) {
    val apps by viewModel.apps.collectAsState()
    val targets by viewModel.targets.collectAsState()
    val selectedCaller by viewModel.selectedCaller.collectAsState()
    val rule by viewModel.rule.collectAsState()
    val query by viewModel.query.collectAsState()
    val status by viewModel.status.collectAsState()
    val showAppPicker = remember { mutableStateOf(false) }
    val selectedApp = apps.firstOrNull { it.packageName == selectedCaller }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    if (showAppPicker.value) {
        AppPickerDialog(
            apps = apps.filter { it.packageName != com.topjohnwu.magisk.core.AppContext.packageName },
            onSelect = {
                viewModel.selectCaller(it.packageName)
                showAppPicker.value = false
            },
            onDismiss = { showAppPicker.value = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreR.string.hide_apps_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.padding(start = 16.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = stringResource(
                    if (status.available) CoreR.string.hide_apps_service_active
                    else CoreR.string.hide_apps_service_inactive,
                    status.filterCount,
                ),
                color = if (status.available) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAppPicker.value = true },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    selectedApp?.let {
                        Image(
                            painter = rememberDrawablePainter(it.icon),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(CoreR.string.hide_apps_querying_app), style = MaterialTheme.typography.labelMedium)
                        Text(selectedApp?.label ?: stringResource(CoreR.string.hide_apps_choose_app))
                        selectedApp?.let {
                            Text(it.packageName, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SettingSwitchRow(
                title = stringResource(CoreR.string.hide_apps_enable_rule),
                checked = rule != null,
                enabled = selectedApp != null,
                onCheckedChange = viewModel::setEnabled,
            )
            SettingSwitchRow(
                title = stringResource(CoreR.string.hide_apps_whitelist_mode),
                summary = stringResource(
                    if (rule?.useWhitelist == true) CoreR.string.hide_apps_whitelist_summary
                    else CoreR.string.hide_apps_blacklist_summary,
                ),
                checked = rule?.useWhitelist == true,
                enabled = rule != null,
                onCheckedChange = viewModel::setWhitelist,
            )
            if (rule?.useWhitelist == true) {
                SettingSwitchRow(
                    title = stringResource(CoreR.string.hide_apps_exclude_system),
                    checked = rule?.excludeSystemApps == true,
                    enabled = true,
                    onCheckedChange = viewModel::setExcludeSystem,
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                label = { Text(stringResource(CoreR.string.hide_apps_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(targets, key = { it.packageName }) { app ->
                    TargetRow(
                        app = app,
                        checked = app.packageName in rule?.packages.orEmpty(),
                        enabled = rule != null,
                        onClick = { viewModel.togglePackage(app.packageName) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TargetRow(app: HidePackageInfo, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(rememberDrawablePainter(app.icon), null, Modifier.size(36.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall)
        }
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = { onClick() })
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<HidePackageInfo>,
    onSelect: (HidePackageInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreR.string.hide_apps_choose_app)) },
        text = {
            Box(Modifier.fillMaxWidth().height(420.dp)) {
                LazyColumn {
                    items(apps, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(app) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(rememberDrawablePainter(app.icon), null, Modifier.size(36.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(app.label)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
