package com.immichframe.standalone

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface ImmichApiService {
    @GET("api/server/version")
    fun getServerVersion(): Call<ServerVersionDto>

    @GET("api/server-info/version")
    fun getServerInfoVersion(): Call<ServerVersionDto>

    @POST("api/search/random")
    fun getRandomAssets(@Body body: RandomSearchDto): Call<List<ImmichAsset>>

    @POST("api/search/metadata")
    fun searchMetadata(@Body body: MetadataSearchDto): Call<MetadataSearchResponse>

    @GET("api/memories")
    fun getMemories(@Query("timestamp") timestamp: String? = null): Call<List<MemoryResponseDto>>

    @GET("api/albums")
    fun getAlbums(): Call<List<ImmichAlbumDto>>

    @GET("api/people")
    fun getPeople(@Query("withHidden") withHidden: Boolean = true): Call<ImmichPeopleResponse>
}
