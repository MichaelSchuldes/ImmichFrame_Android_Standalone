package com.immichframe.standalone

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionSpec

class ImmichManager(private val context: Context) {

    data class FrameSettings(
        val serverUrl: String,
        val apiKey: String,
        val intervalSeconds: Int,
        val transitionDurationSeconds: Double,
        val favoritesOnly: Boolean,
        val includeMemories: Boolean,
        val albumIds: List<String>,
        val personIds: List<String>,
        val excludedPeople: List<String> = emptyList(),
        val tagIds: List<String>,
        val layout: String,
        val imageFill: Boolean,
        val imageZoom: Boolean,
        val blurredBackground: Boolean,
        val showPhotoDate: Boolean,
        val showImageLocation: Boolean,
        val showClock: Boolean,
        val showCurrentDate: Boolean,
        val photoDateFormat: String,
        val clockFormat: String,
        val imageLocationFormat: String,
        val primaryColor: String?,
        val secondaryColor: String?,
        val baseFontSize: String?,
        val recentDays: Int = 0
    )

    private val assetQueue = Collections.synchronizedList(mutableListOf<ImmichAsset>())
    private val prefetchedQueue = Collections.synchronizedList(mutableListOf<ImmichImageDisplay>())
    private val history = Collections.synchronizedList(mutableListOf<ImmichImageDisplay>())
    private var historyIndex = -1
    private var offlineIndex = -1

    @Volatile
    var isOnline: Boolean = true
        private set

    var onOnlineStatusChanged: ((Boolean) -> Unit)? = null

    private val prefetchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var isPrefetching = false
    @Volatile
    private var isFetchingAssets = false

    @Volatile
    private var albumCache: Map<String, String>? = null
    @Volatile
    private var albumCacheTime = 0L
    @Volatile
    private var albumAssetCountCache: Map<String, Int> = emptyMap()

    @Volatile
    private var peopleCache: Map<String, String>? = null
    @Volatile
    private var peopleCacheTime = 0L

    @Volatile
    private var lastServerUrl: String = ""
    @Volatile
    private var lastApiKey: String = ""

    private val okHttpClient: OkHttpClient = buildOkHttpClient()

    fun getSettings(): FrameSettings {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val url = prefs.getString("immich_server_url", "")
            ?.takeIf { it.isNotBlank() }
            ?: prefs.getString("webview_url", "") ?: ""

        val key = prefs.getString("immich_api_key", "")
            ?.takeIf { it.isNotBlank() }
            ?: prefs.getString("authSecret", "") ?: ""

        val intervalStr = prefs.getString("slideshow_interval", "20") ?: "20"
        val interval = intervalStr.toIntOrNull()?.coerceAtLeast(3) ?: 20

        val transitionStr = prefs.getString("transition_duration", "1.0") ?: "1.0"
        val transition = transitionStr.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 1.0

        val favoritesOnly = prefs.getBoolean("filter_favorites_only", false)
        val includeMemories = prefs.getBoolean("filter_include_memories", false)

        val albumsRaw = prefs.getString("filter_album_ids", "") ?: ""
        val albumIds = albumsRaw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val peopleRaw = prefs.getString("filter_person_ids", "") ?: ""
        val personIds = peopleRaw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val excludedRaw = prefs.getString("filter_excluded_person_ids", "") ?: ""
        val excludedPeople = excludedRaw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val tagsRaw = prefs.getString("filter_tag_ids", "") ?: ""
        val tagIds = tagsRaw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val splitView = prefs.getBoolean("splitView", false)
        val layout = if (splitView) "splitview" else (prefs.getString("layout", "single") ?: "single")

        val imageFill = prefs.getBoolean("imageFill", false)
        val imageZoom = prefs.getBoolean("imageZoom", false)
        val blurredBg = prefs.getBoolean("blurredBackground", true)

        val showDate = prefs.getBoolean("showPhotoDate", true)
        val showLoc = prefs.getBoolean("showImageLocation", true)
        val showClock = prefs.getBoolean("showClock", true)
        val showCurrent = prefs.getBoolean("showCurrentDate", true)

        val photoDateFormat = Helpers.sanitizeDatePattern(
            prefs.getString("photoDateFormat", "EEE, MMM d, yyyy")?.takeIf { it.isNotBlank() }
        )

        val clockFormat = prefs.getString("clockFormat", "h:mm a")
            ?.takeIf { it.isNotBlank() } ?: "h:mm a"

        val locFormat = prefs.getString("imageLocationFormat", "City, Country")
            ?.takeIf { it.isNotBlank() } ?: "City, Country"

        val primaryColor = prefs.getString("primaryColor", "#FFFFFF")
        val secondaryColor = prefs.getString("secondaryColor", "#000000")
        val baseFontSize = prefs.getString("baseFontSize", "24px")

        val recentDaysStr = prefs.getString("filter_recent_days", "") ?: ""
        val recentDays = recentDaysStr.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0

        return FrameSettings(
            serverUrl = normalizeUrl(url),
            apiKey = key.trim(),
            intervalSeconds = interval,
            transitionDurationSeconds = transition,
            favoritesOnly = favoritesOnly,
            includeMemories = includeMemories,
            albumIds = albumIds,
            personIds = personIds,
            excludedPeople = excludedPeople,
            tagIds = tagIds,
            layout = layout,
            imageFill = imageFill,
            imageZoom = imageZoom,
            blurredBackground = blurredBg,
            showPhotoDate = showDate,
            showImageLocation = showLoc,
            showClock = showClock,
            showCurrentDate = showCurrent,
            photoDateFormat = photoDateFormat,
            clockFormat = clockFormat,
            imageLocationFormat = locFormat,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            baseFontSize = baseFontSize,
            recentDays = recentDays
        )
    }

    private fun normalizeUrl(url: String): String {
        var clean = url.trim()
        if (clean.isBlank()) return ""
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "http://$clean"
        }
        if (!clean.endsWith("/")) {
            clean = "$clean/"
        }
        return clean
    }

    private fun createApiService(settings: FrameSettings): ImmichApiService? {
        if (settings.serverUrl.isBlank()) return null
        val retrofit = Retrofit.Builder()
            .baseUrl(settings.serverUrl)
            .client(
                okHttpClient.newBuilder()
                    .addInterceptor { chain ->
                        val req = chain.request().newBuilder()
                        if (settings.apiKey.isNotBlank()) {
                            req.addHeader("x-api-key", settings.apiKey)
                        }
                        chain.proceed(req.build())
                    }
                    .build()
            )
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return retrofit.create(ImmichApiService::class.java)
    }

    fun testConnection(
        serverUrl: String,
        apiKey: String,
        callback: (success: Boolean, message: String) -> Unit
    ) {
        val normalized = normalizeUrl(serverUrl)
        if (normalized.isBlank()) {
            callback(false, "Server URL is required")
            return
        }

        val testSettings = FrameSettings(
            serverUrl = normalized,
            apiKey = apiKey.trim(),
            intervalSeconds = 20,
            transitionDurationSeconds = 1.0,
            favoritesOnly = false,
            includeMemories = false,
            albumIds = emptyList(),
            personIds = emptyList(),
            tagIds = emptyList(),
            layout = "single",
            imageFill = false,
            imageZoom = false,
            blurredBackground = true,
            showPhotoDate = true,
            showImageLocation = true,
            showClock = true,
            showCurrentDate = true,
            photoDateFormat = "",
            clockFormat = "",
            imageLocationFormat = "",
            primaryColor = null,
            secondaryColor = null,
            baseFontSize = null,
            recentDays = 0
        )

        val apiService = createApiService(testSettings)
        if (apiService == null) {
            callback(false, "Invalid Server URL")
            return
        }

        apiService.getServerVersion().enqueue(object : Callback<ServerVersionDto> {
            override fun onResponse(call: Call<ServerVersionDto>, response: Response<ServerVersionDto>) {
                if (response.isSuccessful && response.body() != null) {
                    val v = response.body()!!
                    val versionStr = "${v.major}.${v.minor}.${v.patch}"
                    callback(true, "Connected successfully! Immich Server v$versionStr")
                } else if (response.code() == 401) {
                    callback(false, "Authentication failed (401). Please check your API Key.")
                } else {
                    // Try alternative endpoint /api/server-info/version
                    apiService.getServerInfoVersion().enqueue(object : Callback<ServerVersionDto> {
                        override fun onResponse(c: Call<ServerVersionDto>, r: Response<ServerVersionDto>) {
                            if (r.isSuccessful && r.body() != null) {
                                val v = r.body()!!
                                val versionStr = "${v.major}.${v.minor}.${v.patch}"
                                callback(true, "Connected successfully! Immich Server v$versionStr")
                            } else {
                                callback(false, "HTTP ${response.code()}: ${response.message()}")
                            }
                        }

                        override fun onFailure(c: Call<ServerVersionDto>, t: Throwable) {
                            callback(false, "Connection error: ${t.localizedMessage}")
                        }
                    })
                }
            }

            override fun onFailure(call: Call<ServerVersionDto>, t: Throwable) {
                callback(false, "Connection error: ${t.localizedMessage}")
            }
        })
    }

    fun setOnlineStatus(online: Boolean) {
        if (isOnline != online) {
            isOnline = online
            Log.d("ImmichManager", "Connection status changed: isOnline=$online")
            onOnlineStatusChanged?.invoke(online)
            if (online) {
                triggerPrefetch()
            }
        }
    }

    suspend fun getNextImage(): ImmichImageDisplay? = withContext(Dispatchers.IO) {
        val settings = getSettings()
        if (settings.serverUrl.isBlank()) {
            return@withContext null
        }

        // 1. If walking forward through existing history
        synchronized(history) {
            if (historyIndex + 1 < history.size) {
                historyIndex++
                return@withContext history[historyIndex]
            }
        }

        // 2. If an image is ready in prefetchedQueue, display it immediately (0ms delay!)
        var display: ImmichImageDisplay? = null
        synchronized(prefetchedQueue) {
            if (prefetchedQueue.isNotEmpty()) {
                display = prefetchedQueue.removeAt(0)
            }
        }

        if (display != null) {
            addToHistory(display!!, isOnline)
            triggerPrefetch()
            return@withContext display
        }

        // 3. If offline (or network is failing), cycle cached images
        if (!isOnline) {
            val offlineDisplay = getNextOfflineImage()
            if (offlineDisplay != null) {
                return@withContext offlineDisplay
            }
        }

        // 4. If online and queue was empty (cold start), download one directly and launch prefetch
        ensureAssetsAvailable(settings)
        display = downloadNextAssetDisplay(settings)
        if (display != null) {
            addToHistory(display!!, isOnline)
            triggerPrefetch()
            return@withContext display
        }

        // 5. If online fetch failed (e.g. lost connection), fallback to cycling cached images
        val fallback = getNextOfflineImage()
        if (fallback != null) {
            setOnlineStatus(false)
            return@withContext fallback
        }

        return@withContext null
    }

    suspend fun getPreviousImage(): ImmichImageDisplay? = withContext(Dispatchers.IO) {
        if (!isOnline) {
            val offlineDisplay = getPreviousOfflineImage()
            if (offlineDisplay != null) {
                return@withContext offlineDisplay
            }
        }

        synchronized(history) {
            if (historyIndex > 0) {
                historyIndex--
                return@withContext history[historyIndex]
            }
            return@withContext if (history.isNotEmpty()) history[0] else null
        }
    }

    private fun getNextOfflineImage(): ImmichImageDisplay? {
        val pool = getCachedPool()
        if (pool.isEmpty()) return null
        offlineIndex = (offlineIndex + 1) % pool.size
        return pool[offlineIndex]
    }

    private fun getPreviousOfflineImage(): ImmichImageDisplay? {
        val pool = getCachedPool()
        if (pool.isEmpty()) return null
        offlineIndex = if (offlineIndex <= 0) pool.size - 1 else offlineIndex - 1
        return pool[offlineIndex]
    }

    fun getCachedPool(): List<ImmichImageDisplay> {
        val pool = mutableListOf<ImmichImageDisplay>()
        synchronized(history) { pool.addAll(history) }
        synchronized(prefetchedQueue) { pool.addAll(prefetchedQueue) }
        return pool.distinctBy { it.assetId }
    }

    private fun addToHistory(display: ImmichImageDisplay, online: Boolean) {
        synchronized(history) {
            history.add(display)
            // When online, keep at least MIN_HISTORY_KEEP (5) in history, trim past MAX_HISTORY_KEEP (8)
            // When offline, do NOT trim so all cached images can be cycled!
            if (online && history.size > MAX_HISTORY_KEEP) {
                val removed = history.removeAt(0)
                val isStillInUse = history.contains(removed) || prefetchedQueue.contains(removed)
                if (!isStillInUse) {
                    if (!removed.bitmap.isRecycled) {
                        removed.bitmap.recycle()
                    }
                    removed.blurredBackground?.let {
                        if (!it.isRecycled) it.recycle()
                    }
                }
            }
            historyIndex = history.size - 1
        }
    }

    fun triggerPrefetch() {
        if (isPrefetching) return
        prefetchScope.launch {
            prefetchNextImages()
        }
    }

    private fun prefetchNextImages() {
        if (isPrefetching) return
        isPrefetching = true
        try {
            val settings = getSettings()
            if (settings.serverUrl.isBlank()) return

            while (prefetchedQueue.size < PREFETCH_TARGET) {
                if (assetQueue.isEmpty()) {
                    fetchAssetsBatch(settings)
                }
                if (assetQueue.isEmpty()) {
                    break
                }

                val asset = synchronized(assetQueue) {
                    if (assetQueue.isNotEmpty()) assetQueue.removeAt(0) else null
                } ?: break

                val display = downloadAndBuildDisplay(asset, settings)
                if (display != null) {
                    synchronized(prefetchedQueue) {
                        prefetchedQueue.add(display)
                    }
                    Log.d("ImmichManager", "Prefetched asset ${display.assetId} (queue: ${prefetchedQueue.size}/$PREFETCH_TARGET)")
                }
            }
        } catch (e: Exception) {
            Log.w("ImmichManager", "Prefetch loop error: ${e.message}")
            if (e is java.io.IOException) {
                setOnlineStatus(false)
            }
        } finally {
            isPrefetching = false
        }
    }

    private fun downloadNextAssetDisplay(settings: FrameSettings): ImmichImageDisplay? {
        var attempts = 0
        while (assetQueue.isNotEmpty() && attempts < 10) {
            attempts++
            val asset = synchronized(assetQueue) {
                if (assetQueue.isNotEmpty()) assetQueue.removeAt(0) else null
            } ?: break
            val display = downloadAndBuildDisplay(asset, settings)
            if (display != null) {
                return display
            }
        }
        return null
    }

    private fun ensureAssetsAvailable(settings: FrameSettings) {
        if (assetQueue.isEmpty()) {
            fetchAssetsBatch(settings)
        }
    }

    fun clearCache() {
        synchronized(prefetchedQueue) {
            for (item in prefetchedQueue) {
                if (!item.bitmap.isRecycled) item.bitmap.recycle()
                item.blurredBackground?.let { if (!it.isRecycled) it.recycle() }
            }
            prefetchedQueue.clear()
        }
        synchronized(assetQueue) {
            assetQueue.clear()
        }
        synchronized(history) {
            while (history.size > MIN_HISTORY_KEEP) {
                val removed = history.removeAt(0)
                if (!removed.bitmap.isRecycled) removed.bitmap.recycle()
                removed.blurredBackground?.let { if (!it.isRecycled) it.recycle() }
            }
            historyIndex = history.size - 1
        }
        triggerPrefetch()
    }

    private fun fetchAssetsBatch(settings: FrameSettings) {
        if (isFetchingAssets) return
        isFetchingAssets = true
        try {
            val apiService = createApiService(settings) ?: return

            // Invalidate cache if credentials or server changed
            if (settings.serverUrl != lastServerUrl || settings.apiKey != lastApiKey) {
                albumCache = null
                peopleCache = null
                lastServerUrl = settings.serverUrl
                lastApiKey = settings.apiKey
            }

            // Resolve excluded person IDs ahead of time
            val resolvedExcludedIds = if (settings.excludedPeople.isNotEmpty()) {
                resolvePersonIds(apiService, settings.excludedPeople).toSet()
            } else {
                emptySet()
            }

            val takenAfterIso: String? = if (settings.recentDays > 0) {
                val cal = java.util.Calendar.getInstance()
                cal.add(java.util.Calendar.DAY_OF_YEAR, -settings.recentDays)
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                sdf.format(cal.time)
            } else null

            val cutoffMillis: Long = if (settings.recentDays > 0) {
                System.currentTimeMillis() - (settings.recentDays.toLong() * 24L * 60L * 60L * 1000L)
            } else 0L

            // 1. Memories check (if enabled and queue is empty, and NOT restricted to recent days)
            if (settings.includeMemories && settings.recentDays <= 0 && assetQueue.isEmpty()) {
                try {
                    val memResponse = apiService.getMemories().execute()
                    if (memResponse.isSuccessful && memResponse.body() != null) {
                        for (memory in memResponse.body()!!) {
                            val yearsAgo = memory.data?.year?.let { y ->
                                val curYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                                curYear - y
                            }
                            for (asset in memory.assets) {
                                val currentExif = asset.exifInfo ?: ImmichExifInfo()
                                val desc = if (yearsAgo != null) "$yearsAgo ${if (yearsAgo == 1) "year" else "years"} ago" else currentExif.description
                                val updatedAsset = asset.copy(
                                    exifInfo = currentExif.copy(description = desc)
                                )
                                if (!containsExcludedPerson(updatedAsset, settings.excludedPeople, resolvedExcludedIds)) {
                                    synchronized(assetQueue) {
                                        assetQueue.add(updatedAsset)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("ImmichManager", "Memories fetch failed: ${e.message}")
                }
            }

            // 2. Random Search from Full List of Pictures
            val resolvedAlbumIds = if (settings.albumIds.isNotEmpty()) {
                resolveAlbumIds(apiService, settings.albumIds)
            } else emptyList()

            val resolvedPersonIds = if (settings.personIds.isNotEmpty()) {
                resolvePersonIds(apiService, settings.personIds)
            } else emptyList()

            // If user specified an album filter, but no matching albums were found, abort query
            if (settings.albumIds.isNotEmpty() && resolvedAlbumIds.isEmpty()) {
                Log.w("ImmichManager", "Could not resolve any album IDs for ${settings.albumIds}. Aborting query.")
                return
            }

            // If user specified a person filter, but no matching people were found, abort query
            if (settings.personIds.isNotEmpty() && resolvedPersonIds.isEmpty()) {
                Log.w("ImmichManager", "Could not resolve any person IDs for ${settings.personIds}. Aborting query.")
                return
            }

            // Immich /api/search/random samples uniformly across the full matching collection of pictures!
            val randomDto = RandomSearchDto(
                size = 50,
                type = "IMAGE",
                withExif = true,
                withPeople = true,
                takenAfter = takenAfterIso,
                albumIds = resolvedAlbumIds.takeIf { it.isNotEmpty() },
                personIds = resolvedPersonIds.takeIf { it.isNotEmpty() },
                tagIds = settings.tagIds.takeIf { it.isNotEmpty() },
                isFavorite = if (settings.favoritesOnly) true else null,
                visibility = if (resolvedAlbumIds.isEmpty()) "timeline" else null
            )

            val response = apiService.getRandomAssets(randomDto).execute()
            var fetchedAssets: List<ImmichAsset>? = null
            if (response.isSuccessful && response.body() != null) {
                fetchedAssets = response.body()
                setOnlineStatus(true)
            } else {
                Log.w("ImmichManager", "getRandomAssets returned code ${response.code()}, attempting metadata fallback...")
                // Fallback for older servers that might not support albumIds in search/random
                val totalAssetsInAlbums = resolvedAlbumIds.sumOf { albumAssetCountCache[it.lowercase()] ?: 0 }
                val maxAlbumPages = if (totalAssetsInAlbums > 0) ((totalAssetsInAlbums + 99) / 100).coerceAtLeast(1) else 1
                val targetPage = if (settings.recentDays > 0 || maxAlbumPages <= 1) 1 else (1..maxAlbumPages).random()

                val metadataDto = MetadataSearchDto(
                    page = targetPage,
                    size = 50,
                    type = "IMAGE",
                    isFavorite = if (settings.favoritesOnly) true else null,
                    albumIds = resolvedAlbumIds.takeIf { it.isNotEmpty() },
                    personIds = resolvedPersonIds.takeIf { it.isNotEmpty() },
                    tagIds = settings.tagIds.takeIf { it.isNotEmpty() },
                    withExif = true,
                    withPeople = true,
                    takenAfter = takenAfterIso
                )
                val metaResp = apiService.searchMetadata(metadataDto).execute()
                if (metaResp.isSuccessful && metaResp.body() != null) {
                    fetchedAssets = metaResp.body()!!.assets.items
                    setOnlineStatus(true)
                }
            }

            if (fetchedAssets != null) {
                val filtered = fetchedAssets
                    .filterNot { containsExcludedPerson(it, settings.excludedPeople, resolvedExcludedIds) }
                    .filter { isWithinRecentDays(it, settings.recentDays, cutoffMillis) }
                    .shuffled()

                synchronized(assetQueue) {
                    assetQueue.addAll(filtered)
                }
                Log.d("ImmichManager", "Fetched and queued ${filtered.size} assets from full collection.")
            }
        } catch (e: Exception) {
            Log.e("ImmichManager", "Error fetching assets batch: ${e.message}", e)
            if (e is java.io.IOException) {
                setOnlineStatus(false)
            }
        } finally {
            isFetchingAssets = false
        }
    }

    private fun isWithinRecentDays(asset: ImmichAsset, recentDays: Int, cutoffMillis: Long): Boolean {
        if (recentDays <= 0) return true
        val dateStr = asset.exifInfo?.dateTimeOriginal
            ?: asset.localDateTime
            ?: asset.fileCreatedAt
            ?: return true
        val millis = parseDateToMillis(dateStr) ?: return true
        return millis >= cutoffMillis
    }

    private fun parseDateToMillis(dateStr: String): Long? {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy:MM:dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.US)
                if (fmt.endsWith("'Z'")) {
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                }
                val d = sdf.parse(dateStr)
                if (d != null) return d.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun containsExcludedPerson(
        asset: ImmichAsset,
        excludedList: List<String>,
        resolvedExcludedIds: Set<String> = emptySet()
    ): Boolean {
        if (excludedList.isEmpty()) return false
        val people = asset.people ?: return false
        for (person in people) {
            val personId = person.id.trim()
            val personName = person.name?.trim() ?: ""
            val personNameLower = personName.lowercase()

            if (resolvedExcludedIds.contains(personId)) {
                Log.d("ImmichManager", "Filtered out asset ${asset.id}: contains excluded person ID $personId (${if (personName.isNotEmpty()) personName else "unnamed"})")
                return true
            }

            for (target in excludedList) {
                val t = target.trim()
                if (t.isEmpty()) continue
                val tLower = t.lowercase()

                if (personId.equals(t, ignoreCase = true) ||
                    personNameLower == tLower ||
                    (personNameLower.isNotEmpty() && (personNameLower.contains(tLower) || tLower.contains(personNameLower)))) {
                    Log.d("ImmichManager", "Filtered out asset ${asset.id}: contains excluded person '$t' (matched: $personName)")
                    return true
                }
            }
        }
        return false
    }

    private fun resolveAlbumIds(apiService: ImmichApiService, inputs: List<String>): List<String> {
        if (inputs.isEmpty()) return emptyList()

        val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        val needsResolution = inputs.any { !uuidRegex.matches(it.trim()) }

        val now = System.currentTimeMillis()
        if (albumCache == null || (needsResolution && (now - albumCacheTime > 10 * 60 * 1000L))) {
            try {
                val response = apiService.getAlbums().execute()
                if (response.isSuccessful && response.body() != null) {
                    val map = mutableMapOf<String, String>()
                    val countMap = mutableMapOf<String, Int>()
                    for (album in response.body()!!) {
                        val count = album.assetCount ?: 0
                        val name = album.albumName?.trim()?.lowercase()
                        if (!name.isNullOrEmpty()) {
                            map[name] = album.id
                            countMap[name] = count
                        }
                        map[album.id.lowercase()] = album.id
                        countMap[album.id.lowercase()] = count
                    }
                    albumCache = map
                    albumAssetCountCache = countMap
                    albumCacheTime = now
                    Log.d("ImmichManager", "Cached ${response.body()!!.size} albums from Immich")
                } else {
                    Log.w("ImmichManager", "Failed to fetch albums: ${response.code()} ${response.message()}")
                }
            } catch (e: Exception) {
                Log.w("ImmichManager", "Failed to fetch albums for name resolution: ${e.message}")
            }
        }

        val cache = albumCache ?: emptyMap()
        val resolved = mutableListOf<String>()

        for (item in inputs) {
            val trimmed = item.trim()
            if (trimmed.isEmpty()) continue
            val lower = trimmed.lowercase()
            val mappedId = cache[lower]
            if (mappedId != null) {
                resolved.add(mappedId)
                Log.d("ImmichManager", "Resolved album name '$trimmed' -> $mappedId")
            } else if (uuidRegex.matches(trimmed)) {
                resolved.add(trimmed)
            } else {
                val partialMatch = cache.entries.firstOrNull { it.key.contains(lower) || lower.contains(it.key) }
                if (partialMatch != null) {
                    resolved.add(partialMatch.value)
                    Log.d("ImmichManager", "Partially resolved album '$trimmed' -> ${partialMatch.value}")
                } else {
                    Log.w("ImmichManager", "Could not resolve album name '$trimmed' to an ID")
                }
            }
        }

        return resolved.distinct()
    }

    private fun resolvePersonIds(apiService: ImmichApiService, inputs: List<String>): List<String> {
        if (inputs.isEmpty()) return emptyList()

        val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        val needsResolution = inputs.any { !uuidRegex.matches(it.trim()) }

        val now = System.currentTimeMillis()
        if (peopleCache == null || (needsResolution && (now - peopleCacheTime > 10 * 60 * 1000L))) {
            try {
                val response = apiService.getPeople().execute()
                if (response.isSuccessful && response.body() != null) {
                    val map = mutableMapOf<String, String>()
                    for (person in response.body()!!.people) {
                        val name = person.name?.trim()?.lowercase()
                        if (!name.isNullOrEmpty()) {
                            map[name] = person.id
                        }
                        map[person.id.lowercase()] = person.id
                    }
                    peopleCache = map
                    peopleCacheTime = now
                    Log.d("ImmichManager", "Cached ${response.body()!!.people.size} people from Immich")
                } else {
                    Log.w("ImmichManager", "Failed to fetch people: ${response.code()} ${response.message()}")
                }
            } catch (e: Exception) {
                Log.w("ImmichManager", "Failed to fetch people for name resolution: ${e.message}")
            }
        }

        val cache = peopleCache ?: emptyMap()
        val resolved = mutableListOf<String>()

        for (item in inputs) {
            val trimmed = item.trim()
            if (trimmed.isEmpty()) continue
            val lower = trimmed.lowercase()
            val mappedId = cache[lower]
            if (mappedId != null) {
                resolved.add(mappedId)
                Log.d("ImmichManager", "Resolved person name '$trimmed' -> $mappedId")
            } else if (uuidRegex.matches(trimmed)) {
                resolved.add(trimmed)
            } else {
                val partialMatch = cache.entries.firstOrNull { it.key.contains(lower) || lower.contains(it.key) }
                if (partialMatch != null) {
                    resolved.add(partialMatch.value)
                    Log.d("ImmichManager", "Partially resolved person '$trimmed' -> ${partialMatch.value}")
                } else {
                    Log.w("ImmichManager", "Could not resolve person name '$trimmed' to an ID")
                }
            }
        }

        return resolved.distinct()
    }

    private fun downloadAndBuildDisplay(asset: ImmichAsset, settings: FrameSettings): ImmichImageDisplay? {
        if (containsExcludedPerson(asset, settings.excludedPeople)) {
            return null
        }

        // Try preview first (1080p/1440p), fallback to original
        var bitmap = fetchBitmap(asset.id, "preview", settings)
        if (bitmap == null) {
            bitmap = fetchBitmap(asset.id, null, settings)
        }

        if (bitmap == null) {
            return null
        }

        val isPortrait = bitmap.height > bitmap.width

        val blurred = if (settings.blurredBackground) {
            Helpers.createBlurredBackground(bitmap)
        } else null

        val photoDate = if (settings.showPhotoDate) {
            formatAssetDate(asset, settings.photoDateFormat)
        } else ""

        val location = if (settings.showImageLocation) {
            formatLocation(asset.exifInfo, settings.imageLocationFormat)
        } else ""

        return ImmichImageDisplay(
            assetId = asset.id,
            bitmap = bitmap,
            blurredBackground = blurred,
            photoDate = photoDate,
            imageLocation = location,
            isPortrait = isPortrait,
            asset = asset
        )
    }

    private fun fetchBitmap(assetId: String, size: String?, settings: FrameSettings): Bitmap? {
        val url = if (size != null) {
            "${settings.serverUrl}api/assets/$assetId/thumbnail?size=$size"
        } else {
            "${settings.serverUrl}api/assets/$assetId/original"
        }

        val request = Request.Builder()
            .url(url)
            .apply {
                if (settings.apiKey.isNotBlank()) {
                    addHeader("x-api-key", settings.apiKey)
                }
            }
            .build()

        return try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }

            val body = response.body ?: return null
            val inputStream: InputStream = body.byteStream()

            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                // If decoding original full resolution (often 24-48MP), downsample to prevent OOM
                if (size == null) {
                    inSampleSize = 2
                }
            }

            val bmp = BitmapFactory.decodeStream(inputStream, null, options)
            body.close()
            if (bmp != null) {
                setOnlineStatus(true)
            }
            bmp
        } catch (e: Exception) {
            Log.w("ImmichManager", "fetchBitmap error for asset $assetId: ${e.message}")
            if (e is java.io.IOException) {
                setOnlineStatus(false)
            }
            null
        }
    }

    private fun formatAssetDate(asset: ImmichAsset, pattern: String): String {
        val rawDate = asset.exifInfo?.dateTimeOriginal
            ?: asset.localDateTime
            ?: asset.fileCreatedAt
            ?: return ""

        return try {
            val clean = rawDate.replace("Z", "+0000")
            val formats = arrayOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ssZ",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss"
            )

            var parsedDate: Date? = null
            for (fmt in formats) {
                try {
                    val sdf = SimpleDateFormat(fmt, Locale.US)
                    parsedDate = sdf.parse(clean)
                    if (parsedDate != null) break
                } catch (_: Exception) {}
            }

            if (parsedDate != null) {
                val safePattern = Helpers.sanitizeDatePattern(pattern)
                SimpleDateFormat(safePattern, Locale.getDefault()).format(parsedDate)
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.w("ImmichManager", "Failed to format photo date with pattern '$pattern': ${e.message}")
            ""
        }
    }

    private fun formatLocation(exif: ImmichExifInfo?, format: String): String {
        if (exif == null) return ""
        val city = exif.city ?: ""
        val state = exif.state ?: ""
        val country = exif.country ?: ""
        if (city.isEmpty() && state.isEmpty() && country.isEmpty()) return ""

        val replaced = format
            .replace("City", city)
            .replace("State", state)
            .replace("Country", country)

        return replaced.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
    }

    private fun buildOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .connectionSpecs(
                listOf(
                    ConnectionSpec.MODERN_TLS,
                    ConnectionSpec.COMPATIBLE_TLS,
                    ConnectionSpec.CLEARTEXT
                )
            )

        try {
            val defaultTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            defaultTmf.init(null as KeyStore?)
            val defaultTrustManager = defaultTmf.trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull()

            val cf = CertificateFactory.getInstance("X.509")
            val isrgCert = cf.generateCertificate(
                ByteArrayInputStream(ISRG_ROOT_X1_PEM.toByteArray(Charsets.UTF_8))
            ) as X509Certificate

            val isrgKeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("isrg_root_x1", isrgCert)
            }
            val isrgTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            isrgTmf.init(isrgKeyStore)
            val isrgTrustManager = isrgTmf.trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull()

            if (defaultTrustManager != null && isrgTrustManager != null) {
                val combinedTrustManager = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                        defaultTrustManager.checkClientTrusted(chain, authType)
                    }

                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                        try {
                            defaultTrustManager.checkServerTrusted(chain, authType)
                        } catch (e: Exception) {
                            try {
                                isrgTrustManager.checkServerTrusted(chain, authType)
                            } catch (e2: Exception) {
                                throw e
                            }
                        }
                    }

                    override fun getAcceptedIssuers(): Array<X509Certificate> {
                        return defaultTrustManager.acceptedIssuers + isrgTrustManager.acceptedIssuers
                    }
                }

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(combinedTrustManager), null)
                builder.sslSocketFactory(sslContext.socketFactory, combinedTrustManager)
            }
        } catch (e: Exception) {
            Log.w("ImmichManager", "Failed to configure ISRG Root X1 SSL fallback", e)
        }

        return builder.build()
    }

    companion object {
        const val PREFETCH_TARGET = 5
        const val MIN_HISTORY_KEEP = 5
        const val MAX_HISTORY_KEEP = 8

        private const val ISRG_ROOT_X1_PEM =
            "-----BEGIN CERTIFICATE-----\n" +
            "MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw\n" +
            "TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh\n" +
            "cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4\n" +
            "WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu\n" +
            "ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY\n" +
            "MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc\n" +
            "h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+\n" +
            "0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U\n" +
            "A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW\n" +
            "T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH\n" +
            "B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC\n" +
            "B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv\n" +
            "KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn\n" +
            "OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn\n" +
            "jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw\n" +
            "qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI\n" +
            "rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV\n" +
            "HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq\n" +
            "hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL\n" +
            "ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ\n" +
            "3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK\n" +
            "NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5\n" +
            "ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur\n" +
            "TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC\n" +
            "jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc\n" +
            "oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq\n" +
            "4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA\n" +
            "mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d\n" +
            "emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=\n" +
            "-----END CERTIFICATE-----\n"
    }
}
