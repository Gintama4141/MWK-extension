package com.oploverz

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OploverzProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OploverzProvider())
    }
}
