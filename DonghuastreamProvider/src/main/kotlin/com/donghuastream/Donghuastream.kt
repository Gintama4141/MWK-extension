package com.donghuastream

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.io.IOException
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

private data class DmQuality(val url: String?, val type: String?)
private data class DmSubtitleData(val label: String?, val urls: List<String>?)
private data class DmSubtitles(val enable: Boolean?, val data: Map<String, DmSubtitleData>?)
private data class DmMetadata(
    val qualities: Map<String, List<DmQuality>>?,
    val subtitles: DmSubtitles?
)

open class Donghuastream : MainAPI() {
    companion object {
        private val dmVideoIdRegex = Regex("^[kx][a-zA-Z0-9]+$")
        private val vidmolyUrlRegex = Regex("""https?://(?:www\.)?vidmoly\.[a-z]+/[^"'\s]+""")
    }

    override var mainUrl = "https://donghuastream.org"
    override var name = "Donghuastream"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update&page=" to "Recently Updated",
        "anime/?status=completed&type=&order=update" to "Completed",
        "anime/?status=&type=special&sub=&order=update" to "Special Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}$page").document
        val home = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    fun Element.toSearchResult(): SearchResponse {
        val title = this.select("div.bsx > a").attr("title")
        val href = fixUrl(this.select("div.bsx > a").attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("div.bsx a img")?.getImageAttr())
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("data-src")
            this.hasAttr("src") -> this.attr("src")
            else -> this.attr("src")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..3) {
            try {
                val doc = app.get("${mainUrl}/?s=$query" + if (i > 1) "&page=$i" else "").document
                val results = doc.select("div.listupd > article").mapNotNull { it.toSearchResult() }
                if (results.isEmpty()) break
                if (searchResponse.containsAll(results)) break
                searchResponse.addAll(results)
            } catch (_: Exception) { break }
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text().orEmpty()
        val isMovie = type.contains("Movie")

        var poster = document.selectFirst("div.ime > img")?.getImageAttr()?.trim().orEmpty()
        if (poster.isEmpty()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty()
        }

        if (isMovie) {
            val href = document.selectFirst(".eplister li > a")?.attr("href").orEmpty()
            return newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        val episodes = document.select(".eplister ul li a").mapNotNull { a ->
            val href = a.attr("href")
            val numText = a.selectFirst(".epl-num")?.text()?.trim()
            val titleText = a.selectFirst(".epl-title")?.text()?.trim() ?: ""
            val epNum = numText?.substringBefore(" ")?.toIntOrNull()
            if (href.isBlank()) return@mapNotNull null
            newEpisode(href) {
                this.name = titleText.replace(title, "", ignoreCase = true).trim()
                this.episode = epNum
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to mainUrl,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9,id;q=0.8",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )
        val html = app.get(data, headers = headers).document

        if (html.title().contains("Cloudflare", ignoreCase = true)
            || html.select("form[id=challenge-form]").isNotEmpty()
            || html.select("#challenge-running").isNotEmpty()
        ) {
            throw IOException("Cloudflare challenge detected for $data")
        }

        // Server 1: Direct iframe (handles lazy-loaded data-litespeed-src)
        html.select("iframe[data-litespeed-src], iframe[src*=\"dailymotion\"], iframe[src*=\"rumble\"], iframe[src*=\"streamplay\"], iframe[src*=\"ok.ru\"]")
            .forEach { iframe ->
                val iframeUrl = iframe.attr("data-litespeed-src").ifEmpty { iframe.attr("src") }.let(::httpsify)
                if (!iframeUrl.isNullOrEmpty() && iframeUrl != "about:blank") {
                    processIframeUrl(iframeUrl, "Default", subtitleCallback, callback)
                }
            }

        // Server 2..N: Base64-encoded options from <select class="mirror">
        html.select("select.mirror option[data-index], select option[data-index]").amap { option ->
            val base64 = option.attr("value")
            if (base64.isBlank()) return@amap
            val label = option.text().trim()
            val decodedHtml = try {
                base64Decode(base64)
            } catch (_: Exception) {
                return@amap
            }

            val doc = Jsoup.parse(decodedHtml)

            // Try iframe src first
            var iframeUrl = doc.selectFirst("iframe")?.attr("src")?.let(::httpsify)

            // Fallback: meta itemprop="embedUrl" (Dailymotion pattern)
            if (iframeUrl.isNullOrEmpty()) {
                val metaContent = doc.selectFirst("meta[itemprop=embedUrl]")?.attr("content")
                if (!metaContent.isNullOrBlank()) {
                    val videoId = Regex("""video=([^&]+)""").find(metaContent)?.groupValues?.get(1)
                    if (!videoId.isNullOrBlank()) {
                        iframeUrl = "https://geo.dailymotion.com/player/xir9c.html?video=$videoId"
                    }
                }
            }

            if (iframeUrl.isNullOrEmpty()) return@amap
            processIframeUrl(iframeUrl, label, subtitleCallback, callback)
        }
        return true
    }

    private suspend fun processIframeUrl(
        iframeUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            "dailymotion.com" in iframeUrl -> {
                extractDailymotion(iframeUrl, subtitleCallback, callback)
            }
            "vidmoly" in iframeUrl -> {
                val cleanedUrl = vidmolyUrlRegex.find(iframeUrl)?.value
                if (cleanedUrl != null) {
                    loadExtractor(cleanedUrl, referer = iframeUrl, subtitleCallback, callback)
                }
            }
            "rumble.com" in iframeUrl -> {
                Rumble().getUrl(iframeUrl, iframeUrl, subtitleCallback, callback)
            }
            "ok.ru" in iframeUrl || "okko.tv" in iframeUrl -> {
                Okru().getUrl(iframeUrl, iframeUrl, subtitleCallback, callback)
            }
        iframeUrl.endsWith(".mp4") -> {
            callback(
                newExtractorLink(label, label, url = iframeUrl, INFER_TYPE) {
                    this.referer = iframeUrl
                    this.quality = getQualityFromName(label)
                }
            )
        }
            else -> {
                loadExtractor(iframeUrl, referer = iframeUrl, subtitleCallback, callback)
            }
        }
    }

    private suspend fun extractDailymotion(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = when {
            iframeUrl.contains("/embed/") || iframeUrl.contains("/video/") -> iframeUrl
            iframeUrl.contains("geo.dailymotion.com") -> {
                val videoId = iframeUrl.substringAfter("video=")
                "https://www.dailymotion.com/embed/video/$videoId"
            }
            else -> null
        }
        if (embedUrl == null) return
        val id = try {
            java.net.URI(embedUrl).path.substringAfter("/video/")
        } catch (_: Exception) { return }
        if (!id.matches(dmVideoIdRegex)) return
        val meta = tryParseJson<DmMetadata>(
            app.get("https://www.dailymotion.com/player/metadata/video/$id", referer = embedUrl).text
        ) ?: return
        meta.qualities?.values?.flatMap { it.orEmpty() }
            ?.map { it.url }
            ?.filterNotNull()
            ?.filter { it.contains(".m3u8") }
            ?.forEach { generateM3u8("Dailymotion", it, embedUrl).forEach(callback) }
        meta.subtitles?.data?.values?.flatMap { it?.urls.orEmpty() }
            ?.forEachIndexed { i, subUrl ->
                subtitleCallback(newSubtitleFile("Sub $i", subUrl))
            }
    }
}
