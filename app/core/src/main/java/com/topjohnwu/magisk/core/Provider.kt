package com.topjohnwu.magisk.core

import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.topjohnwu.magisk.core.base.BaseProvider
import com.topjohnwu.magisk.core.su.SuCallbackHandler

class Provider : BaseProvider() {

    // Legacy hidden stubs publish only this provider. Keep the APK handoff
    // endpoint here as a compatibility bridge so they can rotate directly to
    // the current two-provider stub without privileged filesystem writes.
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        return openMigrationApk(context, uri, mode)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return when (method) {
            SuCallbackHandler.NOTIFY -> {
                SuCallbackHandler.run(context!!, method, extras)
                Bundle.EMPTY
            }
            else -> Bundle.EMPTY
        }
    }
}
