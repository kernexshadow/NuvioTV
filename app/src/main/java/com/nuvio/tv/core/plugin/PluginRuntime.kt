package com.nuvio.tv.core.plugin

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.nuvio.tv.domain.model.LocalScraperResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "PluginRuntime"
private const val PLUGIN_TIMEOUT_MS = 60_000L
private const val MAX_RESPONSE_SIZE = 5 * 1024 * 1024L

@Singleton
class PluginRuntime @Inject constructor() {
    
    private val gson: Gson = GsonBuilder().create()
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    
    /**
     * Execute a plugin and return streams
     */
    suspend fun executePlugin(
        code: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String,
        scraperSettings: Map<String, Any> = emptyMap()
    ): List<LocalScraperResult> = withContext(Dispatchers.IO) {
        withTimeout(PLUGIN_TIMEOUT_MS) {
            executePluginInternal(code, tmdbId, mediaType, season, episode, scraperId, scraperSettings)
        }
    }
    
    private suspend fun executePluginInternal(
        code: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String,
        scraperSettings: Map<String, Any>
    ): List<LocalScraperResult> {
        val context = Context.enter()
        try {
            // Rhino optimization level (-1 = interpreter mode, required for Android)
            context.optimizationLevel = -1
            context.languageVersion = Context.VERSION_ES6
            
            val scope = context.initStandardObjects()
            
            // Inject global utilities
            injectGlobals(context, scope, scraperId, scraperSettings)
            
            // Inject CommonJS module system
            val moduleExports = context.newObject(scope)
            val moduleObj = context.newObject(scope) as ScriptableObject
            ScriptableObject.putProperty(moduleObj, "exports", moduleExports)
            ScriptableObject.putProperty(scope, "module", moduleObj)
            ScriptableObject.putProperty(scope, "exports", moduleExports)
            
            // Execute plugin code
            context.evaluateString(scope, code, scraperId, 1, null)
            
            // Find and call getStreams function
            val getStreams = findGetStreamsFunction(scope, moduleObj)
                ?: throw IllegalStateException("No getStreams function found in plugin")
            
            // Call getStreams(tmdbId, mediaType, season, episode)
            val args = arrayOf<Any?>(
                tmdbId,
                mediaType,
                season?.let { Context.javaToJS(it, scope) } ?: Undefined.instance,
                episode?.let { Context.javaToJS(it, scope) } ?: Undefined.instance
            )
            
            val result = getStreams.call(context, scope, scope, args)
            
            // Handle promise result
            val resolvedResult = resolvePromise(context, scope, result)
            
            // Convert result to LocalScraperResult list
            return parseResults(resolvedResult)
            
        } finally {
            Context.exit()
        }
    }
    
    private fun findGetStreamsFunction(scope: ScriptableObject, moduleObj: ScriptableObject): Function? {
        // Try global getStreams
        val globalFn = ScriptableObject.getProperty(scope, "getStreams")
        if (globalFn is Function) return globalFn
        
        // Try module.exports.getStreams
        val exports = ScriptableObject.getProperty(moduleObj, "exports")
        if (exports is ScriptableObject) {
            val exportedFn = ScriptableObject.getProperty(exports, "getStreams")
            if (exportedFn is Function) return exportedFn
        }
        
        return null
    }
    
    private fun resolvePromise(context: Context, scope: ScriptableObject, result: Any?): Any? {
        if (result == null || result is Undefined) return null
        
        // Check if result is a Promise-like object
        if (result is ScriptableObject) {
            val then = ScriptableObject.getProperty(result, "then")
            if (then is Function) {
                // It's a promise - we need to resolve it synchronously
                // This is a simplified approach - full async would require more work
                var resolvedValue: Any? = null
                var error: Throwable? = null
                var completed = false
                
                val resolveFn = object : org.mozilla.javascript.BaseFunction() {
                    override fun call(cx: Context, s: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any? {
                        resolvedValue = args?.firstOrNull()
                        completed = true
                        return Undefined.instance
                    }
                }
                
                val rejectFn = object : org.mozilla.javascript.BaseFunction() {
                    override fun call(cx: Context, s: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any? {
                        val errArg = args?.firstOrNull()
                        error = if (errArg is Throwable) errArg else RuntimeException(errArg?.toString() ?: "Promise rejected")
                        completed = true
                        return Undefined.instance
                    }
                }
                
                then.call(context, scope, result, arrayOf(resolveFn, rejectFn))
                
                // Poll for completion (simplified - real impl would use proper async)
                val startTime = System.currentTimeMillis()
                while (!completed && System.currentTimeMillis() - startTime < PLUGIN_TIMEOUT_MS) {
                    Thread.sleep(10)
                }
                
                if (error != null) throw error!!
                return resolvedValue
            }
        }
        
        return result
    }
    
    private fun parseResults(result: Any?): List<LocalScraperResult> {
        if (result == null || result is Undefined) return emptyList()
        
        val results = mutableListOf<LocalScraperResult>()
        
        when (result) {
            is NativeArray -> {
                for (i in 0 until result.length.toInt()) {
                    val item = result.get(i, result)
                    parseResultItem(item)?.let { results.add(it) }
                }
            }
            is List<*> -> {
                result.filterNotNull().forEach { item ->
                    parseResultItem(item)?.let { results.add(it) }
                }
            }
        }
        
        return results.filter { it.url.isNotBlank() }
    }
    
    private fun parseResultItem(item: Any?): LocalScraperResult? {
        if (item == null || item is Undefined) return null
        
        return try {
            when (item) {
                is NativeObject -> {
                    val title = getStringProp(item, "title") ?: getStringProp(item, "name") ?: "Unknown"
                    val url = getStringProp(item, "url") ?: return null
                    
                    LocalScraperResult(
                        title = title,
                        name = getStringProp(item, "name"),
                        url = url,
                        quality = getStringProp(item, "quality"),
                        size = getStringProp(item, "size"),
                        language = getStringProp(item, "language"),
                        provider = getStringProp(item, "provider"),
                        type = getStringProp(item, "type"),
                        seeders = getIntProp(item, "seeders"),
                        peers = getIntProp(item, "peers"),
                        infoHash = getStringProp(item, "infoHash"),
                        headers = getMapProp(item, "headers")
                    )
                }
                is Map<*, *> -> {
                    val title = item["title"]?.toString() ?: item["name"]?.toString() ?: "Unknown"
                    val url = item["url"]?.toString() ?: return null
                    
                    LocalScraperResult(
                        title = title,
                        name = item["name"]?.toString(),
                        url = url,
                        quality = item["quality"]?.toString(),
                        size = item["size"]?.toString(),
                        language = item["language"]?.toString(),
                        provider = item["provider"]?.toString(),
                        type = item["type"]?.toString(),
                        seeders = (item["seeders"] as? Number)?.toInt(),
                        peers = (item["peers"] as? Number)?.toInt(),
                        infoHash = item["infoHash"]?.toString(),
                        headers = null
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse result item: $e")
            null
        }
    }
    
    private fun getStringProp(obj: NativeObject, key: String): String? {
        val value = ScriptableObject.getProperty(obj, key)
        return if (value is Undefined || value == null) null else value.toString()
    }
    
    private fun getIntProp(obj: NativeObject, key: String): Int? {
        val value = ScriptableObject.getProperty(obj, key)
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }
    
    private fun getMapProp(obj: NativeObject, key: String): Map<String, String>? {
        val value = ScriptableObject.getProperty(obj, key)
        if (value is Undefined || value == null) return null
        
        return try {
            when (value) {
                is NativeObject -> {
                    val map = mutableMapOf<String, String>()
                    value.ids.forEach { id ->
                        val k = id.toString()
                        val v = ScriptableObject.getProperty(value, k)
                        if (v !is Undefined && v != null) {
                            map[k] = v.toString()
                        }
                    }
                    map.ifEmpty { null }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun injectGlobals(
        context: Context,
        scope: ScriptableObject,
        scraperId: String,
        scraperSettings: Map<String, Any>
    ) {
        // Console - create as native JS object with functions
        val console = context.newObject(scope)
        val logFn = createLogFunction(scraperId, "D")
        val errorFn = createLogFunction(scraperId, "E")
        val warnFn = createLogFunction(scraperId, "W")
        val infoFn = createLogFunction(scraperId, "I")
        ScriptableObject.putProperty(console, "log", logFn)
        ScriptableObject.putProperty(console, "error", errorFn)
        ScriptableObject.putProperty(console, "warn", warnFn)
        ScriptableObject.putProperty(console, "info", infoFn)
        ScriptableObject.putProperty(console, "debug", logFn)
        ScriptableObject.putProperty(scope, "console", console)
        
        // Fetch function
        val fetchFn = FetchFunction(httpClient, context, scope)
        ScriptableObject.putProperty(scope, "fetch", fetchFn)
        
        // Cheerio-like HTML parser using jsoup
        val cheerioFn = CheerioFunction(context, scope)
        ScriptableObject.putProperty(scope, "cheerio", cheerioFn)
        
        // Require function
        val requireFn = RequireFunction(context, scope, cheerioFn)
        ScriptableObject.putProperty(scope, "require", requireFn)
        
        // URL and URLSearchParams
        injectUrlClasses(context, scope)
        
        // encodeURIComponent
        val encodeUriFn = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context, s: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any {
                val str = args?.firstOrNull()?.toString() ?: ""
                return java.net.URLEncoder.encode(str, "UTF-8")
            }
        }
        ScriptableObject.putProperty(scope, "encodeURIComponent", encodeUriFn)
        
        // decodeURIComponent
        val decodeUriFn = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context, s: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any {
                val str = args?.firstOrNull()?.toString() ?: ""
                return java.net.URLDecoder.decode(str, "UTF-8")
            }
        }
        ScriptableObject.putProperty(scope, "decodeURIComponent", decodeUriFn)
        
        // Global settings - use native JS objects instead of javaToJS
        ScriptableObject.putProperty(scope, "SCRAPER_ID", scraperId)
        
        // Convert scraperSettings to native JS object
        val settingsObj = context.newObject(scope)
        scraperSettings.forEach { (key, value) ->
            ScriptableObject.putProperty(settingsObj, key, value.toString())
        }
        ScriptableObject.putProperty(scope, "SCRAPER_SETTINGS", settingsObj)
        
        ScriptableObject.putProperty(scope, "TMDB_API_KEY", "1865f43a0549ca50d341dd9ab8b29f49")
    }
    
    private fun createLogFunction(tag: String, level: String): org.mozilla.javascript.BaseFunction {
        return object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context, s: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any {
                val message = args?.joinToString(" ") { it?.toString() ?: "null" } ?: ""
                when (level) {
                    "E" -> Log.e("Plugin:$tag", message)
                    "W" -> Log.w("Plugin:$tag", message)
                    "I" -> Log.i("Plugin:$tag", message)
                    else -> Log.d("Plugin:$tag", message)
                }
                return Undefined.instance
            }
        }
    }
    
    private fun injectUrlClasses(context: Context, scope: ScriptableObject) {
        // URL class
        val urlConstructor = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context, s: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any {
                val urlString = args?.firstOrNull()?.toString() ?: ""
                val url = java.net.URL(urlString)
                
                val obj = cx.newObject(s)
                ScriptableObject.putProperty(obj, "href", urlString)
                ScriptableObject.putProperty(obj, "protocol", url.protocol + ":")
                ScriptableObject.putProperty(obj, "host", url.host + if (url.port > 0) ":${url.port}" else "")
                ScriptableObject.putProperty(obj, "hostname", url.host)
                ScriptableObject.putProperty(obj, "port", if (url.port > 0) url.port.toString() else "")
                ScriptableObject.putProperty(obj, "pathname", url.path ?: "/")
                ScriptableObject.putProperty(obj, "search", if (url.query != null) "?${url.query}" else "")
                ScriptableObject.putProperty(obj, "hash", if (url.ref != null) "#${url.ref}" else "")
                
                return obj
            }
            
            override fun construct(cx: Context, scope: org.mozilla.javascript.Scriptable, args: Array<out Any>?): org.mozilla.javascript.Scriptable {
                return call(cx, scope, null, args) as org.mozilla.javascript.Scriptable
            }
        }
        ScriptableObject.putProperty(scope, "URL", urlConstructor)
        
        // URLSearchParams class
        val urlSearchParamsConstructor = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context, s: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any {
                val params = mutableMapOf<String, String>()
                val initArg = args?.firstOrNull()
                
                if (initArg is NativeObject) {
                    initArg.ids.forEach { id ->
                        val key = id.toString()
                        val value = ScriptableObject.getProperty(initArg, key)
                        if (value !is Undefined && value != null) {
                            params[key] = value.toString()
                        }
                    }
                }
                
                val obj = cx.newObject(s)
                
                val toStringFn = object : org.mozilla.javascript.BaseFunction() {
                    override fun call(cx: Context, s: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any {
                        return params.entries.joinToString("&") { (k, v) ->
                            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
                        }
                    }
                }
                ScriptableObject.putProperty(obj, "toString", toStringFn)
                
                return obj
            }
            
            override fun construct(cx: Context, scope: org.mozilla.javascript.Scriptable, args: Array<out Any>?): org.mozilla.javascript.Scriptable {
                return call(cx, scope, null, args) as org.mozilla.javascript.Scriptable
            }
        }
        ScriptableObject.putProperty(scope, "URLSearchParams", urlSearchParamsConstructor)
    }
}

/**
 * Fetch function implementation
 */
class FetchFunction(
    private val httpClient: OkHttpClient,
    private val jsContext: Context,
    private val jsScope: ScriptableObject
) : org.mozilla.javascript.BaseFunction() {
    
    private fun createPromise(
        cx: Context, 
        scope: org.mozilla.javascript.Scriptable,
        isResolved: Boolean,
        value: Any?,
        error: Any? = null
    ): ScriptableObject {
        val promiseObj = cx.newObject(scope) as ScriptableObject
        
        val thenFn = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx2: Context, s: org.mozilla.javascript.Scriptable, thisObj2: org.mozilla.javascript.Scriptable?, args2: Array<out Any>?): Any {
                return if (isResolved) {
                    val callback = args2?.firstOrNull() as? Function
                    if (callback != null) {
                        try {
                            val result = callback.call(cx2, s, thisObj2, arrayOf(value ?: Undefined.instance))
                            // Return a resolved promise with the result
                            createPromise(cx2, s, true, result)
                        } catch (e: Exception) {
                            // Return a rejected promise
                            createPromise(cx2, s, false, null, e.message ?: "Error")
                        }
                    } else {
                        createPromise(cx2, s, true, value)
                    }
                } else {
                    // Pass through rejection
                    createPromise(cx2, s, false, null, error)
                }
            }
        }
        ScriptableObject.putProperty(promiseObj, "then", thenFn)
        
        val catchFn = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx2: Context, s: org.mozilla.javascript.Scriptable, thisObj2: org.mozilla.javascript.Scriptable?, args2: Array<out Any>?): Any {
                return if (!isResolved) {
                    val callback = args2?.firstOrNull() as? Function
                    if (callback != null) {
                        try {
                            val errorObj = cx2.newObject(s)
                            ScriptableObject.putProperty(errorObj, "message", error?.toString() ?: "Unknown error")
                            val result = callback.call(cx2, s, thisObj2, arrayOf(errorObj))
                            createPromise(cx2, s, true, result)
                        } catch (e: Exception) {
                            createPromise(cx2, s, false, null, e.message)
                        }
                    } else {
                        createPromise(cx2, s, false, null, error)
                    }
                } else {
                    // Resolved, no error to catch
                    createPromise(cx2, s, true, value)
                }
            }
        }
        ScriptableObject.putProperty(promiseObj, "catch", catchFn)
        
        val finallyFn = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx2: Context, s: org.mozilla.javascript.Scriptable, thisObj2: org.mozilla.javascript.Scriptable?, args2: Array<out Any>?): Any {
                val callback = args2?.firstOrNull() as? Function
                callback?.call(cx2, s, thisObj2, emptyArray())
                return createPromise(cx2, s, isResolved, value, error)
            }
        }
        ScriptableObject.putProperty(promiseObj, "finally", finallyFn)
        
        return promiseObj
    }
    
    override fun call(
        cx: Context,
        scope: org.mozilla.javascript.Scriptable,
        thisObj: org.mozilla.javascript.Scriptable?,
        args: Array<out Any>?
    ): Any {
        val url = args?.firstOrNull()?.toString() ?: throw IllegalArgumentException("URL required")
        val options = args?.getOrNull(1) as? NativeObject
        
        return try {
            val response = performFetch(url, options)
            val responseObj = createResponseObject(cx, scope, response)
            createPromise(cx, scope, true, responseObj)
        } catch (e: Exception) {
            Log.e(TAG, "Fetch error: ${e.message}")
            createPromise(cx, scope, false, null, e.message ?: "Fetch failed")
        }
    }
    
    private fun performFetch(url: String, options: NativeObject?): FetchResponse {
        val method = options?.let { 
            ScriptableObject.getProperty(it, "method")?.toString()?.uppercase() 
        } ?: "GET"
        
        val headers = mutableMapOf<String, String>()
        options?.let {
            val headersObj = ScriptableObject.getProperty(it, "headers")
            if (headersObj is NativeObject) {
                headersObj.ids.forEach { id ->
                    val key = id.toString()
                    val value = ScriptableObject.getProperty(headersObj, key)
                    if (value !is Undefined && value != null) {
                        headers[key] = value.toString()
                    }
                }
            }
        }
        
        // Default User-Agent
        if (!headers.containsKey("User-Agent")) {
            headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        }
        
        val body = options?.let {
            val bodyProp = ScriptableObject.getProperty(it, "body")
            if (bodyProp !is Undefined && bodyProp != null) bodyProp.toString() else null
        }
        
        val requestBuilder = Request.Builder()
            .url(url)
            .headers(Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
        
        when (method) {
            "POST" -> {
                val contentType = headers["Content-Type"] ?: "application/x-www-form-urlencoded"
                requestBuilder.post((body ?: "").toRequestBody(contentType.toMediaType()))
            }
            "PUT" -> {
                val contentType = headers["Content-Type"] ?: "application/json"
                requestBuilder.put((body ?: "").toRequestBody(contentType.toMediaType()))
            }
            "DELETE" -> requestBuilder.delete()
            else -> requestBuilder.get()
        }
        
        val request = requestBuilder.build()
        val response = httpClient.newCall(request).execute()
        
        val responseBody = response.body?.string() ?: ""
        val responseHeaders = mutableMapOf<String, String>()
        response.headers.forEach { (name, value) ->
            responseHeaders[name] = value
        }
        
        return FetchResponse(
            ok = response.isSuccessful,
            status = response.code,
            statusText = response.message,
            url = response.request.url.toString(),
            body = responseBody,
            headers = responseHeaders
        )
    }
    
    private fun createResponseObject(cx: Context, scope: org.mozilla.javascript.Scriptable, response: FetchResponse): ScriptableObject {
        val responseObj = cx.newObject(scope) as ScriptableObject
        
        ScriptableObject.putProperty(responseObj, "ok", response.ok)
        ScriptableObject.putProperty(responseObj, "status", response.status)
        ScriptableObject.putProperty(responseObj, "statusText", response.statusText)
        ScriptableObject.putProperty(responseObj, "url", response.url)
        
        // Headers object
        val headersObj = cx.newObject(scope)
        response.headers.forEach { (k, v) ->
            ScriptableObject.putProperty(headersObj, k.lowercase(), v)
        }
        val getFn = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx2: Context, s: org.mozilla.javascript.Scriptable, thisObj2: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any? {
                val key = args?.firstOrNull()?.toString()?.lowercase() ?: return null
                return response.headers[key] ?: response.headers.entries.find { 
                    it.key.lowercase() == key 
                }?.value
            }
        }
        ScriptableObject.putProperty(headersObj, "get", getFn)
        ScriptableObject.putProperty(responseObj, "headers", headersObj)
        
        // text() method - uses createPromise for proper chaining
        val textFn = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx2: Context, s: org.mozilla.javascript.Scriptable, thisObj2: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any {
                return createPromise(cx2, s, true, response.body)
            }
        }
        ScriptableObject.putProperty(responseObj, "text", textFn)
        
        // json() method - uses createPromise for proper chaining
        val jsonFn = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx2: Context, s: org.mozilla.javascript.Scriptable, thisObj2: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any {
                return try {
                    val parsed = cx2.evaluateString(s, "(${response.body})", "json", 1, null)
                    createPromise(cx2, s, true, parsed)
                } catch (e: Exception) {
                    createPromise(cx2, s, false, null, "JSON parse error: ${e.message}")
                }
            }
        }
        ScriptableObject.putProperty(responseObj, "json", jsonFn)
        
        return responseObj
    }
}

data class FetchResponse(
    val ok: Boolean,
    val status: Int,
    val statusText: String,
    val url: String,
    val body: String,
    val headers: Map<String, String>
)

/**
 * Cheerio-like HTML parser using jsoup
 */
class CheerioFunction(
    private val jsContext: Context,
    private val jsScope: ScriptableObject
) : org.mozilla.javascript.BaseFunction() {
    
    override fun call(
        cx: Context,
        scope: org.mozilla.javascript.Scriptable,
        thisObj: org.mozilla.javascript.Scriptable?,
        args: Array<out Any>?
    ): Any {
        // Return cheerio object with load function
        val cheerioObj = cx.newObject(scope)
        
        val loadFn = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx2: Context, s: org.mozilla.javascript.Scriptable, thisObj2: org.mozilla.javascript.Scriptable?, args2: Array<out Any>?): Any {
                val html = args2?.firstOrNull()?.toString() ?: ""
                val doc = Jsoup.parse(html)
                return createJQueryFunction(cx2, s as ScriptableObject, doc)
            }
        }
        ScriptableObject.putProperty(cheerioObj, "load", loadFn)
        
        return cheerioObj
    }
    
    private fun createJQueryFunction(cx: Context, scope: ScriptableObject, doc: Document): org.mozilla.javascript.BaseFunction {
        return object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx2: Context, s: org.mozilla.javascript.Scriptable, thisObj2: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any {
                val selector = args?.firstOrNull()?.toString() ?: return createCheerioWrapper(cx2, s as ScriptableObject, Elements())
                
                val elements = try {
                    doc.select(selector)
                } catch (e: Exception) {
                    Elements()
                }
                
                return createCheerioWrapper(cx2, s as ScriptableObject, elements)
            }
        }
    }
    
    private fun createCheerioWrapper(cx: Context, scope: ScriptableObject, elements: Elements): ScriptableObject {
        val wrapper = cx.newObject(scope) as ScriptableObject
        
        // length property
        ScriptableObject.putProperty(wrapper, "length", elements.size)
        
        // Index access
        elements.forEachIndexed { index, element ->
            ScriptableObject.putProperty(wrapper, index, createElementWrapper(cx, scope, element))
        }
        
        // each(callback)
        val eachFn = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx2: Context, s: org.mozilla.javascript.Scriptable, thisObj2: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any {
                val callback = args?.firstOrNull() as? Function ?: return wrapper
                elements.forEachIndexed { index, element ->
                    val elWrapper = createElementWrapper(cx2, s as ScriptableObject, element)
                    callback.call(cx2, s, thisObj2, arrayOf(index, elWrapper))
                }
                return wrapper
            }
        }
        ScriptableObject.putProperty(wrapper, "each", eachFn)
        
        // find(selector)
        val findFn = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx2: Context, s: org.mozilla.javascript.Scriptable, thisObj2: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any {
                val selector = args?.firstOrNull()?.toString() ?: return createCheerioWrapper(cx2, s as ScriptableObject, Elements())
                val found = Elements()
                elements.forEach { el ->
                    try {
                        found.addAll(el.select(selector))
                    } catch (e: Exception) { }
                }
                return createCheerioWrapper(cx2, s as ScriptableObject, found)
            }
        }
        ScriptableObject.putProperty(wrapper, "find", findFn)
        
        // text()
        val textFn = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx2: Context, s: org.mozilla.javascript.Scriptable, thisObj2: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any {
                return elements.text()
            }
        }
        ScriptableObject.putProperty(wrapper, "text", textFn)
        
        // html()
        val htmlFn = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx2: Context, s: org.mozilla.javascript.Scriptable, thisObj2: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any {
                return elements.html()
            }
        }
        ScriptableObject.putProperty(wrapper, "html", htmlFn)
        
        // attr(name)
        val attrFn = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx2: Context, s: org.mozilla.javascript.Scriptable, thisObj2: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any? {
                val name = args?.firstOrNull()?.toString() ?: return null
                return elements.firstOrNull()?.attr(name) ?: Undefined.instance
            }
        }
        ScriptableObject.putProperty(wrapper, "attr", attrFn)
        
        // first()
        val firstFn = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx2: Context, s: org.mozilla.javascript.Scriptable, thisObj2: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any {
                val first = elements.firstOrNull()
                return if (first != null) {
                    createCheerioWrapper(cx2, s as ScriptableObject, Elements(listOf(first)))
                } else {
                    createCheerioWrapper(cx2, s as ScriptableObject, Elements())
                }
            }
        }
        ScriptableObject.putProperty(wrapper, "first", firstFn)
        
        // last()
        val lastFn = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx2: Context, s: org.mozilla.javascript.Scriptable, thisObj2: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any {
                val last = elements.lastOrNull()
                return if (last != null) {
                    createCheerioWrapper(cx2, s as ScriptableObject, Elements(listOf(last)))
                } else {
                    createCheerioWrapper(cx2, s as ScriptableObject, Elements())
                }
            }
        }
        ScriptableObject.putProperty(wrapper, "last", lastFn)
        
        // next()
        val nextFn = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx2: Context, s: org.mozilla.javascript.Scriptable, thisObj2: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any {
                val nextElements = Elements()
                elements.forEach { el ->
                    el.nextElementSibling()?.let { nextElements.add(it) }
                }
                return createCheerioWrapper(cx2, s as ScriptableObject, nextElements)
            }
        }
        ScriptableObject.putProperty(wrapper, "next", nextFn)
        
        // prev()
        val prevFn = object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx2: Context, s: org.mozilla.javascript.Scriptable, thisObj2: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any {
                val prevElements = Elements()
                elements.forEach { el ->
                    el.previousElementSibling()?.let { prevElements.add(it) }
                }
                return createCheerioWrapper(cx2, s as ScriptableObject, prevElements)
            }
        }
        ScriptableObject.putProperty(wrapper, "prev", prevFn)
        
        return wrapper
    }
    
    private fun createElementWrapper(cx: Context, scope: ScriptableObject, element: Element): ScriptableObject {
        return createCheerioWrapper(cx, scope, Elements(listOf(element)))
    }
}

/**
 * Require function for CommonJS modules
 */
class RequireFunction(
    private val jsContext: Context,
    private val jsScope: ScriptableObject,
    private val cheerioFn: CheerioFunction
) : org.mozilla.javascript.BaseFunction() {
    
    override fun call(
        cx: Context,
        scope: org.mozilla.javascript.Scriptable,
        thisObj: org.mozilla.javascript.Scriptable?,
        args: Array<out Any>?
    ): Any {
        val moduleName = args?.firstOrNull()?.toString() ?: throw IllegalArgumentException("Module name required")
        
        return when (moduleName) {
            "cheerio", "cheerio-without-node-native", "react-native-cheerio" -> cheerioFn
            else -> throw IllegalArgumentException("Module '$moduleName' is not available")
        }
    }
}
