package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionCatalogSource
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CollectionsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "collections"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val gson = Gson()
    private val collectionsKey = stringPreferencesKey("collections_json")

    val collections: Flow<List<Collection>> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            factory.get(pid, FEATURE).data.map { prefs ->
                parseCollections(prefs[collectionsKey])
            }
        }

    suspend fun setCollections(collections: List<Collection>) {
        store().edit { prefs ->
            if (collections.isEmpty()) {
                prefs.remove(collectionsKey)
            } else {
                prefs[collectionsKey] = gson.toJson(collections.map { it.toSerializable() })
            }
        }
    }

    suspend fun addCollection(collection: Collection) {
        store().edit { prefs ->
            val current = parseCollections(prefs[collectionsKey]).toMutableList()
            current.add(collection)
            prefs[collectionsKey] = gson.toJson(current.map { it.toSerializable() })
        }
    }

    suspend fun updateCollection(collection: Collection) {
        store().edit { prefs ->
            val current = parseCollections(prefs[collectionsKey]).toMutableList()
            val index = current.indexOfFirst { it.id == collection.id }
            if (index >= 0) {
                current[index] = collection
            }
            prefs[collectionsKey] = gson.toJson(current.map { it.toSerializable() })
        }
    }

    suspend fun removeCollection(collectionId: String) {
        store().edit { prefs ->
            val current = parseCollections(prefs[collectionsKey]).toMutableList()
            current.removeAll { it.id == collectionId }
            if (current.isEmpty()) {
                prefs.remove(collectionsKey)
            } else {
                prefs[collectionsKey] = gson.toJson(current.map { it.toSerializable() })
            }
        }
    }

    fun generateId(): String = UUID.randomUUID().toString()

    private fun parseCollections(json: String?): List<Collection> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<SerializableCollection>>() {}.type
            val parsed = gson.fromJson<List<SerializableCollection>>(json, type).orEmpty()
            parsed.map { it.toDomain() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class SerializableCollection(
        val id: String,
        val title: String,
        val folders: List<SerializableFolder> = emptyList()
    )

    private data class SerializableFolder(
        val id: String,
        val title: String,
        val coverImageUrl: String? = null,
        val tileShape: String = "SQUARE",
        val hideTitle: Boolean = false,
        val catalogSources: List<SerializableCatalogSource> = emptyList()
    )

    private data class SerializableCatalogSource(
        val addonId: String,
        val type: String,
        val catalogId: String
    )

    private fun Collection.toSerializable() = SerializableCollection(
        id = id,
        title = title,
        folders = folders.map { folder ->
            SerializableFolder(
                id = folder.id,
                title = folder.title,
                coverImageUrl = folder.coverImageUrl,
                tileShape = folder.tileShape.name,
                hideTitle = folder.hideTitle,
                catalogSources = folder.catalogSources.map { source ->
                    SerializableCatalogSource(
                        addonId = source.addonId,
                        type = source.type,
                        catalogId = source.catalogId
                    )
                }
            )
        }
    )

    private fun SerializableCollection.toDomain() = Collection(
        id = id,
        title = title,
        folders = folders.map { folder ->
            CollectionFolder(
                id = folder.id,
                title = folder.title,
                coverImageUrl = folder.coverImageUrl,
                tileShape = PosterShape.fromString(folder.tileShape),
                hideTitle = folder.hideTitle,
                catalogSources = folder.catalogSources.map { source ->
                    CollectionCatalogSource(
                        addonId = source.addonId,
                        type = source.type,
                        catalogId = source.catalogId
                    )
                }
            )
        }
    )
}
