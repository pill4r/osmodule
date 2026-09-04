package dev.konraditurbe.osmosis.ui

import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import dev.konraditurbe.osmosis.feature.media.R
import dev.konraditurbe.osmosis.ble.CameraModel

/**
 * One row in the camera selector: a saved or freshly-scanned camera. [inRange] drives the 📶/🚫
 * status; unsaved cameras (surfaced by the scan) get a NEW tag. [device] is the live scan result,
 * present only when the camera is in range — that's what a tap connects to.
 */
data class CamRow(
    val mac: String,
    val name: String?,
    val model: CameraModel,
    val inRange: Boolean,
    val saved: Boolean,
    val device: BluetoothDevice?,
    val installedModuleNames: List<String> = emptyList(),
    val remoteSupported: Boolean = false,
    val remoteInstalled: Boolean = false,
)

class CameraListAdapter(
    private val rows: List<CamRow>,
    private val onGalleryClick: (CamRow) -> Unit,
    private val onRemoteClick: ((CamRow) -> Unit)? = null,
    private val onModulesClick: ((CamRow) -> Unit)? = null,
) : BaseAdapter() {
    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_camera, parent, false)
        val r = rows[position]
        v.findViewById<TextView>(R.id.camType).text = if (r.model.verified) r.model.name
            else v.context.getString(R.string.camera_beta_name, r.model.name)
        v.findViewById<TextView>(R.id.camName).text = r.name ?: r.mac
        val statusLabel = v.findViewById<TextView>(R.id.camStatus)
        statusLabel.text = v.context.getString(if (r.inRange) R.string.camera_ready else R.string.camera_offline)
        statusLabel.setTextColor(ContextCompat.getColor(
            v.context,
            if (r.inRange) R.color.osmo_green_dark else R.color.osmo_muted,
        ))
        v.findViewById<ImageView>(R.id.camSignal).setColorFilter(ContextCompat.getColor(
            v.context,
            if (r.inRange) R.color.osmo_green else R.color.osmo_muted,
        ))
        v.findViewById<TextView>(R.id.camTag).visibility = if (r.saved) View.GONE else View.VISIBLE
        v.findViewById<TextView>(R.id.camInstalledModules).apply {
            visibility = if (r.installedModuleNames.isEmpty()) View.GONE else View.VISIBLE
            text = v.context.getString(
                R.string.camera_installed_modules,
                r.installedModuleNames.joinToString(" · "),
            )
        }
        v.findViewById<MaterialButton>(R.id.camGallery).apply {
            isEnabled = r.inRange
            alpha = if (isEnabled) 1f else 0.45f
            setOnClickListener { onGalleryClick(r) }
        }
        v.findViewById<MaterialButton>(R.id.camRemote).apply {
            visibility = if (r.remoteSupported && onRemoteClick != null) View.VISIBLE else View.GONE
            text = v.context.getString(
                if (r.remoteInstalled) R.string.open_remote_control else R.string.install_remote_control,
            )
            // An unavailable camera cannot be controlled, but its missing module can still be installed.
            isEnabled = !r.remoteInstalled || r.inRange
            alpha = if (isEnabled) 1f else 0.45f
            setOnClickListener { onRemoteClick?.invoke(r) }
        }
        v.findViewById<MaterialButton>(R.id.camModules).apply {
            visibility = if (onModulesClick == null) View.GONE else View.VISIBLE
            setOnClickListener { onModulesClick?.invoke(r) }
        }
        // Keep module management readable and actionable for an offline saved camera; only dim identity.
        v.findViewById<View>(R.id.camIdentity).alpha = if (r.inRange) 1f else 0.5f
        v.alpha = 1f
        v.contentDescription = v.context.getString(
            R.string.camera_row_description,
            r.model.name,
            r.name ?: r.mac,
            statusLabel.text,
        )
        return v
    }
}
