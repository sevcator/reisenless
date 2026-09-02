package com.topjohnwu.magisk.core.base

import android.Manifest.permission.REQUEST_INSTALL_PACKAGES
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.magisk.StubApk
import com.topjohnwu.magisk.core.BuildConfig
import com.topjohnwu.magisk.core.BuildConfig.APP_PACKAGE_NAME
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.R
import com.topjohnwu.magisk.core.isRunningAsStub
import com.topjohnwu.magisk.core.ktx.writeTo
import com.topjohnwu.magisk.core.tasks.AppMigration
import com.topjohnwu.magisk.core.utils.RootUtils
import com.topjohnwu.magisk.view.Notifications
import com.topjohnwu.magisk.view.Shortcuts
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

interface SplashScreenHost : IActivityExtension {
    val splashController: SplashController<*>

    fun onCreateUi(savedInstanceState: Bundle?)
    fun showInvalidStateMessage()
}

class SplashController<T>(private val activity: T)
    where T : ComponentActivity, T: SplashScreenHost {

    companion object {
        private const val MIGRATION_ROOT_RETRY = "migration_root_retry"
        private var splashShown = false
    }

    private var shouldCreateUiOnResume = false
    private var shouldRequireHiddenOnResume = false
    private var mandatoryHideDialog: AlertDialog? = null
    private var mandatoryHideProgress: ProgressDialog? = null

    fun preOnCreate() {
        if (isRunningAsStub && !splashShown) {
            activity.theme.applyStyle(R.style.StubSplashTheme, true)
        } else if (!isRunningAsStub) {
            activity.installSplashScreen().setKeepOnScreenCondition { !splashShown }
        }
    }

    fun onCreate(savedInstanceState: Bundle?) {
        if (isPublicBootstrap) {
            // Never wait for or expose the regular manager UI under the public
            // package. Root initialization is deferred until the user accepts
            // the mandatory hidden-identity migration.
            splashShown = true
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                requireHiddenManager()
            } else {
                shouldRequireHiddenOnResume = true
            }
            return
        }
        if (splashShown) {
            doCreateUi(savedInstanceState)
        } else {
            val migrationLaunch =
                activity.intent.hasExtra(Const.Key.PREV_PACKAGE) &&
                    activity.intent.hasExtra(Const.Key.PREV_CONFIG)
            val alreadyRetried =
                activity.intent.getBooleanExtra(MIGRATION_ROOT_RETRY, false)
            if (migrationLaunch && !alreadyRetried) {
                // The daemon can briefly retain the previous manager identity
                // after the database and APK handoff are both complete. In
                // that state Shell.getShell may wait indefinitely instead of
                // returning a non-root shell. A bounded, authenticated restart
                // refreshes the daemon lookup and preserves all migration data.
                val retryIntent = Intent(activity.intent).apply {
                    putExtra(MIGRATION_ROOT_RETRY, true)
                }
                activity.window.decorView.postDelayed({
                    if (!splashShown && !activity.isFinishing) {
                        activity.finishAffinity()
                        activity.startActivity(retryIntent)
                        Runtime.getRuntime().exit(0)
                    }
                }, 4_000L)
            }
            Shell.getShell(Shell.EXECUTOR) {
                if (isRunningAsStub && !it.isRoot) {
                    if (migrationLaunch && !alreadyRetried) {




                        val retryIntent = Intent(activity.intent).apply {
                            putExtra(MIGRATION_ROOT_RETRY, true)
                        }
                        activity.runOnUiThread {
                            activity.window.decorView.postDelayed({
                                activity.finishAffinity()
                                activity.startActivity(retryIntent)
                                Runtime.getRuntime().exit(0)
                            }, 500L)
                        }
                    } else {
                        activity.showInvalidStateMessage()
                    }
                    return@getShell
                }
                RootUtils.Connection.await()
                activity.initializeApp()
                activity.runOnUiThread {
                    splashShown = true
                    if (isRunningAsStub) {

                        activity.relaunch()
                    } else {
                        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            doCreateUi(savedInstanceState)
                        } else {
                            shouldCreateUiOnResume = true
                        }
                    }
                }
            }
        }
    }

    fun onResume() {
        if (shouldRequireHiddenOnResume) {
            requireHiddenManager()
        } else if (shouldCreateUiOnResume) {
            doCreateUi(null)
        }
    }

    /** The signed public package is a bootstrap installer, never a usable manager. */
    private val isPublicBootstrap
        get() = activity.packageName == APP_PACKAGE_NAME

    @Suppress("DEPRECATION")
    private fun requireHiddenManager() {
        shouldRequireHiddenOnResume = false
        shouldCreateUiOnResume = false
        if (activity.isFinishing || mandatoryHideDialog?.isShowing == true ||
            mandatoryHideProgress?.isShowing == true
        ) return

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.mandatory_hide_title)
            .setMessage(R.string.mandatory_hide_message)
            .setCancelable(false)
            .setPositiveButton(R.string.mandatory_hide_action) { _, _ ->
                startMandatoryHide()
            }
            .setNegativeButton(R.string.mandatory_hide_exit) { _, _ ->
                activity.finishAffinity()
            }
            .create()
        dialog.setOnDismissListener {
            if (mandatoryHideDialog === dialog) mandatoryHideDialog = null
        }
        mandatoryHideDialog = dialog
        dialog.show()
    }

    @Suppress("DEPRECATION")
    private fun startMandatoryHide() {
        if (activity.isFinishing || mandatoryHideProgress?.isShowing == true) return
        val progress = ProgressDialog(activity).apply {
            setTitle(activity.getString(R.string.hide_app_title))
            isIndeterminate = true
            setCancelable(false)
            show()
        }
        mandatoryHideProgress = progress
        activity.lifecycleScope.launch {
            val success = preparePublicBootstrap() && AppMigration.patchAndHide(activity)
            if (progress.isShowing) progress.dismiss()
            if (mandatoryHideProgress === progress) mandatoryHideProgress = null
            if (!success && !activity.isFinishing) {
                Toast.makeText(
                    activity,
                    R.string.mandatory_hide_failure,
                    Toast.LENGTH_LONG,
                ).show()
                if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    requireHiddenManager()
                } else {
                    shouldRequireHiddenOnResume = true
                }
            }
        }
    }

    private suspend fun preparePublicBootstrap(): Boolean {
        return withTimeoutOrNull(25_000L) {
            suspendCancellableCoroutine { continuation ->
                Shell.getShell(Shell.EXECUTOR) { shell ->
                    if (!continuation.isActive) return@getShell
                    if (!shell.isRoot) {
                        continuation.resume(false)
                        return@getShell
                    }
                    RootUtils.Connection.await()
                    activity.initializeApp()
                    if (continuation.isActive) continuation.resume(true)
                }
            }
        } ?: false
    }

    private fun doCreateUi(savedInstanceState: Bundle?) {
        shouldCreateUiOnResume = false
        if (isPublicBootstrap) {
            shouldRequireHiddenOnResume = true
            return
        }
        activity.onCreateUi(savedInstanceState)
    }

    private fun T.initializeApp() {
        val prevPkg = intent.getStringExtra(Const.Key.PREV_PACKAGE)
        val prevConfig = intent.getBundleExtra(Const.Key.PREV_CONFIG)
        val migrationSource = AppMigration.pendingMigrationSource(this, prevPkg)
        val authenticatedConfig = migrationSource != null &&
            prevPkg == migrationSource &&
            prevConfig != null

        Config.init(if (authenticatedConfig) prevConfig else null)

        if (packageName != APP_PACKAGE_NAME) {
            Config.suManager = packageName
        }

        if (migrationSource != null && AppMigration.completeMigration(this, migrationSource)) {
            intent.removeExtra(Const.Key.PREV_PACKAGE)
            intent.removeExtra(Const.Key.PREV_CONFIG)
            runOnUiThread {
                StubApk.restartProcess(this)
            }
            return
        }


        if (isRunningAsStub && (

                Info.stub!!.version != BuildConfig.STUB_VERSION ||

                intent.component!!.className.contains(AppMigration.PLACEHOLDER))
        ) {
            withPermission(REQUEST_INSTALL_PACKAGES) { granted ->
                if (granted) {
                    lifecycleScope.launch {
                        val apk = File(cacheDir, Const.STUB_NAME)
                        try {
                            assets.open(Const.STUB_NAME).writeTo(apk)
                            AppMigration.upgradeStub(activity, apk)?.let {
                                startActivity(it)
                            }
                        } catch (e: IOException) {
                        }
                    }
                }
            }
            return
        }

        Notifications.setup()
        Shortcuts.setupDynamic(this)

    }
}
