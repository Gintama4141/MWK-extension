package com.streamplay

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class StreamPlayPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(StreamPlay())
    }
}
