package com.torrastream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TorraStreamPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(TorraStream())
    }
}
