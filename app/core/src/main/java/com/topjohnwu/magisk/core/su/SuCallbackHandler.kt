package com.topjohnwu.magisk.core.su

import android.content.Context
import android.os.Bundle

object SuCallbackHandler {

    const val REQUEST = "request"
    const val NOTIFY = "notify"

    fun run(context: Context, action: String?, data: Bundle?) {
        // Root request notifications are completely disabled
    }
}
