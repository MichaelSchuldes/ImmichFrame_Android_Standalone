package com.immichframe.standalone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import android.util.Log
import retrofit2.Call
import retrofit2.http.GET
import androidx.core.graphics.scale
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Calendar
import java.util.concurrent.TimeUnit

object Helpers {
    fun cssFontSizeToSp(cssSize: String?, context: Context, baseFontSizePx: Float = 16f): Float {
        val resources = context.resources
        val displayMetrics = resources.displayMetrics
        val fontScale = resources.configuration.fontScale
        val density = displayMetrics.density

        // Handle null cssSize
        val effectiveCssSize = cssSize ?: "medium"

        return when {
            effectiveCssSize.equals("xx-small", ignoreCase = true) -> 8f * fontScale
            effectiveCssSize.equals("x-small", ignoreCase = true) -> 10f * fontScale
            effectiveCssSize.equals("small", ignoreCase = true) -> 12f * fontScale
            effectiveCssSize.equals("medium", ignoreCase = true) -> 16f * fontScale
            effectiveCssSize.equals("large", ignoreCase = true) -> 20f * fontScale
            effectiveCssSize.equals("x-large", ignoreCase = true) -> 24f * fontScale
            effectiveCssSize.equals("xx-large", ignoreCase = true) -> 32f * fontScale

            effectiveCssSize.endsWith("px", ignoreCase = true) -> {
                val px = effectiveCssSize.removeSuffix("px").toFloatOrNull() ?: baseFontSizePx
                px / (density * fontScale)
            }

            effectiveCssSize.endsWith("pt", ignoreCase = true) -> {
                val pt = effectiveCssSize.removeSuffix("pt").toFloatOrNull() ?: baseFontSizePx
                val px = pt * (density * 160f / 72f)
                px / (density * fontScale)
            }

            effectiveCssSize.endsWith("em", ignoreCase = true) -> {
                val em = effectiveCssSize.removeSuffix("em").toFloatOrNull() ?: 1f
                val px = em * baseFontSizePx
                px / (density * fontScale)
            }

            else -> 16f * fontScale
        }
    }

    fun sanitizeDatePattern(pattern: String?, defaultPattern: String = "EEE, MMM d, yyyy"): String {
        if (pattern.isNullOrBlank()) return defaultPattern
        // Android 6.0 (API 23) SimpleDateFormat throws IllegalArgumentException on 'Y' (Week Year).
        // Users commonly type 'YYYY' intending calendar year 'yyyy'.
        return pattern.replace('Y', 'y')
    }

    /**
     * Creates a high-quality, smooth Gaussian blurred background bitmap without blockiness.
     * Downscales to ~160px width and applies a fast, multi-pass StackBlur algorithm,
     * then applies a subtle dark scrim so the foreground image stands out crisply.
     */
    fun createBlurredBackground(bitmap: Bitmap, targetWidth: Int = 160, blurRadius: Int = 18): Bitmap? {
        return try {
            val aspect = bitmap.height.toFloat() / bitmap.width.toFloat()
            val targetHeight = (targetWidth * aspect).toInt().coerceAtLeast(1)
            val downscaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            val blurred = stackBlur(downscaled, blurRadius) ?: downscaled

            // Apply a subtle dark scrim over the blurred canvas to enhance foreground contrast
            val canvas = Canvas(blurred)
            val paint = Paint().apply { color = 0x33000000 }
            canvas.drawRect(0f, 0f, blurred.width.toFloat(), blurred.height.toFloat(), paint)

            blurred
        } catch (e: Exception) {
            Log.w("Helpers", "Failed to create blurred background: ${e.message}")
            null
        }
    }

    fun stackBlur(sentBitmap: Bitmap, radius: Int): Bitmap? {
        if (radius < 1) return null

        val bitmap = sentBitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val w = bitmap.width
        val h = bitmap.height

        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(maxOf(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (i in 0 until 256 * divsum) {
            dv[i] = i / divsum
        }

        yw = 0
        yi = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        for (y in 0 until h) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            for (i in -radius..radius) {
                p = pix[yi + minOf(wm, maxOf(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                rbs = r1 - Math.abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }
            stackpointer = radius

            for (x in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (y == 0) {
                    vmin[x] = minOf(x + radius + 1, wm)
                }
                p = pix[yw + vmin[x]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
            }
            yw += w
        }

        for (x in 0 until w) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            yp = -radius * w
            for (i in -radius..radius) {
                yi = maxOf(0, yp) + x

                sir = stack[i + radius]

                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]

                rbs = r1 - Math.abs(i)

                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs

                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }

                if (i < hm) {
                    yp += w
                }
            }
            yi = x
            stackpointer = radius
            for (y in 0 until h) {
                pix[yi] = (-0x1000000 and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (x == 0) {
                    vmin[y] = minOf(y + r1, hm) * w
                }
                p = x + vmin[y]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }

    fun mergeImages(leftImage: Bitmap, rightImage: Bitmap, lineColor: Int): Bitmap {
        val lineWidth = 10
        val targetHeight = maxOf(leftImage.height, rightImage.height) // Use max height

        val totalWidth = leftImage.width + rightImage.width + lineWidth

        val result = Bitmap.createBitmap(totalWidth, targetHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        canvas.drawBitmap(leftImage, 0f, 0f, paint)

        // Draw dividing line
        paint.color = lineColor
        canvas.drawRect(
            leftImage.width.toFloat(), // Line starts after left image
            0f, (leftImage.width + lineWidth).toFloat(), targetHeight.toFloat(), paint
        )

        canvas.drawBitmap(rightImage, (leftImage.width + lineWidth).toFloat(), 0f, paint)

        return result
    }

    fun decodeBitmapFromBytes(data: String): Bitmap {
        val decodedImage = Base64.decode(data, Base64.DEFAULT)

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        return BitmapFactory.decodeByteArray(decodedImage, 0, decodedImage.size, options)
    }

    fun reduceBitmapQuality(bitmap: Bitmap, maxSize: Int = 1000): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val maxDim = maxOf(width, height)
        if (maxDim <= maxSize) {
            return bitmap
        }

        // Downscale while maintaining aspect ratio
        val scaleFactor = maxSize.toFloat() / maxDim
        val newWidth = (width * scaleFactor).toInt().coerceAtLeast(1)
        val newHeight = (height * scaleFactor).toInt().coerceAtLeast(1)

        return try {
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("Helpers", "OOM in reduceBitmapQuality: ${e.message}")
            bitmap
        }
    }

    fun generateQrCodeBitmap(content: String, sizePx: Int = 300): Bitmap? {
        return try {
            val hints = java.util.EnumMap<com.google.zxing.EncodeHintType, Any>(com.google.zxing.EncodeHintType::class.java).apply {
                put(com.google.zxing.EncodeHintType.MARGIN, 1)
                put(com.google.zxing.EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M)
            }
            val bitMatrix = com.google.zxing.qrcode.QRCodeWriter().encode(
                content,
                com.google.zxing.BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                hints
            )
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            bmp.setPixels(pixels, 0, width, 0, 0, width, height)
            bmp
        } catch (e: Exception) {
            android.util.Log.e("Helpers", "Failed to generate QR code: ${e.message}", e)
            null
        }
    }

    data class ImageResponse(
        val randomImageBase64: String,
        val thumbHashImageBase64: String,
        val photoDate: String,
        val imageLocation: String
    )

    data class ServerSettings(
        val margin: String,
        val interval: Int,
        val transitionDuration: Double,
        val downloadImages: Boolean,
        val renewImagesDuration: Int,
        val showClock: Boolean,
        val clockFormat: String,
        val showPhotoDate: Boolean,
        val photoDateFormat: String,
        val showImageDesc: Boolean,
        val showPeopleDesc: Boolean,
        val showImageLocation: Boolean,
        val imageLocationFormat: String,
        val primaryColor: String?,
        val secondaryColor: String,
        val style: String,
        val baseFontSize: String?,
        val showWeatherDescription: Boolean,
        val unattendedMode: Boolean,
        val imageZoom: Boolean,
        val imageFill: Boolean,
        val layout: String,
        val language: String
    )

    data class Weather(
        val location: String,
        val temperature: Double,
        val unit: String,
        val temperatureUnit: String,
        val description: String,
        val iconId: String
    )

    interface ApiService {
        @GET("api/Asset/RandomImageAndInfo")
        fun getImageData(): Call<ImageResponse>

        @GET("api/Config")
        fun getServerSettings(): Call<ServerSettings>

        @GET("api/Weather")
        fun getWeather(): Call<Weather>
    }

    fun createRetrofit(baseUrl: String, authSecret: String): Retrofit {
        val normalizedBaseUrl = if (!baseUrl.endsWith("/")) "$baseUrl/" else baseUrl

        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val originalRequest = chain.request()

            val request = if (authSecret.isNotEmpty()) {
                originalRequest.newBuilder().addHeader("Authorization", "Bearer $authSecret")
                    .build()
            } else {
                originalRequest
            }

            chain.proceed(request)
        }.build()

        return Retrofit.Builder().baseUrl(normalizedBaseUrl).client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
    }

    private val reachabilityClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun isServerReachable(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .build()
            reachabilityClient.newCall(request).execute().use {
                true // any HTTP response = reachable
            }
        } catch (e: Exception) {
            false
        }
    }

    // --- Active schedule ---------------------------------------------------
    // Days use java.util.Calendar constants: SUNDAY=1 .. SATURDAY=7.

    data class ActiveRange(val start: String, val end: String) // "HH:mm"
    data class ActiveRule(val days: Set<Int>, val ranges: List<ActiveRange>)
    data class ActiveSchedule(val rules: List<ActiveRule>)

    private fun timeToMinutes(time: String): Int? {
        val parts = time.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    fun parseActiveSchedule(json: String?): ActiveSchedule {
        if (json.isNullOrBlank()) return ActiveSchedule(emptyList())
        return try {
            val rulesArr = JSONObject(json).optJSONArray("rules") ?: JSONArray()
            val rules = mutableListOf<ActiveRule>()
            for (i in 0 until rulesArr.length()) {
                val ruleObj = rulesArr.getJSONObject(i)
                val daysArr = ruleObj.optJSONArray("days") ?: JSONArray()
                val days = mutableSetOf<Int>()
                for (j in 0 until daysArr.length()) days.add(daysArr.getInt(j))
                val rangesArr = ruleObj.optJSONArray("ranges") ?: JSONArray()
                val ranges = mutableListOf<ActiveRange>()
                for (j in 0 until rangesArr.length()) {
                    val rangeObj = rangesArr.getJSONObject(j)
                    ranges.add(ActiveRange(rangeObj.getString("start"), rangeObj.getString("end")))
                }
                rules.add(ActiveRule(days, ranges))
            }
            ActiveSchedule(rules)
        } catch (_: Exception) {
            ActiveSchedule(emptyList())
        }
    }

    fun serializeActiveSchedule(schedule: ActiveSchedule): String {
        val rulesArr = JSONArray()
        for (rule in schedule.rules) {
            val daysArr = JSONArray()
            rule.days.sorted().forEach { daysArr.put(it) }
            val rangesArr = JSONArray()
            for (range in rule.ranges) {
                rangesArr.put(JSONObject().put("start", range.start).put("end", range.end))
            }
            rulesArr.put(JSONObject().put("days", daysArr).put("ranges", rangesArr))
        }
        return JSONObject().put("rules", rulesArr).toString()
    }

    /**
     * Returns whether the frame should be active right now.
     * An empty schedule means "always active" so enabling the feature without
     * configuring it never blacks out the screen.
     * Overnight ranges (start >= end) are anchored to the day they start on.
     */
    fun isActiveNow(schedule: ActiveSchedule, now: Calendar): Boolean {
        if (schedule.rules.isEmpty()) return true
        val today = now.get(Calendar.DAY_OF_WEEK)
        val yesterday = if (today == Calendar.SUNDAY) Calendar.SATURDAY else today - 1
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        for (rule in schedule.rules) {
            for (range in rule.ranges) {
                val start = timeToMinutes(range.start) ?: continue
                val end = timeToMinutes(range.end) ?: continue
                when {
                    start < end -> {
                        // Same-day range
                        if (rule.days.contains(today) && nowMinutes in start until end) return true
                    }

                    start > end -> {
                        // Overnight range: part before midnight belongs to today's rule,
                        // part after midnight belongs to yesterday's rule.
                        if (rule.days.contains(today) && nowMinutes >= start) return true
                        if (rule.days.contains(yesterday) && nowMinutes < end) return true
                    }

                    else -> {
                        // start == end -> treat as active all day
                        if (rule.days.contains(today)) return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Returns the next time (after [now]) at which the schedule becomes active, or null if the
     * schedule has no rules (always active) or no active period is found within the next week.
     * Scans minute-by-minute up to 8 days ahead.
     */
    fun nextActiveStart(schedule: ActiveSchedule, now: Calendar): Calendar? {
        if (schedule.rules.isEmpty()) return null
        val cursor = now.clone() as Calendar
        cursor.set(Calendar.SECOND, 0)
        cursor.set(Calendar.MILLISECOND, 0)
        cursor.add(Calendar.MINUTE, 1)
        val maxSteps = 8 * 24 * 60
        repeat(maxSteps) {
            if (isActiveNow(schedule, cursor)) return cursor
            cursor.add(Calendar.MINUTE, 1)
        }
        return null
    }

}