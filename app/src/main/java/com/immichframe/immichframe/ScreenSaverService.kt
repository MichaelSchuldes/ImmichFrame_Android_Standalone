package com.immichframe.immichframe

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.dreams.DreamService
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ScreenSaverService : DreamService() {
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var imageView1: ImageView
    private lateinit var imageView2: ImageView
    private lateinit var txtPhotoInfo: TextView
    private lateinit var txtDateTime: TextView

    private lateinit var immichManager: ImmichManager
    private lateinit var currentSettings: ImmichManager.FrameSettings

    private var isImageTimerRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private var previousImage: ImmichImageDisplay? = null
    private var currentImage: ImmichImageDisplay? = null
    private var portraitCache: ImmichImageDisplay? = null

    private val imageRunnable = object : Runnable {
        override fun run() {
            if (isImageTimerRunning) {
                handler.postDelayed(this, (currentSettings.intervalSeconds * 1000).toLong())
                getNextImage()
            }
        }
    }

    private var isShowingFirst = true
    private var zoomAnimator: ObjectAnimator? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onDreamingStarted() {
        super.onDreamingStarted()
        isFullscreen = true
        isInteractive = true
        setContentView(R.layout.screen_saver_view)

        immichManager = ImmichManager(this)
        currentSettings = immichManager.getSettings()

        imageView1 = findViewById(R.id.imageView1)
        imageView2 = findViewById(R.id.imageView2)
        txtPhotoInfo = findViewById(R.id.txtPhotoInfo)
        txtDateTime = findViewById(R.id.txtDateTime)

        findViewById<View>(R.id.webView)?.visibility = View.GONE

        val rootView = findViewById<View>(android.R.id.content)
        rootView.setOnTouchListener { _, _ ->
            finish()
            true
        }

        acquireWakeLock()
        loadSettings()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        stopImageTimer()
        releaseWakeLock()
        handler.removeCallbacksAndMessages(null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    previousAction()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    nextAction()
                    return true
                }
                else -> {
                    finish()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun previousAction() {
        CoroutineScope(Dispatchers.Main).launch {
            val display = immichManager.getPreviousImage()
            if (display != null) {
                stopImageTimer()
                showImage(display)
                startImageTimer()
            }
        }
    }

    private fun nextAction() {
        stopImageTimer()
        getNextImage()
        startImageTimer()
    }

    private fun showImage(display: ImmichImageDisplay) {
        CoroutineScope(Dispatchers.IO).launch {
            val decorView = window.decorView
            val width = decorView.width
            val height = decorView.height
            val maxSize = maxOf(width, height).coerceAtLeast(1000)

            var finalBitmap = display.bitmap
            val blurredBitmap = display.blurredBackground
            var isMerged = false

            val isPortrait = finalBitmap.height > finalBitmap.width
            if (isPortrait && currentSettings.layout == "splitview") {
                if (portraitCache != null) {
                    var firstPortrait = portraitCache!!.bitmap
                    firstPortrait = Helpers.reduceBitmapQuality(firstPortrait, maxSize)
                    finalBitmap = Helpers.reduceBitmapQuality(finalBitmap, maxSize)

                    val colorString = currentSettings.primaryColor?.takeIf { it.isNotBlank() } ?: "#FFFFFF"
                    val parsedColor = runCatching { colorString.toColorInt() }.getOrDefault(Color.WHITE)

                    finalBitmap = Helpers.mergeImages(firstPortrait, finalBitmap, parsedColor)
                    isMerged = true
                } else {
                    portraitCache = display
                    getNextImage()
                    return@launch
                }
            } else {
                finalBitmap = Helpers.reduceBitmapQuality(finalBitmap, maxSize * 2)
            }

            withContext(Dispatchers.Main) {
                updateUI(finalBitmap, blurredBitmap, isMerged, display)
            }
        }
    }

    private fun updateUI(
        finalImage: Bitmap,
        blurredBitmap: Bitmap?,
        isMerged: Boolean,
        display: ImmichImageDisplay
    ) {
        val imageViewOld = if (isShowingFirst) imageView1 else imageView2
        val imageViewNew = if (isShowingFirst) imageView2 else imageView1

        zoomAnimator?.cancel()
        imageViewNew.alpha = 0f
        imageViewNew.scaleX = 1f
        imageViewNew.scaleY = 1f
        imageViewNew.setImageBitmap(finalImage)
        imageViewNew.visibility = View.VISIBLE

        if (currentSettings.blurredBackground && blurredBitmap != null) {
            imageViewNew.background = blurredBitmap.toDrawable(resources)
        } else {
            imageViewNew.background = null
        }

        imageViewNew.animate()
            .alpha(1f)
            .setDuration((currentSettings.transitionDurationSeconds * 1000).toLong())
            .withEndAction {
                if (currentSettings.imageZoom) {
                    startZoomAnimation(imageViewNew)
                }
            }
            .start()

        imageViewOld.animate()
            .alpha(0f)
            .setDuration((currentSettings.transitionDurationSeconds * 1000).toLong())
            .withEndAction {
                imageViewOld.visibility = View.GONE
            }
            .start()

        isShowingFirst = !isShowingFirst

        if (isMerged && portraitCache != null) {
            val mergedDate = listOf(portraitCache!!.photoDate, display.photoDate)
                .filter { it.isNotEmpty() }
                .joinToString(" | ")
            val mergedLoc = listOf(portraitCache!!.imageLocation, display.imageLocation)
                .filter { it.isNotEmpty() }
                .joinToString(" | ")
            updatePhotoInfo(mergedDate, mergedLoc)
            portraitCache = null
        } else {
            updatePhotoInfo(display.photoDate, display.imageLocation)
        }

        updateDateTime()
    }

    private fun updatePhotoInfo(photoDate: String, photoLocation: String) {
        if (currentSettings.showPhotoDate || currentSettings.showImageLocation) {
            val photoInfo = buildString {
                if (currentSettings.showPhotoDate && photoDate.isNotEmpty()) {
                    append(photoDate)
                }
                if (currentSettings.showImageLocation && photoLocation.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append(photoLocation)
                }
            }
            txtPhotoInfo.text = photoInfo
        }
    }

    private fun updateDateTime() {
        if (currentSettings.showClock) {
            val currentDateTime = Calendar.getInstance().time

            val formattedDate = try {
                SimpleDateFormat(currentSettings.photoDateFormat, Locale.getDefault()).format(currentDateTime)
            } catch (_: Exception) {
                ""
            }

            val formattedTime = try {
                SimpleDateFormat(currentSettings.clockFormat, Locale.getDefault()).format(currentDateTime)
            } catch (_: Exception) {
                ""
            }

            val dt = if (currentSettings.showCurrentDate && formattedDate.isNotEmpty()) {
                "$formattedDate\n$formattedTime"
            } else {
                formattedTime
            }

            txtDateTime.text = SpannableString(dt).apply {
                val start = if (currentSettings.showCurrentDate && formattedDate.isNotEmpty()) formattedDate.length + 1 else 0
                setSpan(RelativeSizeSpan(2f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun getNextImage() {
        CoroutineScope(Dispatchers.Main).launch {
            val display = immichManager.getNextImage()
            if (display != null) {
                previousImage = currentImage
                currentImage = display
                showImage(display)
            } else {
                Log.w("ScreenSaverService", "Failed to fetch image from Immich")
            }
        }
    }

    private fun startImageTimer() {
        if (!isImageTimerRunning) {
            isImageTimerRunning = true
            handler.postDelayed(imageRunnable, (currentSettings.intervalSeconds * 1000).toLong())
        }
    }

    private fun stopImageTimer() {
        isImageTimerRunning = false
        handler.removeCallbacks(imageRunnable)
    }

    private fun startZoomAnimation(imageView: ImageView) {
        zoomAnimator?.cancel()
        zoomAnimator = ObjectAnimator.ofPropertyValuesHolder(
            imageView,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.2f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.2f)
        )
        zoomAnimator?.duration = (currentSettings.intervalSeconds * 1000).toLong()
        zoomAnimator?.start()
    }

    private fun loadSettings() {
        currentSettings = immichManager.getSettings()

        imageView1.visibility = View.VISIBLE
        imageView2.visibility = View.VISIBLE

        if (currentSettings.imageFill) {
            imageView1.scaleType = ImageView.ScaleType.CENTER_CROP
            imageView2.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            imageView1.scaleType = ImageView.ScaleType.FIT_CENTER
            imageView2.scaleType = ImageView.ScaleType.FIT_CENTER
        }

        if (currentSettings.showPhotoDate || currentSettings.showImageLocation) {
            txtPhotoInfo.visibility = View.VISIBLE
            txtPhotoInfo.textSize = Helpers.cssFontSizeToSp(currentSettings.baseFontSize, this)
            if (currentSettings.primaryColor != null) {
                txtPhotoInfo.setTextColor(
                    runCatching { currentSettings.primaryColor!!.toColorInt() }.getOrDefault(Color.WHITE)
                )
            } else {
                txtPhotoInfo.setTextColor(Color.WHITE)
            }
        } else {
            txtPhotoInfo.visibility = View.GONE
        }

        if (currentSettings.showClock) {
            txtDateTime.visibility = View.VISIBLE
            txtDateTime.textSize = Helpers.cssFontSizeToSp(currentSettings.baseFontSize, this)
            if (currentSettings.primaryColor != null) {
                txtDateTime.setTextColor(
                    runCatching { currentSettings.primaryColor!!.toColorInt() }.getOrDefault(Color.WHITE)
                )
            } else {
                txtDateTime.setTextColor(Color.WHITE)
            }
        } else {
            txtDateTime.visibility = View.GONE
        }

        getNextImage()
        startImageTimer()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "ImmichFrame::ScreenSaverWakeLock"
            )
            wakeLock?.acquire(120 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
}