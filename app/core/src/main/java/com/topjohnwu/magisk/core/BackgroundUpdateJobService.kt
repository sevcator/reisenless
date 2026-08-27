package com.topjohnwu.magisk.core

import android.app.job.JobParameters
import com.topjohnwu.magisk.core.base.BaseJobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BackgroundUpdateJobService : BaseJobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var updateJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (params.jobId != Const.ID.BACKGROUND_UPDATE_JOB_ID) return false
        updateJob = scope.launch {
            try {
                Udonge.runBackgroundUpdates()
            } catch (_: Exception) {
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        updateJob?.cancel()
        updateJob = null
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
