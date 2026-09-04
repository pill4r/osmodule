package dev.konraditurbe.osmosis.rsdk

import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import dev.konraditurbe.osmosis.feature.control.rsdk.R
import dev.konraditurbe.osmosis.modules.CameraRemoteCommand
import dev.konraditurbe.osmosis.modules.CameraRemoteCommandOutcome
import dev.konraditurbe.osmosis.modules.CameraRemoteCommandResult
import dev.konraditurbe.osmosis.modules.CameraRemoteControl
import dev.konraditurbe.osmosis.modules.CameraRemoteMode
import dev.konraditurbe.osmosis.modules.CameraRemotePhase
import dev.konraditurbe.osmosis.modules.CameraRemoteState
import dev.konraditurbe.osmosis.modules.CameraRemoteStatus
import dev.konraditurbe.osmosis.modules.CameraExclusiveController
import dev.konraditurbe.osmosis.modules.CameraExclusiveState
import dev.konraditurbe.osmosis.modules.ModuleRegistry
import dev.konraditurbe.osmosis.panorama.render.PanoramaSurfaceView
import dev.konraditurbe.osmosis.panorama.render.PanoramaCalibrationCodec

/** Plugin-owned UI for the optional R-SDK control capability. */
class RsdkRemoteActivity : AppCompatActivity(), CameraRemoteControl.Listener {
    private lateinit var controller: CameraRemoteControl
    private var gpsController: CameraExclusiveController? = null
    private lateinit var cameraAddress: String
    private lateinit var cameraName: String

    private lateinit var connectionState: TextView
    private lateinit var connectionBadge: TextView
    private lateinit var approvalHint: TextView
    private lateinit var statusPrimary: TextView
    private lateinit var statusSecondary: TextView
    private lateinit var versionText: TextView
    private lateinit var commandResult: TextView
    private lateinit var connectButton: MaterialButton
    private lateinit var wakeButton: MaterialButton
    private lateinit var shutterButton: MaterialButton
    private lateinit var snapshotButton: MaterialButton
    private lateinit var quickSwitchButton: MaterialButton
    private lateinit var openModePickerButton: MaterialButton
    private lateinit var queryVersionButton: MaterialButton
    private lateinit var sleepButton: MaterialButton
    private lateinit var restartButton: MaterialButton
    private lateinit var gpsButton: MaterialButton
    private lateinit var gpsLabel: TextView
    private lateinit var modeLabel: TextView
    private lateinit var currentModeText: TextView
    private lateinit var shutterLabel: TextView
    private lateinit var controlSheet: View
    private lateinit var previewSurface: PanoramaSurfaceView
    private lateinit var previewEmptyState: View
    private lateinit var previewIcon: ImageView
    private lateinit var previewProgress: CircularProgressIndicator
    private lateinit var previewState: TextView
    private lateinit var previewDetail: TextView
    private lateinit var previewMeta: TextView
    private lateinit var previewLiveBadge: TextView
    private lateinit var previewRecordBadge: TextView
    private lateinit var previewBattery: TextView
    private lateinit var previewAction: MaterialButton
    private lateinit var previewLiveAction: MaterialButton
    private lateinit var previewRecenter: MaterialButton
    private lateinit var livePreview: RsdkLivePreviewController
    private var previewOutputSurface: Surface? = null
    private var activityStarted = false
    private var previewUserEnabled = true

    private val main = Handler(Looper.getMainLooper())
    private val permissionQueue = ArrayDeque<PendingPermissionRequest>()
    private var activePermissionRequest: PendingPermissionRequest? = null
    private var versionRequested = false
    private var requestedMode: CameraRemoteMode? = null
    private var lastPhase = CameraRemotePhase.DISCONNECTED
    private val clearRequestedMode = Runnable {
        requestedMode = null
        render(controller.state)
    }
    private val gpsListener = CameraExclusiveController.Listener { state ->
        main.post { if (!isFinishing && !isDestroyed) renderGps(state) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val request = activePermissionRequest
        activePermissionRequest = null
        if (request != null && request.permissions.all(::isPermissionGranted)) {
            request.action()
        } else {
            request?.onDenied?.invoke()
            showResult(getString(R.string.rsdk_permission_denied))
        }
        drainPermissionQueue()
    }

    private val modePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        RsdkModePickerActivity.selectedMode(result.data)?.let(::requestModeSwitch)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraAddress = intent.getStringExtra(EXTRA_CAMERA_ADDRESS).orEmpty()
        cameraName = intent.getStringExtra(EXTRA_CAMERA_NAME).orEmpty().ifBlank { cameraAddress }
        if (!MAC.matches(cameraAddress)) {
            finish()
            return
        }
        val capability = ModuleRegistry.capability(CameraRemoteControl::class.java)
        if (capability == null) {
            android.widget.Toast.makeText(this, R.string.rsdk_module_missing, android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        controller = capability
        gpsController = ModuleRegistry.capability(CameraExclusiveController::class.java)

        enableEdgeToEdge()
        @Suppress("DEPRECATION")
        fun colorSystemBars() {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        colorSystemBars()
        setContentView(R.layout.activity_rsdk_remote)
        bindViews()
        applyFullscreenInsets()
        enterImmersive()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (controlSheet.visibility == View.VISIBLE) hideControls() else finish()
            }
        })
        livePreview = RsdkLivePreviewController(
            context = this,
            cameraAddress = cameraAddress,
            ssid = intent.getStringExtra(EXTRA_WIFI_SSID),
            passphrase = intent.getStringExtra(EXTRA_WIFI_PASSPHRASE),
            wpa3 = intent.getBooleanExtra(EXTRA_WIFI_WPA3, false),
            datalinkPort = intent.getIntExtra(EXTRA_DATALINK_PORT, DEFAULT_DATALINK_PORT),
            tcpPoke = intent.getBooleanExtra(EXTRA_DATALINK_TCP_POKE, true),
            calibrationStreams = intent.getStringArrayListExtra(
                EXTRA_PANORAMA_CALIBRATION_STREAMS,
            ).orEmpty(),
            initialCalibration = PanoramaCalibrationCodec.decode(
                intent.getFloatArrayExtra(EXTRA_PANORAMA_CALIBRATION_DATA),
            ),
            onCalibration = previewSurface::setCalibration,
        ) { preview ->
            main.post { if (!isFinishing && !isDestroyed) renderPreview(preview) }
        }
        bindPreview()
        bindActions()
        render(controller.state)
        renderPreview(livePreview.state)

        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_AUTO_CONNECT, false)) {
            main.post { requestConnect() }
        }
    }

    private fun bindViews() {
        findViewById<MaterialToolbar>(R.id.rsdkToolbar).setNavigationOnClickListener { finish() }
        findViewById<TextView>(R.id.rsdkCameraIdentity).text = cameraName
        connectionState = findViewById(R.id.rsdkConnectionState)
        connectionBadge = findViewById(R.id.rsdkConnectionBadge)
        approvalHint = findViewById(R.id.rsdkApprovalHint)
        statusPrimary = findViewById(R.id.rsdkStatusPrimary)
        statusSecondary = findViewById(R.id.rsdkStatusSecondary)
        versionText = findViewById(R.id.rsdkVersion)
        commandResult = findViewById(R.id.rsdkCommandResult)
        connectButton = findViewById(R.id.rsdkConnect)
        wakeButton = findViewById(R.id.rsdkWake)
        shutterButton = findViewById(R.id.rsdkShutter)
        snapshotButton = findViewById(R.id.rsdkSnapshot)
        quickSwitchButton = findViewById(R.id.rsdkQuickSwitch)
        openModePickerButton = findViewById(R.id.rsdkOpenModePicker)
        queryVersionButton = findViewById(R.id.rsdkQueryVersion)
        sleepButton = findViewById(R.id.rsdkSleep)
        restartButton = findViewById(R.id.rsdkRestart)
        gpsButton = findViewById(R.id.rsdkGps)
        gpsLabel = findViewById(R.id.rsdkGpsLabel)
        modeLabel = findViewById(R.id.rsdkModeLabel)
        currentModeText = findViewById(R.id.rsdkCurrentMode)
        shutterLabel = findViewById(R.id.rsdkShutterLabel)
        controlSheet = findViewById(R.id.rsdkControlSheet)
        previewSurface = findViewById(R.id.rsdkPreviewSurface)
        previewEmptyState = findViewById(R.id.rsdkPreviewEmptyState)
        previewIcon = findViewById(R.id.rsdkPreviewIcon)
        previewProgress = findViewById(R.id.rsdkPreviewProgress)
        previewState = findViewById(R.id.rsdkPreviewState)
        previewDetail = findViewById(R.id.rsdkPreviewDetail)
        previewMeta = findViewById(R.id.rsdkPreviewMeta)
        previewLiveBadge = findViewById(R.id.rsdkPreviewLiveBadge)
        previewRecordBadge = findViewById(R.id.rsdkPreviewRecordBadge)
        previewBattery = findViewById(R.id.rsdkPreviewBattery)
        previewAction = findViewById(R.id.rsdkPreviewAction)
        previewLiveAction = findViewById(R.id.rsdkPreviewLiveAction)
        previewRecenter = findViewById(R.id.rsdkPreviewRecenter)
        previewSurface.setRollDegrees(PREVIEW_ROLL_DEGREES)
        val gpsVisibility = if (gpsController == null) View.GONE else View.VISIBLE
        gpsButton.visibility = gpsVisibility
        gpsLabel.visibility = gpsVisibility
        gpsController?.state?.let(::renderGps)

    }

    private fun bindPreview() {
        previewSurface.onVideoSurface = { texture: SurfaceTexture ->
            main.post {
                if (isFinishing || isDestroyed) return@post
                livePreview.attachSurface(null)
                previewOutputSurface?.release()
                previewOutputSurface = Surface(texture).also(livePreview::attachSurface)
                requestPreviewStart()
            }
        }
    }

    private fun bindActions() {
        findViewById<View>(R.id.rsdkMoreControls).setOnClickListener { showControls() }
        findViewById<View>(R.id.rsdkCloseControls).setOnClickListener { hideControls() }
        connectButton.setOnClickListener {
            if (controller.state.phase == CameraRemotePhase.DISCONNECTED) requestConnect()
            else controller.disconnect()
        }
        wakeButton.setOnClickListener {
            withPermissions(controller.wakePermissions(Build.VERSION.SDK_INT)) {
                if (!controller.wake(this, cameraAddress)) showResult(getString(R.string.rsdk_command_not_accepted))
            }
        }
        shutterButton.setOnClickListener { submit(controller.capture()) }
        snapshotButton.setOnClickListener { submit(controller.snapshot()) }
        quickSwitchButton.setOnClickListener { openModePicker() }
        openModePickerButton.setOnClickListener { openModePicker() }
        queryVersionButton.setOnClickListener { submit(controller.queryVersion()) }
        sleepButton.setOnClickListener { submit(controller.sleep()) }
        restartButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.rsdk_restart_title)
                .setMessage(R.string.rsdk_restart_message)
                .setPositiveButton(R.string.rsdk_restart) { _, _ -> submit(controller.restart()) }
                .setNegativeButton(R.string.rsdk_cancel, null)
                .show()
        }
        gpsButton.setOnClickListener {
            val gps = gpsController ?: return@setOnClickListener
            if (gps.state.locked) {
                gps.stop(this)
            } else {
                withPermissions(gps.requiredPermissions(Build.VERSION.SDK_INT)) {
                    gps.start(this, cameraAddress, cameraName)
                }
            }
        }
        previewAction.setOnClickListener {
            if (livePreview.state.active) {
                previewUserEnabled = false
                livePreview.stop()
            } else {
                previewUserEnabled = true
                requestPreviewStart()
            }
        }
        previewLiveAction.setOnClickListener {
            previewUserEnabled = false
            livePreview.stop()
        }
        previewRecenter.setOnClickListener { previewSurface.recenter() }
    }

    private fun openModePicker() {
        modePickerLauncher.launch(
            RsdkModePickerActivity.intent(this, RsdkModeCatalog.currentMode(controller.state)),
        )
    }

    private fun requestModeSwitch(mode: CameraRemoteMode) {
        hideControls()
        if (RsdkModeCatalog.currentMode(controller.state) == mode) {
            requestedMode = null
            render(controller.state)
            return
        }
        requestedMode = mode
        main.removeCallbacks(clearRequestedMode)
        val accepted = controller.switchMode(mode)
        if (accepted) {
            showResult(getString(R.string.rsdk_switching_to_mode, modeDisplayName(mode)))
            main.postDelayed(clearRequestedMode, MODE_SWITCH_LABEL_TIMEOUT_MS)
            render(controller.state)
        } else {
            requestedMode = null
            submit(false)
        }
    }

    private fun applyFullscreenInsets() {
        val root = findViewById<View>(R.id.rsdkRemoteRoot)
        val top = findViewById<View>(R.id.rsdkTopOverlay)
        val bottom = findViewById<View>(R.id.rsdkBottomOverlay)
        val topPadding = intArrayOf(top.paddingLeft, top.paddingTop, top.paddingRight, top.paddingBottom)
        val bottomPadding = intArrayOf(
            bottom.paddingLeft,
            bottom.paddingTop,
            bottom.paddingRight,
            bottom.paddingBottom,
        )
        val sheetPadding = intArrayOf(
            controlSheet.paddingLeft,
            controlSheet.paddingTop,
            controlSheet.paddingRight,
            controlSheet.paddingBottom,
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            top.updatePadding(
                left = topPadding[0] + safe.left,
                top = topPadding[1] + safe.top,
                right = topPadding[2] + safe.right,
                bottom = topPadding[3],
            )
            bottom.updatePadding(
                left = bottomPadding[0] + safe.left,
                top = bottomPadding[1],
                right = bottomPadding[2] + safe.right,
                bottom = bottomPadding[3] + safe.bottom,
            )
            controlSheet.updatePadding(
                left = sheetPadding[0] + safe.left,
                top = sheetPadding[1],
                right = sheetPadding[2] + safe.right,
                bottom = sheetPadding[3] + safe.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun enterImmersive() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showControls() {
        if (controlSheet.visibility == View.VISIBLE) return
        controlSheet.animate().cancel()
        controlSheet.alpha = 0f
        controlSheet.translationY = 64f
        controlSheet.visibility = View.VISIBLE
        controlSheet.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(CONTROL_SHEET_ANIMATION_MS)
            .start()
        findViewById<View>(R.id.rsdkCloseControls).requestFocus()
    }

    private fun hideControls() {
        controlSheet.animate().cancel()
        controlSheet.animate()
            .alpha(0f)
            .translationY(64f)
            .setDuration(CONTROL_SHEET_ANIMATION_MS)
            .withEndAction {
                controlSheet.visibility = View.GONE
                controlSheet.alpha = 1f
                controlSheet.translationY = 0f
            }
            .start()
    }

    private fun requestPreviewStart() {
        if (!activityStarted || !previewUserEnabled || previewOutputSurface == null) return
        if (!livePreview.canStart) {
            livePreview.start()
            return
        }
        withPermissions(
            permissions = RsdkPermissionPolicy.livePreviewPermissions(Build.VERSION.SDK_INT),
            action = { if (activityStarted && previewUserEnabled) livePreview.start() },
            onDenied = livePreview::permissionDenied,
        )
    }

    private fun requestConnect() {
        withPermissions(controller.connectionPermissions(Build.VERSION.SDK_INT)) {
            versionRequested = false
            requestedMode = null
            main.removeCallbacks(clearRequestedMode)
            if (!controller.connect(this, cameraAddress, cameraName)) {
                controller.state.lastError?.let(::showResult)
                    ?: showResult(getString(R.string.rsdk_command_not_accepted))
            }
        }
    }

    private fun withPermissions(
        permissions: Set<String>,
        onDenied: () -> Unit = {},
        action: () -> Unit,
    ) {
        permissionQueue.addLast(PendingPermissionRequest(permissions, action, onDenied))
        drainPermissionQueue()
    }

    private fun drainPermissionQueue() {
        if (activePermissionRequest != null) return
        while (permissionQueue.isNotEmpty()) {
            val request = permissionQueue.removeFirst()
            val missing = RsdkPermissionPolicy.pendingRequest(
                Build.VERSION.SDK_INT,
                request.permissions,
                ::isPermissionGranted,
            )
            if (missing.isEmpty()) {
                request.action()
                continue
            }
            activePermissionRequest = request
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
    }

    private data class PendingPermissionRequest(
        val permissions: Set<String>,
        val action: () -> Unit,
        val onDenied: () -> Unit,
    )

    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun submit(accepted: Boolean) {
        if (!accepted) showResult(getString(R.string.rsdk_command_not_accepted))
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        previewSurface.onResume()
        if (::controller.isInitialized) controller.addListener(this)
        gpsController?.addListener(gpsListener)
        requestPreviewStart()
    }

    override fun onStop() {
        activityStarted = false
        requestedMode = null
        main.removeCallbacks(clearRequestedMode)
        if (::livePreview.isInitialized) livePreview.stop()
        if (::previewSurface.isInitialized) previewSurface.onPause()
        if (::controller.isInitialized) controller.removeListener(this)
        gpsController?.removeListener(gpsListener)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::livePreview.isInitialized) livePreview.close()
        if (::previewSurface.isInitialized) previewSurface.onVideoSurface = null
        previewOutputSurface?.release()
        previewOutputSurface = null
        if (isFinishing && ::controller.isInitialized) controller.disconnect()
        super.onDestroy()
    }

    override fun onStateChanged(state: CameraRemoteState) {
        main.post {
            if (isFinishing || isDestroyed) return@post
            if (requestedMode == RsdkModeCatalog.currentMode(state)) {
                requestedMode = null
                main.removeCallbacks(clearRequestedMode)
            }
            render(state)
        }
    }

    override fun onCommandResult(result: CameraRemoteCommandResult) {
        main.post {
            if (isFinishing || isDestroyed) return@post
            val outcome = when (result.outcome) {
                CameraRemoteCommandOutcome.SUCCEEDED -> getString(R.string.rsdk_result_succeeded)
                CameraRemoteCommandOutcome.REJECTED -> getString(R.string.rsdk_result_rejected)
                CameraRemoteCommandOutcome.TIMED_OUT -> getString(R.string.rsdk_result_timed_out)
                CameraRemoteCommandOutcome.TRANSPORT_FAILED -> getString(R.string.rsdk_result_transport_failed)
            }
            val wireDetail = buildList {
                result.returnCode?.let { add("code 0x%04X".format(it)) }
                result.sequence?.let { add("seq $it") }
                result.detail?.takeIf { it.isNotBlank() }?.let(::add)
            }.joinToString(" · ")
            val detail = if (wireDetail.isBlank()) "" else getString(R.string.rsdk_result_detail, wireDetail)
            val commandName = if (result.command == CameraRemoteCommand.CAPTURE) {
                getString(R.string.rsdk_shutter)
            } else {
                humanize(result.command.name)
            }
            showResult(getString(R.string.rsdk_result, commandName, outcome, detail))

            if (result.command == CameraRemoteCommand.SWITCH_MODE) {
                if (result.outcome != CameraRemoteCommandOutcome.SUCCEEDED) {
                    requestedMode = null
                    main.removeCallbacks(clearRequestedMode)
                    render(controller.state)
                }
            }

            if (result.command == CameraRemoteCommand.WAKE &&
                result.outcome == CameraRemoteCommandOutcome.SUCCEEDED
            ) {
                main.postDelayed({
                    if (controller.state.phase == CameraRemotePhase.DISCONNECTED) requestConnect()
                }, WAKE_SETTLE_MS)
            }
        }
    }

    private fun render(state: CameraRemoteState) {
        val panel = RsdkRemotePanelStateMapper.from(state)
        if (requestedMode == RsdkModeCatalog.currentMode(state)) {
            requestedMode = null
            main.removeCallbacks(clearRequestedMode)
        }
        val phaseText = when (state.phase) {
            CameraRemotePhase.DISCONNECTED -> getString(R.string.rsdk_state_disconnected)
            CameraRemotePhase.CONNECTING -> getString(R.string.rsdk_state_connecting)
            CameraRemotePhase.CONNECTED -> getString(R.string.rsdk_state_connected)
        }
        connectionState.text = getString(R.string.rsdk_connection_state, phaseText, cameraAddress)
        connectionBadge.text = phaseText
        connectionBadge.setTextColor(ContextCompat.getColor(this, when (state.phase) {
            CameraRemotePhase.CONNECTED -> R.color.rsdk_success
            CameraRemotePhase.CONNECTING -> R.color.rsdk_warning
            CameraRemotePhase.DISCONNECTED -> R.color.rsdk_text_secondary
        }))
        approvalHint.visibility = if (panel.connecting) View.VISIBLE else View.GONE
        connectButton.text = getString(if (panel.canDisconnect) R.string.rsdk_disconnect else R.string.rsdk_connect)
        connectButton.isEnabled = panel.canConnect || panel.canDisconnect
        wakeButton.isEnabled = panel.canWake
        val commandsEnabled = panel.commandsEnabled
        listOf(
            shutterButton, snapshotButton, quickSwitchButton, openModePickerButton,
            queryVersionButton, sleepButton, restartButton,
        ).forEach { it.isEnabled = commandsEnabled }
        renderShutter(state, panel.recording)

        renderStatus(state.status, state.modeLabel)
        renderModeState(state)
        renderVersion(state)
        state.lastError?.takeIf { it.isNotBlank() }?.let(::showResult)

        if (state.phase == CameraRemotePhase.CONNECTED && lastPhase != CameraRemotePhase.CONNECTED && !versionRequested) {
            versionRequested = controller.queryVersion()
        }
        if (state.phase == CameraRemotePhase.DISCONNECTED) versionRequested = false
        lastPhase = state.phase
    }

    private fun renderShutter(state: CameraRemoteState, recording: Boolean) {
        val currentMode = RsdkModeCatalog.currentMode(state)
        val photoMode = RsdkModeCatalog.isPhoto(currentMode)
        val label = getString(when {
            recording -> R.string.rsdk_record_stop
            currentMode == null -> R.string.rsdk_shutter
            photoMode -> R.string.rsdk_capture
            else -> R.string.rsdk_record_start
        })
        shutterButton.text = ""
        shutterButton.contentDescription = label
        shutterButton.icon = ContextCompat.getDrawable(
            this,
            when {
                recording -> R.drawable.ic_rsdk_stop
                photoMode -> R.drawable.ic_rsdk_capture
                else -> R.drawable.ic_rsdk_record
            },
        )
        shutterLabel.text = label
        shutterButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(
            this,
            when {
                recording -> R.color.rsdk_record_dark
                photoMode -> R.color.rsdk_accent_dark
                else -> R.color.rsdk_record
            },
        ))
    }

    private fun renderGps(state: CameraExclusiveState) {
        val label = getString(
            if (state.locked) R.string.rsdk_gps_short_active else R.string.rsdk_gps_short,
        )
        gpsButton.text = ""
        gpsButton.contentDescription = getString(
            if (state.locked) R.string.rsdk_gps_stop else R.string.rsdk_gps_start,
        )
        gpsLabel.text = label
        gpsButton.isChecked = state.locked
        gpsButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(
            this,
            if (state.locked) R.color.rsdk_accent_dark else R.color.rsdk_panel,
        ))
        gpsButton.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(
            this,
            if (state.locked) R.color.rsdk_accent else R.color.rsdk_outline,
        ))
        gpsLabel.setTextColor(ContextCompat.getColor(
            this,
            if (state.locked) R.color.rsdk_accent else R.color.rsdk_text_primary,
        ))
    }

    private fun renderPreview(state: RsdkPreviewState) {
        val waiting = state.phase in setOf(
            RsdkPreviewPhase.JOINING,
            RsdkPreviewPhase.CONNECTING,
            RsdkPreviewPhase.BUFFERING,
        )
        val playing = state.phase == RsdkPreviewPhase.PLAYING
        previewEmptyState.visibility = if (playing) View.GONE else View.VISIBLE
        previewProgress.visibility = if (waiting) View.VISIBLE else View.GONE
        previewIcon.visibility = if (waiting) View.GONE else View.VISIBLE
        previewLiveBadge.visibility = if (playing) View.VISIBLE else View.GONE
        previewLiveAction.visibility = if (playing) View.VISIBLE else View.GONE
        previewRecenter.visibility = if (playing) View.VISIBLE else View.GONE
        previewState.text = state.message
        previewDetail.text = state.detail
        previewDetail.visibility = if (state.detail.isBlank()) View.GONE else View.VISIBLE
        previewMeta.text = if (playing) state.detail else ""
        previewAction.text = getString(when {
            state.active -> R.string.rsdk_preview_stop
            state.phase == RsdkPreviewPhase.FAILED -> R.string.rsdk_preview_retry
            else -> R.string.rsdk_preview_start
        })
        previewAction.isEnabled = state.phase != RsdkPreviewPhase.UNAVAILABLE
    }

    private fun renderStatus(status: CameraRemoteStatus?, fallbackMode: String?) {
        if (status == null) {
            statusPrimary.text = fallbackMode ?: getString(R.string.rsdk_status_waiting)
            statusSecondary.text = getString(R.string.rsdk_status_waiting)
            previewBattery.visibility = View.GONE
            previewRecordBadge.visibility = View.GONE
            return
        }
        val capture = when {
            status.recording -> getString(R.string.rsdk_capture_recording)
            status.preRecording -> getString(R.string.rsdk_capture_prerecording)
            status.activeCapture -> getString(R.string.rsdk_capture_active)
            else -> getString(R.string.rsdk_capture_idle)
        }
        val localizedMode = status.mode?.let(::modeDisplayName) ?: status.modeLabel
        statusPrimary.text = getString(R.string.rsdk_status_primary, localizedMode, capture)
        previewBattery.text = status.batteryPercent?.let { "$it%" } ?: unknown()
        previewBattery.visibility = View.VISIBLE
        previewRecordBadge.visibility = if (status.recording) View.VISIBLE else View.GONE
        statusSecondary.text = buildList {
            add(getString(R.string.rsdk_battery, status.batteryPercent?.let { "$it%" } ?: unknown()))
            add(getString(R.string.rsdk_format_codes, status.resolutionCode, status.fpsCode))
            status.eisCode?.let { add(getString(R.string.rsdk_eis_code, it)) }
            add(getString(R.string.rsdk_record_time, duration(status.recordTimeSeconds.toLong())))
            add(getString(
                R.string.rsdk_remaining,
                status.remainingCapacityMb?.toString() ?: unknown(),
                status.remainingPhotos?.toString() ?: unknown(),
                status.remainingRecordSeconds?.let(::duration) ?: unknown(),
            ))
        }.joinToString("\n")
    }

    private fun renderVersion(state: CameraRemoteState) {
        val version = state.version
        if (version == null) {
            versionText.visibility = View.GONE
            return
        }
        versionText.text = getString(
            R.string.rsdk_version_line,
            version.deviceName ?: version.productId.ifBlank { cameraName },
            version.firmwareVersion ?: unknown(),
            version.sdkVersion ?: unknown(),
        )
        versionText.visibility = View.VISIBLE
    }

    private fun showResult(text: String) {
        commandResult.text = text
        commandResult.visibility = View.VISIBLE
    }

    private fun renderModeState(state: CameraRemoteState) {
        val reportedMode = RsdkModeCatalog.currentMode(state)?.let(::modeDisplayName)
            ?: state.status?.modeLabel?.takeIf(String::isNotBlank)
            ?: state.modeLabel?.takeIf(String::isNotBlank)
        val current = reportedMode ?: unknown()
        val target = requestedMode
        modeLabel.text = target?.let(::modeDisplayName)
            ?: reportedMode
            ?: getString(R.string.rsdk_mode_short)
        currentModeText.text = if (target == null) {
            getString(R.string.rsdk_current_mode, current)
        } else {
            getString(R.string.rsdk_switching_to_mode, modeDisplayName(target))
        }
        quickSwitchButton.contentDescription = getString(R.string.rsdk_mode_open_picker, current)
    }

    private fun modeDisplayName(mode: CameraRemoteMode): String =
        getString(RsdkModeCatalog.labelRes(mode))

    private fun unknown() = getString(R.string.rsdk_unknown)

    private fun duration(totalSeconds: Long): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        return "%02d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
    }

    private fun humanize(name: String): String = name.lowercase()
        .split('_')
        .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }

    companion object {
        const val EXTRA_CAMERA_ADDRESS = "camera_address"
        const val EXTRA_CAMERA_NAME = "camera_name"
        const val EXTRA_AUTO_CONNECT = "auto_connect"
        const val EXTRA_WIFI_SSID = "wifi_ssid"
        const val EXTRA_WIFI_PASSPHRASE = "wifi_passphrase"
        const val EXTRA_WIFI_WPA3 = "wifi_wpa3"
        const val EXTRA_DATALINK_PORT = "datalink_port"
        const val EXTRA_DATALINK_TCP_POKE = "datalink_tcp_poke"
        const val EXTRA_PANORAMA_CALIBRATION_STREAMS = "panorama_calibration_streams"
        const val EXTRA_PANORAMA_CALIBRATION_DATA = "panorama_calibration_data"
        private const val DEFAULT_DATALINK_PORT = 9004
        private const val WAKE_SETTLE_MS = 2_300L
        private const val CONTROL_SHEET_ANIMATION_MS = 220L
        private const val MODE_SWITCH_LABEL_TIMEOUT_MS = 8_000L
        private const val PREVIEW_ROLL_DEGREES = 90f
        private val MAC = Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    }
}
