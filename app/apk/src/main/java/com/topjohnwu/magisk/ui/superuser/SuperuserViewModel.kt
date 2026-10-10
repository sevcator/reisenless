package com.topjohnwu.magisk.ui.superuser

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.os.Process
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.arch.AsyncLoadViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.R
import com.topjohnwu.magisk.core.data.magiskdb.PolicyDao
import com.topjohnwu.magisk.core.ktx.getLabel
import com.topjohnwu.magisk.core.model.su.SuPolicy
import com.topjohnwu.magisk.core.su.SuEvents
import com.topjohnwu.magisk.core.utils.AppCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class AddableAppInfo(
    val packageName: String,
    val appName: String,
    val uid: Int,
)

data class PolicyItem(
    val policy: SuPolicy,
    val packageName: String,
    val isSharedUid: Boolean,
    val appName: String,
    val policyValue: Int = policy.policy,
    val notification: Boolean = policy.notification,
) {
    val title get() = appName
    val isEnabled get() = policyValue >= SuPolicy.ALLOW
    val isRestricted get() = policyValue == SuPolicy.RESTRICT
}

class SuperuserViewModel(
    private val db: PolicyDao
) : AsyncLoadViewModel() {

    var authenticate: (onSuccess: () -> Unit) -> Unit = { it() }

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            SuEvents.policyChanged.debounce(500).collect { reload() }
        }
        viewModelScope.launch { AppCatalog.generation.drop(1).collect { reload() } }
    }

    data class UiState(
        val loading: Boolean = true,
        val policies: List<PolicyItem> = emptyList(),
        val suRestrict: Boolean = Config.suRestrict,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _installableApps = MutableStateFlow<List<AddableAppInfo>>(emptyList())
    val installableApps: StateFlow<List<AddableAppInfo>> = _installableApps.asStateFlow()

    @SuppressLint("InlinedApi")
    override suspend fun doLoadWork() {
        if (!Info.showSuperUser) {
            _uiState.update { it.copy(loading = false) }
            return
        }
        _uiState.update { it.copy(loading = true) }
        withContext(Dispatchers.IO) {
            db.deleteOutdated()
            db.delete(AppContext.applicationInfo.uid)
            val policies = ArrayList<PolicyItem>()
            val pm = AppContext.packageManager
            for (policy in db.fetchAll()) {
                val pkgs =
                    if (policy.uid == Process.SYSTEM_UID) arrayOf("android")
                    else pm.getPackagesForUid(policy.uid)
                if (pkgs == null) {
                    db.delete(policy.uid)
                    continue
                }
                val map = pkgs.mapNotNull { pkg ->
                    try {
                        val info = pm.getPackageInfo(pkg, MATCH_UNINSTALLED_PACKAGES)
                        PolicyItem(
                            policy = policy,
                            packageName = info.packageName,
                            isSharedUid = info.sharedUserId != null,
                            appName = info.applicationInfo?.getLabel(pm) ?: info.packageName,
                            policyValue = policy.policy,
                            notification = policy.notification,
                        )
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                }
                if (map.isEmpty()) {
                    db.delete(policy.uid)
                    continue
                }
                policies.addAll(map)
            }
            policies.sortWith(compareBy(
                { it.appName.lowercase(Locale.ROOT) },
                { it.packageName }
            ))
            _uiState.update { it.copy(loading = false, policies = policies, suRestrict = Config.suRestrict) }
        }
    }

    suspend fun loadInstallableApps() {
        withContext(Dispatchers.IO) {
            val installed = AppCatalog.apps()
            val currentUids = _uiState.value.policies.map { it.policy.uid }.toSet()
            val list = installed.filter { app ->
                app.packageName != AppContext.packageName &&
                app.uid != Process.SYSTEM_UID &&
                app.uid !in currentUids
            }.map { app ->
                AddableAppInfo(
                    packageName = app.packageName,
                    appName = app.label,
                    uid = app.uid,
                )
            }.sortedBy { it.appName.lowercase(Locale.ROOT) }
            _installableApps.value = list
        }
    }

    fun grantApp(app: AddableAppInfo) {
        val grant: () -> Unit = {
            viewModelScope.launch {
                val policy = SuPolicy(
                    uid = app.uid,
                    policy = SuPolicy.ALLOW,
                    remain = 0L,
                    notification = true,
                )
                withContext(Dispatchers.IO) {
                    db.update(policy)
                }
                val newItem = PolicyItem(
                    policy = policy,
                    packageName = app.packageName,
                    isSharedUid = false,
                    appName = app.appName,
                    policyValue = SuPolicy.ALLOW,
                    notification = true,
                )
                _uiState.update { state ->
                    val updated = (state.policies.filter { it.policy.uid != app.uid } + newItem)
                        .sortedWith(compareBy({ it.appName.lowercase(Locale.ROOT) }, { it.packageName }))
                    state.copy(policies = updated)
                }
                _installableApps.update { list ->
                    list.filter { it.uid != app.uid }
                }
                showSnackbar(AppContext.getString(R.string.su_snack_grant, app.appName))
            }
        }
        if (Config.suAuth) {
            authenticate(grant)
        } else {
            grant()
        }
    }

    fun refreshSuRestrict() {
        _uiState.update { it.copy(suRestrict = Config.suRestrict) }
    }

    val requiresAuth get() = Config.suAuth

    fun performDelete(item: PolicyItem, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            db.delete(item.policy.uid)
            _uiState.update { state ->
                state.copy(policies = state.policies.filter { it.policy.uid != item.policy.uid })
            }
            onDeleted()
        }
    }

    fun updateNotify(item: PolicyItem) {
        val newNotification = !item.notification
        item.policy.notification = newNotification
        viewModelScope.launch {
            db.update(item.policy)
            _uiState.update { state ->
                state.copy(
                    policies = state.policies.map {
                        if (it.policy.uid == item.policy.uid) it.copy(notification = newNotification) else it
                    }
                )
            }
            val res = if (newNotification) R.string.su_snack_notif_on else R.string.su_snack_notif_off
            showSnackbar(AppContext.getString(res, item.appName))
        }
    }

    fun updatePolicy(item: PolicyItem, newPolicy: Int) {
        fun updateState() {
            viewModelScope.launch {
                item.policy.policy = newPolicy
                db.update(item.policy)
                _uiState.update { state ->
                    state.copy(
                        policies = state.policies.map {
                            if (it.policy.uid == item.policy.uid) it.copy(policyValue = newPolicy) else it
                        }
                    )
                }
                val res = if (newPolicy >= SuPolicy.ALLOW) R.string.su_snack_grant else R.string.su_snack_deny
                showSnackbar(AppContext.getString(res, item.appName))
            }
        }

        if (Config.suAuth) {
            authenticate { updateState() }
        } else {
            updateState()
        }
    }

    fun togglePolicy(item: PolicyItem) {
        val newPolicy = if (item.isEnabled) SuPolicy.DENY else SuPolicy.ALLOW
        updatePolicy(item, newPolicy)
    }

    fun toggleRestrict(item: PolicyItem) {
        val newPolicy = if (item.isRestricted) SuPolicy.ALLOW else SuPolicy.RESTRICT
        updatePolicy(item, newPolicy)
    }
}
