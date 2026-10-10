package com.topjohnwu.magisk.core.ktx

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ApplicationLabelTest {
    @Test fun fallbackLabelPreservesThePublishersCapitalization() {
        val pm = mock(PackageManager::class.java)
        val app = mock(ApplicationInfo::class.java)
        `when`(app.loadLabel(pm)).thenReturn("YouTube Music")
        assertEquals("YouTube Music", app.getLabel(pm))
    }
}
