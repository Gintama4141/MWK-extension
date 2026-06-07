package com.otakudesu

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.JWPlayer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class OtakudesuProvider : MainAPI() {
    override var mainUrl = "https://otakudesu.blog"
    override var name = "OtakuDesu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        const val acefile = "https://acefile.co"
        val mirrorBlackList = arrayOf(
            "Mega",
            "MegaUp",
            "Otakufiles",
        )

        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/ongoing-anime/page/" to "Ongoing Anime",
        "$mainUrl/complete-anime/page/" to "Complete Anime"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.venz > ul > li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("h2.jdlflm")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.select("div.thumbz > img").attr("src").toString()
        val epNum = this.selectFirst("div.epz")?.ownText()?.replace(Regex("\\D"), "")?.trim()
            ?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query&post_type=anime").document.select("ul.chivsrc > li")
            .mapNotNull {
                val title = it.selectFirst("h2 > a")?.ownText()?.trim() ?: return@mapNotNull null
                val href = it.selectFirst("h2 > a")?.attr("href") ?: return@mapNotNull null
                val posterUrl = it.selectFirst("img")?.attr("src") ?: ""
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = posterUrl
                }
            }
    }


    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("div.infozingle > p:nth-child(1) > span")?.ownText()
            ?.replace(":", "")?.trim().toString()
        val poster = document.selectFirst("div.fotoanime > img")?.attr("src")
        val tags = document.select("div.infozingle > p:nth-child(11) > span > a").map { it.text() }
        val type = getType(
            document.selectFirst("div.infozingle > p:nth-child(5) > span")?.ownText()
                ?.replace(":", "")?.trim() ?: "tv"
        )

        val year = Regex("\\d, (\\d*)").find(
            document.select("div.infozingle > p:nth-child(9) > span").text()
        )?.groupValues?.get(1)?.toIntOrNull()
        val status = getStatus(
            document.selectFirst("div.infozingle > p:nth-child(6) > span")?.ownText()
                ?.replace(":", "")
                ?.trim() ?: ""
        )
        val description = document.select("div.sinopc > p").text()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val malId = tracker?.malId

        var animeMetaData: MetaAnimeData? = null
        var tmdbid: Int? = null
        var kitsuid: String? = null

        if (malId != null) {
            try {
                val syncMetaData = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
                animeMetaData = parseAnimeData(syncMetaData)
                tmdbid = animeMetaData?.mappings?.themoviedbId
                kitsuid = animeMetaData?.mappings?.kitsuId
            } catch (e: Exception) {}
        }

        val logoUrl = fetchTmdbLogoUrl(
            tmdbAPI = "https://api.themoviedb.org/3",
            apiKey = "98ae14df2b8d8f8f8136499daf79f0e0",
            type = type,
            tmdbId = tmdbid,
            appLangCode = "id"
        )

        val backgroundposter = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover

        val episodes = document.select("div.episodelist")[1].select("ul > li").amap { element ->
            val name = element.selectFirst("a")?.text() ?: return@amap null
            var episodeNum = Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val fallbackName = Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(0) ?: name
            val link = fixUrl(element.selectFirst("a")?.attr("href") ?: return@amap null)

            if (type == TvType.AnimeMovie && episodeNum == null) {
                episodeNum = 1
            }

            val episodeKey = episodeNum?.toString()
            val metaEp = if (episodeKey != null) animeMetaData?.episodes?.get(episodeKey) else null

            val epOverview = metaEp?.overview
            val finalOverview = if (!epOverview.isNullOrBlank()) {
                epOverview
            } else {
                "Synopsis not yet available."
            }

            newEpisode(link) { 
                this.name = if (type == TvType.AnimeMovie) {
                    animeMetaData?.titles?.get("en") ?: animeMetaData?.titles?.get("ja") ?: title
                } else {
                    metaEp?.title?.get("en") ?: metaEp?.title?.get("ja") ?: fallbackName
                }
                this.episode = episodeNum
                this.score = Score.from10(metaEp?.rating)
                this.posterUrl = metaEp?.image ?: animeMetaData?.images?.firstOrNull()?.url ?: ""
                this.description = finalOverview
                this.addDate(metaEp?.airDateUtc)
                this.runTime = metaEp?.runtime
            }
        }.filterNotNull().reversed()

        val recommendations =
            document.select("div.isi-recommend-anime-series > div.isi-konten").mapNotNull {
                val recName = it.selectFirst("span.judul-anime > a")?.text() ?: return@mapNotNull null
                val recHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val recPosterUrl = it.selectFirst("a > img")?.attr("src") ?: ""
                newAnimeSearchResponse(recName, recHref, TvType.Anime) {
                    this.posterUrl = recPosterUrl
                }
            }

        val apiDescription = animeMetaData?.description?.replace(Regex("<.*?>"), "")
        val rawPlot = apiDescription ?: animeMetaData?.episodes?.get("1")?.overview
        
        val finalPlot = if (!rawPlot.isNullOrBlank()) {
            rawPlot
        } else {
            description
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.engName = animeMetaData?.titles?.get("en") ?: title
            this.japName = animeMetaData?.titles?.get("ja") ?: animeMetaData?.titles?.get("x-jat")
            this.posterUrl = tracker?.image ?: poster
            this.backgroundPosterUrl = backgroundposter
            try { this.logoUrl = logoUrl } catch(_:Throwable){}
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus = status
            this.plot = finalPlot
            this.tags = tags
            this.recommendations = recommendations
            addMalId(malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            try { addKitsuId(kitsuid) } catch(_:Throwable){}
        }
    }


    data class ResponseSources(
        @JsonProperty("id") val id: String,
        @JsonProperty("i") val i: String,
        @JsonProperty("q") val q: String,
    )

    data class ResponseData(
        @JsonProperty("data") val data: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        runAllAsync(
            {
                val scriptData = document.select("script:containsData(__x__nonce)").firstOrNull()?.data()
                    ?: document.select("script:containsData(mirrorstream)").firstOrNull()?.data()
                    ?: document.select("script:containsData(admin-ajax)").lastOrNull()?.data()
                    ?: ""

                val nonceAction = Regex("""data:\{action:"([^"]+)"""").find(scriptData)?.groupValues?.getOrNull(1)
                    ?: Regex("""action:\s*"([a-f0-9]{32})"""").find(scriptData)?.groupValues?.getOrNull(1)
                val embedAction = Regex("""nonce:[^,]+,action:"([^"]+)"""").find(scriptData)?.groupValues?.getOrNull(1)
                    ?: Regex("""nonce[^,]*,\s*action:\s*"([a-f0-9]{32})"""").find(scriptData)?.groupValues?.getOrNull(1)

                if (nonceAction != null && embedAction != null) {
                    val nonce = app.post("$mainUrl/wp-admin/admin-ajax.php", data = mapOf("action" to nonceAction))
                        .text.let { tryParseJson<ResponseData>(it) }?.data ?: return@runAllAsync

                    document.select("div.mirrorstream > ul > li").amap { li ->
                        val dataContent = li.select("a").attr("data-content")
                        if (dataContent.isNotBlank()) {
                            val decodedData = base64Decode(dataContent)
                            val res = tryParseJson<ResponseSources>(decodedData)
                            
                            if (res != null) {
                                val embedData = app.post(
                                    "${mainUrl}/wp-admin/admin-ajax.php", data = mapOf(
                                        "id" to res.id,
                                        "i" to res.i,
                                        "q" to res.q,
                                        "nonce" to nonce,
                                        "action" to embedAction
                                    )
                                ).text.let { tryParseJson<ResponseData>(it) }?.data ?: return@amap null

                                val sources = Jsoup.parse(
                                    base64Decode(embedData)
                                ).select("iframe").attr("src")

                                loadCustomExtractor(sources, data, subtitleCallback, callback, getQuality(res.q))
                            }
                        }
                    }
                }
            },
            {
                document.select("div.download li").map { ele ->
                    val quality = getQuality(ele.select("strong").text())
                    ele.select("a").map {
                        it.attr("href") to it.text()
                    }.filter {
                        !inBlacklist(it.first) && quality != Qualities.P360.value
                    }.amap {
                        val link = app.get(it.first, referer = "$mainUrl/").url
                        loadCustomExtractor(
                            fixedIframe(link),
                            data,
                            subtitleCallback,
                            callback,
                            quality
                        )
                    }
                }
            }
        )

        return true
    }

    private suspend fun loadCustomExtractor(
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        quality: Int = Qualities.Unknown.value,
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            withContext(Dispatchers.IO) {
                callback.invoke(
                    newExtractorLink(
                        link.name,
                        link.name,
                        link.url,
                        link.type
                    ) {
                        this.referer = link.referer
                        this.quality = quality
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun fixedIframe(url: String): String {
        return when {
            url.startsWith(acefile) -> {
                val id = Regex("""(?:/f/|/file/)(\w+)""").find(url)?.groupValues?.getOrNull(1)
                "${acefile}/player/$id"
            }

            else -> fixUrl(url)
        }
    }

    private fun inBlacklist(host: String?): Boolean {
        return mirrorBlackList.any { it.equals(host, true) }
    }

    private fun getQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url") val url: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaEpisode(
        @JsonProperty("episode") val episode: String?,
        @JsonProperty("airDateUtc") val airDateUtc: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("title") val title: Map<String, String>?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("rating") val rating: String?,
        @JsonProperty("finaleType") val finaleType: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaAnimeData(
        @JsonProperty("titles") val titles: Map<String, String>?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("images") val images: List<MetaImage>?,
        @JsonProperty("episodes") val episodes: Map<String, MetaEpisode>?,
        @JsonProperty("mappings") val mappings: MetaMappings? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaMappings(
        @JsonProperty("themoviedb_id") val themoviedbId: Int? = null,
        @JsonProperty("kitsu_id") val kitsuId: String? = null
    )

    private fun parseAnimeData(jsonString: String): MetaAnimeData? =
        tryParseJson(jsonString)
}

data class TmdbImagesResponse(
    val logos: List<TmdbLogo>? = null
)
data class TmdbLogo(
    @param:JsonProperty("aspect_ratio") val aspectRatio: Double? = null,
    val height: Int? = null,
    @param:JsonProperty("iso_639_1") val iso6391: String? = null,
    @param:JsonProperty("file_path") val filePath: String? = null,
    @param:JsonProperty("vote_average") val voteAverage: Double? = null,
    @param:JsonProperty("vote_count") val voteCount: Int? = null,
    val width: Int? = null
)

suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String,
    apiKey: String,
    type: TvType,
    tmdbId: Int?,
    appLangCode: String?
): String? {
    if (tmdbId == null) return null

    val url = if (type == TvType.AnimeMovie)
        "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey"
    else
        "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"

    val imageResponse = runCatching {
        app.get(url).text.let { tryParseJson<TmdbImagesResponse>(it) }
    }.getOrNull() ?: return null
    val logos = imageResponse.logos?.filter { !it.filePath.isNullOrBlank() } ?: return null

    val lang = appLangCode?.trim()?.lowercase()

    fun path(o: TmdbLogo) = o.filePath ?: ""
    fun isSvg(o: TmdbLogo) = path(o).endsWith(".svg", true)
    fun urlOf(o: TmdbLogo) = "https://image.tmdb.org/t/p/w500${path(o)}"

    var svgFallback: TmdbLogo? = null

    for (logo in logos) {
        val p = path(logo)
        if (p.isBlank()) continue

        val l = logo.iso6391?.trim()?.lowercase()
        if (l == lang) {
            if (!isSvg(logo)) return urlOf(logo)
            if (svgFallback == null) svgFallback = logo
        }
    }
    svgFallback?.let { return urlOf(it) }

    var best: TmdbLogo? = null
    var bestSvg: TmdbLogo? = null

    fun voted(o: TmdbLogo) = (o.voteAverage ?: 0.0) > 0 && (o.voteCount ?: 0) > 0
    fun better(a: TmdbLogo?, b: TmdbLogo): Boolean {
        if (a == null) return true
        val aAvg = a.voteAverage ?: 0.0
        val aCnt = a.voteCount ?: 0
        val bAvg = b.voteAverage ?: 0.0
        val bCnt = b.voteCount ?: 0
        return bAvg > aAvg || (bAvg == aAvg && bCnt > aCnt)
    }

    for (logo in logos) {
        if (!voted(logo)) continue
        if (isSvg(logo)) {
            if (better(bestSvg, logo)) bestSvg = logo
        } else {
            if (better(best, logo)) best = logo
        }
    }

    best?.let { return urlOf(it) }
    bestSvg?.let { return urlOf(it) }
    return null
}

class Moedesu : JWPlayer() {
    override val name = "Moedesu"
    override val mainUrl = "https://desustream.me/moedesu/"
}

class DesuBeta : JWPlayer() {
    override val name = "DesuBeta"
    override val mainUrl = "https://desustream.me/beta/"
}

class Desudesuhd : JWPlayer() {
    override val name = "Desudesuhd"
    override val mainUrl = "https://desustream.me/desudesuhd/"
}

class Odvidhide : Filesim() {
    override val name = "Odvidhide"
    override var mainUrl = "https://odvidhide.com"
}

class DesustreamInfo : JWPlayer() {
    override val name = "DesustreamInfo"
    override val mainUrl = "https://desustream.info"
}

class Updesu : JWPlayer() {
    override val name = "Updesu"
    override val mainUrl = "https://desustream.info/dstream/updesu"
}
