package com.topjohnwu.magisk.core

import android.app.Application
import android.content.Context

open class App : Application() {

    override fun attachBaseContext(context: Context) {
        if (context is Application) {
            AppContext.attachApplication(context)
        } else {
            super.attachBaseContext(context)
            AppContext.attachApplication(this)
        }
    }
}
