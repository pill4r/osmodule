package dev.pillar.osmodule.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import dev.pillar.osmodule.feature.media.R

/** Product information and the upstream projects explicitly used by osmodule. */
class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val scroll = NestedScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(color(R.color.osmo_bg))
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(32))
        }
        scroll.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        content.addView(MaterialButton(this).apply {
            text = getString(R.string.about_back)
            setTextColor(color(R.color.osmo_ink))
            backgroundTintList = ColorStateList.valueOf(color(R.color.osmo_surface))
            strokeColor = ColorStateList.valueOf(color(R.color.osmo_outline_soft))
            strokeWidth = dp(1)
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(48)))

        content.addView(text(getString(R.string.about_title), 32f, R.color.osmo_ink, bold = true).apply {
            setPadding(0, dp(24), 0, 0)
        })
        content.addView(text(getString(R.string.about_version, appVersion()), 13f, R.color.osmo_muted).apply {
            setPadding(0, dp(2), 0, 0)
        })
        content.addView(text(getString(R.string.about_intro), 15f, R.color.osmo_ink).apply {
            setPadding(0, dp(20), 0, 0)
            setLineSpacing(0f, 1.15f)
        })
        content.addView(text(getString(R.string.about_open_source_title), 21f, R.color.osmo_ink, bold = true).apply {
            setPadding(0, dp(30), 0, dp(2))
        })
        content.addView(text(getString(R.string.about_open_source_intro), 13f, R.color.osmo_muted).apply {
            setLineSpacing(0f, 1.12f)
        })

        PROJECTS.forEach { project -> content.addView(projectCard(project)) }

        content.addView(MaterialButton(this).apply {
            text = getString(R.string.about_full_notices)
            setTextColor(color(R.color.osmo_on_accent))
            backgroundTintList = ColorStateList.valueOf(color(R.color.osmo_accent))
            setOnClickListener { openUrl(THIRD_PARTY_NOTICES_URL) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { topMargin = dp(22) })

        content.addView(text(getString(R.string.about_legal), 12f, R.color.osmo_muted).apply {
            setPadding(0, dp(20), 0, 0)
            setLineSpacing(0f, 1.12f)
        })

        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
    }

    private fun projectCard(project: Project): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(color(R.color.osmo_surface))
        strokeColor = color(R.color.osmo_outline_soft)
        strokeWidth = dp(1)
        isClickable = true
        isFocusable = true
        contentDescription = getString(R.string.about_project_link_description, project.name)
        setOnClickListener { openUrl(project.url) }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }

        addView(LinearLayout(this@AboutActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(text(project.name, 17f, R.color.osmo_ink, bold = true))
            addView(text(getString(project.relationship), 12f, R.color.osmo_accent, bold = true).apply {
                setPadding(0, dp(3), 0, 0)
            })
            addView(text(getString(project.summary), 14f, R.color.osmo_muted).apply {
                setPadding(0, dp(7), 0, 0)
                setLineSpacing(0f, 1.1f)
            })
            addView(text(project.url.removePrefix("https://"), 12f, R.color.osmo_accent).apply {
                setPadding(0, dp(8), 0, 0)
            })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun text(value: String, size: Float, colorRes: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color(colorRes))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, R.string.about_open_link_failed, Toast.LENGTH_SHORT).show() }
    }

    private fun appVersion(): String {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        return info.versionName.orEmpty().ifBlank { "—" }
    }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class Project(
        val name: String,
        @StringRes val relationship: Int,
        @StringRes val summary: Int,
        val url: String,
    )

    private companion object {
        const val THIRD_PARTY_NOTICES_URL =
            "https://github.com/pill4r/osmodule/blob/main/THIRD_PARTY_NOTICES.md"

        val PROJECTS = listOf(
            Project(
                "Osmosis",
                R.string.about_relationship_inherited_mit,
                R.string.about_project_osmosis,
                "https://github.com/KonradIT/osmosis",
            ),
            Project(
                "OpenPocketCine",
                R.string.about_relationship_adapted_apache,
                R.string.about_project_open_pocket_cine,
                "https://github.com/erik-sutton95/OpenPocketCine",
            ),
            Project(
                "osmo360",
                R.string.about_relationship_adapted_mit,
                R.string.about_project_osmo360,
                "https://github.com/yesbhautik/osmo360",
            ),
            Project(
                "PanoForge",
                R.string.about_relationship_adapted_mit,
                R.string.about_project_panoforge,
                "https://github.com/Belenos-Toutatis/PanoForge",
            ),
            Project(
                "o-gs",
                R.string.about_relationship_research,
                R.string.about_project_ogs,
                "https://github.com/o-gs",
            ),
            Project(
                "dji-remote",
                R.string.about_relationship_adapted_mit,
                R.string.about_project_dji_remote,
                "https://github.com/dimadesu/dji-remote",
            ),
            Project(
                "osmo-download",
                R.string.about_relationship_research,
                R.string.about_project_osmo_download,
                "https://github.com/SemiConscious/osmo-download",
            ),
            Project(
                "DJI-Wifi-Connect",
                R.string.about_relationship_research,
                R.string.about_project_wifi_connect,
                "https://github.com/sniffingpickles/DJI-Wifi-Connect",
            ),
            Project(
                "lib-osmo-ble",
                R.string.about_relationship_research,
                R.string.about_project_osmo_ble,
                "https://github.com/yigitkonur/lib-osmo-ble",
            ),
            Project(
                "DJI Osmo GPS Controller Demo",
                R.string.about_relationship_dji_sample,
                R.string.about_project_gps_demo,
                "https://github.com/dji-sdk/Osmo-GPS-Controller-Demo",
            ),
        )
    }
}
