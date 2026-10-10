package com.topjohnwu.magisk.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.painter.Painter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.utils.AppCatalog

@Composable
fun rememberAppIcon(packageName: String): Painter {
    val generation by AppCatalog.generation.collectAsStateWithLifecycle()
    val icon by produceState(AppContext.packageManager.defaultActivityIcon, packageName, generation) {
        value = AppCatalog.icon(packageName)
    }
    return rememberDrawablePainter(icon)
}
