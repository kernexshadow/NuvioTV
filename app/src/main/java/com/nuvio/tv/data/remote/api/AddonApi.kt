package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.AddonManifestDto
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

interface AddonApi {

    @GET
    suspend fun getManifest(@Url manifestUrl: String): Response<AddonManifestDto>

    @GET
    suspend fun getCatalog(@Url catalogUrl: String): Response<CatalogResponseDto>
}
