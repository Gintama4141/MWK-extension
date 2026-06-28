package com.kawanfilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element

class Kawanfilm : MainAPI() {

    override var mainUrl = "https://tv2.kawanfilm21.co"
    private var directUrl: String? = null
    override var name = "Kawanfilm"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    companion object {
        private val DIGIT_REGEX = Regex("\\D")
        private val QUALITY_REGEX = Regex("(-\\d*x\\d*)")
        private val EPISODE_REGEX = Regex("(?i)(?:ep|episode)\\s*(\\d+)")
        private val SEASON_REGEX = Regex("(?i)season\\s*(\\d+)")
        private val TITLE_CLEANUP_REGEX = Regex("\\s+(Season|Episode)\\s+\\d+.*$", RegexOption.IGNORE_CASE)
        private const val DEFAULT_TIMEOUT = 30_000L
    }

    override val mainPage = mainPageOf(
        "/page/%d/?s&search=advanced&post_type=movie&index&orderby&genre&movieyear&country&quality=" to "Update Terbaru",
        "category/box-office/page/%d/" to "Box Office",
		"category/action/page/%d/" to "Action",
		"category/animation/page/%d/" to "Animation",
		"category/comedy/page/%d/" to "Comedy",
		"category/drama/page/%d/" to "Drama",
		"category/horror/page/%d/" to "Horror",
		"category/war/page/%d/" to "War",
		"country/china/page/%d/" to "China",
		"country/japan/page/%d/" to "Japan",
		"country/philippines/page/%d/" to "Philippines",
        "country/thailand/page/%d/" to "Thailand"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data.format(page)
        val document = app.get("$mainUrl/$data", timeout = DEFAULT_TIMEOUT).document
        val home = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr()).fixImageQuality()
        val quality = this.select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")
		val ratingText = this.selectFirst("div.gmr-rating-item")?.ownText()?.trim()
		val eps = selectFirst(".gmr-numbeps span")?.text()?.trim()?.toIntOrNull()
		val isSeries = eps != null
		
        return if (isSeries) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                if (eps !=null){
					addSub(eps)
				} else {
					this.score = Score.from10(ratingText?.toDoubleOrNull())
				}
				addQuality(quality)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
				this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("${mainUrl}?s=$encodedQuery&post_type[]=post&post_type[]=tv", timeout = DEFAULT_TIMEOUT).document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fetch = app.get(url, timeout = 15_000L)
        directUrl = getBaseUrl(fetch.url)
        val document = fetch.document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?.replace(TITLE_CLEANUP_REGEX, "")?.trim() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr())?.fixImageQuality()
        val tags = document.select("div.gmr-moviedata a").map { it.text() }
        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a").text().trim().toIntOrNull()
        val tvType = if (url.contains("/tv/")) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div[itemprop=description] > p")?.text()?.trim()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")?.text()?.trim()
        val actors = document.select("div.gmr-moviedata").lastOrNull()?.select("span[itemprop=actors]")?.map { it.select("a").text() }
        val duration = document.selectFirst("div.gmr-moviedata span[property=duration]")?.text()?.replace(DIGIT_REGEX, "")?.toIntOrNull()
        val recommendations = document.select("article.item.col-md-20").mapNotNull { it.toSearchResult() }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("div.vid-episodes a, div.gmr-listseries a").map { eps ->
                val href = fixUrl(eps.attr("href"))
                val name = eps.text()
                val episode = EPISODE_REGEX.find(name)?.groupValues?.get(1)?.toIntOrNull()
                val season = SEASON_REGEX.find(name)?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(href) {
                    this.name = name
                    this.episode = episode
                    this.season = season
                }
            }.filter { it.episode != null }
			
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
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
                this.plot = description
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
        return try {
            val document = app.get(data, timeout = DEFAULT_TIMEOUT).document
            val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
            val referer = "$directUrl/"

            if (id.isNullOrEmpty()) {
                val tabs = document.select("ul.muvipro-player-tabs li a")
                coroutineScope {
                    tabs.map { ele ->
                        async {
                            val iframe = app.get(fixUrl(ele.attr("href")), timeout = DEFAULT_TIMEOUT).document
                                .selectFirst("div.gmr-embed-responsive iframe")?.getIframeAttr()?.let { httpsify(it) }
                                ?: return@async
                            loadExtractor(iframe, referer, subtitleCallback, callback)
                        }
                    }.awaitAll()
                }
            } else {
                val ajaxTabs = document.select("div.tab-content-ajax")
                coroutineScope {
                    ajaxTabs.map { ele ->
                        async {
                            val server = app.post(
                                "$directUrl/wp-admin/admin-ajax.php",
                                data = mapOf("action" to "muvipro_player_content", "tab" to ele.attr("id"), "post_id" to "$id"),
                                timeout = DEFAULT_TIMEOUT
                            ).document.select("iframe").attr("src").let { httpsify(it) }
                            if (server.isNotBlank()) loadExtractor(server, referer, subtitleCallback, callback)
                        }
                    }.awaitAll()
                }
            }

            document.select("ul.gmr-download-list li a").forEach { linkEl ->
                val downloadUrl = linkEl.attr("href")
                if (downloadUrl.isNotBlank()) loadExtractor(downloadUrl, data, subtitleCallback, callback)
            }

            true
        } catch (e: Exception) {
            throw ErrorLoadingException(e.message ?: "Gagal memuat video")
        }
    }

    private fun Element.getImageAttr(): String = when {
        this.hasAttr("data-src") -> this.attr("abs:data-src")
        this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
        this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
        else -> this.attr("abs:src")
    }

    private fun Element?.getIframeAttr(): String? = this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true } ?: this?.attr("src")

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val match = QUALITY_REGEX.find(this)?.groupValues?.get(0) ?: return this
        return this.replace(match, "")
    }

    private fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }
}
