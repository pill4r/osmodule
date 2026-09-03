package dev.konraditurbe.osmosis.rsdk

import android.annotation.SuppressLint
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
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
    private lateinit var modeSpinner: Spinner
    private lateinit var connectButton: MaterialButton
    private lateinit var wakeButton: MaterialButton
    private lateinit var captureButton: MaterialButton
    private lateinit var recordButton: MaterialButton
    private lateinit var snapshotButton: MaterialButton
    private lateinit var quickSwitchButton: MaterialButton
    private lateinit var applyModeButton: MaterialButton
    private lateinit var queryVersionButton: MaterialButton
    private lateinit var sleepButton: MaterialButton
    private lateinit var restartButton: MaterialButton
    private lateinit var gpsButton: MaterialButton
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
    private var modeInitialized = false
    private var modeSelectionDirty = false
    private var lastRenderedMode: CameraRemoteMode? = null
    private var lastPhase = CameraRemotePhase.DISCONNECTED
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
            window.navigationBarColor = ContextCompat.getColor(this, R.color.rsdk_console_background)
        }
        colorSystemBars()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContentView(R.layout.activity_rsdk_remote)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rsdkRemoteRoot)) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        bindViews()
        livePreview = RsdkLivePreviewController(
            context = this,
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

    @SuppressLint("ClickableViewAccessibility") // Spinner handles performClick; this passive listener only marks user intent.
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
        modeSpinner = findViewById(R.id.rsdkModeSpinner)
        connectButton = findViewById(R.id.rsdkConnect)
        wakeButton = findViewById(R.id.rsdkWake)
        captureButton = findViewById(R.id.rsdkCapture)
        recordButton = findViewById(R.id.rsdkRecord)
        snapshotButton = findViewById(R.id.rsdkSnapshot)
        quickSwitchButton = findViewById(R.id.rsdkQuickSwitch)
        applyModeButton = findViewById(R.id.rsdkApplyMode)
        queryVersionButton = findViewById(R.id.rsdkQueryVersion)
        sleepButton = findViewById(R.id.rsdkSleep)
        restartButton = findViewById(R.id.rsdkRestart)
        gpsButton = findViewById(R.id.rsdkGps)
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
        gpsButton.visibility = if (gpsController == null) View.GONE else View.VISIBLE
        gpsController?.state?.let(::renderGps)

        modeSpinner.adapter = ArrayAdapter(
            this,
            R.layout.item_rsdk_mode,
            CameraRemoteMode.entries.map { humanize(it.name) },
        ).also { it.setDropDownViewResource(R.layout.item_rsdk_mode_dropdown) }
        modeSpinner.setOnTouchListener { _, _ ->
            modeSelectionDirty = true
            false
        }
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
        connectButton.setOnClickListener {
            if (controller.state.phase == CameraRemotePhase.DISCONNECTED) requestConnect()
            else controller.disconnect()
        }
        wakeButton.setOnClickListener {
            withPermissions(controller.wakePermissions(Build.VERSION.SDK_INT)) {
                if (!controller.wake(this, cameraAddress)) showResult(getString(R.string.rsdk_command_not_accepted))
            }
        }
        captureButton.setOnClickListener { submit(controller.capture()) }
        recordButton.setOnClickListener {
            submit(controller.setRecording(controller.state.status?.recording != true))
        }
        snapshotButton.setOnClickListener { submit(controller.snapshot()) }
        quickSwitchButton.setOnClickListener { submit(controller.quickSwitch()) }
        applyModeButton.setOnClickListener {
            CameraRemoteMode.entries.getOrNull(modeSpinner.selectedItemPosition)?.let {
                val accepted = controller.switchMode(it)
                if (accepted) modeSelectionDirty = false
                submit(accepted)
            }
        }
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
            modeInitialized = false
            modeSelectionDirty = false
            lastRenderedMode = null
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
        if (::livePreview.isInitialized) livePreview.stop()
        if (::previewSurface.isInitialized) previewSurface.onPause()
        if (::controller.isInitialized) controller.removeListener(this)
        gpsController?.removeListener(gpsListener)
        super.onStop()
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
        main.post { if (!isFinishing && !isDestroyed) render(state) }
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
            showResult(getString(R.string.rsdk_result, humanize(result.command.name), outcome, detail))

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
        listOf(
            captureButton, recordButton, snapshotButton, quickSwitchButton, applyModeButton,
            queryVersionButton, sleepButton, restartButton,
        ).forEach { it.isEnabled = panel.commandsEnabled }
        modeSpinner.isEnabled = panel.commandsEnabled
        recordButton.text = getString(if (panel.recording) R.string.rsdk_record_stop else R.string.rsdk_record_start)
        recordButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(
            this,
            if (panel.recording) R.color.rsdk_record_dark else R.color.rsdk_record,
        ))

        renderStatus(state.status, state.modeLabel)
        renderVersion(state)
        state.lastError?.takeIf { it.isNotBlank() }?.let(::showResult)

        if (state.phase == CameraRemotePhase.CONNECTED && lastPhase != CameraRemotePhase.CONNECTED && !versionRequested) {
            versionRequested = controller.queryVersion()
        }
        if (state.phase == CameraRemotePhase.DISCONNECTED) versionRequested = false
        lastPhase = state.phase
    }

    private fun renderGps(state: CameraExclusiveState) {
        gpsButton.text = getString(
            if (state.locked) R.string.rsdk_gps_short_active else R.string.rsdk_gps_short,
        )
        gpsButton.isChecked = state.locked
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
        statusPrimary.text = getString(R.string.rsdk_status_primary, status.modeLabel, capture)
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
        val actualMode = status.mode
        if (actualMode != null && (!modeInitialized || (!modeSelectionDirty && actualMode != lastRenderedMode))) {
            modeSpinner.setSelection(actualMode.ordinal)
            modeInitialized = true
        }
        lastRenderedMode = actualMode
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
    }

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
        private val MAC = Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    }
}
