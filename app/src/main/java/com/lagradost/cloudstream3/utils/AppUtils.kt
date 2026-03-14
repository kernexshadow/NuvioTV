@file:Suppress("unused")

package com.lagradost.cloudstream3.utils

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@PublishedApi
internal val gson = Gson()

fun <T> T.toJson(): String = gson.toJson(this)

inline fun <reified T> String.parseJson(): T =
    gson.fromJson(this, object : TypeToken<T>() {}.type)

inline fun <reified T> String.tryParseJson(): T? = try {
    gson.fromJson(this, object : TypeToken<T>() {}.type)
} catch (_: Exception) {
    null
}

/** Object wrapper for extensions that import AppUtils.parseJson etc. */
object AppUtils {
    fun <T> toJson(obj: T): String = gson.toJson(obj)

    inline fun <reified T> parseJson(json: String): T =
        gson.fromJson(json, object : TypeToken<T>() {}.type)

    inline fun <reified T> tryParseJson(json: String): T? = try {
        gson.fromJson(json, object : TypeToken<T>() {}.type)
    } catch (_: Exception) {
        null
    }
}

fun getQualityFromName(name: String?): Int {
    if (name == null) return Qualities.Unknown.value
    val lower = name.lowercase()
    return when {
        lower.contains("2160") || lower.contains("4k") || lower.contains("uhd") -> Qualities.P2160.value
        lower.contains("1440") -> Qualities.P1440.value
        lower.contains("1080") -> Qualities.P1080.value
        lower.contains("720") -> Qualities.P720.value
        lower.contains("480") -> Qualities.P480.value
        lower.contains("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}
