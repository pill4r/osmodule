package dev.konraditurbe.osmosis.plugin;

import android.app.PendingIntent;
import android.os.Bundle;

/** Narrow, versioned boundary between osmodule Base and a separately installed plugin APK. */
interface IOsmosisPlugin {
    int getProtocolVersion();
    Bundle getDescriptor();
    Bundle getRuntimeState();
    PendingIntent createPanelIntent(in Bundle request);
}
