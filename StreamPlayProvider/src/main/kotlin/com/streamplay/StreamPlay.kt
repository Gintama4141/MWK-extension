package com.streamplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class StreamPlay : MainAPI() {
    override var mainUrl = "https://streamplay.co"
    override var name = "StreamPlay"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    private val apiBase = "https://streamplay.best"

    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "popular" to "Popular",
        "latest" to "Latest",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$apiBase/api/${request.data}?page=$page"
        val items = app.get(url).parsedSafe<ApiResponse>()?.results?.mapNotNull { it.toSearchResponse(this) }
            ?: throw ErrorLoadingException("No data")
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$apiBase/api/search?q=$query")
            .parsedSafe<ApiResponse>()?.results?.mapNotNull { it.toSearchResponse(this) }
            ?: emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfterLast("/")
        val doc = app.get("$apiBase/api/detail?id=$id").parsedSafe<DetailResponse>()?.data
            ?: throw ErrorLoadingException("No data")
        val title = doc.title ?: "Unknown"
        val type = if (doc.type == "movie") TvType.Movie else TvType.TvSeries
        return if (type == TvType.TvSeries) {
            val episodes = (doc.episodes ?: emptyList()).amap { ep ->
                newEpisode(LoadData(id, ep.season, ep.episode).toJson()) {
                    this.name = "Episode ${ep.episode}"
                    this.season = ep.season
                    this.episode = ep.episode
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = doc.poster
                this.plot = doc.description
                this.tags = doc.genres
                this.year = doc.year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, LoadData(id).toJson()) {
                this.posterUrl = doc.poster
                this.plot = doc.description
                this.tags = doc.genres
                this.year = doc.year
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val media = parseJson<LoadData>(data)
        val sources = app.get("$apiBase/api/links?id=${media.id}&season=${media.season ?: 0}&episode=${media.episode ?: 0}")
            .parsedSafe<LinksResponse>()?.sources ?: emptyList()
        sources.amap { source ->
            val url = source.url ?: return@amap
            when {
                url.contains(".m3u8") -> M3u8Helper.generateM3u8(name, url, mainUrl).forEach(callback)
                url.contains(".mp4") -> callback.invoke(newExtractorLink(name, name, url, INFER_TYPE) { this.quality = source.quality })
                else -> loadExtractor(url, mainUrl, subtitleCallback, callback)
            }
        }
        return true
    }

    data class LoadData(val id: String? = null, val season: Int? = null, val episode: Int? = null)
    data class ApiResponse(val results: List<MediaItem>?)
    data class MediaItem(val id: String?, val title: String?, val poster: String?, val type: String?) {
        fun toSearchResponse(provider: StreamPlay): SearchResponse? {
            return provider.newMovieSearchResponse(title ?: return null, "${provider.mainUrl}/detail/$id", if (type == "movie") TvType.Movie else TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }
    data class DetailResponse(val data: DetailData?)
    data class DetailData(val title: String?, val poster: String?, val description: String?, val type: String?, val year: Int?, val genres: List<String>?, val episodes: List<EpisodeData>?)
    data class EpisodeData(val season: Int?, val episode: Int?, val title: String?)
    data class LinksResponse(val sources: List<SourceData>?)
    data class SourceData(val url: String?, val quality: Int = 0)
}
