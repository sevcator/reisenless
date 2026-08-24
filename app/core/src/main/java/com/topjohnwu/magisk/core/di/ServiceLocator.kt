package com.topjohnwu.magisk.core.di

import android.annotation.SuppressLint
import android.content.Context
import android.text.method.LinkMovementMethod
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.data.magiskdb.PolicyDao
import com.topjohnwu.magisk.core.data.magiskdb.SettingsDao
import com.topjohnwu.magisk.core.data.magiskdb.StringDao
import com.topjohnwu.magisk.core.ktx.deviceProtectedContext
import com.topjohnwu.magisk.core.repository.NetworkService
import io.noties.markwon.Markwon
import io.noties.markwon.utils.NoCopySpannableFactory

@SuppressLint("StaticFieldLeak")
object ServiceLocator {

    val deContext by lazy { AppContext.deviceProtectedContext }
    val timeoutPrefs by lazy { deContext.getSharedPreferences("su_timeout", 0) }


    val policyDB = PolicyDao()
    val settingsDB = SettingsDao()
    val stringDB = StringDao()


    val okhttp by lazy { createOkHttpClient(AppContext) }
    val retrofit by lazy { createRetrofit(okhttp) }
    val markwon by lazy { createMarkwon(AppContext) }
    val networkService by lazy {
        NetworkService(createApiService(retrofit, Const.Url.INVALID_URL))
    }
}

private fun createMarkwon(context: Context) =
    Markwon.builder(context).textSetter { textView, spanned, bufferType, onComplete ->
        textView.apply {
            movementMethod = LinkMovementMethod.getInstance()
            setSpannableFactory(NoCopySpannableFactory.getInstance())
            setText(spanned, bufferType)
            onComplete.run()
        }
    }.build()
