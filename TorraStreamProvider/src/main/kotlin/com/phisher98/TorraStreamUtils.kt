package com.phisher98

import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.getQualityFromName
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private object TorraUtilsRegex {
    val QUALITY_PIXEL = Regex("(\\d{3,4})[pP]")
    val QUALITY_FULL = Regex("""\b(4K|2160p|1080p|720p|WEB[-\s]?DL|BluRay|HDRip|DVDRip)\b""", RegexOption.IGNORE_CASE)
    val QUALITY_COMMON = Regex("""\b(2160p|1440p|1080p|720p|480p|360p)\b""", RegexOption.IGNORE_CASE)
    val SIZE = Regex("""(\d+(?:[.,]\d+)?)\s*(GB|MB)""", RegexOption.IGNORE_CASE)
}

fun getIndexQuality(str: String?): Int {
    return TorraUtilsRegex.QUALITY_PIXEL.find(str ?: "") ?. groupValues ?. getOrNull(1) ?. toIntOrNull()
        ?: Qualities.Unknown.value
}


fun getQuality(str: String): Int {
    return when (str) {
        "360p" -> Qualities.P360.value
        "480p" -> Qualities.P480.value
        "HD" -> Qualities.P720.value
        "HEVC" -> Qualities.P1440.value
        "UHD" -> Qualities.P2160.value
        else -> getQualityFromName(str)
    }
}

fun getLanguage(language: String?): String? {
    return SubtitleHelper.fromTagToEnglishLanguageName(language ?: return null)
        ?: SubtitleHelper.fromTagToEnglishLanguageName(language.substringBefore("-"))
}


data class TorrentioResponse(
    @JsonProperty("streams") val streams: List<TorrentioStream> = emptyList()
)

data class TorrentioStream(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("infoHash") val infoHash: String? = null,
    @JsonProperty("fileIdx") val fileIdx: Int? = null
)

data class DebianRoot(
    @JsonProperty("streams") val streams: List<Stream> = emptyList(),
    @JsonProperty("cacheMaxAge") val cacheMaxAge: Long = 0,
    @JsonProperty("staleRevalidate") val staleRevalidate: Long = 0,
    @JsonProperty("staleError") val staleError: Long = 0
)

data class Stream(
    @JsonProperty("name") val name: String = "",
    @JsonProperty("title") val title: String = "",
    @JsonProperty("url") val url: String = "",
    @JsonProperty("behaviorHints") val behaviorHints: BehaviorHints = BehaviorHints()
)

data class BehaviorHints(
    @JsonProperty("bingeGroup") val bingeGroup: String? = null,
    @JsonProperty("filename") val filename: String? = null
)

//Subtitles

data class Subtitles(
    val subtitles: List<Subtitle>,
    val cacheMaxAge: Long,
)

data class Subtitle(
    val id: String,
    val url: String,
    @param:JsonProperty("SubEncoding")
    val subEncoding: String,
    val lang: String,
    val m: String,
    val g: String,
)


data class AniZipEpisodes(
    val episodes: Map<String, AniZipEpisode>? = null
)
data class AniZipEpisode(
    val anidbEid: Int? = null
)

fun getAnidbEid(jsonString: String, episodeNumber: Int?): Int? {
    if (episodeNumber == null) return null
    return try {
        val response = tryParseJson<AniZipEpisodes>(jsonString)
        response?.episodes?.get(episodeNumber.toString())?.anidbEid
    } catch (e: Exception) {
        null
    }
}


fun parseAnimeData(jsonString: String): MetaAnimeData? {
    return tryParseJson(jsonString)
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaMappings(
    @param:JsonProperty("themoviedb_id") val themoviedbId: String? = null,
    @param:JsonProperty("thetvdb_id") val thetvdbId: Int? = null,
    @param:JsonProperty("imdb_id") val imdbId: String? = null,
    @param:JsonProperty("mal_id") val malId: Int? = null,
    @param:JsonProperty("anilist_id") val anilistId: Int? = null,
    @param:JsonProperty("kitsu_id") val kitsuid: String? = null,
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ImageData(
    @param:JsonProperty("coverType") val coverType: String?,
    @param:JsonProperty("url") val url: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaEpisode(
    @param:JsonProperty("episode") val episode: String?,
    @param:JsonProperty("airdate") val airdate: String?,
    @param:JsonProperty("airDateUtc") val airDateUtc: String?,
    @param:JsonProperty("length") val length: Int?,
    @param:JsonProperty("runtime") val runtime: Int?,
    @param:JsonProperty("image") val image: String?,
    @param:JsonProperty("title") val title: Map<String, String>?,
    @param:JsonProperty("overview") val overview: String?,
    @param:JsonProperty("rating") val rating: String?,
    @param:JsonProperty("finaleType") val finaleType: String?
)


@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaAnimeData(
    @param:JsonProperty("titles") val titles: Map<String, String>? = null,
    @param:JsonProperty("images") val images: List<ImageData>? = null,
    @param:JsonProperty("episodes") val episodes: Map<String, MetaEpisode>? = null,
    @param:JsonProperty("mappings") val mappings: MetaMappings? = null
)

data class StreamsResponse(
    val streams: List<StreamItem>? = null
)
data class StreamItem(
    val infoHash: String? = null,
    val name: String? = null,
    val sources: List<String>? = null,
    val behaviorHints: BehaviorHintsItem? = null
)
data class BehaviorHintsItem(
    val bingeGroup: String? = null
)

fun parseStreamsToMagnetLinks(jsonString: String): List<MagnetStream> {
    val response = tryParseJson<StreamsResponse>(jsonString)
    val streams = response?.streams ?: return emptyList()

    return streams.mapNotNull { item ->
        val infoHash = item.infoHash
        if (infoHash.isNullOrBlank()) return@mapNotNull null

        val originalName = item.name ?: "Unnamed"
        val sources = item.sources ?: return@mapNotNull null

        val bingeGroup = item.behaviorHints?.bingeGroup.orEmpty()
        bingeGroup.split("|").filter { it.isNotBlank() && it != "Unknown" }

        val qualityMatch = TorraUtilsRegex.QUALITY_FULL.find(originalName)?.value ?: "Unknown"

        val encodedName = URLEncoder.encode(originalName, "UTF-8")
        val trackers = sources.joinToString("&") { tracker ->
            "tr=${URLEncoder.encode(tracker, "UTF-8")}"
        }

        val magnet = "magnet:?xt=urn:btih:$infoHash&dn=$encodedName&$trackers"

        MagnetStream(
            title = originalName,
            quality = qualityMatch,
            magnet = magnet
        )
    }
}

fun extractResolutionFromDescription(description: String?): String? {
    if (description.isNullOrBlank()) return null
    return TorraUtilsRegex.QUALITY_COMMON.find(description)?.value
}

fun getDate(): TmdbDate {
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val calendar = Calendar.getInstance()

    val today = formatter.format(calendar.time)

    calendar.add(Calendar.WEEK_OF_YEAR, 1)
    val nextWeek = formatter.format(calendar.time)

    calendar.time = Date()
    calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    calendar.add(Calendar.WEEK_OF_YEAR, -1)
    val lastWeekStart = formatter.format(calendar.time)

    calendar.time = Date()
    calendar.set(Calendar.DAY_OF_MONTH, 1)
    val monthStart = formatter.format(calendar.time)

    return TmdbDate(today, nextWeek, lastWeekStart, monthStart)
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

    val url = if (type == TvType.Movie)
        "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey"
    else
        "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"

    val imageResponse = runCatching {
        app.get(url, timeout = 15_000L).text.let { tryParseJson<TmdbImagesResponse>(it) }
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

data class TorrentsDBResponse(
    val streams: List<TorrentsDBStream>?
)

data class TorrentsDBStream(
    val name: String?,
    val title: String?,
    val infoHash: String,
    val fileIdx: Int?,
    val behaviorHints: TorrentsDBBehaviorHints?,
    val sources: List<String>?
)

data class TorrentsDBBehaviorHints(
    val bingeGroup: String?,
    val filename: String?
)

fun filteredCallback(
    sharedPref: SharedPreferences,
    callback: (ExtractorLink) -> Unit
): (ExtractorLink) -> Unit {

    val excludedQualities = sharedPref.getString("qualityfilter", "")
        ?.lowercase()
        ?.split(",")
        ?.filter { it.isNotBlank() }
        ?: emptyList()

    val maxSize = sharedPref.getString("sizefilter", "")?.toDoubleOrNull()
    val limit = sharedPref.getString("limit", "")?.toIntOrNull() ?: 0
    var resultCount = 0
    val sizeRegex = TorraUtilsRegex.SIZE

    return fun(link: ExtractorLink) {

        if (limit in 1..resultCount) return

        val detectedQuality = when (link.quality) {
            in 2000..3000 -> "4k"
            in 1080..1999 -> "1080p"
            in 720..1079 -> "720p"
            in 480..719 -> "480p"
            else -> "other"
        }

        if (detectedQuality in excludedQualities) return

        val sizeMatch = sizeRegex.find(link.name)

        val sizeValue = sizeMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        val sizeUnit = sizeMatch?.groupValues?.get(2)

        val sizeGB = when (sizeUnit?.uppercase()) {
            "GB" -> sizeValue
            "MB" -> sizeValue?.div(1024)
            else -> null
        }
        if (maxSize != null && sizeGB != null && sizeGB > maxSize) return
        callback(link)
        resultCount++
    }
}

fun buildTorrentioApiUrl(sharedPref: SharedPreferences, mainUrl: String): String {
    val sort = sharedPref.getString("sort", "qualitysize")
    val languageOption = sharedPref.getString("language", "")
    val qualityFilter = sharedPref.getString("qualityfilter", "")
    val limit = sharedPref.getString("limit", "")
    val sizeFilter = sharedPref.getString("sizefilter", "")
    val debridProvider = sharedPref.getString("debrid_provider", "")
    val debridKey = sharedPref.getString("debrid_key", "")

    val params = mutableListOf<String>()
    if (!sort.isNullOrEmpty()) params += "sort=$sort"
    if (!languageOption.isNullOrEmpty()) params += "language=${languageOption.lowercase()}"
    if (!qualityFilter.isNullOrEmpty()) params += "qualityfilter=$qualityFilter"
    if (!limit.isNullOrEmpty()) params += "limit=$limit"
    if (!sizeFilter.isNullOrEmpty()) params += "sizefilter=$sizeFilter"

    if (!debridProvider.isNullOrEmpty() && !debridKey.isNullOrEmpty()) {
        params += "$debridProvider=$debridKey"
    }

    val query = params.joinToString("%7C")
    return "$mainUrl/$query"
}

fun buildMeteorUrl(sharedPref: SharedPreferences, baseUrl: String): String {
    val debridProvider = sharedPref.getString("debrid_provider", "") ?: ""
    val debridKey = sharedPref.getString("debrid_key", "") ?: ""
    val languagesPref = sharedPref.getString("language", "") ?: ""
    val limit = sharedPref.getString("limit", "0") ?: "0"
    val sizeFilter = sharedPref.getString("sizefilter", "0") ?: "0"

    val preferredLangs = if (languagesPref.isNotEmpty()) {
        languagesPref.split(",").joinToString(",") { "\"${it.lowercase()}\"" }
    } else {
        "\"en\",\"multi\""
    }

    val jsonStr = """{"debridService":"${debridProvider.lowercase()}","debridApiKey":"$debridKey","cachedOnly":false,"removeTrash":true,"removeSamples":true,"removeAdult":false,"exclude3D":false,"enableSeaDex":false,"minSeeders":0,"maxResults":${limit.toIntOrNull() ?: 0},"maxResultsPerRes":0,"maxSize":${sizeFilter.toIntOrNull() ?: 0},"resolutions":[],"languages":{"preferred":[$preferredLangs],"required":[],"exclude":[]},"resultFormat":["title","quality","size","audio"],"sortOrder":["cached","resolution","quality","seeders","size","pack","language","seadex"]}"""

    val encoded = android.util.Base64.encodeToString(
        jsonStr.toByteArray(),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
    )

    return "$baseUrl/$encoded"
}
