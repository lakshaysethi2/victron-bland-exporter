package com.lakshaysethi.victronbleexporter

import com.lakshaysethi.victronbleexporter.tunnel.CloudflaredManager

object AppState {
    @Volatile var tunnelStatus: String = "Stopped"
    @Volatile var tunnelUrl: String? = null

    /** Last DNS/network self-test report text (also embedded in shareable debug logs). */
    @Volatile var dnsSelfTestResult: String? = null

    /**
     * Live reference to the active tunnel manager so the UI can build and share
     * a debug log. Set by CloudflaredManager itself when the service instantiates it.
     */
    @Volatile var cloudflaredManager: CloudflaredManager? = null
}
