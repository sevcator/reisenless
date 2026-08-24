package com.topjohnwu.magisk.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.di.ServiceLocator
import com.topjohnwu.magisk.core.repository.ModuleRepository
import com.topjohnwu.magisk.core.repository.TrustedRepository
import com.topjohnwu.magisk.core.R as CoreR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RepositorySettingsScreen(onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val repository = remember { ModuleRepository(ServiceLocator.networkService) }
    var configured by remember { mutableStateOf(readConfiguredRepositories()) }
    var trusted by remember { mutableStateOf(emptyList<TrustedRepository>()) }
    var customUrl by rememberSaveable { mutableStateOf("") }
    var invalidCustomUrl by rememberSaveable { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        trusted = repository.loadTrustedRepositories()
    }

    fun updateConfigured(values: List<String>) {
        configured = values.distinctBy {
            ModuleRepository.normalizeRepositoryUrl(it)?.lowercase() ?: it.lowercase()
        }
        Config.moduleRepositoryUrls = configured.joinToString("\n")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(android.R.string.cancel),
                            tint = colors.primary,
                        )
                    }
                },
                title = { Text(stringResource(CoreR.string.repository_searcher)) },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = {
                        customUrl = it
                        invalidCustomUrl = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    singleLine = true,
                    isError = invalidCustomUrl,
                    label = { Text(stringResource(CoreR.string.repository_mmrl_url_hint)) },
                    supportingText = if (invalidCustomUrl) {
                        { Text(stringResource(CoreR.string.repository_mmrl_url_invalid)) }
                    } else {
                        null
                    },
                    trailingIcon = {
                        IconButton(onClick = {
                            val normalized = ModuleRepository.normalizeRepositoryUrl(customUrl)
                            if (normalized == null) {
                                invalidCustomUrl = true
                            } else {
                                updateConfigured(configured + customUrl.trim())
                                customUrl = ""
                            }
                        }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(CoreR.string.repository_add),
                                tint = colors.primary,
                            )
                        }
                    },
                )
            }

            item {
                Text(
                    text = stringResource(CoreR.string.repository_configured),
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.primary,
                )
            }

            if (configured.isEmpty()) {
                item {
                    Text(
                        stringResource(CoreR.string.repository_none_configured),
                        modifier = Modifier.padding(16.dp),
                        color = colors.onSurfaceVariant,
                    )
                }
            } else {
                items(configured, key = { "configured:$it" }) { source ->
                    val known = trusted.firstOrNull { sameRepository(source, it.url) }
                    RepositorySourceCard(
                        name = known?.name ?: source.substringAfter("://").substringBefore('/'),
                        url = source,
                        description = known?.description.orEmpty(),
                        trusted = known != null,
                        installed = true,
                        onWeb = { runCatching { uriHandler.openUri(source) } },
                        onAction = { updateConfigured(configured - source) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RepositorySourceCard(
    name: String,
    url: String,
    description: String,
    trusted: Boolean,
    installed: Boolean,
    onWeb: () -> Unit,
    onAction: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val actionBg = colors.secondaryContainer.copy(alpha = 0.8f)
    val actionTint = colors.onSecondaryContainer.copy(alpha = 0.9f)
    val removeBg = colors.errorContainer.copy(alpha = 0.6f)
    val removeTint = colors.onErrorContainer.copy(alpha = 0.9f)

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.65f)),
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (trusted) {
                    Icon(
                        Icons.Default.Verified,
                        contentDescription = stringResource(CoreR.string.repository_trusted),
                        tint = colors.primary,
                    )
                }
            }
            Text(
                url,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (description.isNotBlank()) {
                Text(
                    description,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                thickness = 0.5.dp,
                color = colors.outline.copy(alpha = 0.5f),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                Button(
                    onClick = onWeb,
                    colors = ButtonDefaults.buttonColors(containerColor = actionBg),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Icon(Icons.Default.Language, contentDescription = null, tint = actionTint)
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(CoreR.string.repository_web), color = actionTint)
                }
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (installed) removeBg else colors.primaryContainer,
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Icon(
                        if (installed) Icons.Default.Delete else Icons.Default.Add,
                        contentDescription = null,
                        tint = if (installed) removeTint else colors.onPrimaryContainer,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        stringResource(
                            if (installed) CoreR.string.module_state_remove
                            else CoreR.string.repository_add
                        ),
                        color = if (installed) removeTint else colors.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

private fun readConfiguredRepositories(): List<String> = Config.moduleRepositoryUrls
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .toList()

private fun sameRepository(first: String, second: String): Boolean =
    ModuleRepository.normalizeRepositoryUrl(first)
        ?.equals(ModuleRepository.normalizeRepositoryUrl(second), ignoreCase = true) == true
