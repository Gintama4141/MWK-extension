package com.lk21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Lk21Provider : MainAPI() {
    override var mainUrl = "https://tv10.lk21official.cc"
    override var name = "LK21"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/latest" to "Latest",
        "$mainUrl/populer" to "Populer",
        "$mainUrl/genre/action" to "Action",
        "$mainUrl/genre/horror" to "Horror",
        "$mainUrl/genre/animation" to "Anime",
        "$mainUrl/country/south-korea" to "Korea",
        "$mainUrl/country/japan" to "Jepang",
        "$mainUrl/country/china" to "Cina",
        "$mainUrl/country/thailand" to "Thailand",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}/page/$page"
        val document = app.get(url).document
        val items = document.select("article a[itemprop=url]").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = fixUrl(attr("href"))
        val title = selectFirst("h3.poster-title")?.text()?.trim() ?: return null
        val poster = selectFirst("img")?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        }?.let { fixUrlNull(it) }

        val text = text().lowercase()
        val isSeries = text.contains("eps")

        return newMovieSearchResponse(title, href, if (isSeries) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article a[itemprop=url]").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: ""

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.select("img").firstOrNull()?.let {
                it.attr("src").ifEmpty { it.attr("data-src") }
            }

        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.select("div.entry-content p, .gmr-desc p, p:contains(film)").firstOrNull()?.text()?.trim()

        val tags = document.select("a[href*='/genre/']").mapNotNull { it.text().trim().ifEmpty { null } }
            .ifEmpty { document.select("span:contains(Action), span:contains(Comedy), span:contains(Drama), span:contains(Horror)").mapNotNull { it.text().trim().ifEmpty { null } } }

        val yearText = document.selectFirst("a[href*='/year/']")?.text()?.trim()
            ?: document.body().text().let { Regex("(\\d{4})").findAll(it).map { it.value }.firstOrNull { it.toIntOrNull() in 1900..2030 } }
        val year = yearText?.toIntOrNull()

        val quality = document.selectFirst("span:contains(HD), span:contains(CAM), span:contains(WebDL)")?.text()?.trim()
        val durationText = document.selectFirst("span:contains(h), span:contains(min)")?.text()
            ?: Regex("(\\d+)h\\s*(\\d+)?m?").find(document.text())?.value
        val runtime = durationText?.let { parseDuration(it) }

        val ratingText = document.selectFirst("span:contains(.), .gmr-rating, .rating")?.text()?.trim()
            ?: Regex("(\\d\\.\\d)").find(document.text())?.value

        val hasEps = document.text().contains("EPS", true)
        val isSeries = document.select("a[href*='/nontondrama'], a:contains(Episode)").isNotEmpty()
                || (hasEps && !document.text().contains("HD01:"))

        if (isSeries) {
            val episodes = document.select("a[href*='/']:has(h3):has(img)").mapNotNull { ep ->
                val epLink = fixUrl(ep.attr("href"))
                val epName = ep.selectFirst("h3")?.text()?.trim() ?: ""
                newEpisode(epLink) {
                    this.name = epName
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
                this.tags = tags
                this.year = year
                this.score = Score.from10(ratingText)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
            this.tags = tags
            this.year = year
            this.duration = runtime
            this.score = Score.from10(ratingText)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframes = document.select("iframe[src]")
        if (iframes.isNotEmpty()) {
            iframes.forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && !src.contains("youtube", true) && !src.contains("google", true)) {
                    loadExtractor(fixUrl(src), subtitleCallback, callback)
                }
            }
            return true
        }

        val playerLinks = document.select("a[data-player], a[data-embed], a[data-src], .player-list a, .server-list a")
        playerLinks.forEach { link ->
            val src = link.attr("data-player").ifEmpty {
                link.attr("data-embed").ifEmpty {
                    link.attr("data-src").ifEmpty {
                        link.attr("href")
                    }
                }
            }
            if (src.isNotBlank() && !src.contains("youtube", true)) {
                loadExtractor(fixUrl(src), subtitleCallback, callback)
            }
        }

        return true
    }

    private fun parseDuration(text: String): Int? {
        val regex = Regex("(?:(\\d+)h)?\\s*(?:(\\d+)m)?")
        val match = regex.find(text) ?: return null
        val hours = match.groupValues[1].toIntOrNull() ?: 0
        val minutes = match.groupValues[2].toIntOrNull() ?: 0
        return (hours * 60 + minutes) * 60
    }
}
