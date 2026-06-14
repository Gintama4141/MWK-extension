package com.mwk.idlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://tv10.idlixku.com"
    override var name = "Idlix"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val instantLinkLoading = true

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        private var cachedDomain: String? = null
        private val KNOWN_DOMAINS = listOf(
            "https://tv10.idlixku.com",
            "https://tv11.idlixku.com",
            "https://idlix.biz",
            "https://idlix.site"
        )

        suspend fun getWorkingDomain(): String {
            return cachedDomain ?: run {
                KNOWN_DOMAINS.firstOrNull { domain ->
                    try {
                        val response = app.get(domain, timeout = 5000)
                        response.isSuccessful
                    } catch (_: Exception) {
                        false
                    }
                }?.also { cachedDomain = it } ?: KNOWN_DOMAINS[0]
            }
        }

        fun fixUrl(url: String, baseUrl: String): String {
            return when {
                url.startsWith("http") -> url
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> "$baseUrl$url"
                else -> "$baseUrl/$url"
            }
        }
    }

    override val mainPage = mainPageOf(
        "/page/{page}/" to "🔥 Terbaru",
        "/?orderby=rating&page={page}/" to "⭐ Terpopuler",
        "/country/indonesia/page/{page}/" to "🇮🇩 Indonesia",
        "/country/korea/page/{page}/" to "🇰🇷 Korea",
        "/country/japan/page/{page}/" to "🇯🇵 Jepang",
        "/country/china/page/{page}/" to "🇨🇳 China",
        "/country/thailand/page/{page}/" to "🇹🇭 Thailand",
        "/country/philippines/page/{page}/" to "🇵🇭 Philippines",
        "/country/malaysia/page/{page}/" to "🇲🇾 Malaysia",
        "/genre/action/page/{page}/" to "🎬 Action",
        "/genre/drama/page/{page}/" to "🎭 Drama",
        "/genre/comedy/page/{page}/" to "😂 Comedy",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = coroutineScope {
            mainPage.map { (path, title) ->
                async {
                    try {
                        val domain = getWorkingDomain()
                        val url = if (path.contains("{page}")) "$domain${path.replace("{page}", page.toString())}"
                        else "$domain$path"
                        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
                        val results = doc.select("article").mapNotNull { it.toIdlixSearchResult() }
                        if (results.isNotEmpty()) HomePageList(title, results) else null
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return newHomePageResponse(items, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val domain = getWorkingDomain()
        return app.get("$domain/?s=$encoded&post_type[]=post&post_type[]=tv", headers = mapOf("User-Agent" to UA)).document
            .select("article").mapNotNull { it.toIdlixSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val domain = getWorkingDomain()
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("img")?.attr("src")
        val plot = doc.selectFirst("div.entry-content p")?.text()?.trim()
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val rating = doc.selectFirst("[itemprop='ratingValue']")?.text()?.trim()
        val tags = doc.select("a[href*='genre']").map { it.text() }.distinct()
        val actors = doc.select("[itemprop='actors'] a").map { it.text() }

        val epElements = doc.select("a[href*='/eps/']")
        val isSeries = epElements.isNotEmpty()

        if (isSeries) {
            val seasons = parseSeasons(epElements, domain)
            val episodes = seasons.flatMap { season ->
                season.episodes.map { ep ->
                    newEpisode(ep.href) {
                        this.name = ep.title
                        this.episode = ep.episodeNum
                    }
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = Score.from10(rating)
                this.tags = tags
                this.actors = actors.map { ActorData(Actor(it)) }
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = Score.from10(rating)
                this.tags = tags
                this.actors = actors.map { ActorData(Actor(it)) }
            }
        }
    }

    private fun parseSeasons(epElements: Elements, baseUrl: String): List<IdlixSeason> {
        val seasonMap = mutableMapOf<Int, MutableList<IdlixEpisode>>()
        epElements.forEach { el ->
            val href = el.attr("href")
            val epNum = Regex("""episode-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            val seasonNum = Regex("""season-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epTitle = el.text().trim()
            if (epNum != null) {
                val fullUrl = fixUrl(href, baseUrl)
                seasonMap.getOrPut(seasonNum) { mutableListOf() }.add(IdlixEpisode(fullUrl, epTitle, epNum))
            }
        }
        return seasonMap.toList().sortedBy { it.first }.map { (season, eps) ->
            IdlixSeason("Season $season", eps.sortedBy { it.episodeNum ?: 0 })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = mapOf("User-Agent" to UA)).document
        val scriptRegex = """window\.idlixNonce=['"]([a-f0-9]+)['"].*?window\.idlixTime=(\d+).*?""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val script = doc.select("script").map { it.data() }.find { it.contains("window.idlix") }
        val match = script?.let { scriptRegex.find(it) }
        val nonce = match?.groups?.get(1)?.value ?: ""
        val time = match?.groups?.get(2)?.value ?: ""
        val baseUrl = getWorkingDomain()

        doc.select("ul#playeroptionsul > li").map {
            Triple(it.attr("data-post"), it.attr("data-nume"), it.attr("data-type"))
        }.forEach { (id, nume, type) ->
            try {
                val json = app.post(
                    url = "$baseUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to id,
                        "nume" to nume,
                        "type" to type,
                        "_n" to nonce,
                        "_p" to id,
                        "_t" to time
                    ),
                    referer = data,
                    headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest")
                ).text.let { AppUtils.tryParseJson<ResponseHash>(it) } ?: return@forEach

                val metrix = AppUtils.tryParseJson<AesData>(json.embed_url)?.m ?: return@forEach
                val decrypted = IdlixCrypto.decryptEmbedUrl(json.embed_url, json.key, metrix) ?: return@forEach

                when {
                    decrypted.contains("jeniusplay", true) -> {
                        val finalUrl = if (decrypted.startsWith("//")) "https:$decrypted" else decrypted
                        Jeniusplay().getUrl(finalUrl, "$baseUrl/", subtitleCallback, callback)
                    }
                    !decrypted.contains("youtube", true) -> {
                        loadExtractor(decrypted, baseUrl, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return true
    }

    private fun Element.toIdlixSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst("a") ?: return null
        val title = titleEl.text().trim()
        val href = titleEl.attr("href")
        if (href.isBlank() || href == "#") return null
        val poster = this.selectFirst("img")?.attr("src")
        val quality = this.selectFirst(".gmr-quality-item, span.quality")?.text()?.trim()
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.addQuality(quality ?: "HD")
            if (year != null) this.year = year
        }
    }
}
