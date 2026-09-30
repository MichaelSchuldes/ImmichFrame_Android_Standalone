package com.immichframe.immichframe

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
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
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
    private lateinit var txtPhotoInfo: TextView
    private lateinit var txtDateTime: TextView
    private lateinit var btnPrevious: Button
    private lateinit var btnPause: Button
    private lateinit var btnNext: Button
    private lateinit var dimOverlay: View
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    private lateinit var immichManager: ImmichManager
    private lateinit var currentSettings: ImmichManager.FrameSettings
    private lateinit var rcpServer: RpcHttpServer

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
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                loadSettings()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.main_view)
        hideSystemUI()

        immichManager = ImmichManager(this)
        currentSettings = immichManager.getSettings()

        imageView1 = findViewById(R.id.imageView1)
        imageView2 = findViewById(R.id.imageView2)
        txtPhotoInfo = findViewById(R.id.txtPhotoInfo)
        txtDateTime = findViewById(R.id.txtDateTime)
        btnPrevious = findViewById(R.id.btnPrevious)
        btnPause = findViewById(R.id.btnPause)
        btnNext = findViewById(R.id.btnNext)
        dimOverlay = findViewById(R.id.dimOverlay)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)

        // Webview is hidden in standalone mode
        findViewById<View>(R.id.webView)?.visibility = View.GONE

        swipeRefreshLayout.setOnRefreshListener {
            swipeRefreshLayout.isRefreshing = false
            settingsAction()
        }

        btnPrevious.setOnClickListener {
            val toast = Toast.makeText(this, "Previous", Toast.LENGTH_SHORT)
            toast.setGravity(Gravity.CENTER_VERTICAL or Gravity.START, 0, 0)
            toast.show()
            previousAction()
        }

        btnPause.setOnClickListener {
            val toast = Toast.makeText(this, "Pause", Toast.LENGTH_SHORT)
            toast.setGravity(Gravity.CENTER, 0, 0)
            toast.show()
            pauseAction()
        }

        btnNext.setOnClickListener {
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
        )
        rcpServer.start()

        registerReceiver(
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )

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
        swipeRefreshLayout.isEnabled = !settingsLock

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
        lifecycleScope.launch {
            val display = immichManager.getNextImage()
            if (display != null) {
                previousImage = currentImage
                currentImage = display
                showImage(display)
            } else {
                Log.w("MainActivity", "Failed to fetch image from Immich")
            }
        }
    }

    private fun previousAction() {
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
        stopImageTimer()
        getNextImage()
        startImageTimer()
    }

    private fun pauseAction() {
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

    override fun onResume() {
        super.onResume()
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

    override fun onDestroy() {
        super.onDestroy()
        rcpServer.stop()
        unregisterReceiver(screenStateReceiver)
        handler.removeCallbacksAndMessages(null)
    }
}
