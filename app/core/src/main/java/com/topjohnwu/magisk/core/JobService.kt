package com.topjohnwu.magisk.core

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Notification
import android.app.job.JobParameters
import com.topjohnwu.magisk.core.base.BaseJobService
import com.topjohnwu.magisk.core.download.DownloadEngine
import com.topjohnwu.magisk.core.download.DownloadSession
import com.topjohnwu.magisk.core.download.Subject

class JobService : BaseJobService() {

    private var mSession: Session? = null

    @TargetApi(value = 34)
    inner class Session(
        private var params: JobParameters
    ) : DownloadSession {

        override val context get() = this@JobService
        val engine = DownloadEngine(this)

        fun updateParams(params: JobParameters) {
            this.params = params
            engine.reattach()
        }

        override fun attachNotification(id: Int, builder: Notification.Builder) {
            setNotification(params, id, builder.build(), JOB_END_NOTIFICATION_POLICY_REMOVE)
        }

        override fun onDownloadComplete() {
            jobFinished(params, false)
        }
    }

    @SuppressLint("NewApi")
    override fun onStartJob(params: JobParameters): Boolean {
        return when (params.jobId) {
            Const.ID.DOWNLOAD_JOB_ID -> downloadFile(params)
            else -> false
        }
    }

    override fun onStopJob(params: JobParameters?) = false

    @TargetApi(value = 34)
    private fun downloadFile(params: JobParameters): Boolean {
        params.transientExtras.classLoader = Subject::class.java.classLoader
        val subject = params.transientExtras
            .getParcelable(DownloadEngine.SUBJECT_KEY, Subject::class.java) ?:
            return false

        val session = mSession?.also {
            it.updateParams(params)
        } ?: run {
            Session(params).also { mSession = it }
        }

        session.engine.download(subject)
        return true
    }
}
