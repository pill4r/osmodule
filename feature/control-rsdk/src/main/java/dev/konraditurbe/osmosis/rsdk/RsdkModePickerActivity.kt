package dev.konraditurbe.osmosis.rsdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import dev.konraditurbe.osmosis.feature.control.rsdk.R
import dev.konraditurbe.osmosis.modules.CameraRemoteMode

/** Camera-style mode page: lens family first, then only the modes available in that family. */
class RsdkModePickerActivity : AppCompatActivity() {
    private lateinit var panoramaGroup: LinearLayout
    private lateinit var singleLensGroup: LinearLayout
    private lateinit var categoryDescription: TextView
    private var currentMode: CameraRemoteMode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        @Suppress("DEPRECATION")
        fun colorSystemBars() {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        colorSystemBars()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContentView(R.layout.activity_rsdk_mode_picker)
        applyInsets()

        currentMode = CameraRemoteMode.fromProtocolValue(
            intent.getIntExtra(EXTRA_CURRENT_MODE, UNKNOWN_MODE),
        )
        findViewById<MaterialToolbar>(R.id.rsdkModePickerToolbar)
            .setNavigationOnClickListener { finish() }
        panoramaGroup = findViewById(R.id.rsdkMode360Group)
        singleLensGroup = findViewById(R.id.rsdkModeSingleLensGroup)
        categoryDescription = findViewById(R.id.rsdkModeCategoryDescription)

        val buttons = mapOf(
            R.id.rsdkModePanoramicPhoto to CameraRemoteMode.PANORAMIC_PHOTO,
            R.id.rsdkModePanoramicVideo to CameraRemoteMode.PANORAMIC_VIDEO,
            R.id.rsdkModePanoramicNight to CameraRemoteMode.PANORAMIC_SUPER_NIGHT,
            R.id.rsdkModeSelfie to CameraRemoteMode.SELFIE,
            R.id.rsdkModeVortex to CameraRemoteMode.VORTEX,
            R.id.rsdkModeStationaryTimelapse to CameraRemoteMode.PANORAMIC_HYPERLAPSE,
            R.id.rsdkModePhoto to CameraRemoteMode.PHOTO,
            R.id.rsdkModeUltraWideVideo to CameraRemoteMode.BOOST_VIDEO,
            R.id.rsdkModeSingleLensNight to CameraRemoteMode.SINGLE_LENS_SUPER_NIGHT,
            R.id.rsdkModeVideo to CameraRemoteMode.VIDEO,
        )
        buttons.forEach { (id, mode) ->
            findViewById<MaterialButton>(id).apply {
                isChecked = mode == currentMode
                setOnClickListener { choose(mode) }
            }
        }

        val tabs = findViewById<MaterialButtonToggleGroup>(R.id.rsdkModeCategoryTabs)
        tabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            showSingleLens(checkedId == R.id.rsdkModeSingleLensTab)
        }
        val startSingleLens = RsdkModeCatalog.isSingleLens(currentMode)
        tabs.check(if (startSingleLens) R.id.rsdkModeSingleLensTab else R.id.rsdkMode360Tab)
        showSingleLens(startSingleLens)
    }

    private fun showSingleLens(singleLens: Boolean) {
        panoramaGroup.visibility = if (singleLens) View.GONE else View.VISIBLE
        singleLensGroup.visibility = if (singleLens) View.VISIBLE else View.GONE
        categoryDescription.setText(
            if (singleLens) R.string.rsdk_mode_single_lens_description
            else R.string.rsdk_mode_360_description,
        )
    }

    private fun choose(mode: CameraRemoteMode) {
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(EXTRA_SELECTED_MODE, mode.protocolValue),
        )
        finish()
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.rsdkModePickerRoot)
        val originalTop = root.paddingTop
        val originalBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(top = originalTop + safe.top, bottom = originalBottom + safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    companion object {
        private const val EXTRA_CURRENT_MODE = "current_mode"
        private const val EXTRA_SELECTED_MODE = "selected_mode"
        private const val UNKNOWN_MODE = -1

        internal fun intent(context: Context, currentMode: CameraRemoteMode?): Intent =
            Intent(context, RsdkModePickerActivity::class.java).putExtra(
                EXTRA_CURRENT_MODE,
                currentMode?.protocolValue ?: UNKNOWN_MODE,
            )

        internal fun selectedMode(data: Intent?): CameraRemoteMode? =
            data?.getIntExtra(EXTRA_SELECTED_MODE, UNKNOWN_MODE)
                ?.takeIf { it != UNKNOWN_MODE }
                ?.let { CameraRemoteMode.fromProtocolValue(it) }
    }
}
