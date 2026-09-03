@file:Suppress("DEPRECATION")

package com.topjohnwu.magisk.core

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import com.topjohnwu.magisk.core.ktx.unwrap
import com.topjohnwu.magisk.core.utils.LocaleSetting

fun Resources.patch(): Resources {
    LocaleSetting.instance.updateResource(this)
    return this
}

fun Context.patch(): Context {
    unwrap().resources.patch()
    return this
}


fun Context.wrap(): Context {
    patch()
    return object : ContextWrapper(this) {
        override fun createConfigurationContext(config: Configuration): Context {
            return super.createConfigurationContext(config).wrap()
        }
    }
}

fun Class<*>.cmp(pkg: String) =
    ComponentName(pkg, name)

inline fun <reified T> Context.intent() = Intent().setComponent(T::class.java.cmp(packageName))



val shouldKeepResources = listOf(
    R.string.no_info_provided,
    R.string.release_notes,
    R.string.app_changelog,
    R.string.home_item_source,
    R.drawable.ic_more,
    R.array.allow_timeout,
)
