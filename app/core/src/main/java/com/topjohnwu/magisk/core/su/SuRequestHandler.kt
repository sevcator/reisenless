package com.topjohnwu.magisk.core.su

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.data.magiskdb.PolicyDao
import com.topjohnwu.magisk.core.ktx.getPackageInfo
import com.topjohnwu.magisk.core.model.su.SuPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class SuRequestHandler(
    val pm: PackageManager,
    private val policyDB: PolicyDao
) {

    private lateinit var output: File
    private lateinit var policy: SuPolicy
    lateinit var pkgInfo: PackageInfo
        private set


    suspend fun start(intent: Intent): Boolean {
        if (!init(intent))
            return false

        when (Config.suAutoResponse) {
            Config.Value.SU_AUTO_DENY -> {
                respond(SuPolicy.DENY, 0)
                return false
            }
            Config.Value.SU_AUTO_ALLOW -> {
                respond(SuPolicy.ALLOW, 0)
                return false
            }
        }

        return true
    }

    private suspend fun init(intent: Intent): Boolean {
        val uid = intent.getIntExtra("uid", -1)
        val pid = intent.getIntExtra("pid", -1)
        val fifo = intent.getStringExtra("fifo")
        if (uid <= 0 || pid <= 0 || fifo == null) {
            return false
        }
        output = File(fifo)
        policy = policyDB.fetch(uid) ?: SuPolicy(uid)
        try {
            pkgInfo = pm.getPackageInfo(uid, pid) ?: PackageInfo().apply {
                val name = pm.getNameForUid(uid) ?: throw PackageManager.NameNotFoundException()

                sharedUserId = name.split(":")[0]
            }
        } catch (e: PackageManager.NameNotFoundException) {
            respond(SuPolicy.DENY, -1)
            return false
        }
        if (!output.canWrite()) {
            return false
        }
        return true
    }

    suspend fun respond(action: Int, time: Long) {
        policy.policy = action
        if (time >= 0) {
            policy.remain = TimeUnit.MINUTES.toSeconds(time)
        } else {
            policy.remain = time
        }

        withContext(Dispatchers.IO) {
            try {
                DataOutputStream(FileOutputStream(output)).use {
                    it.writeInt(policy.policy)
                    it.flush()
                }
            } catch (e: IOException) {
            }
            if (time >= 0) {
                policyDB.update(policy)
            }
        }
    }
}
