package com.lakshaysethi.victronbleexporter

object AppState {
    @Volatile var tunnelStatus: String = "Stopped"
    @Volatile var tunnelUrl: String? = null
}
