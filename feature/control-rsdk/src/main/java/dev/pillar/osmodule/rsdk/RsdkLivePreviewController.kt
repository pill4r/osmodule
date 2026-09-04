package dev.pillar.osmodule.rsdk

import android.content.Context
import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import dev.pillar.osmodule.net.ApJoiner
import dev.pillar.osmodule.feature.control.rsdk.R
import dev.pillar.osmodule.panorama.render.DjmdCalibrationLoader
import dev.pillar.osmodule.panorama.render.PanoramaCalibration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal enum class RsdkPreviewPhase { UNAVAILABLE, IDLE, JOINING, CONNECTING, BUFFERING, PLAYING, FAILED }

internal data class RsdkPreviewState(
    val phase: RsdkPreviewPhase,
    val message: String,
    val detail: String = "",
    val fps: Int = 0,
    val codec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
) {
    val active: Boolean get() = phase in setOf(
        RsdkPreviewPhase.JOINING,
        RsdkPreviewPhase.CONNECTING,
        RsdkPreviewPhase.BUFFERING,
        RsdkPreviewPhase.PLAYING,
    )
}

/** Coordinates the Wi-Fi request, live UDP transport and MediaCodec lifecycle for the remote page. */
internal class RsdkLivePreviewController(
    context: Context,
    private val cameraAddress: String,
    private val ssid: String?,
    private val passphrase: String?,
    private val wpa3: Boolean,
    private val datalinkPort: Int,
    private val tcpPoke: Boolean,
    private val calibrationStreams: List<String>,
    private val initialCalibration: PanoramaCalibration?,
    private val onCalibration: (PanoramaCalibration) -> Unit,
    private val listener: (RsdkPreviewState) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)
    private val decodeExecutor = Executors.newSingleThreadExecutor { Thread(it, "osmodule.live.decode") }
    @Volatile private var joiner: ApJoiner? = null
    @Volatile private var client: OsmoLiveViewClient? = null
    @Volatile private var decoder: LiveVideoDecoder? = null
    @Volatile private var ownership: RsdkCameraOwnership.Lease? = null
    @Volatile private var ownershipRequest: RsdkCameraOwnership.Request? = null
    private var surface: Surface? = null
    @Volatile private var requested = false
    @Volatile private var current = initialState()
    @Volatile private var codecLabel: String? = null
    @Volatile private var pictureWidth = 0
    @Volatile private var pictureHeight = 0
    private val presentedFrames = AtomicInteger(0)
    private var lastMetricFrames = 0
    private var lastMetricAt = 0L
    @Volatile private var firstFrameAt = 0L

    val state: RsdkPreviewState get() = current
    val canStart: Boolean get() = !ssid.isNullOrBlank() && !passphrase.isNullOrBlank()

    fun attachSurface(next: Surface?) {
        surface = next
        decoder?.attachSurface(next)
    }

    @Synchronized
    fun start() {
        if (requested || !canStart) {
            if (!canStart) publish(initialState())
            return
        }
        requested = true
        val run = generation.incrementAndGet()
        publish(RsdkPreviewState(
            RsdkPreviewPhase.JOINING,
            string(R.string.rsdk_preview_connecting_wifi),
            ssid.orEmpty(),
        ))
        val request = RsdkCameraOwnership.acquireAsync(appContext, cameraAddress) { result ->
            ownershipAcquired(run, result)
        }
        if (run == generation.get() && requested) ownershipRequest = request
        else request.cancel()
    }

    @Synchronized
    private fun ownershipAcquired(run: Int, result: RsdkCameraOwnership.Result) {
        if (run != generation.get() || !requested) {
            (result as? RsdkCameraOwnership.Result.Granted)?.lease?.close()
            return
        }
        ownershipRequest = null
        when (result) {
            is RsdkCameraOwnership.Result.Busy -> {
                fail(run, result.reason)
            }

            is RsdkCameraOwnership.Result.Granted -> {
                ownership = result.lease
                startTransport(run)
            }
        }
    }

    /** Called only after this generation owns both the cross-process and process-local guards. */
    private fun startTransport(run: Int) {
        presentedFrames.set(0)
        lastMetricFrames = 0
        lastMetricAt = SystemClock.elapsedRealtime()
        firstFrameAt = 0L
        codecLabel = null
        pictureWidth = 0
        pictureHeight = 0
        initialCalibration?.let {
            onCalibration(it)
            Log.i(TAG, "Preloaded OSV djmd factory calibration applied to live panorama")
        }
        val liveDecoder = LiveVideoDecoder(object : LiveVideoDecoder.Listener {
            override fun onFramePresented(codec: String, width: Int, height: Int) {
                if (run != generation.get() || !requested) return
                presentedFrames.incrementAndGet()
                codecLabel = codec
                pictureWidth = width
                pictureHeight = height
                if (firstFrameAt == 0L) {
                    firstFrameAt = SystemClock.elapsedRealtime()
                    publish(
                        RsdkPreviewState(
                            RsdkPreviewPhase.PLAYING,
                            string(R.string.rsdk_preview_live_title),
                            string(R.string.rsdk_preview_dimensions_codec, width, height, codec),
                            codec = codec,
                            width = width,
                            height = height,
                        ),
                    )
                }
            }

            override fun onDecoderFailure(message: String) {
                Log.w(TAG, message)
                fail(run, string(R.string.rsdk_preview_decode_failed))
            }
        })
        decoder = liveDecoder
        liveDecoder.attachSurface(surface)

        val ap = ApJoiner(appContext, object : ApJoiner.Listener {
            override fun onLog(s: String) {
                android.util.Log.i(TAG, s)
            }

            override fun onNetwork(network: Network, link: LinkProperties?) {
                if (run != generation.get() || !requested) return
                if (initialCalibration == null) loadFactoryCalibration(run)
                // Base has just released its own camera-network request and media session. HyperOS can
                // report onAvailable before the route and camera-side session teardown have settled;
                // starting immediately is the race that made the first attempt silent while Retry worked.
                main.postDelayed({
                    if (run == generation.get() && requested) openDatalink(run, network, attempt = 0)
                }, NETWORK_SETTLE_MS)
            }

            override fun onFailed(reason: String) {
                Log.w(TAG, reason)
                fail(run, string(R.string.rsdk_preview_wifi_failed))
            }

            override fun onLost() {
                if (run == generation.get() && requested) {
                    fail(run, string(R.string.rsdk_preview_wifi_lost))
                }
            }
        })
        joiner = ap
        ap.join(ssid.orEmpty(), passphrase.orEmpty(), wpa3)
    }

    private fun loadFactoryCalibration(run: Int) {
        if (calibrationStreams.isEmpty()) return
        DjmdCalibrationLoader.load(calibrationStreams) { calibration ->
            if (calibration == null || run != generation.get() || !requested) return@load
            main.post {
                if (run == generation.get() && requested) {
                    onCalibration(calibration)
                    Log.i(TAG, "OSV djmd factory calibration applied to live panorama")
                }
            }
        }
    }

    @Synchronized
    private fun openDatalink(run: Int, network: Network, attempt: Int) {
        if (run != generation.get() || !requested) return
        client?.close()
        lastMetricFrames = presentedFrames.get()
        lastMetricAt = SystemClock.elapsedRealtime()
        publish(RsdkPreviewState(
            RsdkPreviewPhase.CONNECTING,
            string(R.string.rsdk_preview_establishing),
            string(R.string.rsdk_preview_udp_port, datalinkPort),
        ))
        lateinit var liveClient: OsmoLiveViewClient
        var lastMetricBytes = 0L
        liveClient = OsmoLiveViewClient(
            port = datalinkPort,
            tcpPoke = tcpPoke,
            listener = object : OsmoLiveViewClient.Listener {
                override fun onDatalinkReady() {
                    if (run != generation.get() || !requested || client !== liveClient) return
                    publish(RsdkPreviewState(
                        RsdkPreviewPhase.BUFFERING,
                        string(R.string.rsdk_preview_buffering),
                        string(R.string.rsdk_preview_waiting_keyframe),
                    ))
                    main.postDelayed({
                        if (run == generation.get() && requested && client === liveClient && firstFrameAt == 0L) {
                            liveClient.requestKeyframe()
                        }
                    }, KEYFRAME_RETRY_MS)
                    main.postDelayed({
                        if (run == generation.get() && requested && client === liveClient &&
                            firstFrameAt == 0L && attempt < MAX_DATALINK_RESTARTS
                        ) {
                            Log.w(TAG, "No media on initial session; rebuilding preview automatically")
                            openDatalink(run, network, attempt + 1)
                        }
                    }, DATALINK_RESTART_MS)
                    main.postDelayed({
                        if (run == generation.get() && requested && client === liveClient &&
                            firstFrameAt == 0L && attempt >= MAX_DATALINK_RESTARTS
                        ) {
                            fail(run, string(R.string.rsdk_preview_no_stream))
                        }
                    }, FIRST_FRAME_TIMEOUT_MS)
                }

                override fun onAccessUnit(accessUnit: ByteArray) {
                    if (run != generation.get() || !requested || client !== liveClient ||
                        decodeExecutor.isShutdown
                    ) return
                    runCatching {
                        decodeExecutor.execute {
                            if (run == generation.get() && requested) decoder?.decode(accessUnit)
                        }
                    }
                }

                override fun onMetrics(
                    videoPackets: Int,
                    accessUnits: Int,
                    videoBytes: Long,
                    droppedFrames: Int,
                ) {
                    if (run != generation.get() || !requested || client !== liveClient) return
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = (now - lastMetricAt).coerceAtLeast(1L)
                    val frameCount = presentedFrames.get()
                    val fps = ((frameCount - lastMetricFrames) * 1_000L / elapsed).toInt()
                    val bitrateMbps = (videoBytes - lastMetricBytes).coerceAtLeast(0L) * 8.0 /
                        elapsed / 1_000.0
                    Log.i(
                        TAG,
                        "stream metrics attempt=$attempt packets=$videoPackets accessUnits=$accessUnits " +
                            "presented=$frameCount fps=$fps bitrate=${"%.2f".format(bitrateMbps)}Mbps " +
                            "dropped=$droppedFrames",
                    )
                    lastMetricBytes = videoBytes
                    lastMetricFrames = frameCount
                    lastMetricAt = now
                    if (firstFrameAt == 0L) return
                    val codec = codecLabel
                    val dimensions = if (pictureWidth > 0 && pictureHeight > 0) {
                        "$pictureWidth × $pictureHeight"
                    } else {
                        string(R.string.rsdk_preview_stream)
                    }
                    val dropped = if (droppedFrames > 0) {
                        appContext.resources.getQuantityString(
                            R.plurals.rsdk_preview_dropped_frames,
                            droppedFrames,
                            droppedFrames,
                        )
                    } else ""
                    publish(
                        RsdkPreviewState(
                            RsdkPreviewPhase.PLAYING,
                            string(R.string.rsdk_preview_live_title),
                            string(
                                R.string.rsdk_preview_metrics,
                                dimensions,
                                codec ?: string(R.string.rsdk_preview_video),
                                fps.coerceAtLeast(0),
                                bitrateMbps,
                                dropped,
                            ),
                            fps = fps,
                            codec = codec,
                            width = pictureWidth,
                            height = pictureHeight,
                        ),
                    )
                }

                override fun onFailure(message: String) {
                    if (client !== liveClient) return
                    Log.w(TAG, message)
                    fail(run, string(R.string.rsdk_preview_stream_failed))
                }
            },
        )
        client = liveClient
        liveClient.start(network)
    }

    @Synchronized
    fun stop() {
        if (!requested && current.phase == RsdkPreviewPhase.IDLE) return
        requested = false
        generation.incrementAndGet()
        main.removeCallbacksAndMessages(null)
        ownershipRequest?.cancel()
        ownershipRequest = null
        client?.close()
        client = null
        decoder?.close()
        decoder = null
        joiner?.release()
        joiner = null
        ownership?.close()
        ownership = null
        publish(if (canStart) {
            RsdkPreviewState(
                RsdkPreviewPhase.IDLE,
                string(R.string.rsdk_preview_paused),
                string(R.string.rsdk_preview_click_to_start),
            )
        } else {
            initialState()
        })
    }

    fun permissionDenied() {
        publish(RsdkPreviewState(
            RsdkPreviewPhase.FAILED,
            string(R.string.rsdk_preview_permission_missing),
            string(R.string.rsdk_preview_permission_hint),
        ))
    }

    @Synchronized
    private fun fail(run: Int, message: String) {
        if (run != generation.get()) return
        requested = false
        generation.incrementAndGet()
        main.removeCallbacksAndMessages(null)
        ownershipRequest?.cancel()
        ownershipRequest = null
        client?.close()
        client = null
        decoder?.close()
        decoder = null
        joiner?.release()
        joiner = null
        ownership?.close()
        ownership = null
        publish(RsdkPreviewState(
            RsdkPreviewPhase.FAILED,
            message,
            string(R.string.rsdk_preview_click_to_retry),
        ))
    }

    private fun initialState(): RsdkPreviewState = if (canStart) {
        RsdkPreviewState(
            RsdkPreviewPhase.IDLE,
            string(R.string.rsdk_preview_ready),
            string(R.string.rsdk_preview_click_to_start),
        )
    } else {
        RsdkPreviewState(
            RsdkPreviewPhase.UNAVAILABLE,
            string(R.string.rsdk_preview_unavailable),
            string(R.string.rsdk_preview_unavailable_hint),
        )
    }

    private fun publish(next: RsdkPreviewState) {
        current = next
        listener(next)
    }

    private fun string(resource: Int, vararg args: Any): String =
        appContext.getString(resource, *args)

    override fun close() {
        stop()
        decodeExecutor.shutdownNow()
        surface = null
    }

    private companion object {
        const val TAG = "RsdkLivePreview"
        const val NETWORK_SETTLE_MS = 900L
        const val KEYFRAME_RETRY_MS = 2_500L
        const val DATALINK_RESTART_MS = 5_000L
        const val FIRST_FRAME_TIMEOUT_MS = 12_000L
        const val MAX_DATALINK_RESTARTS = 1
    }
}
