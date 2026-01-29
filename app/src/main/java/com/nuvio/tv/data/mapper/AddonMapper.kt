package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.AddonManifestDto
import com.nuvio.tv.data.remote.dto.CatalogDescriptorDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType

fun AddonManifestDto.toDomain(baseUrl: String): Addon {
    return Addon(
        id = id,
        name = name,
        version = version,
        description = description,
        logo = logo,
        baseUrl = baseUrl,
        catalogs = catalogs.map { it.toDomain() },
        types = types.map { ContentType.fromString(it) }
    )
}

fun CatalogDescriptorDto.toDomain(): CatalogDescriptor {
    return CatalogDescriptor(
        type = ContentType.fromString(type),
        id = id,
        name = name
    )
}
