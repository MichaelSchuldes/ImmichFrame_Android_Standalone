package com.immichframe.standalone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

object MapTileHelper {
    private const val TILE_SIZE = 256
    private const val USER_AGENT = "ImmichFrame-Android/1.0"

    const val ZOOM_CITY = 12
    const val ZOOM_COUNTRY = 6

    // In-memory cache for tile bitmaps (keeps up to 150 tiles)
    private val tileMemoryCache = object : LruCache<String, Bitmap>(150) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    suspend fun renderMap(
        lat: Double,
        lon: Double,
        zoom: Int,
        widthPx: Int,
        heightPx: Int,
        client: OkHttpClient
    ): Bitmap = withContext(Dispatchers.IO) {
        val bitmap = Bitmap.createBitmap(widthPx.coerceAtLeast(100), heightPx.coerceAtLeast(100), Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)

        // 1. Draw fallback dark background
        val bgPaint = Paint().apply {
            color = Color.parseColor("#1C2329")
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), bgPaint)

        // Draw subtle grid lines
        val gridPaint = Paint().apply {
            color = Color.parseColor("#28343D")
            strokeWidth = 1f
        }
        val step = 40f
        var gx = 0f
        while (gx < widthPx) {
            canvas.drawLine(gx, 0f, gx, heightPx.toFloat(), gridPaint)
            gx += step
        }
        var gy = 0f
        while (gy < heightPx) {
            canvas.drawLine(0f, gy, widthPx.toFloat(), gy, gridPaint)
            gy += step
        }

        // 2. Slippy tile math
        val safeLat = lat.coerceIn(-85.0511, 85.0511)
        val safeLon = lon.coerceIn(-180.0, 180.0)
        val safeZoom = zoom.coerceIn(1, 19)

        val n = 2.0.pow(safeZoom.toDouble())
        val xExact = (safeLon + 180.0) / 360.0 * n
        val latRad = Math.toRadians(safeLat)
        val yExact = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n

        val centerTileX = floor(xExact).toInt()
        val centerTileY = floor(yExact).toInt()

        val subTileX = (xExact - centerTileX) * TILE_SIZE
        val subTileY = (yExact - centerTileY) * TILE_SIZE

        val screenCenterX = widthPx / 2f
        val screenCenterY = heightPx / 2f

        val minTileX = floor(xExact - (screenCenterX / TILE_SIZE)).toInt() - 1
        val maxTileX = floor(xExact + ((widthPx - screenCenterX) / TILE_SIZE)).toInt() + 1
        val minTileY = floor(yExact - (screenCenterY / TILE_SIZE)).toInt() - 1
        val maxTileY = floor(yExact + ((heightPx - screenCenterY) / TILE_SIZE)).toInt() + 1

        val maxTileIdx = (1 shl safeZoom) - 1

        // 3. Download and composite tiles
        val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (ty in minTileY..maxTileY) {
            if (ty < 0 || ty > maxTileIdx) continue
            for (tx in minTileX..maxTileX) {
                val normalizedTx = ((tx % (maxTileIdx + 1)) + (maxTileIdx + 1)) % (maxTileIdx + 1)
                val tileBmp = getTile(safeZoom, normalizedTx, ty, client)
                if (tileBmp != null && !tileBmp.isRecycled) {
                    val drawX = (screenCenterX - subTileX + (tx - centerTileX) * TILE_SIZE).toFloat()
                    val drawY = (screenCenterY - subTileY + (ty - centerTileY) * TILE_SIZE).toFloat()
                    canvas.drawBitmap(tileBmp, drawX, drawY, tilePaint)
                }
            }
        }

        // 4. Draw Red Map Pin at exact center (screenCenterX, screenCenterY)
        drawRedPin(canvas, screenCenterX, screenCenterY)

        return@withContext bitmap
    }

    private fun getTile(zoom: Int, x: Int, y: Int, client: OkHttpClient): Bitmap? {
        val cacheKey = "$zoom/$x/$y"
        synchronized(tileMemoryCache) {
            val cached = tileMemoryCache.get(cacheKey)
            if (cached != null && !cached.isRecycled) return cached
        }

        val url = "https://tile.openstreetmap.org/$zoom/$x/$y.png"
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }
            val body = response.body ?: return null
            val inputStream: InputStream = body.byteStream()
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeStream(inputStream, null, options)
            body.close()
            if (bmp != null) {
                synchronized(tileMemoryCache) {
                    tileMemoryCache.put(cacheKey, bmp)
                }
            }
            bmp
        } catch (e: Exception) {
            Log.w("MapTileHelper", "Failed to fetch tile $zoom/$x/$y: ${e.message}")
            null
        }
    }

    private fun drawRedPin(canvas: Canvas, x: Float, y: Float) {
        val pinWidth = 26f
        val pinHeight = 38f

        // Drop shadow under the pin tip
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#73000000")
            style = Paint.Style.FILL
        }
        val shadowRect = RectF(x - pinWidth * 0.35f, y - 2f, x + pinWidth * 0.35f, y + 4f)
        canvas.drawOval(shadowRect, shadowPaint)

        // Pin Head & Pointer
        val headRadius = pinWidth / 2f
        val headCenterY = y - pinHeight + headRadius

        val path = Path().apply {
            moveTo(x, y) // pin tip pointing to photo spot
            lineTo(x - headRadius * 0.95f, headCenterY + headRadius * 0.25f)
            arcTo(
                RectF(x - headRadius, headCenterY - headRadius, x + headRadius, headCenterY + headRadius),
                160f,
                220f
            )
            lineTo(x, y)
            close()
        }

        // Red fill
        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E53935") // Vibrant Material Red
            style = Paint.Style.FILL
        }
        canvas.drawPath(path, pinPaint)

        // Dark red border for high contrast against light map tiles
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B71C1C")
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawPath(path, borderPaint)

        // Inner white circle
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(x, headCenterY, headRadius * 0.38f, dotPaint)
    }
}
