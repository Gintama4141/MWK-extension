package com.moviepedia21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

class MoviePedia21Provider : MainAPI() {
    override var mainUrl = "https://moviepedia21.tv"
    private var directUrl: String? = null
    override var name = "MoviePedia21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

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
        val doc = app.get("$mainUrl/${request.data.format(page)}").document
        val items = doc.select("article.item-infinite").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv").document
        return doc.select("article.item-infinite").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title a")?.text()?.trim() ?: return null
        val href = fixUrl(selectFirst("h2.entry-title a")?.attr("href") ?: return null)
        val poster = fixUrlNull(selectFirst("img.wp-post-image")?.getImageAttr())?.fixImageQuality()
        val rating = selectFirst(".gmr-rating-item")?.ownText()?.trim()
        val isTV = selectFirst(".gmr-posttype-item")?.text()?.contains("TV", true) == true
        val eps = selectFirst(".gmr-numbeps span")?.text()?.toIntOrNull()

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
        val title = selectFirst("h2.entry-title a")?.text()?.trim() ?: return null
        val href = selectFirst("h2.entry-title a")?.attr("href") ?: return null
        val poster = fixUrlNull(selectFirst("div.content-thumbnail img")?.attr("src"))?.fixImageQuality()
        val isTV = selectFirst(".gmr-posttype-item")?.text()?.contains("TV", true) == true
        val eps = selectFirst(".gmr-numbeps span")?.text()?.toIntOrNull()

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

    override suspend fun load(url: String): LoadResponse {
        val fetch = app.get(url)
        val doc = fetch.document
        directUrl = getBaseUrl(fetch.url)

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
            val latestEpLink = doc.selectFirst(".gmr-listseries a:not([href*=\"/tv/\"])")?.attr("href")
            val episodes = if (latestEpLink != null) {
                try {
                    val epDoc = app.get(fixUrl(latestEpLink)).document
                    epDoc.select("ul.muvipro-player-tabs li a").mapIndexed { _, tab ->
                        val href = fixUrl(tab.attr("href"))
                        val text = tab.text().trim()
                        val epNum = Regex("Episode\\s*(\\d+)").find(text)
                            ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        newEpisode(href) {
                            this.name = text
                            this.episode = epNum
                            this.posterUrl = poster
                        }
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        doc.select("div.gmr-embed-responsive iframe").forEach { iframe ->
            iframe.getIframeAttr()?.let { src ->
                loadExtractor(httpsify(src), directUrl ?: mainUrl, subtitleCallback, callback)
            }
        }
        doc.select("ul.gmr-download-list li a").forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank()) {
                loadExtractor(href, data, subtitleCallback, callback)
            }
        }
        return true
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
        URI(url).let { "${it.scheme}://${it.host}" }
}
