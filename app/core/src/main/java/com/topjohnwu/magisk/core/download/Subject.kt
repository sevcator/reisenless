package com.topjohnwu.magisk.core.download

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.model.UpdateInfo
import com.topjohnwu.magisk.core.model.module.OnlineModule
import com.topjohnwu.magisk.core.utils.MediaStoreUtils
import com.topjohnwu.magisk.view.Notifications
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

abstract class Subject : Parcelable {

    abstract val url: String
    abstract val file: Uri
    abstract val title: String
    abstract val notifyId: Int
    open val autoLaunch: Boolean get() = true

    open fun pendingIntent(context: Context): PendingIntent? = null

    abstract class Module : Subject() {
        abstract val module: OnlineModule
        final override val url: String get() = module.zipUrl
        final override val title: String get() = module.downloadFilename
        final override val file by lazy {
            MediaStoreUtils.getFile(title).uri
        }
    }

    @Parcelize
    class App(
        private val json: UpdateInfo = Info.update,
        override val notifyId: Int = Notifications.nextId(),
    ) : Subject() {
        override val title: String get() = "Reisenless-${json.version}(${json.versionCode})"
        override val url: String get() = json.link

        @IgnoredOnParcel
        override val file by lazy { MediaStoreUtils.getFile("$title.apk").uri }

        @IgnoredOnParcel
        var intent: Intent? = null

        override fun pendingIntent(context: Context) = intent?.toPending(context)
    }

    @SuppressLint("InlinedApi")
    protected fun Intent.toPending(context: Context): PendingIntent {
        return PendingIntent.getActivity(
            context,
            notifyId,
            this,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
        )
    }
}
