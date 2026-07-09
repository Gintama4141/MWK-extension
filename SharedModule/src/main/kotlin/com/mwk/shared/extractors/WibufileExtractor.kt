package com.mwk.shared.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Override untuk extractor core CloudStream `Wibufile`.
 *
 * Samehadaku menyajikan server wibufile via `https://api.wibufile.com/embed/<id>`.
 * Halaman embed (≈39 KB) tidak lagi menyimpan video di `src: '...'` melainkan di JSON
 * `file":"https:\/\/s0.wibufile.com\/...mp4"`. Regex core (`src: ['"](.*?)['"]`) gagal
 * cocok sehingga tidak ada link yang dihasilkan -> "No Links Found".
 *
 * Fix:
 *  - `mainUrl = https://api.wibufile.com` agar cocok persis dengan URL embed (loadExtractor
 *    iterasi terbalik -> extractor extension menang atas core).
 *  - ekstrak `file":"<url>"` lalu unescape `\/` -> `/`.
 *  - pakai `.textLarge` untuk amankan response besar (guard OOM >5 MB).
 */
class Wibufile : ExtractorApi() {
    override val name = "Wibufile"
    override val mainUrl = "https://api.wibufile.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer, timeout = 30_000L).textLarge

        val raw = Regex("""file"\s*:\s*"(.*?)"""").find(res)?.groupValues?.get(1)
            ?: Regex("""src:\s*['"](.*?)['"]""").find(res)?.groupValues?.get(1)
            ?: return

        val video = raw.replace("\\/", "/")

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
