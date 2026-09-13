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
import java.util.concurrent.atomic.AtomicBoolean

class SuRequestHandler(
    val pm: PackageManager,
    private val policyDB: PolicyDao
) {

    private lateinit var output: File
    private lateinit var policy: SuPolicy
    lateinit var pkgInfo: PackageInfo
        private set

    private val responseSent = AtomicBoolean(false)


    suspend fun start(intent: Intent): Boolean {
        return try {
            startInternal(intent)
        } catch (_: Exception) {
            reject()
            false
        }
    }

    private suspend fun startInternal(intent: Intent): Boolean {
        if (!init(intent)) {
            reject()
            return false
        }

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
        policy = try {
            policyDB.fetch(uid) ?: SuPolicy(uid)
        } catch (_: Exception) {
            // A stale or unavailable daemon must not prevent the request UI
            // from answering the native FIFO. Start with a query policy and
            // let the user make the decision.
            SuPolicy(uid)
        }
        try {
            pkgInfo = pm.getPackageInfo(uid, pid) ?: PackageInfo().apply {
                val name = pm.getNameForUid(uid) ?: throw PackageManager.NameNotFoundException()

                sharedUserId = name.split(":")[0]
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        if (!output.canWrite()) {
            return false
        }
        return true
    }

    suspend fun respond(action: Int, time: Long) {
        if (!::output.isInitialized || !::policy.isInitialized ||
            !responseSent.compareAndSet(false, true)
        ) return

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
                runCatching { policyDB.update(policy) }
            }
        }
    }

    private suspend fun reject() {
        if (::output.isInitialized && ::policy.isInitialized) {
            respond(SuPolicy.DENY, -1)
        }
    }
}
