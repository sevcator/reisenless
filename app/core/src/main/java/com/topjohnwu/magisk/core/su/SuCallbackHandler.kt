package com.topjohnwu.magisk.core.su

import android.content.Context
import android.os.Bundle
import com.topjohnwu.magisk.core.ktx.getLabel
import com.topjohnwu.magisk.core.ktx.getPackageInfo
import com.topjohnwu.magisk.core.model.su.SuPolicy
import com.topjohnwu.magisk.view.Notifications

object SuCallbackHandler {

    const val REQUEST = "request"
    const val NOTIFY = "notify"

    fun run(context: Context, action: String?, data: Bundle?) {
        if (action != NOTIFY || data == null) return

        val uid = data.getInt("from.uid", -1)
        if (uid <= 0) return
        val granted = when (data.getInt("policy", SuPolicy.QUERY)) {
            SuPolicy.ALLOW, SuPolicy.RESTRICT -> true
            SuPolicy.DENY -> false
            else -> return
        }

        val pm = context.packageManager
        val appName = runCatching {
            val info = pm.getPackageInfo(uid, data.getInt("pid", -1))
            info?.applicationInfo?.getLabel(pm) ?: info?.sharedUserId
        }.getOrNull() ?: pm.getNameForUid(uid) ?: return

        Notifications.suNotificationOrToast(context, granted, appName)
    }
}
