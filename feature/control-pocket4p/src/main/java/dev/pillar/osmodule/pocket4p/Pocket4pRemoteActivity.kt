package dev.pillar.osmodule.pocket4p

import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import dev.pillar.osmodule.duml.PocketShootingMode
import dev.pillar.osmodule.feature.control.pocket4p.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Plugin-owned Pocket 4 Pro remote panel: HEVC monitor, camera controls, and gimbal stick. */
class Pocket4pRemoteActivity : AppCompatActivity() {
    private lateinit var cameraAddress: String
    private lateinit var cameraName: String
    private lateinit var controller: Pocket4pRemoteController
    private lateinit var decoder: PocketHevcDecoder
    private val decodeExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "pocket4p.preview.decode").apply { isDaemon = true }
    }

    private lateinit var preview: TextureView
    private lateinit var phaseText: TextView
    private lateinit var previewText: TextView
    private lateinit var cameraStatusText: TextView
    private lateinit var gimbalStatusText: TextView
    private lateinit var resultText: TextView
    private lateinit var connectButton: MaterialButton
    private lateinit var galleryButton: MaterialButton
    private lateinit var shutterButton: MaterialButton
    private lateinit var modeButton: MaterialButton
    private lateinit var previewQualityButton: MaterialButton
    private lateinit var recenterButton: MaterialButton
    private lateinit var flipButton: MaterialButton
    private lateinit var joystick: PocketJoystickView
    private lateinit var zoomSlider: PocketZoomSliderView

    private var previewSurface: Surface? = null
    private var latestState = Pocket4pRemoteState()
    private var autoConnectAttempted = false
    private var previewHasFrame = false
    private var lastRenderedActionSerial = 0
    private var pendingRecordingTarget: Boolean? = null
    @Volatile private var destroyed = false
    private var previewVideoWidth = DEFAULT_PREVIEW_WIDTH
    private var previewVideoHeight = DEFAULT_PREVIEW_HEIGHT
    private val previewBytesSinceSample = AtomicLong(0L)
    private val presentedFrameSerial = AtomicLong(0L)
    private val lastPresentedFrameAt = AtomicLong(0L)
    private val latestPresentedRaster = AtomicLong(packRaster(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT))
    private var previewMetricStartedAt = 0L
    private var lastSampledFrameSerial = 0L
    private var previewMetricTickerRunning = false
    private var previewBitrateMbps = 0.0
    private var currentZoomFactor = 1.0
    private var previousNetwork: Network? = null
    private var borrowedNetworkBound = false
    private var galleryReturnRequested = false
    private var galleryFinishScheduled = false

    private val previewMetricTicker = object : Runnable {
        override fun run() {
            if (!previewMetricTickerRunning || destroyed || !latestState.canControl) return

            val now = SystemClock.elapsedRealtime()
            val elapsed = (now - previewMetricStartedAt).coerceAtLeast(1L)
            val sampledBytes = previewBytesSinceSample.getAndSet(0L)
            previewBitrateMbps = sampledBytes * 8.0 / elapsed / 1_000.0
            previewMetricStartedAt = now

            val frameSerial = presentedFrameSerial.get()
            if (frameSerial != lastSampledFrameSerial) {
                lastSampledFrameSerial = frameSerial
                previewHasFrame = true
                val raster = latestPresentedRaster.get()
                val width = (raster ushr 32).toInt()
                val height = raster.toInt()
                if (previewVideoWidth != width || previewVideoHeight != height) {
                    previewVideoWidth = width
                    previewVideoHeight = height
                    preview.surfaceTexture?.setDefaultBufferSize(width, height)
                    applyPreviewTransform(width, height)
                    renderPreviewQuality()
                }
            } else if (previewHasFrame &&
                now - lastPresentedFrameAt.get() >= PREVIEW_STALE_AFTER_MS
            ) {
                previewHasFrame = false
            }
            previewText.text = if (previewHasFrame) {
                getString(
                    R.string.pocket4p_preview_live,
                    "H.265",
                    previewVideoWidth,
                    previewVideoHeight,
                    previewBitrateMbps,
                )
            } else {
                getString(R.string.pocket4p_preview_waiting)
            }
            preview.postDelayed(this, PREVIEW_METRIC_INTERVAL_MS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) connectNow()
        else showResult(getString(R.string.pocket4p_permission_denied))
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraAddress = intent.getStringExtra(EXTRA_CAMERA_ADDRESS).orEmpty().uppercase()
        cameraName = intent.getStringExtra(EXTRA_CAMERA_NAME).orEmpty().ifBlank { cameraAddress }
        if (!MAC.matches(cameraAddress)) {
            finish()
            return
        }

        val ssid = intent.getStringExtra(EXTRA_WIFI_SSID).orEmpty()
        borrowedNetworkBound = bindBorrowedNetwork()
        controller = Pocket4pRemoteController(
            context = this,
            cameraAddress = cameraAddress,
            cameraName = cameraName,
            ssid = ssid,
            passphrase = intent.getStringExtra(EXTRA_WIFI_PASSPHRASE).orEmpty(),
            wpa3 = intent.getBooleanExtra(EXTRA_WIFI_WPA3, false),
            reuseBoundNetwork = borrowedNetworkBound,
            datalinkPort = intent.getIntExtra(EXTRA_DATALINK_PORT, DEFAULT_DATALINK_PORT),
            tcpPoke = intent.getBooleanExtra(EXTRA_DATALINK_TCP_POKE, true),
            listener = object : Pocket4pRemoteController.Listener {
                override fun onState(state: Pocket4pRemoteState) = handleState(state)
                override fun onLog(message: String) = handleLog(message)
                override fun onAccessUnit(accessUnit: ByteArray) = handleAccessUnit(accessUnit)
                override fun onLiveViewRestartRequested() = decoder.awaitFreshIrap()
            },
        )
        decoder = PocketHevcDecoder(object : PocketHevcDecoder.Listener {
            override fun onFramePresented(width: Int, height: Int) {
                latestPresentedRaster.set(packRaster(width, height))
                lastPresentedFrameAt.set(SystemClock.elapsedRealtime())
                presentedFrameSerial.incrementAndGet()
            }

            override fun onKeyFrameRequired() {
                controller.requestFreshLiveView()
            }

            override fun onDecoderFailure(message: String) {
                runOnUiThread {
                    if (!destroyed) showResult(getString(R.string.pocket4p_decoder_failed, message))
                }
            }
        })

        enableEdgeToEdge()
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        buildContent()
        bindPreview()
        render(latestState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = returnToGallery()
        })

        if (ssid.isBlank() && !borrowedNetworkBound) {
            showResult(getString(R.string.pocket4p_missing_wifi))
        }
    }

    override fun onStart() {
        super.onStart()
        if (!::connectButton.isInitialized) return
        if (!autoConnectAttempted && intent.getBooleanExtra(EXTRA_AUTO_CONNECT, false)) {
            autoConnectAttempted = true
            connectButton.post(::requestConnect)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuildContentForConfiguration()
    }

    override fun onPause() {
        if (::controller.isInitialized) controller.restGimbal()
        super.onPause()
    }

    override fun onStop() {
        if (::controller.isInitialized && !isChangingConfigurations) controller.disconnect()
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        if (::preview.isInitialized) stopPreviewMetricTicker()
        if (::controller.isInitialized) {
            controller.restGimbal()
            controller.close()
        }
        if (borrowedNetworkBound) {
            getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(previousNetwork)
            borrowedNetworkBound = false
        }
        decodeExecutor.shutdownNow()
        if (::decoder.isInitialized) decoder.close()
        previewSurface?.release()
        previewSurface = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun bindBorrowedNetwork(): Boolean {
        val requested = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_NETWORK, Network::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_NETWORK)
        } ?: return false
        val connectivity = getSystemService(ConnectivityManager::class.java)
        previousNetwork = connectivity.boundNetworkForProcess
        return connectivity.bindProcessToNetwork(requested)
    }

    private fun rebuildContentForConfiguration() {
        stopPreviewMetricTicker()
        preview.surfaceTextureListener = null
        decoder.attachSurface(null)
        previewSurface?.release()
        previewSurface = null
        buildContent()
        bindPreview()
        render(latestState)
    }

    private fun buildContent() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = TextureView(this).apply {
            isOpaque = true
        }
        root.addView(preview, FrameLayout.LayoutParams(MATCH, MATCH))

        // The outer containers only provide safe-area spacing. Rounded translucent cards and
        // individual controls float over the feed, so neither edge becomes an opaque black bar.
        val top = FrameLayout(this).apply {
            setPadding(dp(10), dp(if (landscape) 6 else 10), dp(10), 0)
        }
        val topCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(if (landscape) 7 else 10))
            background = glassSurface(radiusDp = 22, strong = true)
            elevation = dp(5).toFloat()
        }
        val titleRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            galleryButton = MaterialButton(this@Pocket4pRemoteActivity).apply {
                text = getString(R.string.pocket4p_gallery)
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(10), 0, dp(10), 0)
                styleGlassButton(cornerDp = 20, compact = true)
                isEnabled = !galleryReturnRequested
                setOnClickListener { returnToGallery() }
            }
            addView(galleryButton, LinearLayout.LayoutParams(WRAP, dp(40)).apply {
                marginEnd = dp(10)
            })
            addView(TextView(this@Pocket4pRemoteActivity).apply {
                text = getString(R.string.pocket4p_title)
                textSize = if (landscape) 17f else 20f
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
            }, LinearLayout.LayoutParams(0, WRAP, if (landscape) 0.8f else 1f))
            if (landscape) {
                phaseText = overlayText(12f).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                }
                previewText = overlayText(11f).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                }
                addView(phaseText, LinearLayout.LayoutParams(0, WRAP, 0.9f))
                addView(previewText, LinearLayout.LayoutParams(0, WRAP, 1.25f))
            }
            connectButton = MaterialButton(this@Pocket4pRemoteActivity).apply {
                textSize = 11f
                minWidth = dp(76)
                styleGlassButton(cornerDp = 18, compact = true)
                setOnClickListener {
                    if (latestState.phase == Pocket4pConnectionPhase.DISCONNECTED ||
                        latestState.phase == Pocket4pConnectionPhase.FAILED
                    ) requestConnect() else controller.disconnect()
                }
            }
            addView(connectButton, LinearLayout.LayoutParams(WRAP, dp(40)).apply {
                marginStart = dp(8)
            })
        }
        topCard.addView(titleRow, LinearLayout.LayoutParams(MATCH, WRAP))
        if (!landscape) {
            topCard.addView(TextView(this).apply {
                text = "$cameraName · $cameraAddress"
                textSize = 11f
                setTextColor(Color.argb(185, 255, 255, 255))
                setPadding(dp(50), dp(2), 0, 0)
                maxLines = 1
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            val liveRow = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(50), dp(5), 0, 0)
            }
            phaseText = overlayText(13f).apply {
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                maxLines = 1
            }
            previewText = overlayText(11f).apply {
                gravity = Gravity.END
                maxLines = 1
                setTextColor(Color.argb(205, 255, 255, 255))
            }
            liveRow.addView(phaseText, LinearLayout.LayoutParams(0, WRAP, 0.8f))
            liveRow.addView(previewText, LinearLayout.LayoutParams(0, WRAP, 1.2f))
            topCard.addView(liveRow, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        top.addView(topCard, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(if (landscape) 6 else 10), dp(10), dp(if (landscape) 8 else 10))
        }
        val controlContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }

        cameraStatusText = overlayText(if (landscape) 12f else 14f).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        gimbalStatusText = overlayText(if (landscape) 11f else 12f).apply {
            setTextColor(Color.argb(210, 255, 255, 255))
        }
        resultText = overlayText(10f).apply {
            setTextColor(Color.argb(185, 255, 255, 255))
            if (landscape) maxLines = 1
        }
        modeButton = actionButton(R.string.pocket4p_mode_video) { showModeMenu() }
        shutterButton = actionButton(R.string.pocket4p_record) { pressShutter() }.apply {
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        previewQualityButton = actionButton(R.string.pocket4p_preview_quality) {
            showPreviewQualityMenu()
        }.apply { textSize = 10f }
        recenterButton = actionButton(R.string.pocket4p_recenter) { submit(controller.recenter()) }
        flipButton = actionButton(R.string.pocket4p_flip) { submit(controller.flip()) }
        zoomSlider = PocketZoomSliderView(this).apply {
            contentDescription = getString(R.string.pocket4p_zoom)
            listener = PocketZoomSliderView.Listener { factor, final ->
                if (!controller.setZoom(factor) && final) {
                    submit(false)
                }
            }
        }
        joystick = PocketJoystickView(this).apply {
            listener = PocketJoystickView.Listener { x, y, held ->
                if (held) controller.updateGimbal(x, y) else controller.restGimbal()
            }
        }

        val statusColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(9), dp(11), dp(9))
            background = glassSurface(radiusDp = 16, strong = true)
            elevation = dp(3).toFloat()
            addView(cameraStatusText)
            addView(gimbalStatusText)
            addView(resultText)
        }
        val actionColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttonRow().apply {
                addView(modeButton, weightedButtonParams())
                addView(shutterButton, weightedButtonParams())
            })
            addView(buttonRow().apply {
                addView(previewQualityButton, weightedButtonParams())
                addView(recenterButton, weightedButtonParams())
                addView(flipButton, weightedButtonParams())
            })
        }

        if (landscape) {
            controlContent.addView(statusColumn, LinearLayout.LayoutParams(0, WRAP, 0.85f).apply {
                marginEnd = dp(8)
                bottomMargin = dp(4)
            })
            controlContent.addView(actionColumn, LinearLayout.LayoutParams(0, WRAP, 1.15f).apply {
                marginEnd = dp(8)
            })
        } else {
            statusColumn.addView(TextView(this).apply {
                text = getString(R.string.pocket4p_unverified_note)
                textSize = 10f
                setTextColor(Color.rgb(255, 210, 105))
                setPadding(0, dp(4), 0, 0)
                maxLines = 2
            })
            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(statusColumn)
                addView(actionColumn, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dp(3)
                })
            }
            controlContent.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginEnd = dp(8)
            })
        }

        controlContent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(this@Pocket4pRemoteActivity).apply {
                text = getString(R.string.pocket4p_zoom)
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(
                zoomSlider,
                LinearLayout.LayoutParams(
                    dp(if (landscape) 58 else 52),
                    dp(if (landscape) 140 else 132),
                ),
            )
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })

        val stickSize = if (landscape) 116 else 108
        controlContent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(this@Pocket4pRemoteActivity).apply {
                text = getString(R.string.pocket4p_gimbal)
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(joystick, LinearLayout.LayoutParams(dp(stickSize), dp(stickSize)))
        })

        bottom.addView(controlContent, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        val topInsetBase = if (landscape) 6 else 10
        val bottomInsetBase = if (landscape) 8 else 10
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            top.updatePadding(
                top = dp(topInsetBase) + bars.top,
                left = dp(10) + bars.left,
                right = dp(10) + bars.right,
            )
            bottom.updatePadding(
                bottom = dp(bottomInsetBase) + bars.bottom,
                left = dp(10) + bars.left,
                right = dp(10) + bars.right,
            )
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun bindPreview() {
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                texture.setDefaultBufferSize(previewVideoWidth, previewVideoHeight)
                previewSurface?.release()
                previewSurface = Surface(texture).also(decoder::attachSurface)
                applyPreviewTransform(previewVideoWidth, previewVideoHeight)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                applyPreviewTransform(previewVideoWidth, previewVideoHeight)
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                decoder.attachSurface(null)
                previewSurface?.release()
                previewSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
    }

    private fun applyPreviewTransform(videoWidth: Int, videoHeight: Int) {
        if (preview.width == 0 || preview.height == 0 || videoWidth <= 0 || videoHeight <= 0) return
        val viewWidth = preview.width.toFloat()
        val viewHeight = preview.height.toFloat()
        val scale = PocketPreviewLayout.aspectFit(preview.width, preview.height, videoWidth, videoHeight)
        preview.setTransform(Matrix().apply {
            setScale(scale.x, scale.y, viewWidth / 2f, viewHeight / 2f)
        })
    }

    private fun requestConnect() {
        val missing = Pocket4pPermissionPolicy.wifiPermissions(Build.VERSION.SDK_INT)
            .filterNot(::permissionGranted)
        if (missing.isEmpty()) connectNow() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun connectNow() {
        if (!controller.connect()) {
            if (intent.getStringExtra(EXTRA_WIFI_SSID).isNullOrBlank()) {
                showResult(getString(R.string.pocket4p_missing_wifi))
            }
        }
    }

    private fun permissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun showModeMenu() {
        val menu = PopupMenu(this, modeButton)
        MODE_MENU.forEachIndexed { index, mode -> menu.menu.add(0, index, index, modeName(mode)) }
        menu.setOnMenuItemClickListener { item ->
            MODE_MENU.getOrNull(item.itemId)?.let { submit(controller.setMode(it)) } ?: false
        }
        menu.show()
    }

    private fun showPreviewQualityMenu() {
        PopupMenu(this, previewQualityButton).apply {
            menu.add(
                getString(
                    R.string.pocket4p_preview_quality_native,
                    previewVideoWidth,
                    previewVideoHeight,
                ),
            ).apply {
                isCheckable = true
                isChecked = true
            }
            setOnMenuItemClickListener {
                showResult(getString(R.string.pocket4p_preview_quality_fixed))
                true
            }
            show()
        }
    }

    private fun pressShutter() {
        when (PocketShutter.action(latestState.cameraStatus)) {
            PocketShutter.Action.PHOTO -> submit(controller.shootPhoto())
            PocketShutter.Action.START_RECORDING -> {
                if (submit(controller.setRecording(true))) guardRecordingCommand(true)
            }
            PocketShutter.Action.STOP_RECORDING -> {
                if (submit(controller.setRecording(false))) guardRecordingCommand(false)
            }
            PocketShutter.Action.WAIT -> submit(false)
        }
    }

    private fun submit(accepted: Boolean): Boolean {
        if (!accepted) showResult(getString(R.string.pocket4p_action_rejected))
        return accepted
    }

    private fun handleState(state: Pocket4pRemoteState) {
        if (destroyed) return
        latestState = state
        render(state)
        if (galleryReturnRequested && (state.phase == Pocket4pConnectionPhase.DISCONNECTED ||
                state.phase == Pocket4pConnectionPhase.FAILED)
        ) {
            scheduleGalleryFinish()
        }
    }

    /**
     * Do not reveal Base while this process still owns the Pocket DUML session. Previously finish()
     * resumed the gallery first and only then did onStop() begin disconnecting; Base consequently
     * negotiated against the camera's still-live remote sequence channel and had to reopen twice.
     */
    private fun returnToGallery() {
        if (galleryReturnRequested) return
        galleryReturnRequested = true
        if (::galleryButton.isInitialized) galleryButton.isEnabled = false
        when (latestState.phase) {
            Pocket4pConnectionPhase.DISCONNECTED, Pocket4pConnectionPhase.FAILED ->
                scheduleGalleryFinish()
            else -> controller.disconnect()
        }
    }

    private fun scheduleGalleryFinish() {
        if (galleryFinishScheduled) return
        galleryFinishScheduled = true
        // Camera session disposal is asynchronous even after the socket closes. The same 600 ms
        // quiescence used by media-session recovery keeps the next handshake on a fresh sequence.
        preview.postDelayed({
            if (!destroyed && galleryReturnRequested) finish()
        }, GALLERY_HANDOFF_SETTLE_MS)
    }

    private fun handleLog(message: String) {
        if (!destroyed) resultText.text = message
    }

    private fun handleAccessUnit(accessUnit: ByteArray) {
        if (destroyed || decodeExecutor.isShutdown) return
        previewBytesSinceSample.addAndGet(accessUnit.size.toLong())
        runCatching {
            decodeExecutor.execute { if (!destroyed) decoder.decode(accessUnit) }
        }
    }

    private fun startPreviewMetricTicker() {
        if (previewMetricTickerRunning) return
        previewMetricTickerRunning = true
        previewBytesSinceSample.set(0L)
        previewMetricStartedAt = SystemClock.elapsedRealtime()
        lastSampledFrameSerial = presentedFrameSerial.get()
        previewBitrateMbps = 0.0
        preview.postDelayed(previewMetricTicker, PREVIEW_METRIC_INTERVAL_MS)
    }

    private fun stopPreviewMetricTicker() {
        previewMetricTickerRunning = false
        preview.removeCallbacks(previewMetricTicker)
        previewBytesSinceSample.set(0L)
        previewMetricStartedAt = 0L
        previewBitrateMbps = 0.0
        lastSampledFrameSerial = presentedFrameSerial.get()
        previewHasFrame = false
    }

    private fun render(state: Pocket4pRemoteState) {
        phaseText.text = getString(when (state.phase) {
            Pocket4pConnectionPhase.DISCONNECTED -> R.string.pocket4p_phase_disconnected
            Pocket4pConnectionPhase.JOINING_WIFI -> R.string.pocket4p_phase_joining
            Pocket4pConnectionPhase.OPENING_DATALINK -> R.string.pocket4p_phase_datalink
            Pocket4pConnectionPhase.READY -> R.string.pocket4p_phase_ready
            Pocket4pConnectionPhase.DISCONNECTING -> R.string.pocket4p_phase_disconnecting
            Pocket4pConnectionPhase.FAILED -> R.string.pocket4p_phase_failed
        })
        if (state.canControl) {
            startPreviewMetricTicker()
        } else {
            pendingRecordingTarget = null
            stopPreviewMetricTicker()
        }
        if (!previewHasFrame) previewText.text = getString(R.string.pocket4p_preview_waiting)
        connectButton.text = when (state.phase) {
            Pocket4pConnectionPhase.DISCONNECTED, Pocket4pConnectionPhase.FAILED ->
                getString(R.string.pocket4p_connect)
            Pocket4pConnectionPhase.JOINING_WIFI, Pocket4pConnectionPhase.OPENING_DATALINK ->
                getString(R.string.pocket4p_connecting)
            else -> getString(R.string.pocket4p_disconnect)
        }
        connectButton.isEnabled = state.phase != Pocket4pConnectionPhase.DISCONNECTING

        val status = state.cameraStatus
        pendingRecordingTarget?.let { target ->
            if (status?.isRecording == target && !status.isRecordingTransitionInProgress) {
                pendingRecordingTarget = null
            }
        }
        cameraStatusText.text = when {
            status?.isRecording == true -> getString(
                R.string.pocket4p_record_status,
                status.elapsedRecordSeconds / 60,
                status.elapsedRecordSeconds % 60,
                status.remainingRecordSeconds,
            )
            status != null -> getString(R.string.pocket4p_ready_status, status.remainingRecordSeconds)
            else -> modeName(null)
        }
        val telemetry = state.gimbalTelemetry
        gimbalStatusText.text = if (telemetry == null) {
            getString(R.string.pocket4p_gimbal_waiting)
        } else {
            getString(
                R.string.pocket4p_gimbal_status,
                telemetry.wrappedYawDegrees,
                telemetry.operatorTiltDegrees ?: telemetry.pitchDegrees,
            )
        }
        modeButton.text = getString(
            R.string.pocket4p_mode,
            modeName(status?.shootingMode, status?.shootingModeRaw),
        )
        val shutterAction = PocketShutter.action(status)
        shutterButton.text = getString(when (shutterAction) {
            PocketShutter.Action.PHOTO -> R.string.pocket4p_photo
            PocketShutter.Action.START_RECORDING -> R.string.pocket4p_record
            PocketShutter.Action.STOP_RECORDING -> R.string.pocket4p_stop
            PocketShutter.Action.WAIT -> R.string.pocket4p_shutter_waiting
        })
        listOf(modeButton, previewQualityButton, recenterButton, flipButton).forEach {
            it.isEnabled = state.canControl
        }
        modeButton.isEnabled = state.canControl && status?.isRecording != true &&
            status?.isRecordingTransitionInProgress != true
        shutterButton.isEnabled = state.canControl && shutterAction != PocketShutter.Action.WAIT &&
            pendingRecordingTarget == null

        val maxZoom = PocketZoomCapabilities.maxFactor(status)
        zoomSlider.maxFactor = maxZoom
        state.zoomFactor?.let { liveFactor ->
            currentZoomFactor = liveFactor.coerceAtMost(maxZoom)
        }
        // Feed camera telemetry into the confirmed baseline. The rocker keeps its local target
        // visible while dragging and briefly after release so delayed reports cannot make it jump.
        zoomSlider.factor = currentZoomFactor.coerceAtMost(maxZoom)
        zoomSlider.enabledForControl = state.canControl && status != null
        joystick.enabledForControl = state.canControl
        renderPreviewQuality()
        state.error?.let(::showResult)
        if (state.actionSerial > lastRenderedActionSerial) {
            lastRenderedActionSerial = state.actionSerial
            state.lastAction?.let { action ->
                showResult(getString(R.string.pocket4p_action_sent, actionLabel(action)))
            }
        }
    }

    private fun modeName(mode: PocketShootingMode?, raw: Int? = null): String = when (mode) {
        PocketShootingMode.PANORAMA -> getString(R.string.pocket4p_mode_panorama)
        PocketShootingMode.VIDEO -> getString(R.string.pocket4p_mode_video)
        PocketShootingMode.PHOTO -> getString(R.string.pocket4p_mode_photo)
        PocketShootingMode.SLOW_MOTION -> getString(R.string.pocket4p_mode_slow_motion)
        PocketShootingMode.STATIC_TIMELAPSE -> getString(R.string.pocket4p_mode_static_timelapse)
        PocketShootingMode.LOW_LIGHT_VIDEO -> getString(R.string.pocket4p_mode_low_light_video)
        null -> raw?.let { getString(R.string.pocket4p_mode_unknown, it) }
            ?: getString(R.string.pocket4p_mode_waiting)
    }

    private fun actionLabel(action: Pocket4pAction): String = when (action) {
        Pocket4pAction.SHOOT_PHOTO -> getString(R.string.pocket4p_photo)
        Pocket4pAction.START_RECORDING -> getString(R.string.pocket4p_record)
        Pocket4pAction.STOP_RECORDING -> getString(R.string.pocket4p_stop)
        Pocket4pAction.SET_MODE -> modeButton.text.toString()
        Pocket4pAction.SET_ZOOM -> getString(R.string.pocket4p_zoom)
        Pocket4pAction.RECENTER_GIMBAL -> getString(R.string.pocket4p_recenter)
        Pocket4pAction.FLIP_GIMBAL -> getString(R.string.pocket4p_flip)
    }

    private fun showResult(message: String) {
        resultText.text = message
    }

    private fun renderPreviewQuality() {
        previewQualityButton.text = getString(
            R.string.pocket4p_preview_quality_value,
            previewVideoHeight,
        )
    }

    private fun guardRecordingCommand(target: Boolean) {
        pendingRecordingTarget = target
        render(latestState)
        shutterButton.postDelayed({
            if (!destroyed && pendingRecordingTarget == target) {
                pendingRecordingTarget = null
                render(latestState)
                showResult(getString(R.string.pocket4p_record_unconfirmed))
            }
        }, RECORD_COMMAND_GUARD_MS)
    }

    private fun actionButton(label: Int, action: () -> Unit) = MaterialButton(this).apply {
        text = getString(label)
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        styleGlassButton(cornerDp = 15)
        setOnClickListener { action() }
    }

    private fun MaterialButton.styleGlassButton(cornerDp: Int, compact: Boolean = false) {
        isAllCaps = false
        insetTop = 0
        insetBottom = 0
        minimumHeight = 0
        cornerRadius = dp(cornerDp)
        strokeWidth = dp(1)
        backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(
                Color.argb(48, 90, 100, 112),
                Color.argb(92, 40, 49, 60),
            ),
        )
        strokeColor = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(
                Color.argb(35, 255, 255, 255),
                Color.argb(105, 255, 255, 255),
            ),
        )
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(
                Color.argb(90, 255, 255, 255),
                Color.WHITE,
            ),
        ))
        rippleColor = ColorStateList.valueOf(Color.argb(85, 255, 255, 255))
        elevation = dp(2).toFloat()
        if (!compact) setPadding(dp(8), 0, dp(8), 0)
    }

    private fun glassSurface(radiusDp: Int, strong: Boolean = false) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(
            Color.argb(if (strong) 145 else 100, 12, 17, 24),
            Color.argb(if (strong) 82 else 58, 115, 130, 146),
        ),
    ).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), Color.argb(if (strong) 100 else 82, 255, 255, 255))
    }

    private fun buttonRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun weightedButtonParams() = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        marginEnd = dp(6)
        topMargin = dp(5)
    }

    private fun overlayText(size: Float) = TextView(this).apply {
        textSize = size
        setTextColor(Color.WHITE)
        maxLines = 2
        setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.argb(210, 0, 0, 0))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_CAMERA_ADDRESS = "pocket4p.camera_address"
        const val EXTRA_CAMERA_NAME = "pocket4p.camera_name"
        const val EXTRA_AUTO_CONNECT = "pocket4p.auto_connect"
        const val EXTRA_WIFI_SSID = "pocket4p.wifi_ssid"
        const val EXTRA_WIFI_PASSPHRASE = "pocket4p.wifi_passphrase"
        const val EXTRA_WIFI_WPA3 = "pocket4p.wifi_wpa3"
        const val EXTRA_NETWORK = "pocket4p.network"
        const val EXTRA_DATALINK_PORT = "pocket4p.datalink_port"
        const val EXTRA_DATALINK_TCP_POKE = "pocket4p.datalink_tcp_poke"

        private const val DEFAULT_DATALINK_PORT = 9004
        private const val DEFAULT_PREVIEW_WIDTH = 1280
        private const val DEFAULT_PREVIEW_HEIGHT = 720
        private const val PREVIEW_METRIC_INTERVAL_MS = 1_000L
        private const val PREVIEW_STALE_AFTER_MS = 2_000L
        private const val RECORD_COMMAND_GUARD_MS = 3_000L
        private const val GALLERY_HANDOFF_SETTLE_MS = 650L
        private const val MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        private fun packRaster(width: Int, height: Int): Long =
            (width.toLong() shl 32) or (height.toLong() and 0xFFFF_FFFFL)

        private val MAC = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")
        private val MODE_MENU = listOf(
            PocketShootingMode.PANORAMA,
            PocketShootingMode.PHOTO,
            PocketShootingMode.VIDEO,
            PocketShootingMode.LOW_LIGHT_VIDEO,
            PocketShootingMode.SLOW_MOTION,
            PocketShootingMode.STATIC_TIMELAPSE,
        )
    }
}
