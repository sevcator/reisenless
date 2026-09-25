package com.topjohnwu.magisk.core.utils

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PowerManager
import androidx.collection.ArraySet
import androidx.core.content.getSystemService
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.ktx.registerRuntimeReceiver

@SuppressLint("MissingPermission")
class NetworkObserver(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService<ConnectivityManager>()!!
    private val activeNetworks = ArraySet<Network>()
    private var observing = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            activeNetworks.add(network)
            postValue(true)
        }
        override fun onLost(network: Network) {
            activeNetworks.remove(network)
            postValue(!activeNetworks.isEmpty())
        }
    }

    private val receiver = object : BroadcastReceiver() {
        private fun Context.isIdleMode(): Boolean {
            val pwm = getSystemService<PowerManager>() ?: return true
            val isIgnoringOptimizations = pwm.isIgnoringBatteryOptimizations(packageName)
            return pwm.isDeviceIdleMode && !isIgnoringOptimizations
        }
        override fun onReceive(context: Context, intent: Intent) {
            if (context.isIdleMode()) {
                postValue(false)
            } else {
                postCurrentState()
            }
        }
    }

    fun start() {
        if (observing) return
        observing = true
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        manager.registerNetworkCallback(request, networkCallback)
        val filter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        appContext.registerRuntimeReceiver(receiver, filter)
        postCurrentState()
    }

    fun stop() {
        if (!observing) return
        observing = false
        manager.unregisterNetworkCallback(networkCallback)
        appContext.unregisterReceiver(receiver)
        activeNetworks.clear()
        Info.isConnected.postValue(false)
    }

    private fun postCurrentState() {
        postValue(
            manager.getNetworkCapabilities(manager.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        )
    }

    private fun postValue(b: Boolean) {
        Info.isConnected.postValue(b)
    }
}
