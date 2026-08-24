package com.topjohnwu.magisk.core.su

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.R
import com.topjohnwu.magisk.core.ktx.getLabel
import com.topjohnwu.magisk.core.ktx.getPackageInfo
import com.topjohnwu.magisk.core.ktx.toast
import com.topjohnwu.magisk.core.model.su.SuPolicy

object SuCallbackHandler {

    const val REQUEST = "request"
    const val NOTIFY = "notify"

    fun run(context: Context, action: String?, data: Bundle?) {
        data ?: return

        when (action) {
            NOTIFY -> handleNotify(context, data)
        }
    }


    private fun Bundle.getIntComp(key: String, defaultValue: Int): Int {
        val value = get(key) ?: return defaultValue
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            else -> defaultValue
        }
    }

    private fun handleNotify(context: Context, data: Bundle) {
        val uid = data.getIntComp("from.uid", -1)
        val pid = data.getIntComp("pid", -1)
        val policy = data.getIntComp("policy", SuPolicy.ALLOW)

        val pm = context.packageManager

        val appName = runCatching {
            pm.getPackageInfo(uid, pid)?.applicationInfo?.getLabel(pm)
        }.getOrNull() ?: "[uid] $uid"

        notify(context, policy >= SuPolicy.ALLOW, appName)
    }

    private fun notify(context: Context, granted: Boolean, appName: String) {
        if (Config.suNotification == Config.Value.NOTIFICATION_TOAST) {
            val resId = if (granted)
                R.string.su_allow_toast
            else
                R.string.su_deny_toast

            context.toast(context.getString(resId, appName), Toast.LENGTH_SHORT)
        }
    }
}
