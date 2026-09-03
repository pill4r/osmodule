package dev.konraditurbe.osmosis.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import dev.konraditurbe.osmosis.feature.media.R
import dev.konraditurbe.osmosis.modules.DeviceModuleStatus
import dev.konraditurbe.osmosis.modules.ModuleInstallationState

/** Read-only per-device module card. Installation and removal stay in the Base-owned manager. */
internal object DeviceModulesDialog {
    fun show(
        activity: AppCompatActivity,
        cameraName: String,
        modules: List<DeviceModuleStatus>,
        onManageModules: () -> Unit,
    ) {
        val card = MaterialCardView(activity).apply {
            radius = activity.dp(24).toFloat()
            strokeWidth = activity.dp(1)
            strokeColor = ContextCompat.getColor(activity, R.color.osmo_track)
            setCardBackgroundColor(ContextCompat.getColor(activity, R.color.osmo_surface))
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(20), activity.dp(20), activity.dp(16))
        }
        content.addView(activity.label(
            activity.getString(R.string.device_modules_title, cameraName),
            20f,
            bold = true,
        ))
        content.addView(activity.label(activity.getString(R.string.device_modules_intro), 13f).apply {
            setTextColor(ContextCompat.getColor(activity, R.color.osmo_muted))
            setPadding(0, activity.dp(5), 0, activity.dp(10))
        })

        if (modules.isEmpty()) {
            content.addView(activity.label(activity.getString(R.string.device_modules_empty), 14f).apply {
                setTextColor(ContextCompat.getColor(activity, R.color.osmo_muted))
                setPadding(0, activity.dp(12), 0, activity.dp(12))
            })
        } else {
            modules.forEachIndexed { index, module ->
                if (index > 0) {
                    content.addView(View(activity).apply {
                        setBackgroundColor(ContextCompat.getColor(activity, R.color.osmo_track))
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(1)))
                }
                content.addView(moduleRow(activity, module))
            }
        }
        card.addView(content)

        AlertDialog.Builder(activity)
            .setView(card)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.manage_modules) { _, _ -> onManageModules() }
            .show()
    }

    private fun moduleRow(activity: AppCompatActivity, module: DeviceModuleStatus): View {
        val item = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, activity.dp(12), 0, activity.dp(12))
        }
        val heading = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(activity.label(module.name, 16f, bold = true), LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ))

        val (statusText, statusColor) = when (module.installationState) {
            ModuleInstallationState.INSTALLED ->
                R.string.module_status_installed to R.color.osmo_green
            ModuleInstallationState.NOT_INSTALLED ->
                R.string.module_status_not_installed to R.color.osmo_muted
            ModuleInstallationState.NEEDS_ATTENTION ->
                R.string.module_status_attention to R.color.osmo_danger
        }
        val resolvedStatusColor = ContextCompat.getColor(activity, statusColor)
        heading.addView(activity.label(activity.getString(statusText), 12f, bold = true).apply {
            setTextColor(resolvedStatusColor)
            background = GradientDrawable().apply {
                cornerRadius = activity.dp(999).toFloat()
                setColor(ContextCompat.getColor(activity, R.color.osmo_surface_dim))
                setStroke(activity.dp(1), resolvedStatusColor)
            }
            setPadding(activity.dp(9), activity.dp(4), activity.dp(9), activity.dp(4))
        })
        item.addView(heading)
        item.addView(activity.label(module.description, 13f).apply {
            setTextColor(ContextCompat.getColor(activity, R.color.osmo_muted))
            setPadding(0, activity.dp(5), activity.dp(4), 0)
        })
        return item
    }

    private fun AppCompatActivity.label(value: String, size: Float, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(ContextCompat.getColor(this@label, R.color.osmo_ink))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun AppCompatActivity.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
