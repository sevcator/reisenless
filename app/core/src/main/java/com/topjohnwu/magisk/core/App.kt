package com.topjohnwu.magisk.core

import android.app.Application
import android.content.Context
import com.topjohnwu.magisk.StubApk
import com.topjohnwu.magisk.core.utils.RootUtils

open class App() : Application() {

    constructor(o: Any) : this() {
        val data = StubApk.Data(o)

        data.classToComponent[RootUtils::class.java.name] = data.rootService.name

        data.rootService = RootUtils::class.java
        Info.stub = data
    }

    override fun attachBaseContext(context: Context) {
        if (context is Application) {
            AppContext.attachApplication(context)
        } else {
            super.attachBaseContext(context)
            AppContext.attachApplication(this)
        }
    }
}
