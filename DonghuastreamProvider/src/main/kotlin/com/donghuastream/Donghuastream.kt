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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

open class Donghuastream : MainAPI() {
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
        val html = app.get(data).document

        // Server 1: Direct iframe (handles lazy-loaded data-litespeed-src)
        html.select("#pembed iframe").forEach { iframe ->
            val iframeUrl = iframe.attr("data-litespeed-src").ifEmpty { iframe.attr("src") }.let(::httpsify)
            if (!iframeUrl.isNullOrEmpty() && iframeUrl != "about:blank") {
                processIframeUrl(iframeUrl, "Default", subtitleCallback, callback)
            }
        }

        // Server 2..N: Base64-encoded options from <select class="mirror">
        html.select("option[data-index]").amap { option ->
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
                // Normalize geo.dailymotion.com/player/xXX.html?video=ID → standard embed URL
                val videoId = Regex("""video=([a-zA-Z0-9]+)""").find(iframeUrl)?.groupValues?.get(1)
                if (videoId != null) {
                    val embedUrl = "https://www.dailymotion.com/embed/video/$videoId"
                    loadExtractor(embedUrl, referer = embedUrl, subtitleCallback, callback)
                } else {
                    loadExtractor(iframeUrl, referer = iframeUrl, subtitleCallback, callback)
                }
            }
            "vidmoly" in iframeUrl -> {
                val cleanedUrl = "http:" + iframeUrl.substringAfter("=\"").substringBefore("\"")
                loadExtractor(cleanedUrl, referer = iframeUrl, subtitleCallback, callback)
            }
            "rumble.com" in iframeUrl -> {
                Rumble().getUrl(iframeUrl, iframeUrl, subtitleCallback, callback)
            }
            "ok.ru" in iframeUrl || "okko.tv" in iframeUrl -> {
                Okru().getUrl(iframeUrl, iframeUrl, subtitleCallback, callback)
            }
            iframeUrl.endsWith(".mp4") -> {
                callback(
                    newExtractorLink(
                        label,
                        label,
                        url = iframeUrl,
                        INFER_TYPE
                    ) {
                        this.referer = ""
                        this.quality = getQualityFromName(label)
                    }
                )
            }
            else -> {
                loadExtractor(iframeUrl, referer = iframeUrl, subtitleCallback, callback)
            }
        }
    }
}
