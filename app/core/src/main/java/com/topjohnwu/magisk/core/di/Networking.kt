package com.topjohnwu.magisk.core.di

import android.content.Context
import com.squareup.moshi.Moshi
import com.topjohnwu.magisk.ProviderInstaller
import com.topjohnwu.magisk.core.BuildConfig
import com.topjohnwu.magisk.core.model.DateTimeAdapter
import com.topjohnwu.magisk.core.utils.LocaleSetting
import okhttp3.Cache
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.io.File


fun createOkHttpClient(context: Context): OkHttpClient {
    val appCache = Cache(File(context.cacheDir, "okhttp"), 10 * 1024 * 1024)
    val builder = OkHttpClient.Builder().cache(appCache)

    if (!BuildConfig.DEBUG) {
        builder.connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
    }

    builder.addInterceptor { chain ->
        val request = chain.request().newBuilder()
        request.header("User-Agent", "Magisk/${BuildConfig.APP_VERSION_CODE}")
        request.header("Accept-Language", LocaleSetting.instance.currentLocale.toLanguageTag())
        chain.proceed(request.build())
    }

    ProviderInstaller.install(context)

    return builder.build()
}

fun createMoshiConverterFactory(): MoshiConverterFactory {
    val moshi = Moshi.Builder().add(DateTimeAdapter()).build()
    return MoshiConverterFactory.create(moshi)
}

fun createRetrofit(okHttpClient: OkHttpClient): Retrofit.Builder {
    return Retrofit.Builder()
        .addConverterFactory(ScalarsConverterFactory.create())
        .addConverterFactory(createMoshiConverterFactory())
        .client(okHttpClient)
}

inline fun <reified T> createApiService(retrofitBuilder: Retrofit.Builder, baseUrl: String): T {
    return retrofitBuilder
        .baseUrl(baseUrl)
        .build()
        .create(T::class.java)
}
