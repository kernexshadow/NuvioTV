package com.nuvio.tv.domain.model

data class Addon(
    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    val logo: String?,
    val baseUrl: String,
    val catalogs: List<CatalogDescriptor>,
    val types: List<ContentType>
)

data class CatalogDescriptor(
    val type: ContentType,
    val id: String,
    val name: String
)
