package com.lk21

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Lk21ProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Lk21Provider())
    }
}
