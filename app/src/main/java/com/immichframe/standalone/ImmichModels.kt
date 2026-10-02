package com.immichframe.standalone

import android.graphics.Bitmap
import com.google.gson.annotations.SerializedName

/**
 * Data models for direct communication with the Immich REST API.
 */

data class ImmichAsset(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: String? = "IMAGE",
    @SerializedName("originalFileName") val originalFileName: String? = null,
    @SerializedName("fileCreatedAt") val fileCreatedAt: String? = null,
    @SerializedName("fileModifiedAt") val fileModifiedAt: String? = null,
    @SerializedName("localDateTime") val localDateTime: String? = null,
    @SerializedName("duration") val duration: String? = null,
    @SerializedName("thumbhash") val thumbhash: String? = null,
    @SerializedName("isFavorite") val isFavorite: Boolean? = null,
    @SerializedName("isArchived") val isArchived: Boolean? = null,
    @SerializedName("exifInfo") val exifInfo: ImmichExifInfo? = null,
    @SerializedName("people") val people: List<ImmichPerson>? = null
)

data class ImmichExifInfo(
    @SerializedName("make") val make: String? = null,
    @SerializedName("model") val model: String? = null,
    @SerializedName("lensModel") val lensModel: String? = null,
    @SerializedName("fNumber") val fNumber: Double? = null,
    @SerializedName("focalLength") val focalLength: Double? = null,
    @SerializedName("iso") val iso: Int? = null,
    @SerializedName("exposureTime") val exposureTime: String? = null,
    @SerializedName("fileSizeInByte") val fileSizeInByte: Long? = null,
    @SerializedName("city") val city: String? = null,
    @SerializedName("state") val state: String? = null,
    @SerializedName("country") val country: String? = null,
    @SerializedName("dateTimeOriginal") val dateTimeOriginal: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("exifImageWidth") val exifImageWidth: Int? = null,
    @SerializedName("exifImageHeight") val exifImageHeight: Int? = null,
    @SerializedName("orientation") val orientation: String? = null,
    @SerializedName("rating") val rating: Int? = null
)

data class ImmichPerson(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("birthDate") val birthDate: String? = null
)

data class RandomSearchDto(
    @SerializedName("size") val size: Int = 30,
    @SerializedName("type") val type: String? = "IMAGE",
    @SerializedName("withExif") val withExif: Boolean = true,
    @SerializedName("withPeople") val withPeople: Boolean = true,
    @SerializedName("takenAfter") val takenAfter: String? = null,
    @SerializedName("takenBefore") val takenBefore: String? = null,
    @SerializedName("visibility") val visibility: String? = null,
    @SerializedName("albumIds") val albumIds: List<String>? = null,
    @SerializedName("personIds") val personIds: List<String>? = null,
    @SerializedName("tagIds") val tagIds: List<String>? = null,
    @SerializedName("isFavorite") val isFavorite: Boolean? = null
)

data class MetadataSearchDto(
    @SerializedName("page") val page: Int = 1,
    @SerializedName("size") val size: Int = 100,
    @SerializedName("type") val type: String? = "IMAGE",
    @SerializedName("isFavorite") val isFavorite: Boolean? = null,
    @SerializedName("albumIds") val albumIds: List<String>? = null,
    @SerializedName("personIds") val personIds: List<String>? = null,
    @SerializedName("tagIds") val tagIds: List<String>? = null,
    @SerializedName("withExif") val withExif: Boolean = true,
    @SerializedName("withPeople") val withPeople: Boolean = true,
    @SerializedName("order") val order: String? = "desc",
    @SerializedName("takenAfter") val takenAfter: String? = null
)

data class MetadataSearchResponse(
    @SerializedName("assets") val assets: MetadataSearchAssets
)

data class MetadataSearchAssets(
    @SerializedName("total") val total: Int = 0,
    @SerializedName("count") val count: Int = 0,
    @SerializedName("items") val items: List<ImmichAsset> = emptyList(),
    @SerializedName("nextPage") val nextPage: String? = null
)

data class MemoryResponseDto(
    @SerializedName("id") val id: String,
    @SerializedName("assets") val assets: List<ImmichAsset> = emptyList(),
    @SerializedName("data") val data: MemoryDataDto? = null
)

data class MemoryDataDto(
    @SerializedName("year") val year: Int? = null
)

data class ServerVersionDto(
    @SerializedName("major") val major: Int? = null,
    @SerializedName("minor") val minor: Int? = null,
    @SerializedName("patch") val patch: Int? = null
)

data class ImmichAlbumDto(
    @SerializedName("id") val id: String,
    @SerializedName("albumName") val albumName: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("assetCount") val assetCount: Int? = 0
)

data class ImmichPeopleResponse(
    @SerializedName("people") val people: List<ImmichPerson> = emptyList(),
    @SerializedName("total") val total: Int = 0
)

/**
 * Display container for image and formatted metadata.
 */
data class ImmichImageDisplay(
    val assetId: String,
    val bitmap: Bitmap,
    val blurredBackground: Bitmap?,
    val photoDate: String,
    val imageLocation: String,
    val isPortrait: Boolean,
    val asset: ImmichAsset? = null
)
