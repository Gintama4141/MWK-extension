package com.mwk.shared.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Override untuk extractor core CloudStream `Wibufile` (wibufile.com).
 *
 * Core `Wibufile.getUrl` memanggil `app.get(url).text`. Response wibufile sekarang
 * punya Content-Length > 5.000.000 byte sehingga NiceHttp membuang IllegalStateException
 * (OOM guard) sebelum body dikembalikan -> episode gagal ("No Links Found").
 *
 * Fix: gunakan `.textLarge` yang mengizinkan response besar, dengan regex `src:` yang sama.
 * Didaftarkan lewat SharedModule sehingga (karena loadExtractor iterasi terbalik) versi ini
 * meng-override extractor core.
 */
class Wibufile : ExtractorApi() {
    override val name = "Wibufile"
    override val mainUrl = "https://wibufile.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, timeout = 30_000L).textLarge
        val video = Regex("src: ['\"](.*?)['\"]").find(res)?.groupValues?.get(1) ?: return

        callback.invoke(
            newExtractorLink(
                name,
                name,
                video,
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
