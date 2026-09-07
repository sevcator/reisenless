package com.topjohnwu.magisk.ui.install

import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.text.Spanned
import android.text.SpannedString
import android.widget.Toast
import androidx.databinding.Bindable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.base.ContentResultCallback
import com.topjohnwu.magisk.core.ktx.toast
import com.topjohnwu.magisk.databinding.set
import com.topjohnwu.magisk.dialog.SecondSlotWarningDialog
import com.topjohnwu.magisk.events.GetContentEvent
import com.topjohnwu.magisk.ui.flash.FlashFragment
import kotlinx.parcelize.Parcelize
import com.topjohnwu.magisk.core.R as CoreR

class InstallViewModel : BaseViewModel() {

    val isRooted get() = Info.isRooted
    val skipOptions = Info.isEmulator || (Info.isSAR && !Info.isFDE && Info.ramdisk)
    val noSecondSlot = !isRooted || !Info.isAB || Info.isEmulator

    @get:Bindable
    var step = if (skipOptions) 1 else 0
        set(value) = set(value, field, { field = it }, BR.step)

    private var methodId = -1

    @get:Bindable
    var method
        get() = methodId
        set(value) = set(value, methodId, { methodId = it }, BR.method) {
            when (it) {
                R.id.method_patch -> {
                    GetContentEvent("*/*", UriCallback()).publish()
                }
                R.id.method_inactive_slot -> {
                    SecondSlotWarningDialog().show()
                }
            }
        }

    private fun resetMethod() {
        method = -1
    }

    private val _uri = MutableLiveData<Uri?>()
    val data: LiveData<Uri?> get() = _uri

    private val _apkUri = MutableLiveData<Uri?>()
    val apkData: LiveData<Uri?> get() = _apkUri

    @get:Bindable
    var sourceChoice = R.id.source_launched
        set(value) = set(value, field, { field = it }, BR.sourceChoice) {
            if (it == R.id.source_selected && method != R.id.method_patch) resetMethod()
        }

    fun chooseApk() {
        // Launch only from a user click, not from binding/state restoration.
        // Clicking the already-selected row also lets the user replace the file.
        sourceChoice = R.id.source_selected
        GetContentEvent("application/vnd.android.package-archive", UriCallback(true)).publish()
    }

    fun chooseImage() = GetContentEvent("*/*", UriCallback()).publish()

    private val sourceObserver = Observer<PatchSource?> { source ->
        when (source) {
            is PatchSource.File -> if (source.apk) _apkUri.value = source.uri else {
                _uri.value = source.uri
            }
            is PatchSource.Cancelled -> if (source.apk) {
                if (_apkUri.value == null) sourceChoice = R.id.source_launched
            } else if (_uri.value == null) resetMethod()
            null -> return@Observer
        }
        patchSource.value = null
    }

    @get:Bindable
    var notes: Spanned = SpannedString("")
        set(value) = set(value, field, { field = it }, BR.notes)

    init {
        patchSource.observeForever(sourceObserver)
    }

    fun install() {
        val source = if (sourceChoice == R.id.source_selected) _apkUri.value ?: return else null
        // A foreign build must never use this manager's direct-install helpers.
        if (source != null && method != R.id.method_patch) return
        when (method) {
            R.id.method_patch -> FlashFragment.patch(data.value ?: return, source).navigate(true)
            R.id.method_direct -> FlashFragment.flash(false).navigate(true)
            R.id.method_inactive_slot -> FlashFragment.flash(true).navigate(true)
            else -> error("Unknown value")
        }
    }

    override fun onSaveState(state: Bundle) {
        state.putParcelable(
            INSTALL_STATE_KEY, InstallState(
                methodId,
                step,
                Config.keepVerity,
                Config.keepEnc,
                Config.recovery,
                sourceChoice,
                _uri.value,
                _apkUri.value
            )
        )
    }

    override fun onRestoreState(state: Bundle) {
        state.getParcelable<InstallState>(INSTALL_STATE_KEY)?.let {
            methodId = it.method
            step = it.step
            Config.keepVerity = it.keepVerity
            Config.keepEnc = it.keepEnc
            Config.recovery = it.recovery
            sourceChoice = it.sourceChoice
            _uri.value = it.image
            _apkUri.value = it.apk
        }
    }

    override fun onCleared() {
        patchSource.removeObserver(sourceObserver)
        super.onCleared()
    }

    private sealed interface PatchSource {
        data class File(val uri: Uri, val apk: Boolean) : PatchSource
        data class Cancelled(val apk: Boolean) : PatchSource
    }

    @Parcelize
    class UriCallback(private val apk: Boolean = false) : ContentResultCallback {
        override fun onActivityLaunch() {
            AppContext.toast(if (apk) CoreR.string.install_choose_apk else CoreR.string.patch_file_msg, Toast.LENGTH_LONG)
        }

        override fun onActivityResult(result: Uri) {
            patchSource.value = PatchSource.File(result, apk)
        }

        override fun onActivityCancel() {
            patchSource.value = PatchSource.Cancelled(apk)
        }
    }

    @Parcelize
    class InstallState(
        val method: Int,
        val step: Int,
        val keepVerity: Boolean,
        val keepEnc: Boolean,
        val recovery: Boolean,
        val sourceChoice: Int,
        val image: Uri?,
        val apk: Uri?,
    ) : Parcelable

    companion object {
        private const val INSTALL_STATE_KEY = "install_state"
        private val patchSource = MutableLiveData<PatchSource?>()
    }
}
