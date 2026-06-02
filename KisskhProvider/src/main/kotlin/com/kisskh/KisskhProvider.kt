package com.kisskh

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject

class KisskhProvider : MainAPI() {
    override var mainUrl = "https://kisskh.nl"
    override var name = "Kisskh"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Anime)

    private val kisskhAPI = "https://kisskh.co/api/DramaList/Episode"
    private val kisskhSubAPI = "https://kisskh.co/api/Sub"

    companion object {
        const val TMDBIMAGEBASEURL = "https://image.tmdb.org/t/p/original"
    }

    override val mainPage = mainPageOf(
        "&type=0&sub=0&country=0&status=0&order=2" to "Latest",
        "&type=0&sub=0&country=2&status=0&order=1" to "Top K-Drama",
        "&type=0&sub=0&country=1&status=0&order=1" to "Top C-Drama",
        "&type=2&sub=0&country=2&status=0&order=1" to "Movie Popular",
        "&type=2&sub=0&country=2&status=0&order=2" to "Movie Last Update",
        "&type=1&sub=0&country=2&status=0&order=1" to "TVSeries Popular",
        "&type=1&sub=0&country=2&status=0&order=2" to "TVSeries Last Update",
        "&type=3&sub=0&country=0&status=0&order=1" to "Anime Popular",
        "&type=3&sub=0&country=0&status=0&order=2" to "Anime Latest Update",
        "&type=4&sub=0&country=0&status=0&order=1" to "Hollywood Popular",
        "&type=4&sub=0&country=0&status=0&order=2" to "Hollywood Last Update",
        "&type=0&sub=0&country=0&status=3&order=2" to "Upcoming"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = app.get("$mainUrl/api/DramaList/List?page=$page${request.data}")
            .parsedSafe<Responses>()?.data
            ?.mapNotNull { media -> media.toSearchResponse() }
            ?: throw ErrorLoadingException("Invalid Json reponse")
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = true),
            hasNext = true
        )
    }

    private fun Media.toSearchResponse(): SearchResponse? {
        if (!settingsForProvider.enableAdult && this.label!!.contains("RAW")) return null
        return newAnimeSearchResponse(title ?: return null, "$title/$id", TvType.TvSeries) {
            this.posterUrl = thumbnail
            addSub(episodesCount)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = app.get("$mainUrl/api/DramaList/Search?q=$query&type=0", referer = "$mainUrl/").text
        return tryParseJson<ArrayList<Media>>(searchResponse)?.mapNotNull { media ->
            media.toSearchResponse()
        } ?: throw ErrorLoadingException("Invalid Json reponse")
    }

    private fun getTitle(str: String): String = str.replace(Regex("[^a-zA-Z0-9]"), "-")

    override suspend fun load(url: String): LoadResponse? {
        val id = url.split("/")
        val res = app.get(
            "$mainUrl/api/DramaList/Drama/${id.last()}?isq=false",
            referer = "$mainUrl/Drama/${getTitle(id.first())}?id=${id.last()}"
        ).parsedSafe<MediaDetail>() ?: throw ErrorLoadingException("Invalid Json reponse")

        val cleanTitle = res.title?.replace(Regex("""(?i)\bseason\s*\d+.*"""), "")?.trim() ?: return null
        val year = res.releaseDate?.take(4)?.toIntOrNull()
        val type = res.type?.lowercase()

        val tmdbId = if (type == "anime") null else {
            val isMovie = type in setOf("movie", "hollywood", "bollywood")
            runCatching { fetchtmdb(title = cleanTitle, year = year, isMovie = isMovie) }.getOrNull()
        }

        var tmdbPoster: String? = null
        var tmdbBackdrop: String? = null
        var tmdbOverview: String? = null
        val tmdbSeasonCache = mutableMapOf<Int, JSONObject?>()

        if (tmdbId != null) {
            for (s in listOf(1)) {
                tmdbSeasonCache[s] = runCatching {
                    JSONObject(app.get("${TMDBAPI}/tv/$tmdbId/season/$s?api_key=1865f43a0549ca50d341dd9ab8b29f49").text)
                }.getOrNull()
            }
        }

        val episodes = res.episodes?.map { eps ->
            var epName: String? = null
            var epOverview: String? = null
            var epThumb: String? = null
            var epAir: String? = null
            var epRating: Double? = null
            val season = Regex("""(?i)\bseason\s*(\d+)""").find(res.title.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

            tmdbSeasonCache[season]?.optJSONArray("episodes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val epObj = arr.optJSONObject(i) ?: continue
                    val targetEp = eps.number?.toInt()
                    if (targetEp != null && epObj.optInt("episode_number") == targetEp) {
                        epName = epObj.optString("name").takeIf { it.isNotBlank() }
                        epOverview = epObj.optString("overview").takeIf { it.isNotBlank() }
                        epThumb = epObj.optString("still_path").takeIf { it.isNotBlank() }?.let { TMDBIMAGEBASEURL + it }
                        epAir = epObj.optString("air_date").takeIf { it.isNotBlank() }
                        epRating = epObj.optDouble("vote_average").takeIf { !it.isNaN() && it > 0.0 }
                        break
                    }
                }
            }

            newEpisode(Data(res.title, eps.number?.toInt(), res.id, eps.id).toJson()) {
                this.name = epName ?: "Episode ${eps.number?.toInt() ?: eps.number}"
                this.episode = eps.number?.toInt()
                this.description = epOverview
                this.posterUrl = epThumb
                this.score = Score.from10(epRating)
                addDate(epAir)
            }
        } ?: throw ErrorLoadingException("No Episode")

        if (tmdbId != null) {
            val apiType = if (res.type == "Movie") "movie" else "tv"
            val tmdbJson = runCatching {
                JSONObject(app.get("${TMDBAPI}/$apiType/$tmdbId?api_key=1865f43a0549ca50d341dd9ab8b29f49&append_to_response=credits").text)
            }.getOrNull()

            if (tmdbJson != null) {
                tmdbPoster = tmdbJson.optString("poster_path").takeIf { it.isNotBlank() }?.let { TMDBIMAGEBASEURL + it }
                tmdbBackdrop = tmdbJson.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { TMDBIMAGEBASEURL + it }
                tmdbOverview = tmdbJson.optString("overview").takeIf { it.isNotBlank() }
            }
        }

        return newTvSeriesLoadResponse(res.title ?: return null, url, if (res.type == "Movie" || episodes.size == 1) TvType.Movie else TvType.TvSeries, episodes.reversed()) {
            this.posterUrl = tmdbPoster ?: res.thumbnail
            this.backgroundPosterUrl = tmdbBackdrop ?: tmdbPoster ?: res.thumbnail
            this.year = res.releaseDate?.split("-")?.first()?.toIntOrNull()
            this.plot = res.description ?: tmdbOverview
            this.tags = listOf("${res.country}", "${res.status}", "${res.type}")
            this.showStatus = when (res.status) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> null
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val loadData = parseJson<Data>(data)
        val kkey = app.get("$kisskhAPI/${loadData.epsId}?err=false&ts=&time=&version=2.8.10", timeout = 10000)
            .parsedSafe<Key>()?.key ?: ""
        app.get(
            "$mainUrl/api/DramaList/Episode/${loadData.epsId}.png?err=false&ts=&time=&kkey=$kkey",
            referer = "$mainUrl/Drama/${getTitle("${loadData.title}")}/Episode-${loadData.eps}?id=${loadData.id}&ep=${loadData.epsId}&page=0&pageSize=100"
        ).parsedSafe<Sources>()?.let { source ->
            listOfNotNull(source.video, source.thirdParty).amap { link ->
                safeApiCall {
                    if (link.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(this.name, fixUrl(link), referer = "$mainUrl/", headers = mapOf("Origin" to mainUrl)).forEach(callback)
                    } else if (link.contains("mp4")) {
                        callback.invoke(newExtractorLink(this.name, this.name, url = fixUrl(link), INFER_TYPE) {
                            this.referer = mainUrl; this.quality = Qualities.P720.value
                        })
                    } else {
                        loadExtractor(link.substringBefore("=http") ?: return@safeApiCall, "$mainUrl/", subtitleCallback, callback)
                    }
                }
            }
        }

        val kkey1 = app.get("$kisskhSubAPI/${loadData.epsId}?err=false&ts=&time=&version=2.8.10", timeout = 10000)
            .parsedSafe<Key>()?.key ?: ""
        app.get("$mainUrl/api/Sub/${loadData.epsId}?kkey=$kkey1").text.let { res ->
            tryParseJson<List<Subtitle>>(res)?.map { sub ->
                subtitleCallback.invoke(newSubtitleFile(sub.label ?: return@map, sub.src ?: return@map))
            }
        }
        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request().newBuilder().build()
                val response = chain.proceed(request)
                if (response.request.url.toString().contains(".txt")) {
                    val body = response.body ?: return response
                    val text = body.string()
                    val chunks = text.split(Regex("^\\d+$", RegexOption.MULTILINE)).filter(String::isNotBlank).map(String::trim)
                    val decrypted = chunks.mapIndexed { index, chunk ->
                        if (chunk.isBlank()) return@mapIndexed ""
                        val parts = chunk.split("\n")
                        if (parts.isEmpty()) return@mapIndexed ""
                        val header = parts.first()
                        val textLines = parts.drop(1)
                        val d = textLines.joinToString("\n") { line ->
                            try { decrypt(line) } catch (e: Exception) { "DECRYPT_ERROR:${e.message}" }
                        }
                        listOf(index + 1, header, d).joinToString("\n")
                    }.filter { it.isNotEmpty() }.joinToString("\n\n")
                    val newBody = decrypted.toResponseBody(body.contentType())
                    return response.newBuilder().body(newBody).build()
                }
                return response
            }
        }
    }

    data class Data(val title: String?, val eps: Int?, val id: Int?, val epsId: Int?)
    data class Sources(@JsonProperty("Video") val video: String?, @JsonProperty("ThirdParty") val thirdParty: String?)
    data class Subtitle(@JsonProperty("src") val src: String?, @JsonProperty("label") val label: String?)
    data class Responses(@JsonProperty("data") val data: ArrayList<Media>? = arrayListOf())
    data class Media(@JsonProperty("episodesCount") val episodesCount: Int?, @JsonProperty("thumbnail") val thumbnail: String?, @JsonProperty("label") val label: String?, @JsonProperty("id") val id: Int?, @JsonProperty("title") val title: String?)
    data class Episodes(@JsonProperty("id") val id: Int?, @JsonProperty("number") val number: Double?, @JsonProperty("sub") val sub: Int?)
    data class MediaDetail(@JsonProperty("description") val description: String?, @JsonProperty("releaseDate") val releaseDate: String?, @JsonProperty("status") val status: String?, @JsonProperty("type") val type: String?, @JsonProperty("country") val country: String?, @JsonProperty("episodes") val episodes: ArrayList<Episodes>? = arrayListOf(), @JsonProperty("thumbnail") val thumbnail: String?, @JsonProperty("id") val id: Int?, @JsonProperty("title") val title: String?)
    data class Key(val id: String, val version: String, val key: String)
}
