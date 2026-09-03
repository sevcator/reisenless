package com.topjohnwu.magisk.core.model

import android.os.Parcelable
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.parcelize.Parcelize
import java.time.Instant

@JsonClass(generateAdapter = true)
class UpdateJson(
    val magisk: UpdateInfo = UpdateInfo(),
)

@Parcelize
@JsonClass(generateAdapter = true)
data class UpdateInfo(
    val version: String = "",
    val versionCode: Int = -1,
    val link: String = "",
    val note: String = "",
) : Parcelable

@JsonClass(generateAdapter = true)
data class ReleaseAssets(
    val name: String,
    @param:Json(name = "browser_download_url") val url: String,
)

@JsonClass(generateAdapter = true)
data class Release(
    @param:Json(name = "tag_name") val tag: String,
    val name: String,
    val prerelease: Boolean,
    val assets: List<ReleaseAssets>,
    val body: String,
    @param:Json(name = "created_at") val createdTime: Instant,
) {
    val versionCode: Int get() = if (tag.startsWith("v")) {
        (tag.drop(1).toFloat() * 1000).toInt()
    } else {
        tag.substringAfterLast('-').toInt()
    }
}
