package com.torrastream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class TorraStream : TmdbProvider() {
    override var name = "TorraStream"
    override var mainUrl = "https://torrentio.strem.fun"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    companion object {
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    }

    override val mainPage = mainPageOf(
        "$TMDB_API/trending/all/day?api_key=$API_KEY&region=US" to "Trending",
        "$TMDB_API/trending/movie/week?api_key=$API_KEY&region=US" to "Popular Movies",
        "$TMDB_API/trending/tv/week?api_key=$API_KEY&region=US" to "Popular TV Shows",
        "$TMDB_API/tv/airing_today?api_key=$API_KEY&region=US" to "Airing Today",
        "$TMDB_API/movie/top_rated?api_key=$API_KEY&region=US" to "Top Rated Movies",
        "$TMDB_API/tv/top_rated?api_key=$API_KEY&region=US" to "Top Rated TV Shows",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val type = if (request.data.contains("/movie")) "movie" else "tv"
        val home = app.get("${request.data}&page=$page")
            .parsedSafe<Results>()?.results?.mapNotNull { media ->
                media.toSearchResponse(type)
            } ?: throw ErrorLoadingException("Invalid Json reponse")
        return newHomePageResponse(request.name, home)
    }

    private fun Media.toSearchResponse(type: String? = null): SearchResponse? {
        return newMovieSearchResponse(
            title ?: name ?: originalTitle ?: return null,
            Data(id = id, type = mediaType ?: type).toJson(),
            TvType.Movie,
        ) {
            this.posterUrl = if (posterPath?.startsWith("/") == true) "https://image.tmdb.org/t/p/original$posterPath" else posterPath
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$TMDB_API/search/multi?api_key=$API_KEY&language=en-US&query=$query&include_adult=${settingsForProvider.enableAdult}")
            .parsedSafe<Results>()?.results?.mapNotNull { media ->
                media.toSearchResponse()
            } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<Data>(url)
        val type = if (data.type?.contains("tv", true) == true) TvType.TvSeries else TvType.Movie
        val resUrl = if (type == TvType.Movie) {
            "$TMDB_API/movie/${data.id}?api_key=$API_KEY&append_to_response=credits,videos,recommendations"
        } else {
            "$TMDB_API/tv/${data.id}?api_key=$API_KEY&append_to_response=credits,videos,recommendations"
        }
        val res = app.get(resUrl).parsedSafe<MediaDetail>() ?: throw ErrorLoadingException("Invalid Json Response")
        val title = res.title ?: res.name ?: return null
        val poster = res.posterPath?.let { if (it.startsWith("/")) "https://image.tmdb.org/t/p/original$it" else it }
        val bgPoster = res.backdropPath?.let { if (it.startsWith("/")) "https://image.tmdb.org/t/p/original$it" else it }
        val year = (res.releaseDate ?: res.firstAirDate)?.split("-")?.first()?.toIntOrNull()
        val genres = res.genres?.mapNotNull { it.name }
        val actors = res.credits?.cast?.mapNotNull { cast ->
            ActorData(
                Actor(cast.name ?: cast.originalName ?: return@mapNotNull null,
                    cast.profilePath?.let { if (it.startsWith("/")) "https://image.tmdb.org/t/p/original$it" else it }),
                roleString = cast.character
            )
        } ?: emptyList()
        val recommendations = res.recommendations?.results?.mapNotNull { media -> media.toSearchResponse() }
        val trailer = res.videos?.results?.firstOrNull()?.let { "https://www.youtube.com/watch?v=${it.key}" }

        return if (type == TvType.TvSeries) {
            val episodes = res.seasons?.amap { season ->
                app.get("$TMDB_API/tv/${data.id}/season/${season.seasonNumber}?api_key=$API_KEY")
                    .parsedSafe<MediaDetailEpisodes>()?.episodes?.map { eps ->
                        newEpisode(Data(data.id, data.type, season.seasonNumber, eps.episodeNumber).toJson()) {
                            this.name = eps.name; this.season = eps.seasonNumber; this.episode = eps.episodeNumber
                            this.posterUrl = eps.stillPath; this.description = eps.overview
                        }
                    }.orEmpty()
            }?.flatten() ?: emptyList()
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.backgroundPosterUrl = bgPoster
                this.year = year; this.plot = res.overview; this.tags = genres
                this.recommendations = recommendations; this.actors = actors
                addTrailer(trailer); addImdbId(res.externalIds?.imdbId)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, Data(data.id, data.type).toJson()) {
                this.posterUrl = poster; this.backgroundPosterUrl = bgPoster
                this.year = year; this.plot = res.overview; this.duration = res.runtime
                this.tags = genres; this.recommendations = recommendations; this.actors = actors
                addTrailer(trailer); addImdbId(res.externalIds?.imdbId)
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val media = parseJson<Data>(data)
        val imdbId = media.id ?: return false
        val season = media.season ?: 1
        val episode = media.episode ?: 1
        val torrentioUrl = "$mainUrl/stream/${if (media.type?.contains("tv", true) == true) "series" else "movie"}/$imdbId:$season:$episode.json"
        val response = app.get(torrentioUrl, timeout = 15000).parsedSafe<TorrentioResponse>()
        response?.streams?.amap { stream ->
            val url = stream.url ?: return@amap
            when {
                url.contains(".m3u8") -> M3u8Helper.generateM3u8(name, url, mainUrl).forEach(callback)
                url.contains(".mp4") -> callback.invoke(newExtractorLink(name, name, url, INFER_TYPE) { this.quality = 0 })
                url.startsWith("magnet:") -> { }
                else -> loadExtractor(url, mainUrl, subtitleCallback, callback)
            }
        }
        return true
    }

    data class Media(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
    )
    data class Results(
        @JsonProperty("results") val results: List<Media>? = null,
    )
    data class Genres(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )
    data class Cast(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )
    data class Credits(
        @JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf(),
    )
    data class Episodes(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )
    data class MediaDetailEpisodes(
        @JsonProperty("episodes") val episodes: ArrayList<Episodes>? = arrayListOf(),
    )
    data class Trailers(
        @JsonProperty("key") val key: String? = null,
    )
    data class ResultsTrailer(
        @JsonProperty("results") val results: List<Trailers>? = null,
    )
    data class ExternalIds(
        @JsonProperty("imdb_id") val imdbId: String? = null,
    )
    data class ResultsRecommendations(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )
    data class Seasons(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )
    data class MediaDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("vote_average") val voteAverage: Any? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
        @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
        @JsonProperty("videos") val videos: ResultsTrailer? = null,
        @JsonProperty("external_ids") val externalIds: ExternalIds? = null,
        @JsonProperty("credits") val credits: Credits? = null,
        @JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
    )
    data class Data(val id: Int? = null, val type: String? = null, val season: Int? = null, val episode: Int? = null)
    data class TorrentioResponse(val streams: List<TorrentioStream>?)
    data class TorrentioStream(val url: String?, val title: String?)
}
