package com.klikxxi

import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack



class Klixxistrp2p : VidStack() {
    override var name = "Klixxistrp2p"
    override var mainUrl = "https://klikxxi.strp2p.site"
    override var requiresReferer = true
}

class Hglink : StreamWishExtractor() {
    override val name = "Hglink"
    override val mainUrl = "https://hglink.to"
}

class Klixxiupns : VidStack() {
    override var name = "Klixxiupns"
    override var mainUrl = "https://klikxxi.upns.one"
    override var requiresReferer = true
}

