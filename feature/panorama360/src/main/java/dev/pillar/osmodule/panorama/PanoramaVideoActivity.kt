package dev.pillar.osmodule.panorama

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import dev.pillar.osmodule.feature.panorama360.R
import dev.pillar.osmodule.panorama.render.DjmdCalibrationLoader
import dev.pillar.osmodule.panorama.render.PanoramaImageGeometry
import dev.pillar.osmodule.panorama.render.PanoramaProjection
import dev.pillar.osmodule.panorama.render.PanoramaSurfaceView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Osmo 360 video or stitched photo rendered onto the inside of an interactive OpenGL sphere. */
class PanoramaVideoActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var panorama: PanoramaSurfaceView
    private lateinit var loading: View
    private lateinit var loadingText: TextView
    private lateinit var play: MaterialButton
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private var player: MediaPlayer? = null
    private var streams: List<String> = emptyList()
    private var streamIndex = 0
    private var surface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var sourceKind = SOURCE_DUAL_FISHEYE_VIDEO
    private var prepared = false
    private var renderedFirstFrame = false
    private var scrubbing = false
    @Volatile private var generation = 0
    private var calibrationRequested = false
    private var previousNetwork: Network? = null
    private var boundRequestedNetwork = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        bindRequestedNetwork()
        streams = intent.getStringArrayListExtra(EXTRA_STREAMS).orEmpty()
            .ifEmpty { intent.getStringArrayExtra(EXTRA_STREAMS).orEmpty().toList() }
            .distinct()
        sourceKind = intent.getStringExtra(EXTRA_SOURCE_KIND) ?: SOURCE_DUAL_FISHEYE_VIDEO
        if (streams.isEmpty() || !isSupportedSourceKind(sourceKind)) {
            finish()
            return
        }
        if (!isImageSource) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi(intent.getStringExtra(EXTRA_TITLE).orEmpty()))
        panorama.setProjection(
            if (isImageSource) PanoramaProjection.EQUIRECTANGULAR
            else PanoramaProjection.DUAL_FISHEYE,
        )
        panorama.onVideoSurface = { texture ->
            runOnUiThread {
                surface?.release()
                surfaceTexture = texture
                surface = Surface(texture)
                if (isImageSource) startImage(0) else startStream(0)
            }
        }
    }

    private val isImageSource: Boolean
        get() = sourceKind == SOURCE_EQUIRECTANGULAR_IMAGE

    private fun buildUi(title: String): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        panorama = PanoramaSurfaceView(this)
        root.addView(panorama, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0x99000000.toInt())
        }
        header.addView(MaterialButton(this).apply {
            text = "‹"
            contentDescription = getString(R.string.panorama_back)
            minWidth = 0
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(TextView(this).apply {
            text = title.ifBlank { getString(R.string.panorama_title) }
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        loadingText = TextView(this).apply {
            text = getString(if (isImageSource) R.string.panorama_loading_image else R.string.panorama_buffering)
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        loading = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            addView(ProgressBar(this@PanoramaVideoActivity))
            addView(loadingText)
        }
        root.addView(loading, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        val hint = TextView(this).apply {
            text = getString(R.string.panorama_hint)
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setBackgroundColor(0x66000000)
        }
        root.addView(hint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP,
        ).apply { topMargin = dp(76) })
        main.postDelayed({ hint.animate().alpha(0f).setDuration(500).withEndAction { hint.visibility = View.GONE } }, 3500)

        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(7), dp(10), dp(10))
            setBackgroundColor(0xB3000000.toInt())
        }
        play = MaterialButton(this).apply {
            text = getString(R.string.panorama_pause)
            minWidth = 0
            setOnClickListener { togglePlayback() }
        }
        controls.addView(play, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        seek = SeekBar(this).apply {
            max = 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) renderTime(progress, player?.duration ?: 0)
                }
                override fun onStartTrackingTouch(bar: SeekBar) { scrubbing = true }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    player?.seekTo(bar.progress)
                    scrubbing = false
                }
            })
        }
        controls.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        time = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "0:00 / 0:00"
            gravity = Gravity.END
        }
        controls.addView(time, LinearLayout.LayoutParams(dp(105), ViewGroup.LayoutParams.WRAP_CONTENT))
        controls.addView(MaterialButton(this).apply {
            text = getString(R.string.panorama_recenter)
            minWidth = 0
            setOnClickListener { panorama.recenter() }
        })
        if (isImageSource) {
            play.visibility = View.GONE
            seek.visibility = View.GONE
            time.visibility = View.GONE
            controls.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        root.addView(controls, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        val insetViews = listOf(header to true, controls to false)
        val base = insetViews.associate { (view, _) -> view to intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            insetViews.forEach { (view, top) ->
                val p = base.getValue(view)
                view.setPadding(p[0] + bars.left, p[1] + if (top) bars.top else 0, p[2] + bars.right, p[3] + if (top) 0 else bars.bottom)
            }
            insets
        }
        if (!isImageSource) main.post(tick)
        return root
    }

    private fun startImage(index: Int) {
        val output = surface ?: return
        val texture = surfaceTexture ?: return
        if (index !in streams.indices) {
            showFailure()
            return
        }
        generation++
        val at = generation
        prepared = false
        renderedFirstFrame = false
        loadingText.text = getString(R.string.panorama_loading_image)
        loading.visibility = View.VISIBLE
        releasePlayer()
        val textureLimit = panorama.maxTextureSize
            .takeIf { it > 0 }
            ?.coerceAtMost(MAX_IMAGE_TEXTURE_EDGE)
            ?: FALLBACK_IMAGE_TEXTURE_EDGE
        Thread {
            val bitmap = decodeEquirectangularImage(streams[index], textureLimit)
            if (generation != at) {
                bitmap?.recycle()
                return@Thread
            }
            if (bitmap == null) {
                main.post { if (generation == at) startImage(index + 1) }
                return@Thread
            }
            val rendered = drawImage(texture, output, bitmap)
            bitmap.recycle()
            main.post {
                if (generation != at) return@post
                if (rendered) {
                    renderedFirstFrame = true
                    loading.visibility = View.GONE
                } else {
                    startImage(index + 1)
                }
            }
        }.start()
    }

    private fun decodeEquirectangularImage(source: String, maxTextureSize: Int): Bitmap? {
        val temporary = File.createTempFile("panorama-", ".jpg", cacheDir)
        return try {
            val connection = (URL(source).openConnection() as HttpURLConnection).apply {
                connectTimeout = IMAGE_CONNECT_TIMEOUT_MS
                readTimeout = IMAGE_READ_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
            }
            try {
                if (connection.responseCode !in 200..299) return null
                connection.inputStream.use { input ->
                    temporary.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
            if (!PanoramaImageGeometry.isEquirectangular(bounds.outWidth, bounds.outHeight)) return null
            BitmapFactory.decodeFile(
                temporary.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = PanoramaImageGeometry.decodeSampleSize(
                        bounds.outWidth,
                        bounds.outHeight,
                        maxTextureSize,
                    )
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } catch (_: Exception) {
            null
        } finally {
            temporary.delete()
        }
    }

    private fun drawImage(texture: SurfaceTexture, output: Surface, bitmap: Bitmap): Boolean =
        runCatching {
            texture.setDefaultBufferSize(bitmap.width, bitmap.height)
            val canvas = output.lockCanvas(null)
            try {
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(
                    bitmap,
                    null,
                    Rect(0, 0, canvas.width, canvas.height),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
            } finally {
                output.unlockCanvasAndPost(canvas)
            }
        }.isSuccess

    private fun startStream(index: Int) {
        val output = surface ?: return
        if (index !in streams.indices) {
            showFailure()
            return
        }
        streamIndex = index
        generation++
        val at = generation
        prepared = false
        renderedFirstFrame = false
        loadingText.text = getString(R.string.panorama_buffering)
        loading.visibility = View.VISIBLE
        releasePlayer()
        val media = MediaPlayer()
        player = media
        media.apply {
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            media.setSurface(output)
            media.isLooping = true
            media.setOnPreparedListener {
                if (generation != at) return@setOnPreparedListener
                prepared = true
                seek.max = it.duration.coerceAtLeast(1)
                renderTime(0, it.duration)
                it.start()
                loadFactoryCalibration()
                updatePlayLabel()
            }
            media.setOnInfoListener { _, what, _ ->
                if (generation != at) return@setOnInfoListener true
                when (what) {
                    MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                        renderedFirstFrame = true
                        loading.visibility = View.GONE
                    }
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> loading.visibility = View.VISIBLE
                    MediaPlayer.MEDIA_INFO_BUFFERING_END -> if (renderedFirstFrame) loading.visibility = View.GONE
                }
                false
            }
            media.setOnErrorListener { _, _, _ ->
                if (generation == at) startStream(index + 1)
                true
            }
        }
        runCatching {
            media.setDataSource(streams[index])
            media.prepareAsync()
        }.onFailure {
            if (player === media) startStream(index + 1)
        }
    }

    private fun showFailure() {
        releasePlayer()
        loadingText.text = getString(
            if (isImageSource) R.string.panorama_image_unavailable else R.string.panorama_unavailable,
        )
        loading.visibility = View.VISIBLE
        play.isEnabled = false
    }

    /**
     * Keep the already-fast MediaPlayer handshake on the critical path. Once it is prepared, read
     * only the first 16 KiB of the LRF on a worker thread and switch the renderer to factory geometry.
     */
    private fun loadFactoryCalibration() {
        if (calibrationRequested) return
        calibrationRequested = true
        DjmdCalibrationLoader.load(streams) { calibration ->
            if (calibration == null || isDestroyed || isFinishing) return@load
            runOnUiThread {
                if (!isDestroyed && !isFinishing) panorama.setCalibration(calibration)
            }
        }
    }

    private fun togglePlayback() {
        val media = player ?: return
        if (!prepared) return
        if (media.isPlaying) media.pause() else media.start()
        updatePlayLabel()
    }

    private fun updatePlayLabel() {
        play.text = getString(if (player?.isPlaying == true) R.string.panorama_pause else R.string.panorama_play)
    }

    private val tick = object : Runnable {
        override fun run() {
            val media = player
            if (media != null && prepared) {
                if (!scrubbing) seek.progress = media.currentPosition
                renderTime(if (scrubbing) seek.progress else media.currentPosition, media.duration)
                updatePlayLabel()
            }
            main.postDelayed(this, 250)
        }
    }

    private fun renderTime(current: Int, duration: Int) {
        time.text = "${clock(current)} / ${clock(duration)}"
    }

    private fun clock(ms: Int): String {
        val total = ms.coerceAtLeast(0) / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    override fun onPause() {
        super.onPause()
        if (player?.isPlaying == true) player?.pause()
        panorama.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::panorama.isInitialized) panorama.onResume()
    }

    override fun onDestroy() {
        generation++
        main.removeCallbacksAndMessages(null)
        releasePlayer()
        surface?.release()
        surface = null
        surfaceTexture = null
        if (::panorama.isInitialized) panorama.release()
        if (boundRequestedNetwork) {
            getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(previousNetwork)
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun bindRequestedNetwork() {
        val requested = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_NETWORK, Network::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_NETWORK)
        } ?: return
        val connectivity = getSystemService(ConnectivityManager::class.java)
        previousNetwork = connectivity.boundNetworkForProcess
        boundRequestedNetwork = connectivity.bindProcessToNetwork(requested)
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.stop() }; it.reset(); it.release() }
        player = null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_TITLE = "panorama.title"
        const val EXTRA_STREAMS = "panorama.streams"
        const val EXTRA_NETWORK = "panorama.network"
        const val EXTRA_SOURCE_KIND = "panorama.source_kind"
        const val SOURCE_DUAL_FISHEYE_VIDEO = "dual-fisheye-video"
        const val SOURCE_EQUIRECTANGULAR_IMAGE = "equirectangular-image"

        private const val MAX_IMAGE_TEXTURE_EDGE = 4096
        private const val FALLBACK_IMAGE_TEXTURE_EDGE = 2048
        private const val IMAGE_CONNECT_TIMEOUT_MS = 10_000
        private const val IMAGE_READ_TIMEOUT_MS = 45_000

        fun isSupportedSourceKind(value: String): Boolean =
            value == SOURCE_DUAL_FISHEYE_VIDEO || value == SOURCE_EQUIRECTANGULAR_IMAGE
    }
}
