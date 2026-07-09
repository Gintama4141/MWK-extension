package com.oploverz

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class OploverzProvider : MainAPI() {
    override var mainUrl = "https://plus.oploverz.ltd"
    override var name = "Oploverz"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val apiUrl = "https://backapi.oploverz.ac/api"

    override val mainPage = mainPageOf(
        "$apiUrl/series?sort=latest" to "Terbaru",
        "$apiUrl/series?sort=popular" to "Populer",
        "$apiUrl/series?type=movie" to "Movie",
        "$apiUrl/series?status=ongoing" to "Ongoing",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}&page=$page"
        val res = app.get(url, timeout = 15_000L)
        val parsed = tryParseJson<OplSeriesList>(res.text) ?: return newHomePageResponse(listOf(), hasNext = false)
        val items = parsed.data.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = (parsed.meta?.currentPage ?: 1) < (parsed.meta?.lastPage ?: 1)
        )
    }

    private fun OplSeries.toSearchResponse(): SearchResponse? {
        val slug = slug ?: return null
        val titleStr = title ?: japaneseTitle ?: return null
        return newAnimeSearchResponse(
            titleStr,
            "$apiUrl/series/$slug",
            TvType.Anime
        ) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$apiUrl/series?q=${query.encodeUrl()}&page=1"
        val res = app.get(url, timeout = 15_000L)
        val parsed = tryParseJson<OplSeriesList>(res.text) ?: return emptyList()
        return parsed.data.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfter("/series/").substringBefore("?").takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("URL tidak valid: $url")

        val detailRes = app.get("$apiUrl/series/$slug", timeout = 15_000L)
        val detail = tryParseJson<OplSeriesDetail>(detailRes.text)
            ?: throw ErrorLoadingException("Gagal memuat detail Oploverz")

        val series = detail.data ?: throw ErrorLoadingException("Gagal memuat detail Oploverz")
        val titleStr = series.title ?: series.japaneseTitle ?: "Unknown"

        val episodesRes = app.get("$apiUrl/series/$slug/episodes?page=1", timeout = 15_000L)
        val epsParsed = tryParseJson<OplEpisodeList>(episodesRes.text)
        val episodes = epsParsed?.data?.mapNotNull { ep ->
            val epNum = ep.episodeNumber?.toIntOrNull()
            newEpisode("$apiUrl/episodes/${ep.id}") {
                this.name = "Episode ${ep.episodeNumber ?: "?"}"
                this.episode = epNum
                this.posterUrl = fixUrlNull(series.poster)
            }
        }?.reversed() ?: emptyList()

        return newTvSeriesLoadResponse(titleStr, url, TvType.Anime, episodes) {
            this.posterUrl = fixUrlNull(series.poster)
            this.plot = series.description?.trim()
            this.tags = series.genres?.mapNotNull { it.name }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, timeout = 15_000L)
        val parsed = tryParseJson<OplSingleEpisode>(res.text)
            ?: throw ErrorLoadingException("Gagal memuat episode Oploverz")

        val episode = parsed.data ?: throw ErrorLoadingException("Gagal memuat episode Oploverz")
        var found = false

        // Stream URLs (upbolt.to and similar)
        episode.streamUrl?.forEach { stream ->
            val streamUrl = stream.url
            if (streamUrl == null || !streamUrl.startsWith("http")) return@forEach
            found = true
            try {
                loadExtractor(streamUrl, data, subtitleCallback, callback)
            } catch (_: Exception) {
                // try next stream source
            }
        }

        // Download URLs (acefile.co, vikingfile.com, filedon.co, etc.)
        episode.downloadUrl?.forEach { format ->
            format.resolutions?.forEach { resolution ->
                resolution.downloadLinks?.forEach { link ->
                    val dlUrl = link.url
                    if (dlUrl == null || !dlUrl.startsWith("http")) return@forEach
                    found = true
                    try {
                        loadExtractor(dlUrl, data, subtitleCallback, callback)
                    } catch (_: Exception) {
                        // try next download host
                    }
                }
            }
        }

        if (!found) throw ErrorLoadingException("Tidak ada sumber video di Oploverz")
        return true
    }

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
