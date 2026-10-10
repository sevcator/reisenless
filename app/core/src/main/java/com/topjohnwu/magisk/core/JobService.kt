package com.topjohnwu.magisk.core

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Notification
import android.app.job.JobParameters
import android.os.Build
import com.topjohnwu.magisk.core.base.BaseJobService
import com.topjohnwu.magisk.core.download.DownloadEngine
import com.topjohnwu.magisk.core.download.DownloadSession
import com.topjohnwu.magisk.core.download.Subject

class JobService : BaseJobService() {

    private data class JobKey(val namespace: String?, val id: Int)
    private val sessions = mutableMapOf<JobKey, Session>()

    @SuppressLint("NewApi")
    private fun key(params: JobParameters) = JobKey(
        if (Build.VERSION.SDK_INT >= 34) params.jobNamespace else null,
        params.jobId,
    )

    @TargetApi(value = 34)
    private inner class Session(
        private val params: JobParameters
    ) : DownloadSession {

        override val context get() = this@JobService
        val engine = DownloadEngine(this)
        private val key = key(params)

        override fun attachNotification(id: Int, builder: Notification.Builder) {
            setNotification(params, id, builder.build(), JOB_END_NOTIFICATION_POLICY_REMOVE)
        }

        override fun onDownloadComplete() {
            android.os.Handler(mainLooper).post {
                if (sessions[key] === this && engine.isIdle) {
                    sessions.remove(key)
                    jobFinished(params, false)
                }
            }
        }
    }

    @SuppressLint("NewApi")
    override fun onStartJob(params: JobParameters): Boolean {
        return downloadFile(params)
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        params?.let { sessions.remove(key(it))?.engine?.cancel() }
        return false
    }

    override fun onDestroy() {
        sessions.values.forEach { it.engine.cancel() }
        sessions.clear()
        super.onDestroy()
    }

    @TargetApi(value = 34)
    private fun downloadFile(params: JobParameters): Boolean {
        params.transientExtras.classLoader = Subject::class.java.classLoader
        val subject = params.transientExtras
            .getParcelable(DownloadEngine.SUBJECT_KEY, Subject::class.java) ?:
            return false

        val key = key(params)
        sessions.remove(key)?.engine?.cancel()
        val session = Session(params).also { sessions[key] = it }

        session.engine.download(subject)
        return true
    }
}
