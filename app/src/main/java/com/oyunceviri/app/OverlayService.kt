package com.oyunceviri.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var frameView: FrameView

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var apiKey: String = ""
    private var selectedModel: String = "gemini-flash-lite-latest"
    private var isBusy: Boolean = false
    private var isActive: Boolean = false
    private var emptyFramesCount: Int = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val safeIntent = intent ?: return START_NOT_STICKY
        apiKey = safeIntent.getStringExtra("API_KEY")?.trim()
            ?: safeIntent.getStringExtra("api_key")?.trim()
            ?: ""
        selectedModel = safeIntent.getStringExtra("MODEL")?.trim()
            ?: safeIntent.getStringExtra("model")?.trim()
            ?: "gemini-flash-lite-latest"

        val resultCode = safeIntent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)

        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            safeIntent.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            safeIntent.getParcelableExtra("DATA")
        }

        if (resultCode != Activity.RESULT_OK || data == null) return START_NOT_STICKY

        startForegroundSafely()
        setupOverlay()
        SubtitleProcessor.resetCache()

        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    isActive = false
                    releaseCaptureResources()
                }
            }, mainHandler)

            isActive = true
            startScanLoop()
        } catch (e: Exception) {
            frameView.statusMessage = "Başlatma hatası: ${e.message?.take(30)}"
        }

        return START_STICKY
    }

    private fun startForegroundSafely() {
        val channelId = "oyun_ceviri_overlay_kanal"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Oyun Altyazı Çeviri", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("Oyun Çeviri Aktif")
            .setContentText("Altyazılar taranıyor")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (::frameView.isInitialized) {
            try {
                windowManager.removeView(frameView)
            } catch (_: Exception) {
            }
        }

        frameView = FrameView(this)
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(frameView, params)
    }

    private fun startScanLoop() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "OyunCeviriDisplay",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )

        frameView.statusMessage = "Altyazı bekleniyor..."

        val loopRunnable = object : Runnable {
            override fun run() {
                if (!isActive) return
                processCurrentFrame()
                val nextDelay = if (emptyFramesCount > 2) 5000L else 3500L
                mainHandler.postDelayed(this, nextDelay)
            }
        }
        mainHandler.postDelayed(loopRunnable, 1200L)
    }

    private fun processCurrentFrame() {
        if (isBusy) return
        val image = try {
            imageReader?.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return

        isBusy = true
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val
            
