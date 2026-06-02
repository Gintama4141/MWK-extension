package com.onetouchtv

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class OneTouchTVPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(OneTouchTV())
    }
}
