package com.pencurimovie

import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.StreamWishExtractor


class Hglink : StreamWishExtractor() {
    override val name = "Hglink"
    override val mainUrl = "https://hglink.to"
}

class Dsvplay : DoodLaExtractor() {
    override var mainUrl = "https://dsvplay.com"
}
