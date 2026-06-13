package com.moviepedia21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class MoviePedia21Provider : MainAPI() {

    override var mainUrl = "https://moviepedia21.tv"
    override var name = "MoviePedia21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    private var lastLoadedDoc: Document? = null
    private var lastLoadedUrl: String? = null

    override val mainPage = mainPageOf(
        "category/movie/page/%d/" to "Movie Terbaru",
        "tv/page/%d/" to "Drama / Series",
        "category/action/page/%d/" to "Action",
        "category/adventure/page/%d/" to "Adventure",
        "category/comedy/page/%d/" to "Comedy",
        "category/crime/page/%d/" to "Crime",
        "category/drama/page/%d/" to "Drama",
        "category/fantasy/page/%d/" to "Fantasy",
        "category/horror/page/%d/" to "Horror",
        "category/mystery/page/%d/" to "Mystery",
        "category/romance/page/%d/" to "Romance",
        "category/science-fiction/page/%d/" to "Sci-Fi",
        "category/thriller/page/%d/" to "Thriller",
        "country/japan/page/%d/" to "Japan",
        "country/china/page/%d/" to "China",
        "country/korea/page/%d/" to "Korea",
        "country/philippines/page/%d/" to "Philippines",
        "year/2025/page/%d/" to "Tahun 2025"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val doc = app.get("$mainUrl/${request.data.format(page)}").document
            val items = doc.select(SEL_ITEM).mapNotNull { it.toSearchResult() }
            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            println("MoviePedia21 getMainPage failed: ${request.data} - ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return try {
            val doc = app.get("$mainUrl/?s=$encodedQuery&post_type[]=post&post_type[]=tv").document
            doc.select(SEL_ITEM).mapNotNull { it.toSearchResult() }
        } catch (e: Exception) {
            println("MoviePedia21 search failed: $query - ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val fetch = try {
            app.get(url)
        } catch (e: Exception) {
            println("MoviePedia21 load failed: $url - ${e.message}")
            throw e
        }
        val doc = fetch.document
        val baseUrl = getBaseUrl(fetch.url)

        lastLoadedDoc = doc
        lastLoadedUrl = url

        val title = doc.selectFirst("h1.entry-title")?.text()
            ?.substringBefore("Subtitle Indonesia")?.trim()
            ?.replace(Regex("\\s+"), " ")?.trim() ?: ""

        val poster = fixUrlNull(doc.selectFirst("figure.pull-left img")?.getImageAttr())?.fixImageQuality()
        val tags = doc.select("div.gmr-moviedata a[rel=category tag]").map { it.text() }
        val year = doc.selectFirst("div.gmr-moviedata strong:contains(Year) + a")?.text()?.toIntOrNull()
        val tvType = if (url.contains("/tv/")) TvType.TvSeries else TvType.Movie
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val trailer = doc.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = doc.selectFirst("div.gmr-meta-rating span[itemprop=ratingValue]")?.text()?.trim()
        val actors = doc.select("span[itemprop=actors] a").map { it.text() }
        val duration = doc.selectFirst("div.gmr-moviedata strong:contains(Duration)")
            ?.parent()?.ownText()?.filter { it.isDigit() }?.toIntOrNull()
        val recommendations = doc.select("article.item").mapNotNull { it.toRecommendResult() }

        return if (tvType == TvType.TvSeries) {
            val episodes = parseEpisodes(doc, baseUrl, poster)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        }
    }

    private suspend fun parseEpisodes(doc: Document, baseUrl: String, poster: String?): List<Episode> {
        val latestEpLink = doc.selectFirst(SEL_SERIES_LINK)?.attr("href") ?: return emptyList()
        return try {
            val epDoc = app.get(fixUrl(latestEpLink)).document
            epDoc.select(SEL_EPISODE_TABS).mapNotNull { tab ->
                val href = fixUrl(tab.attr("href"))
                val text = tab.text().trim()
                val epNum = REGEX_EPISODE.find(text)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (href.isNotBlank()) {
                    newEpisode(href) {
                        this.name = text
                        this.episode = epNum
                        this.posterUrl = poster
                    }
                } else null
            }
        } catch (e: Exception) {
            println("MoviePedia21 episode fetch failed: $latestEpLink - ${e.message}")
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = lastLoadedDoc.takeIf { lastLoadedUrl == data }
            ?: try {
                app.get(data).document
            } catch (e: Exception) {
                println("MoviePedia21 loadLinks failed: $data - ${e.message}")
                return false
            }

        val baseUrl = getBaseUrl(data)

        doc.select(SEL_IFRAME).forEach { iframe ->
            iframe.getIframeAttr()?.let { src ->
                loadExtractor(httpsify(src), baseUrl, subtitleCallback, callback)
            }
        }
        doc.select(SEL_DOWNLOAD_LINKS).forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank()) {
                loadExtractor(href, baseUrl, subtitleCallback, callback)
            }
        }

        doc.select(SEL_SUBTITLE_TRACKS).forEach { track ->
            val subUrl = track.attr("abs:src")
            val lang = track.attr("srclang").ifBlank { "id" }
            if (subUrl.isNotBlank()) {
                subtitleCallback(
                    newSubtitleFile(lang, fixUrl(subUrl))
                )
            }
        }

        lastLoadedDoc = null
        lastLoadedUrl = null
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(SEL_TITLE)?.text()?.trim() ?: return null
        val href = fixUrl(selectFirst(SEL_TITLE)?.attr("href") ?: return null)
        val poster = fixUrlNull(selectFirst(SEL_POSTER)?.getImageAttr())?.fixImageQuality()
        val rating = selectFirst(SEL_RATING)?.ownText()?.trim()
        val isTV = selectFirst(SEL_TYPE)?.text()?.contains("TV", true) == true
        val eps = selectFirst(SEL_EP_COUNT)?.text()?.toIntOrNull()

        return if (isTV) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                addSub(eps)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                addQuality("HD")
                this.score = Score.from10(rating?.toDoubleOrNull())
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = selectFirst(SEL_TITLE)?.text()?.trim() ?: return null
        val href = selectFirst(SEL_TITLE)?.attr("href") ?: return null
        val poster = fixUrlNull(selectFirst("div.content-thumbnail img")?.attr("src"))?.fixImageQuality()
        val isTV = selectFirst(SEL_TYPE)?.text()?.contains("TV", true) == true
        val eps = selectFirst(SEL_EP_COUNT)?.text()?.toIntOrNull()

        return if (isTV) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                addSub(eps)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                addQuality("HD")
            }
        }
    }

    private fun Element.getImageAttr(): String = when {
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
        else -> attr("abs:src")
    }

    private fun Element?.getIframeAttr(): String? =
        this?.attr("data-litespeed-src").takeIf { !it.isNullOrEmpty() } ?: this?.attr("src")

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.value ?: return this
        return replace(regex, "")
    }

    private fun getBaseUrl(url: String): String =
        try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            url.substringBefore("/", missingDelimiterValue = mainUrl)
        }

    companion object {
        private const val SEL_ITEM = "article.item-infinite"
        private const val SEL_TITLE = "h2.entry-title a"
        private const val SEL_POSTER = "img.wp-post-image"
        private const val SEL_RATING = ".gmr-rating-item"
        private const val SEL_TYPE = ".gmr-posttype-item"
        private const val SEL_EP_COUNT = ".gmr-numbeps span"
        private const val SEL_SERIES_LINK = ".gmr-listseries a:not([href*=\"/tv/\"])"
        private const val SEL_EPISODE_TABS = "ul.muvipro-player-tabs li a"
        private const val SEL_IFRAME = "div.gmr-embed-responsive iframe"
        private const val SEL_DOWNLOAD_LINKS = "ul.gmr-download-list li a"
        private const val SEL_SUBTITLE_TRACKS = "track[kind=subtitles]"
        private val REGEX_EPISODE = Regex("(?i)episode\\s*(\\d+)")
    }
}
