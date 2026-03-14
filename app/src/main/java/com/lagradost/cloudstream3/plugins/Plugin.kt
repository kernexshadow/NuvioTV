package com.lagradost.cloudstream3.plugins

import android.app.Activity
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.ExtractorApi

/**
 * The actual base class that CloudStream extensions extend.
 * Extensions override [load] to call [registerMainAPI] / [registerExtractorAPI].
 */
open class Plugin {
    private val _registeredMainAPIs = mutableListOf<MainAPI>()
    private val _registeredExtractorAPIs = mutableListOf<ExtractorApi>()

    val registeredMainAPIs: List<MainAPI> get() = _registeredMainAPIs
    val registeredExtractorAPIs: List<ExtractorApi> get() = _registeredExtractorAPIs

    /** Extensions can set this to provide a settings UI callback. No-op in NuvioTV. */
    var openSettings: ((Context) -> Unit)? = null

    /**
     * Called when the plugin is loaded. Override to register APIs.
     * The [activity] parameter may be null when loaded outside an Activity context.
     */
    @Suppress("UNUSED_PARAMETER")
    open fun load(activity: Activity?) {}

    fun registerMainAPI(element: MainAPI) {
        Log.d("CS3Plugin", "registerMainAPI called: ${element.name} (${element.javaClass.name})")
        _registeredMainAPIs.add(element)
    }

    fun registerExtractorAPI(element: ExtractorApi) {
        Log.d("CS3Plugin", "registerExtractorAPI called: ${element.name} (${element.javaClass.name})")
        _registeredExtractorAPIs.add(element)
    }

    // Some extensions call these overloads
    open fun load(context: Context) {
        load(context as? Activity)
    }
}
