package com.immichframe.standalone

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.widget.LinearLayout
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var imageView1: ImageView
    private lateinit var imageView2: ImageView
    private lateinit var layoutPhotoInfo: View
    private lateinit var layoutPhotoDate: View
    private lateinit var imgPhotoDateIcon: ImageView
    private lateinit var txtPhotoDate: TextView
    private lateinit var layoutPhotoLocation: View
    private lateinit var imgPhotoLocationIcon: ImageView
    private lateinit var txtPhotoLocation: TextView
    private lateinit var txtDateTime: TextView
    private lateinit var btnPrevious: Button
    private lateinit var btnPause: Button
    private lateinit var btnNext: Button
    private lateinit var dimOverlay: View
    private lateinit var settingsIndicator: FrameLayout
    private lateinit var imgSettingsGear: ImageView
    private lateinit var infoOverlay: FrameLayout
    private lateinit var infoCardContainer: View
    private lateinit var txtInfoTitle: TextView
    private lateinit var btnInfoClose: ImageButton
    private lateinit var imgQrCode: ImageView
    private lateinit var txtInfoDetails: TextView
    private lateinit var txtInfoUrl: TextView
    private lateinit var layoutOfflineIndicator: LinearLayout
    private lateinit var txtOfflineStatus: TextView
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private lateinit var layoutMapSection: View
    private lateinit var txtMapLocation: TextView
    private lateinit var layoutMapContainer: View
    private lateinit var imgMapView: ImageView
    private lateinit var txtMapZoom: TextView
    private var isCountryZoom = false
    private var currentGpsLat: Double? = null
    private var currentGpsLon: Double? = null

    private lateinit var immichManager: ImmichManager
    private lateinit var currentSettings: ImmichManager.FrameSettings
    private lateinit var rcpServer: RpcHttpServer

    private var isFlingHandled = false

    private val gestureDetector by lazy {
        val viewConfig = ViewConfiguration.get(this)
        val density = resources.displayMetrics.density
        val minVelocity = viewConfig.scaledMinimumFlingVelocity * 1.8
        val horizontalThreshold = 90 * density
        val verticalThreshold = 130 * density
        val screenHeight = resources.displayMetrics.heightPixels

        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onLongPress(e: MotionEvent) {
                // Per user requirement: show info & QR code when long pressing on a paused image
                if (!isImageTimerRunning) {
                    if (::infoOverlay.isInitialized && infoOverlay.visibility != View.VISIBLE) {
                        showImageInfoOverlay()
                    }
                }
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 != null && e1.y < screenHeight * 0.35f) {
                    val totalDragY = e2.y - e1.y
                    val totalDragX = Math.abs(e2.x - e1.x)
                    if (totalDragY > 15 * density && totalDragY > totalDragX * 1.5) {
                        if (::settingsIndicator.isInitialized) {
                            handler.removeCallbacks(hideSettingsRunnable)
                            settingsIndicator.visibility = View.VISIBLE
                            val progress = (totalDragY / (140 * density)).coerceIn(0f, 1f)
                            settingsIndicator.translationY = (-resources.displayMetrics.density * 50f) + (resources.displayMetrics.density * 70f * progress)
                            settingsIndicator.alpha = progress
                            imgSettingsGear.rotation = progress * 90f
                            return true
                        }
                    }
                }
                return false
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                val absDiffX = Math.abs(diffX)
                val absDiffY = Math.abs(diffY)

                if (::infoOverlay.isInitialized && infoOverlay.visibility == View.VISIBLE) {
                    // Swiping down dismisses info overlay
                    if (absDiffY > absDiffX && diffY > 60 * density) {
                        isFlingHandled = true
                        hideImageInfoOverlay()
                        return true
                    }
                    return false
                }

                // 1. Horizontal swipe (left/right for next/previous image)
                // Require clear horizontal dominance (at least 1.4x vertical) and sufficient distance
                if (absDiffX > absDiffY * 1.4 && absDiffX > horizontalThreshold && Math.abs(velocityX) > minVelocity) {
                    isFlingHandled = true
                    if (diffX > 0) {
                        // Swiped left-to-right (Right) -> Previous image
                        val toast = Toast.makeText(this@MainActivity, "Previous", Toast.LENGTH_SHORT)
                        toast.setGravity(Gravity.CENTER_VERTICAL or Gravity.START, 0, 0)
                        toast.show()
                        previousAction()
                    } else {
                        // Swiped right-to-left (Left) -> Next image
                        val toast = Toast.makeText(this@MainActivity, "Next", Toast.LENGTH_SHORT)
                        toast.setGravity(Gravity.CENTER_VERTICAL or Gravity.END, 0, 0)
                        toast.show()
                        nextAction()
                    }
                    return true
                }

                // 2. Vertical swipe UP: Open Info & QR Code
                // Must be strictly vertical (at least 2.0x horizontal), upward by at least 140dp,
                // and must start from the bottom portion of the screen (bottom 40%)
                if (absDiffY > absDiffX * 2.0 && diffY < -verticalThreshold && Math.abs(velocityY) > minVelocity) {
                    if (e1.y > screenHeight * 0.60f) {
                        isFlingHandled = true
                        if (::infoOverlay.isInitialized && infoOverlay.visibility != View.VISIBLE) {
                            if (isImageTimerRunning) {
                                stopImageTimer()
                            }
                            showImageInfoOverlay()
                        }
                        return true
                    }
                }

                // 3. Vertical swipe DOWN: Open Settings
                // Must be strictly vertical (at least 2.0x horizontal), downward by at least 140dp,
                // and must start from the top portion of the screen (top 35%)
                if (absDiffY > absDiffX * 2.0 && diffY > verticalThreshold && Math.abs(velocityY) > minVelocity) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
                    val settingsLock = prefs.getBoolean("settingsLock", false)
                    if (!settingsLock && e1.y < screenHeight * 0.35f) {
                        isFlingHandled = true
                        showSettingsIndicator {
                            settingsAction()
                        }
                        return true
                    }
                }

                return false
            }
        })
    }

    private val hideSettingsRunnable = Runnable {
        if (::settingsIndicator.isInitialized && settingsIndicator.visibility == View.VISIBLE) {
            settingsIndicator.animate()
                .translationY(-resources.displayMetrics.density * 60f)
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    settingsIndicator.visibility = View.GONE
                }
                .start()
        }
    }

    private fun showSettingsIndicator(onComplete: (() -> Unit)? = null) {
        if (!::settingsIndicator.isInitialized) {
            onComplete?.invoke()
            return
        }
        handler.removeCallbacks(hideSettingsRunnable)
        settingsIndicator.visibility = View.VISIBLE
        settingsIndicator.translationY = -resources.displayMetrics.density * 50f
        settingsIndicator.alpha = 0f
        imgSettingsGear.rotation = 0f

        settingsIndicator.animate()
            .translationY(resources.displayMetrics.density * 20f)
            .alpha(1f)
            .setDuration(200)
            .withEndAction {
                imgSettingsGear.animate()
                    .rotation(90f)
                    .setDuration(200)
                    .withEndAction {
                        onComplete?.invoke()
                        hideSettingsIndicator(delayMs = 600)
                    }
                    .start()
            }
            .start()
    }

    private fun hideSettingsIndicator(delayMs: Long = 0) {
        handler.removeCallbacks(hideSettingsRunnable)
        if (delayMs > 0) {
            handler.postDelayed(hideSettingsRunnable, delayMs)
        } else {
            hideSettingsRunnable.run()
        }
    }

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

    private val activeCheckRunnable = object : Runnable {
        override fun run() {
            checkActiveTime()
            handler.postDelayed(this, 30000)
        }
    }

    private var isFrameInactive: Boolean? = null
    private var isManualOverride = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> onScreenTurnedOn()
                Intent.ACTION_SCREEN_OFF -> onScreenTurnedOff()
            }
        }
    }

    private var isShowingFirst = true
    private var zoomAnimator: ObjectAnimator? = null

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            SettingsBackupHelper.backup(this)
            val settings = immichManager.getSettings()
            if (settings.serverUrl.isNotBlank()) {
                immichManager.clearCache()
                loadSettings()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        SettingsBackupHelper.init(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.main_view)
        hideSystemUI()

        immichManager = ImmichManager(this)
        currentSettings = immichManager.getSettings()

        imageView1 = findViewById(R.id.imageView1)
        imageView2 = findViewById(R.id.imageView2)
        layoutPhotoInfo = findViewById(R.id.layoutPhotoInfo)
        layoutPhotoDate = findViewById(R.id.layoutPhotoDate)
        imgPhotoDateIcon = findViewById(R.id.imgPhotoDateIcon)
        txtPhotoDate = findViewById(R.id.txtPhotoDate)
        layoutPhotoLocation = findViewById(R.id.layoutPhotoLocation)
        imgPhotoLocationIcon = findViewById(R.id.imgPhotoLocationIcon)
        txtPhotoLocation = findViewById(R.id.txtPhotoLocation)
        txtDateTime = findViewById(R.id.txtDateTime)
        btnPrevious = findViewById(R.id.btnPrevious)
        btnPause = findViewById(R.id.btnPause)
        btnNext = findViewById(R.id.btnNext)
        dimOverlay = findViewById(R.id.dimOverlay)
        settingsIndicator = findViewById(R.id.settingsIndicator)
        imgSettingsGear = findViewById(R.id.imgSettingsGear)

        infoOverlay = findViewById(R.id.infoOverlay)
        infoCardContainer = findViewById(R.id.infoCardContainer)
        txtInfoTitle = findViewById(R.id.txtInfoTitle)
        btnInfoClose = findViewById(R.id.btnInfoClose)
        imgQrCode = findViewById(R.id.imgQrCode)
        txtInfoDetails = findViewById(R.id.txtInfoDetails)
        txtInfoUrl = findViewById(R.id.txtInfoUrl)
        layoutOfflineIndicator = findViewById(R.id.layoutOfflineIndicator)
        txtOfflineStatus = findViewById(R.id.txtOfflineStatus)

        layoutMapSection = findViewById(R.id.layoutMapSection)
        txtMapLocation = findViewById(R.id.txtMapLocation)
        layoutMapContainer = findViewById(R.id.layoutMapContainer)
        imgMapView = findViewById(R.id.imgMapView)
        txtMapZoom = findViewById(R.id.txtMapZoom)

        val toggleZoomAction = View.OnClickListener {
            val lat = currentGpsLat
            val lon = currentGpsLon
            if (lat != null && lon != null) {
                isCountryZoom = !isCountryZoom
                loadMap(lat, lon, isCountryZoom)
            }
        }
        layoutMapContainer.setOnClickListener(toggleZoomAction)
        imgMapView.setOnClickListener(toggleZoomAction)

        infoOverlay.setOnClickListener {
            hideImageInfoOverlay()
        }
        btnInfoClose.setOnClickListener {
            hideImageInfoOverlay()
        }
        val openLinkAction = View.OnClickListener {
            val targetUrl = (txtInfoUrl.tag as? String)?.takeIf { it.isNotBlank() }
                ?: txtInfoUrl.text.toString()
            if (targetUrl.isNotBlank()) {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                    startActivity(intent)
                }
            }
        }
        txtInfoUrl.setOnClickListener(openLinkAction)
        imgQrCode.setOnClickListener(openLinkAction)

        // Webview is hidden in standalone mode
        findViewById<View>(R.id.webView)?.visibility = View.GONE

        val buttonLongClickListener = View.OnLongClickListener {
            if (!isImageTimerRunning) {
                if (::infoOverlay.isInitialized && infoOverlay.visibility != View.VISIBLE) {
                    showImageInfoOverlay()
                    return@OnLongClickListener true
                }
            }
            false
        }
        btnPrevious.setOnLongClickListener(buttonLongClickListener)
        btnPause.setOnLongClickListener(buttonLongClickListener)
        btnNext.setOnLongClickListener(buttonLongClickListener)

        btnPrevious.setOnClickListener {
            if (::infoOverlay.isInitialized && infoOverlay.visibility == View.VISIBLE) {
                hideImageInfoOverlay()
                return@setOnClickListener
            }
            val toast = Toast.makeText(this, "Previous", Toast.LENGTH_SHORT)
            toast.setGravity(Gravity.CENTER_VERTICAL or Gravity.START, 0, 0)
            toast.show()
            previousAction()
        }

        btnPause.setOnClickListener {
            if (::infoOverlay.isInitialized && infoOverlay.visibility == View.VISIBLE) {
                hideImageInfoOverlay()
                return@setOnClickListener
            }
            val toast = Toast.makeText(this, "Pause", Toast.LENGTH_SHORT)
            toast.setGravity(Gravity.CENTER, 0, 0)
            toast.show()
            pauseAction()
        }

        btnNext.setOnClickListener {
            if (::infoOverlay.isInitialized && infoOverlay.visibility == View.VISIBLE) {
                hideImageInfoOverlay()
                return@setOnClickListener
            }
            val toast = Toast.makeText(this, "Next", Toast.LENGTH_SHORT)
            toast.setGravity(Gravity.CENTER_VERTICAL or Gravity.END, 0, 0)
            toast.show()
            nextAction()
        }

        rcpServer = RpcHttpServer(
            onFrameActiveCommand = { active -> runOnUiThread { setFrameActive(active) } },
            onNextCommand = { runOnUiThread { nextAction() } },
            onPreviousCommand = { runOnUiThread { previousAction() } },
            onPauseCommand = { runOnUiThread { pauseAction() } },
            onSettingsCommand = { runOnUiThread { settingsAction() } },
            onBrightnessCommand = { brightness -> runOnUiThread { screenBrightnessAction(brightness) } },
            onInfoCommand = { runOnUiThread { if (isImageTimerRunning) stopImageTimer(); showImageInfoOverlay() } }
        )
        try {
            rcpServer.start()
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start RPC HTTP server: ${e.message}", e)
        }

        registerReceiver(
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )

        immichManager.onOnlineStatusChanged = { isOnline ->
            runOnUiThread {
                updateOfflineIndicator(!isOnline)
            }
        }
        registerNetworkCallback()

        val settings = immichManager.getSettings()
        if (settings.serverUrl.isBlank()) {
            val intent = Intent(this@MainActivity, SettingsActivity::class.java)
            settingsLauncher.launch(intent)
        } else {
            loadSettings()
        }
    }

    private fun loadSettings() {
        currentSettings = immichManager.getSettings()

        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val settingsLock = prefs.getBoolean("settingsLock", false)
        val activeTimes = prefs.getBoolean("activeTimes", false)

        imageView1.visibility = View.VISIBLE
        imageView2.visibility = View.VISIBLE
        btnPrevious.visibility = View.VISIBLE
        btnPause.visibility = View.VISIBLE
        btnNext.visibility = View.VISIBLE

        if (currentSettings.imageFill) {
            imageView1.scaleType = ImageView.ScaleType.CENTER_CROP
            imageView2.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            imageView1.scaleType = ImageView.ScaleType.FIT_CENTER
            imageView2.scaleType = ImageView.ScaleType.FIT_CENTER
        }

        if (currentSettings.showPhotoDate || currentSettings.showImageLocation) {
            val fontSize = Helpers.cssFontSizeToSp(currentSettings.baseFontSize, this)
            txtPhotoDate.textSize = fontSize
            txtPhotoLocation.textSize = fontSize
            val density = resources.displayMetrics.density
            val iconSize = (fontSize * 1.35f * density).toInt().coerceAtLeast((24 * density).toInt())
            imgPhotoDateIcon.layoutParams.width = iconSize
            imgPhotoDateIcon.layoutParams.height = iconSize
            imgPhotoLocationIcon.layoutParams.width = iconSize
            imgPhotoLocationIcon.layoutParams.height = iconSize
            val color = if (currentSettings.primaryColor != null) {
                runCatching { currentSettings.primaryColor!!.toColorInt() }.getOrDefault(Color.WHITE)
            } else {
                Color.WHITE
            }
            txtPhotoDate.setTextColor(color)
            txtPhotoLocation.setTextColor(color)
            imgPhotoDateIcon.setColorFilter(color)
            imgPhotoLocationIcon.setColorFilter(color)
            txtPhotoDate.setShadowLayer(4f, 1.5f, 1.5f, Color.BLACK)
            txtPhotoLocation.setShadowLayer(4f, 1.5f, 1.5f, Color.BLACK)
        } else {
            layoutPhotoInfo.visibility = View.GONE
        }

        if (currentSettings.showClock) {
            txtDateTime.textSize = Helpers.cssFontSizeToSp(currentSettings.baseFontSize, this)
            if (currentSettings.primaryColor != null) {
                txtDateTime.setTextColor(
                    runCatching { currentSettings.primaryColor!!.toColorInt() }.getOrDefault(Color.WHITE)
                )
            } else {
                txtDateTime.setTextColor(Color.WHITE)
            }
            txtDateTime.setShadowLayer(4f, 1.5f, 1.5f, Color.BLACK)
        } else {
            txtDateTime.visibility = View.GONE
        }

        if (activeTimes) {
            handler.removeCallbacks(activeCheckRunnable)
            handler.post(activeCheckRunnable)
        } else {
            handler.removeCallbacks(activeCheckRunnable)
            if (isFrameInactive != false) {
                setFrameActive(true)
            }
        }

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
                    val firstPortrait = portraitCache!!.bitmap
                    val scaled1 = Helpers.reduceBitmapQuality(firstPortrait, maxSize)
                    val scaled2 = Helpers.reduceBitmapQuality(finalBitmap, maxSize)

                    val colorString = currentSettings.primaryColor?.takeIf { it.isNotBlank() } ?: "#FFFFFF"
                    val parsedColor = runCatching { colorString.toColorInt() }.getOrDefault(Color.WHITE)

                    finalBitmap = Helpers.mergeImages(scaled1, scaled2, parsedColor)
                    if (scaled1 != firstPortrait && !scaled1.isRecycled) scaled1.recycle()
                    if (scaled2 != display.bitmap && !scaled2.isRecycled) scaled2.recycle()
                    portraitCache = null
                    isMerged = true
                } else {
                    portraitCache = display
                    getNextImage()
                    return@launch
                }
            } else {
                finalBitmap = Helpers.reduceBitmapQuality(finalBitmap, maxSize)
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
            val bgDrawable = blurredBitmap.toDrawable(resources).apply {
                isFilterBitmap = true
                setDither(true)
            }
            imageViewNew.background = bgDrawable
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
        if (!::layoutPhotoInfo.isInitialized) return
        val showDate = currentSettings.showPhotoDate && photoDate.isNotBlank()
        val showLoc = currentSettings.showImageLocation && photoLocation.isNotBlank()

        if (showDate || showLoc) {
            layoutPhotoInfo.visibility = View.VISIBLE

            if (showDate) {
                txtPhotoDate.text = photoDate
                layoutPhotoDate.visibility = View.VISIBLE
            } else {
                txtPhotoDate.text = ""
                layoutPhotoDate.visibility = View.GONE
            }

            if (showLoc) {
                txtPhotoLocation.text = photoLocation
                layoutPhotoLocation.visibility = View.VISIBLE
            } else {
                txtPhotoLocation.text = ""
                layoutPhotoLocation.visibility = View.GONE
            }
        } else {
            layoutPhotoInfo.visibility = View.GONE
        }
    }

    private fun updateDateTime() {
        if (currentSettings.showClock) {
            val currentDateTime = Calendar.getInstance().time

            val formattedDate = try {
                val safePattern = Helpers.sanitizeDatePattern(currentSettings.photoDateFormat)
                SimpleDateFormat(safePattern, Locale.getDefault()).format(currentDateTime)
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to format date with pattern '${currentSettings.photoDateFormat}': ${e.message}")
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

            if (dt.isNotBlank()) {
                txtDateTime.visibility = View.VISIBLE
                txtDateTime.text = SpannableString(dt).apply {
                    val start = if (currentSettings.showCurrentDate && formattedDate.isNotEmpty()) formattedDate.length + 1 else 0
                    setSpan(RelativeSizeSpan(2f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else {
                txtDateTime.visibility = View.GONE
            }
        } else {
            txtDateTime.visibility = View.GONE
        }
    }

    private fun getNextImage() {
        lifecycleScope.launch {
            val display = immichManager.getNextImage()
            if (display != null) {
                previousImage = currentImage
                currentImage = display
                showImage(display)
            } else {
                Log.w("MainActivity", "Failed to fetch image from Immich")
                if (currentImage == null) {
                    kotlinx.coroutines.delay(2500)
                    getNextImage()
                }
            }
        }
    }

    private fun previousAction() {
        hideImageInfoOverlay()
        lifecycleScope.launch {
            val display = immichManager.getPreviousImage()
            if (display != null) {
                stopImageTimer()
                showImage(display)
                startImageTimer()
            }
        }
    }

    private fun nextAction() {
        hideImageInfoOverlay()
        stopImageTimer()
        getNextImage()
        startImageTimer()
    }

    private fun pauseAction() {
        hideImageInfoOverlay()
        zoomAnimator?.cancel()
        if (isImageTimerRunning) {
            stopImageTimer()
        } else {
            getNextImage()
            startImageTimer()
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

    private fun showImageInfoOverlay() {
        val display = currentImage ?: return
        val serverUrl = currentSettings.serverUrl.trimEnd('/')
        val photoUrl = if (serverUrl.isNotBlank()) "$serverUrl/photos/${display.assetId}" else "https://my.immich.app/photos/${display.assetId}"
        val deepLinkUrl = if (serverUrl.isNotBlank()) {
            val encodedInstanceUrl = java.net.URLEncoder.encode(serverUrl, "UTF-8")
            "https://my.immich.app/photos/${display.assetId}?instanceUrl=$encodedInstanceUrl"
        } else {
            "https://my.immich.app/photos/${display.assetId}"
        }

        val qrBitmap = Helpers.generateQrCodeBitmap(deepLinkUrl, 360)
        if (qrBitmap != null) {
            imgQrCode.setImageBitmap(qrBitmap)
        }
        txtInfoUrl.text = photoUrl
        txtInfoUrl.tag = deepLinkUrl

        val asset = display.asset
        val exif = asset?.exifInfo
        val sb = StringBuilder()

        if (display.photoDate.isNotBlank()) {
            sb.append("📅 Date: ").append(display.photoDate).append("\n\n")
        }

        val city = exif?.city?.trim()
        val country = exif?.country?.trim()
        val state = exif?.state?.trim()

        val locationString = when {
            !city.isNullOrEmpty() && !country.isNullOrEmpty() -> "$city, $country"
            !city.isNullOrEmpty() && !state.isNullOrEmpty() -> "$city, $state"
            !city.isNullOrEmpty() -> city
            !country.isNullOrEmpty() -> country
            display.imageLocation.isNotBlank() -> display.imageLocation
            else -> ""
        }

        if (locationString.isNotBlank()) {
            sb.append("📍 Location: ").append(locationString).append("\n\n")
        }

        val peopleNames = asset?.people?.mapNotNull { it.name?.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (peopleNames.isNotEmpty()) {
            sb.append("👥 People: ").append(peopleNames.joinToString(", ")).append("\n\n")
        }

        val camera = listOfNotNull(exif?.make?.trim(), exif?.model?.trim()).distinct().joinToString(" ")
        if (camera.isNotBlank()) {
            sb.append("📷 Camera: ").append(camera).append("\n")
        }

        val settingsList = mutableListOf<String>()
        if (exif?.focalLength != null && exif.focalLength > 0) {
            settingsList.add(String.format(java.util.Locale.US, "%.1fmm", exif.focalLength))
        }
        if (exif?.fNumber != null && exif.fNumber > 0) {
            settingsList.add(String.format(java.util.Locale.US, "f/%.1f", exif.fNumber))
        }
        if (!exif?.exposureTime.isNullOrBlank()) {
            settingsList.add("${exif?.exposureTime}s")
        }
        if (exif?.iso != null && exif.iso > 0) {
            settingsList.add("ISO ${exif.iso}")
        }
        if (settingsList.isNotEmpty()) {
            sb.append("⚙️ Settings: ").append(settingsList.joinToString(" · ")).append("\n")
        }

        val width = exif?.exifImageWidth ?: display.bitmap.width
        val height = exif?.exifImageHeight ?: display.bitmap.height
        val mp = (width.toLong() * height.toLong()).toFloat() / 1_000_000f
        val sizeStr = if (exif?.fileSizeInByte != null && exif.fileSizeInByte > 0) {
            String.format(java.util.Locale.US, " · %.1f MB", exif.fileSizeInByte.toDouble() / (1024 * 1024))
        } else ""
        sb.append(String.format(java.util.Locale.US, "📐 Resolution: %d × %d (%.1f MP)%s\n\n", width, height, mp, sizeStr))

        if (!asset?.originalFileName.isNullOrBlank()) {
            sb.append("📁 File: ").append(asset.originalFileName)
        }

        txtInfoDetails.text = sb.toString().trim()
        txtInfoTitle.text = if (!asset?.originalFileName.isNullOrBlank()) asset.originalFileName else "Photo Details"

        // Map and GPS Handling
        val lat = exif?.latitude
        val lon = exif?.longitude
        currentGpsLat = lat
        currentGpsLon = lon
        isCountryZoom = false // Start at city level zoom

        if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
            layoutMapSection.visibility = View.VISIBLE
            txtMapLocation.text = if (locationString.isNotBlank()) locationString else String.format(Locale.US, "%.4f°, %.4f°", lat, lon)
            loadMap(lat, lon, isCountryZoom)
        } else {
            layoutMapSection.visibility = View.GONE
        }

        infoOverlay.visibility = View.VISIBLE
        infoOverlay.alpha = 0f
        infoOverlay.animate().alpha(1f).setDuration(250).start()
    }

    private fun loadMap(lat: Double, lon: Double, isCountry: Boolean) {
        if (!::imgMapView.isInitialized) return
        val zoom = if (isCountry) MapTileHelper.ZOOM_COUNTRY else MapTileHelper.ZOOM_CITY
        txtMapZoom.text = if (isCountry) "Country view · Tap for city" else "City view · Tap for country"

        lifecycleScope.launch {
            val density = resources.displayMetrics.density
            val widthPx = (300 * density).toInt()
            val heightPx = (180 * density).toInt()
            val mapBitmap = MapTileHelper.renderMap(
                lat = lat,
                lon = lon,
                zoom = zoom,
                widthPx = widthPx,
                heightPx = heightPx,
                client = immichManager.okHttpClient
            )
            imgMapView.setImageBitmap(mapBitmap)
        }
    }

    private fun hideImageInfoOverlay() {
        if (!::infoOverlay.isInitialized || infoOverlay.visibility != View.VISIBLE) return
        infoOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                infoOverlay.visibility = View.GONE
            }
            .start()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            isFlingHandled = false
        }
        gestureDetector.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (!isFlingHandled) {
                hideSettingsIndicator(delayMs = 150)
            }
        }
        if (isFlingHandled) {
            val cancelEvent = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancelEvent)
            cancelEvent.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onBackPressed() {
        if (::infoOverlay.isInitialized && infoOverlay.visibility == View.VISIBLE) {
            hideImageInfoOverlay()
            return
        }
        super.onBackPressed()
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

    private fun settingsAction() {
        val intent = Intent(this, SettingsActivity::class.java)
        stopImageTimer()
        settingsLauncher.launch(intent)
    }

    private fun screenBrightnessAction(brightness: Float) {
        val lp = window.attributes
        if (brightness < 0) {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            lp.screenBrightness = brightness
        }
        window.attributes = lp
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    settingsAction()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER -> {
                    pauseAction()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    previousAction()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    nextAction()
                    return true
                }
                KeyEvent.KEYCODE_SPACE -> {
                    pauseAction()
                    return true
                }
                KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_INFO -> {
                    if (::infoOverlay.isInitialized && infoOverlay.visibility == View.VISIBLE) {
                        hideImageInfoOverlay()
                    } else {
                        if (isImageTimerRunning) stopImageTimer()
                        showImageInfoOverlay()
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (::infoOverlay.isInitialized && infoOverlay.visibility == View.VISIBLE) {
                        hideImageInfoOverlay()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }

    private fun checkActiveTime() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val schedule = Helpers.parseActiveSchedule(prefs.getString("activeSchedule", null))
        val shouldBeActive = Helpers.isActiveNow(schedule, Calendar.getInstance())

        if (shouldBeActive && isFrameInactive != false) {
            setFrameActive(true)
        } else if (!shouldBeActive) {
            if (isFrameInactive != true) {
                setFrameActive(false)
            } else {
                scheduleWakeAlarm()
            }
        }
    }

    private fun setFrameActive(active: Boolean) {
        if (active) {
            isFrameInactive = false
            cancelWakeAlarm()
            wakeScreen()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                keyguardManager.requestDismissKeyguard(this, null)
            }
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
            val lp = window.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
            if (dimOverlay.isVisible) {
                dimOverlay.animate()
                    .alpha(0f)
                    .setDuration(500L)
                    .withEndAction {
                        dimOverlay.visibility = View.GONE
                        loadSettings()
                    }
                    .start()
            } else {
                loadSettings()
            }
        } else {
            isFrameInactive = true
            stopImageTimer()
            dimOverlay.apply {
                visibility = View.VISIBLE
                alpha = 0.99f
            }
            val lp = window.attributes
            lp.screenBrightness = 0f
            window.attributes = lp
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(false)
                setTurnScreenOn(false)
            }
            scheduleWakeAlarm()
            lockDeviceIfPossible()
        }
    }

    private fun onScreenTurnedOn() {
        if (isFrameInactive == true && !isManualOverride) {
            isManualOverride = true
            showFrameTemporarily()
        }
    }

    private fun onScreenTurnedOff() {
        if (isManualOverride) {
            isManualOverride = false
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val activeTimes = prefs.getBoolean("activeTimes", false)
            val schedule = Helpers.parseActiveSchedule(prefs.getString("activeSchedule", null))
            val shouldBeActive = !activeTimes || Helpers.isActiveNow(schedule, Calendar.getInstance())
            setFrameActive(shouldBeActive)
        }
    }

    private fun showFrameTemporarily() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        if (dimOverlay.isVisible) {
            dimOverlay.animate()
                .alpha(0f)
                .setDuration(500L)
                .withEndAction {
                    dimOverlay.visibility = View.GONE
                    loadSettings()
                }
                .start()
        } else {
            loadSettings()
        }
    }

    private fun wakeScreen() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    or PowerManager.ACQUIRE_CAUSES_WAKEUP
                    or PowerManager.ON_AFTER_RELEASE,
            "immichframe:activeWake",
        )
        wakeLock.acquire(3000L)
    }

    private fun lockDeviceIfPossible() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(FrameDeviceAdminReceiver.componentName(this))) {
            try {
                dpm.lockNow()
            } catch (e: SecurityException) {
                Log.w("MainActivity", "Unable to lock device: ${e.message}")
            }
        }
    }

    private fun wakeAlarmPendingIntent(): PendingIntent {
        val intent = Intent(this, ScheduleWakeReceiver::class.java).apply {
            action = ScheduleWakeReceiver.ACTION_WAKE
        }
        return PendingIntent.getBroadcast(
            this,
            ScheduleWakeReceiver.REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun scheduleWakeAlarm() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val schedule = Helpers.parseActiveSchedule(prefs.getString("activeSchedule", null))
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = wakeAlarmPendingIntent()
        alarmManager.cancel(pendingIntent)
        val next = Helpers.nextActiveStart(schedule, Calendar.getInstance()) ?: return
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                next.timeInMillis,
                pendingIntent,
            )
        } catch (e: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, next.timeInMillis, pendingIntent)
            Log.w("MainActivity", "Exact alarm denied, using inexact wake: ${e.message}")
        }
    }

    private fun cancelWakeAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(wakeAlarmPendingIntent())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun updateOfflineIndicator(isOffline: Boolean) {
        runOnUiThread {
            if (!::layoutOfflineIndicator.isInitialized) return@runOnUiThread
            if (isOffline) {
                val poolSize = immichManager.getCachedPool().size
                if (poolSize > 0) {
                    txtOfflineStatus.text = "Offline · Cycling $poolSize cached photos"
                } else {
                    txtOfflineStatus.text = "Offline · Waiting for connection..."
                }
                if (layoutOfflineIndicator.visibility != View.VISIBLE) {
                    layoutOfflineIndicator.alpha = 0f
                    layoutOfflineIndicator.visibility = View.VISIBLE
                    layoutOfflineIndicator.animate().alpha(1f).setDuration(300).start()
                }
            } else {
                if (layoutOfflineIndicator.visibility == View.VISIBLE) {
                    layoutOfflineIndicator.animate().alpha(0f).setDuration(300).withEndAction {
                        layoutOfflineIndicator.visibility = View.GONE
                    }.start()
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val activeNet = cm.activeNetworkInfo
            val isConnected = activeNet?.isConnected == true
            if (!isConnected) {
                immichManager.setOnlineStatus(false)
                updateOfflineIndicator(true)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val builder = NetworkRequest.Builder()
                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        immichManager.setOnlineStatus(true)
                        updateOfflineIndicator(false)
                    }
                    override fun onLost(network: Network) {
                        immichManager.setOnlineStatus(false)
                        updateOfflineIndicator(true)
                    }
                }
                cm.registerNetworkCallback(builder.build(), networkCallback!!)
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to register network callback: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        hideSettingsIndicator(0)
        hideSystemUI()
        if (isManualOverride) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        if (prefs.getBoolean("activeTimes", false)) {
            checkActiveTime()
        } else if (isFrameInactive != false) {
            cancelWakeAlarm()
            setFrameActive(true)
        }
    }

    override fun onPause() {
        super.onPause()
        hideSettingsIndicator(0)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { rcpServer.stop() }
        unregisterReceiver(screenStateReceiver)
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback?.let { cm?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
    }
}
