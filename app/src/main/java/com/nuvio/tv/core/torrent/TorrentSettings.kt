package com.nuvio.tv.core.torrent

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.torrentDataStore by preferencesDataStore(name = "torrent_settings")

data class TorrentSettingsData(
    val maxCacheSizeMb: Int = 2048,
    val maxConnections: Int = 200,
    val bufferPiecesBeforePlayback: Int = 15,
    val enableDht: Boolean = true,
    val enableEncryption: Boolean = true,
    val enableUpload: Boolean = true,
    val autoClearCacheOnExit: Boolean = false
)

@Singleton
class TorrentSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val MAX_CACHE_SIZE_MB = intPreferencesKey("max_cache_size_mb")
        val MAX_CONNECTIONS = intPreferencesKey("max_connections")
        val BUFFER_PIECES = intPreferencesKey("buffer_pieces_before_playback")
        val ENABLE_DHT = booleanPreferencesKey("enable_dht")
        val ENABLE_ENCRYPTION = booleanPreferencesKey("enable_encryption")
        val ENABLE_UPLOAD = booleanPreferencesKey("enable_upload")
        val AUTO_CLEAR_CACHE = booleanPreferencesKey("auto_clear_cache_on_exit")
    }

    val settings: Flow<TorrentSettingsData> = context.torrentDataStore.data.map { prefs ->
        TorrentSettingsData(
            maxCacheSizeMb = prefs[Keys.MAX_CACHE_SIZE_MB] ?: 2048,
            maxConnections = prefs[Keys.MAX_CONNECTIONS] ?: 200,
            bufferPiecesBeforePlayback = prefs[Keys.BUFFER_PIECES] ?: 15,
            enableDht = prefs[Keys.ENABLE_DHT] ?: true,
            enableEncryption = prefs[Keys.ENABLE_ENCRYPTION] ?: true,
            enableUpload = prefs[Keys.ENABLE_UPLOAD] ?: true,
            autoClearCacheOnExit = prefs[Keys.AUTO_CLEAR_CACHE] ?: true
        )
    }

    fun setEnableUpload(enabled: Boolean) {
        scope.launch {
            context.torrentDataStore.edit { it[Keys.ENABLE_UPLOAD] = enabled }
        }
    }

    fun setAutoClearCache(enabled: Boolean) {
        scope.launch {
            context.torrentDataStore.edit { it[Keys.AUTO_CLEAR_CACHE] = enabled }
        }
    }

    fun setBufferPieces(count: Int) {
        scope.launch {
            context.torrentDataStore.edit { it[Keys.BUFFER_PIECES] = count }
        }
    }

    fun setMaxCacheSizeMb(sizeMb: Int) {
        scope.launch {
            context.torrentDataStore.edit { it[Keys.MAX_CACHE_SIZE_MB] = sizeMb }
        }
    }
}
