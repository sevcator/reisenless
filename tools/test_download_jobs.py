
import sys
from test_superuser_auth import ROOT, compile_and_run

SOURCES = {
    "Annotations.kt": """
        package android.annotation
        annotation class SuppressLint(vararg val value: String)
        annotation class TargetApi(val value: Int)
    """,
    "Content.kt": """
        package android.content
        import android.app.job.JobScheduler
        open class Context {
            val packageName = "com.example.manager"
            val applicationContext get() = this
            val scheduler = JobScheduler()
            fun startForegroundService(intent: Intent) {}
            fun startService(intent: Intent) {}
        }
        class Intent {
            var action: String? = null
            val extras = mutableMapOf<String, Any>()
            fun setAction(value: String) = apply { action = value }
            fun putExtra(key: String, value: Any) = apply { extras[key] = value }
        }
    """,
    "Os.kt": """
        package android.os
        object Build { object VERSION { const val SDK_INT = 34 } }
        class Bundle {
            var classLoader: ClassLoader? = null
            val values = mutableMapOf<String, Any>()
            fun putParcelable(key: String, value: Any) { values[key] = value }
            fun <T> getParcelable(key: String, type: Class<T>): T? = values[key]?.let { type.cast(it) }
        }
        class Handler(looper: Any) {
            fun post(action: () -> Unit) { pending.add(action) }
            companion object {
                private val pending = mutableListOf<() -> Unit>()
                fun drain() { while (pending.isNotEmpty()) pending.removeAt(0)() }
            }
        }
    """,
    "Manifest.kt": """
        package android
        object Manifest { object permission { const val POST_NOTIFICATIONS = "notifications" } }
    """,
    "Notification.kt": """
        package android.app
        class Notification { class Builder { fun build() = Notification() } }
    """,
    "Pending.kt": """
        package android.app
        import android.content.*
        class PendingIntent(var intent: Intent) {
            companion object {
                const val FLAG_IMMUTABLE = 1
                const val FLAG_UPDATE_CURRENT = 2
                const val FLAG_ONE_SHOT = 4
                private val pending = mutableMapOf<Pair<String, Int>, PendingIntent>()
                fun getBroadcast(context: Context, requestCode: Int, intent: Intent, flags: Int) = obtain("broadcast", requestCode, intent)
                fun getForegroundService(context: Context, requestCode: Int, intent: Intent, flags: Int) = obtain("foreground", requestCode, intent)
                fun getService(context: Context, requestCode: Int, intent: Intent, flags: Int) = obtain("service", requestCode, intent)
                private fun obtain(type: String, code: Int, intent: Intent): PendingIntent {
                    return pending.getOrPut(type to code) { PendingIntent(intent) }.also { it.intent = intent }
                }
            }
        }
    """,
    "Jobs.kt": """
        package android.app.job
        import android.os.Bundle
        class JobInfo(val id: Int, val transientExtras: Bundle) {
            companion object { const val NETWORK_TYPE_ANY = 1 }
            class Builder(private val id: Int, component: Any) {
                private var extras = Bundle()
                fun setRequiredNetworkType(type: Int) = this
                fun setUserInitiated(value: Boolean) = this
                fun setTransientExtras(value: Bundle) = apply { extras = value }
                fun build() = JobInfo(id, extras)
            }
        }
        class JobScheduler(private val jobs: MutableMap<Pair<String?, Int>, JobInfo> = mutableMapOf(), val namespace: String? = null) {
            fun forNamespace(value: String) = JobScheduler(jobs, value)
            fun schedule(info: JobInfo): Int { jobs[namespace to info.id] = info; return 1 }
            fun cancel(id: Int) { jobs.remove(namespace to id) }
            fun scheduled() = jobs.toMap()
        }
        class JobParameters(val jobId: Int, val jobNamespace: String?, val transientExtras: Bundle)
    """,
    "Services.kt": """
        package androidx.core.content
        import android.content.Context
        inline fun <reified T> Context.getSystemService(): T? = scheduler as? T
    """,
    "Activity.kt": """
        package androidx.activity
        open class ComponentActivity : android.content.Context()
    """,
    "Lifecycle.kt": """
        package androidx.lifecycle
        interface LifecycleOwner
        class MutableLiveData<T> {
            var value: T? = null
            fun postValue(value: T) { this.value = value }
            fun observe(owner: LifecycleOwner, callback: (T?) -> Unit) {}
        }
    """,
    "Core.kt": """
        package com.topjohnwu.magisk.core
        import android.content.*
        object Const { object ID { const val DOWNLOAD_JOB_ID = 6; const val BACKGROUND_UPDATE_JOB_ID = 7 } }
        class Receiver
        class Service
        inline fun <reified T> Context.intent() = Intent()
        fun Class<*>.cmp(packageName: String) = Any()
    """,
    "Base.kt": """
        package com.topjohnwu.magisk.core.base
        import android.app.Notification
        import android.app.job.JobParameters
        import android.content.Context
        interface IActivityExtension { fun withPermission(name: String, success: () -> Unit) }
        abstract class BaseJobService : Context() {
            val mainLooper = Any()
            val JOB_END_NOTIFICATION_POLICY_REMOVE = 1
            val finished = mutableListOf<JobParameters>()
            abstract fun onStartJob(params: JobParameters): Boolean
            abstract fun onStopJob(params: JobParameters?): Boolean
            open fun onDestroy() {}
            fun setNotification(params: JobParameters, id: Int, notification: Notification, policy: Int) {}
            fun jobFinished(params: JobParameters, reschedule: Boolean) { finished.add(params) }
        }
    """,
    "Subject.kt": """
        package com.topjohnwu.magisk.core.download
        data class Subject(val notifyId: Int)
    """,
    "JobRegression.kt": """
        import android.app.job.*
        import android.content.Context
        import android.os.*
        import com.topjohnwu.magisk.core.JobService
        import com.topjohnwu.magisk.core.download.*

        fun params(id: Int, namespace: String?) = JobParameters(id, namespace, Bundle().apply {
            putParcelable(DownloadEngine.SUBJECT_KEY, Subject(id))
        })
        fun main() {
            val failures = mutableListOf<String>()
            fun test(name: String, action: () -> Unit) {
                try { action(); println("PASS: $name") }
                catch (failure: Throwable) { failures.add("$name: ${failure.message}") }
            }
            test("two subjects have separate scheduled jobs") {
                val context = Context()
                DownloadEngine.start(context, Subject(6))
                DownloadEngine.start(context, Subject(7))
                val jobs = context.scheduler.scheduled()
                check(jobs.size == 2) { "second subject replaced first scheduled job" }
                check(jobs.keys.all { it.first != null }) { "downloads share default/background namespace" }
                check(jobs.keys.map { it.second }.toSet() == setOf(6, 7))
                context.scheduler.cancel(7)
                check(context.scheduler.scheduled().size == 2) { "background cancellation removed download" }
            }
            test("pending actions keep their original subject") {
                val context = Context()
                val first = DownloadEngine.getPendingIntent(context, Subject(6))
                val second = DownloadEngine.getPendingIntent(context, Subject(7))
                check(first !== second) { "pending action for second subject replaced first" }
                check(first.intent.extras[DownloadEngine.SUBJECT_KEY] == Subject(6))
            }
            test("stopping one download does not cancel another") {
                DownloadEngine.instances.clear()
                val service = JobService()
                val first = params(6, "downloads")
                val second = params(7, "downloads")
                check(service.onStartJob(first))
                check(service.onStartJob(second)) { "second subject job was rejected" }
                val engines = DownloadEngine.instances.toList()
                check(engines.size == 2) { "subjects share a cancellation owner" }
                service.onStopJob(first)
                check(engines[0].cancelled)
                check(!engines[1].cancelled) { "stopping first cancelled second" }
                engines[1].finish()
                Handler.drain()
                check(service.finished.single() === second)
            }
            test("equal job IDs in different namespaces are isolated") {
                DownloadEngine.instances.clear()
                val service = JobService()
                val first = params(6, null)
                val second = params(6, "downloads")
                check(service.onStartJob(first))
                check(service.onStartJob(second))
                val engines = DownloadEngine.instances.toList()
                check(engines.size == 2) { "namespace ignored when identifying owner" }
                service.onStopJob(first)
                check(engines[0].cancelled && !engines[1].cancelled)
                service.onDestroy()
                check(engines[1].cancelled)
            }
            test("delayed completion cannot finish a replacement session") {
                DownloadEngine.instances.clear()
                val service = JobService()
                val first = params(6, "downloads")
                check(service.onStartJob(first))
                DownloadEngine.instances.single().finish()
                service.onStopJob(first)
                val replacement = params(6, "downloads")
                check(service.onStartJob(replacement))
                Handler.drain()
                check(service.finished.isEmpty()) { "old completion finished replacement" }
                DownloadEngine.instances.last().finish()
                Handler.drain()
                check(service.finished.single() === replacement)
            }
            check(failures.isEmpty()) { failures.joinToString("; ") }
            println("Download scheduling/session regressions passed")
        }
    """,
}

def main():
    engine = (ROOT / "app/core/src/main/java/com/topjohnwu/magisk/core/download/DownloadEngine.kt").read_text()
    companion = engine[engine.index("    companion object {"):engine.index("    private val notifications")]
    sources = dict(SOURCES)
    sources["Engine.kt"] = """
        package com.topjohnwu.magisk.core.download
        import android.Manifest
        import android.annotation.SuppressLint
        import android.app.PendingIntent
        import android.app.job.*
        import android.content.Context
        import android.os.*
        import androidx.activity.ComponentActivity
        import androidx.core.content.getSystemService
        import androidx.lifecycle.*
        import com.topjohnwu.magisk.core.*
        import com.topjohnwu.magisk.core.base.IActivityExtension
        class DownloadEngine(private val session: DownloadSession? = null) {
    """ + companion + """
            init { if (session != null) instances.add(this) }
            var cancelled = false
            var isIdle = false
            fun download(subject: Subject) {}
            fun reattach() {}
            fun cancel() { cancelled = true }
            fun finish() { isIdle = true; session!!.onDownloadComplete() }
        }
    """

    sources["Engine.kt"] = sources["Engine.kt"].replace("    companion object {", "    companion object {\n        val instances = mutableListOf<DownloadEngine>()", 1)
    compile_and_run(sources, [
        ROOT / "app/core/src/main/java/com/topjohnwu/magisk/core/JobService.kt",
        ROOT / "app/core/src/main/java/com/topjohnwu/magisk/core/download/Interfaces.kt",
    ], "JobRegressionKt")

if __name__ == "__main__":
    main()
