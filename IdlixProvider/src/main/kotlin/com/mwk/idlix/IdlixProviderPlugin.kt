package com.mwk.idlix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class IdlixProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(IdlixProvider())
        registerExtractorAPI(Jeniusplay())
    }
}
