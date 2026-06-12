package com.donghuastream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DonghuastreamProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(Donghuastream())
        registerExtractorAPI(Vtbe())
        registerExtractorAPI(waaw())
        registerExtractorAPI(wishfast())
        registerExtractorAPI(FileMoonSx())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Geodailymotion())
        registerExtractorAPI(Ultrahd())
        registerExtractorAPI(Rumble())
        registerExtractorAPI(PlayStreamplay())
    }
}
