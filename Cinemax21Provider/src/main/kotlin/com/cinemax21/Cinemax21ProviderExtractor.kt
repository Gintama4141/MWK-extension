package com.cinemax21

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.net.URLDecoder
import com.cinemax21.Cinemax21Provider.Companion.cinemaOSApi
import com.cinemax21.Cinemax21Provider.Companion.Player4uApi
import com.cinemax21.Cinemax21Provider.Companion.idlixAPI
import com.cinemax21.Cinemax21Provider.Companion.RiveStreamAPI
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import android.net.Uri
import android.util.Log


object Cinemax21ProviderExtractor : Cinemax21Provider() {
    private val IDLIX_SCRIPT_REGEX = """window\.idlixNonce=['"]([a-f0-9]+)['"].*?window\.idlixTime=(\d+).*?""".toRegex(RegexOption.DOT_MATCHES_ALL)
    private val DIGIT_REGEX = Regex("""(\d+)""")
    private val SIGNED_URL_REGEX = Regex("""signedUrl\s*=\s*["']([^"']+)["']""")
    private val KEY_REGEX = """key\s*="\s*(\d+)"""".toRegex()
    private val SOURCE_REGEX = Regex("source\\s*=\\s*\"([^\"]+)")
    private val KEY_48_REGEX = Regex("\\w{48}")
    private val VIDEO1_REGEX = Regex("""'token':\s*'(\w+)'[\S\s]+'expires':\s*'(\w+)'[\S\s]+url:\s*'(\S+)'""")
    private val VIDEO_FILE_REGEX = """file:"([^"]+)""".toRegex()
    private val SUBTITLE_FILE_REGEX = """subtitle:"([^"]+)""".toRegex()
    private val SUB_LANG_URL_REGEX = Regex("""\[(\w+)](http\S+)""")
    private val DIGIT_CLEAN_REGEX = Regex("\\d")
    private val HI_CLEAN_REGEX = Regex("\\s+Hi")
    private val RESOLUTION_MATCH_REGEX = Regex("RESOLUTION=(\\d+x\\d+)")
    private val BANDWIDTH_MATCH_REGEX = Regex("BANDWIDTH=(\\d+)")
    private val PLAYER4U_QUALITY_REGEX = Regex("""(\d{3,4}p|4K|CAM|HQ|HD|SD|WEBRip|DVDRip|BluRay|HDRip|TVRip|HDTC|PREDVD)""", RegexOption.IGNORE_CASE)
    private val GO_SUB_PATH_REGEX = Regex("""go\('(.*?)'\)""")
    private val LET_C_KEYLIST_REGEX = Regex("""let\s+c\s*=\s*(\[[^]]*])""")
    private val QUOTE_STRING_REGEX = Regex("\"([^\"]+)\"")

    private fun log(msg: String, error: Boolean = false) {
        if (error) Log.e("Cinemax21", msg) else Log.d("Cinemax21", msg)
    }

    suspend fun invokeIdlix(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        log("Start: Idlix [$title S${season}E${episode}]")
        val fixTitle = title?.createSlug()
        val url = if (season == null) {
            "$idlixAPI/movie/$fixTitle-$year"
        } else {
            "$idlixAPI/episode/$fixTitle-season-$season-episode-$episode"
        }

        try {
            val response = app.get(url, timeout = 15_000L)
            val document = response.document
            val directUrl = getBaseUrl(response.url)

            val scriptRegex = IDLIX_SCRIPT_REGEX
            val script = document.select("script:containsData(window.idlix)").firstOrNull()?.data()
            val match = script?.let { scriptRegex.find(it) }
            val idlixNonce = match?.groups?.get(1)?.value ?: ""
            val idlixTime = match?.groups?.get(2)?.value ?: ""

            document.select("ul#playeroptionsul > li").map {
                Triple(it.attr("data-post"), it.attr("data-nume"), it.attr("data-type"))
            }.amap { (id, nume, type) ->
                val json = app.post(
                    url = "$directUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to id,
                        "nume" to nume,
                        "type" to type,
                        "_n" to idlixNonce,
                        "_p" to id,
                        "_t" to idlixTime
                    ),
                    referer = url,
                    headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest"),
                    timeout = 15_000L
                ).text.let { tryParseJson<ResponseHash>(it) } ?: return@amap

                val metrix = tryParseJson<AesData>(json.embed_url)?.m ?: return@amap
                val password = createIdlixKey(json.key, metrix)
                val decrypted = AesHelper.cryptoAESHandler(json.embed_url, password.toByteArray(), false, "AES/CBC/PKCS5Padding")
                    ?.fixUrlBloat() ?: return@amap

                when {
                    decrypted.contains("jeniusplay", true) -> {
                        val finalUrl = if (decrypted.startsWith("//")) "https:$decrypted" else decrypted
                        Jeniusplay().getUrl(finalUrl, "$directUrl/", subtitleCallback, callback)
                    }
                    !decrypted.contains("youtube") -> {
                        loadExtractor(decrypted, directUrl, subtitleCallback, callback)
                    }
                    else -> return@amap
                }
            }
            log("Success: Idlix")
        } catch (e: Exception) {
            log("Fail: Idlix - ${e.message}", error = true)
        }
    }

    private fun createIdlixKey(r: String, m: String): String {
        val rList = r.split("\\x").filter { it.isNotEmpty() }.toTypedArray()
        var n = ""
        var reversedM = m.split("").reversed().joinToString("")
        while (reversedM.length % 4 != 0) reversedM += "="
        val decodedBytes = try {
            base64Decode(reversedM)
        } catch (_: Exception) { return "" }
        val decodedM = String(decodedBytes.toCharArray())
        for (s in decodedM.split("|")) {
            try {
                val index = Integer.parseInt(s)
                if (index in rList.indices) n += "\\x" + rList[index]
            } catch (_: Exception) {}
        }
        return n
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun invokeDrama(
        title: String, year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        log("Start: Drama [$title S${season}E${episode}]")
        val baseUrl = "https://dramafull.cc"
        val cleanQuery = DramaHelper.normalizeQuery(title)
        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8").replace("+", "%20")
        val searchUrl = "$baseUrl/api/live-search/$encodedQuery"

        try {
            val searchRes = app.get(searchUrl, headers = DramaHelper.headers, timeout = 15_000L).text.let { tryParseJson<DramaSearchResponse>(it) }
            val matchedItem = searchRes?.data?.find { item ->
                val itemTitle = item.title ?: item.name ?: ""
                DramaHelper.isFuzzyMatch(title, itemTitle)
            } ?: searchRes?.data?.firstOrNull()

            if (matchedItem == null) return 
            val slug = matchedItem.slug ?: return
            var targetUrl = "$baseUrl/film/$slug"
            val doc = app.get(targetUrl, headers = DramaHelper.headers, timeout = 15_000L).document

            if (season != null && episode != null) {
                val episodeHref = doc.select("div.episode-item a, .episode-list a").find { 
                    val text = it.text().trim()
                    val epNum = DIGIT_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
                    epNum == episode
                }?.attr("href")
                if (episodeHref == null) return
                targetUrl = fixUrl(episodeHref, baseUrl)
            } else {
                val selectors = listOf("a.btn-watch", "a.watch-now", ".watch-button a", "div.last-episode a", ".film-buttons a.btn-primary")
                for (selector in selectors) {
                    val el = doc.selectFirst(selector)
                    if (el != null) {
                        val href = el.attr("href")
                        if (href.isNotEmpty() && !href.contains("javascript") && href != "#") {
                            targetUrl = fixUrl(href, baseUrl); break
                }
                }
            }
        }
            val docPage = app.get(targetUrl, headers = DramaHelper.headers, timeout = 15_000L).document
            val allScripts = docPage.select("script").joinToString(" ") { it.data() }
            val signedUrl = SIGNED_URL_REGEX.find(allScripts)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            val jsonResponseText = app.get(signedUrl, referer = targetUrl, headers = DramaHelper.headers, timeout = 15_000L).text
            val jsonObject = tryParseJson<Map<String, Any>>(jsonResponseText) ?: return
            val videoSource = jsonObject["video_source"] as? Map<String, String> ?: return
            
            videoSource.forEach { (quality, url) ->
                 if (url.isNotEmpty()) callback.invoke(newExtractorLink("Drama", "Drama ($quality)", url, INFER_TYPE))
            }
             
            val bestQualityKey = videoSource.keys.maxByOrNull { it.toIntOrNull() ?: 0 } ?: return
            val subJson = jsonObject["sub"] as? Map<String, Any>
            val subs = subJson?.get(bestQualityKey) as? List<String>
            subs?.forEach { subPath -> subtitleCallback.invoke(newSubtitleFile("English", fixUrl(subPath, baseUrl))) }
            log("Success: Drama")
        } catch (e: Exception) { log("Fail: Drama - ${e.message}", error = true) }
    }

    suspend fun invokeKisskh(
        title: String, year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        log("Start: Kisskh [$title S${season}E${episode}]")
        val mainUrl = "https://kisskh.ovh"
        val KISSKH_API = "https://script.google.com/macros/s/AKfycbzn8B31PuDxzaMa9_CQ0VGEDasFqfzI5bXvjaIZH4DM8DNq9q6xj1ALvZNz_JT3jF0suA/exec?id="
        val KISSKH_SUB_API = "https://script.google.com/macros/s/AKfycbyq6hTj0ZhlinYC6xbggtgo166tp6XaDKBCGtnYk8uOfYBUFwwxBui0sGXiu_zIFmA/exec?id="

        try {
            val searchRes = app.get("$mainUrl/api/DramaList/Search?q=$title&type=0", timeout = 15_000L).text
            val searchList = tryParseJson<ArrayList<KisskhMedia>>(searchRes) ?: return
            val matched = searchList.find { it.title.equals(title, true) } 
                ?: searchList.firstOrNull { it.title?.contains(title, true) == true } ?: return
            val dramaId = matched.id ?: return
            val detailRes = app.get("$mainUrl/api/DramaList/Drama/$dramaId?isq=false", timeout = 15_000L).text.let { tryParseJson<KisskhDetail>(it) } ?: return
            val episodes = detailRes.episodes ?: return
            val targetEp = if (season == null) episodes.lastOrNull() else episodes.find { it.number?.toInt() == episode }
            val epsId = targetEp?.id ?: return
            val kkeyVideo = app.get("$KISSKH_API$epsId&version=2.8.10", timeout = 15_000L).text.let { tryParseJson<KisskhKey>(it) }?.key ?: ""
            val videoUrl = "$mainUrl/api/DramaList/Episode/$epsId.png?err=false&ts=&time=&kkey=$kkeyVideo"
            val sources = app.get(videoUrl, timeout = 15_000L).text.let { tryParseJson<KisskhSources>(it) }

            listOfNotNull(sources?.video, sources?.thirdParty).forEach { link ->
                if (link.contains(".m3u8")) M3u8Helper.generateM3u8("Kisskh", link, referer = "$mainUrl/", headers = mapOf("Origin" to mainUrl)).forEach(callback)
                else if (link.contains(".mp4")) callback.invoke(newExtractorLink("Kisskh", "Kisskh", link, ExtractorLinkType.VIDEO) { this.referer = mainUrl })
            }
            val kkeySub = app.get("$KISSKH_SUB_API$epsId&version=2.8.10", timeout = 15_000L).text.let { tryParseJson<KisskhKey>(it) }?.key ?: ""
            val subJson = app.get("$mainUrl/api/Sub/$epsId?kkey=$kkeySub", timeout = 15_000L).text
            tryParseJson<List<KisskhSubtitle>>(subJson)?.forEach { sub ->
                subtitleCallback.invoke(newSubtitleFile(sub.label ?: "Unknown", sub.src ?: return@forEach))
            }
            log("Success: Kisskh")
        } catch (e: Exception) { log("Fail: Kisskh - ${e.message}", error = true) }
    }
    
    private data class KisskhMedia(@JsonProperty("id") val id: Int?, @JsonProperty("title") val title: String?)
    private data class KisskhDetail(@JsonProperty("episodes") val episodes: ArrayList<KisskhEpisode>?)
    private data class KisskhEpisode(@JsonProperty("id") val id: Int?, @JsonProperty("number") val number: Double?)
    private data class KisskhKey(@JsonProperty("key") val key: String?)
    private data class KisskhSources(@JsonProperty("Video") val video: String?, @JsonProperty("ThirdParty") val thirdParty: String?)
    private data class KisskhSubtitle(@JsonProperty("src") val src: String?, @JsonProperty("label") val label: String?)

    suspend fun invokeMoviebox(
        title: String, year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        log("Start: Moviebox [$title S${season}E${episode}]")
        val apiUrl = "https://filmboom.top"
        val searchUrl = "$apiUrl/wefeed-h5-bff/web/subject/search"
        val searchBody = mapOf("keyword" to title, "page" to "1", "perPage" to "0", "subjectType" to "0").toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        val searchRes = app.post(searchUrl, requestBody = searchBody, timeout = 15_000L).text
        val items = tryParseJson<MovieboxResponse>(searchRes)?.data?.items ?: return
        val matchedMedia = items.find { item ->
            val itemYear = item.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
            (item.title.equals(title, true)) || (item.title?.contains(title, true) == true && itemYear == year)
        } ?: return
        val subjectId = matchedMedia.subjectId ?: return
        val detailPath = matchedMedia.detailPath
        val se = season ?: 0
        val ep = episode ?: 0
        val playUrl = "$apiUrl/wefeed-h5-bff/web/subject/play?subjectId=$subjectId&se=$se&ep=$ep"
        val validReferer = "$apiUrl/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail&lang=en"
        val playRes = app.get(playUrl, referer = validReferer, timeout = 15_000L).text
        val streams = tryParseJson<MovieboxResponse>(playRes)?.data?.streams ?: return
        streams.reversed().distinctBy { it.url }.forEach { source ->
             callback.invoke(newExtractorLink("Moviebox", "Moviebox", source.url ?: return@forEach, INFER_TYPE) {
                    this.referer = "$apiUrl/"; this.quality = getQualityFromName(source.resolutions)
             })
        }
        val id = streams.firstOrNull()?.id
        val format = streams.firstOrNull()?.format
        if (id != null) {
            val subUrl = "$apiUrl/wefeed-h5-bff/web/subject/caption?format=$format&id=$id&subjectId=$subjectId"
            app.get(subUrl, referer = validReferer, timeout = 15_000L).text.let { tryParseJson<MovieboxResponse>(it) }?.data?.captions?.forEach { sub ->
                subtitleCallback.invoke(newSubtitleFile(sub.lanName ?: "Unknown", sub.url ?: return@forEach))
            }
        }
        log("Success: Moviebox")
    }
    suspend fun invokeGomovies(
        title: String? = null, year: Int? = null, season: Int? = null, episode: Int? = null, callback: (ExtractorLink) -> Unit,
    ) {
        log("Start: Gomovies [$title S${season}E${episode}]")
        invokeGpress(title, year, season, episode, callback, Cinemax21Provider.gomoviesAPI, "Gomovies", base64Decode("X3NtUWFtQlFzRVRi"), base64Decode("X3NCV2NxYlRCTWFU"))
    }
    private suspend fun invokeGpress(
        title: String? = null, year: Int? = null, season: Int? = null, episode: Int? = null,
        callback: (ExtractorLink) -> Unit, api: String, name: String, mediaSelector: String, episodeSelector: String,
    ) {
        fun String.decrypt(key: String): List<GpressSources>? { return tryParseJson<List<GpressSources>>(base64Decode(this).xorDecrypt(key)) }
        val slug = getEpisodeSlug(season, episode)
        val query = if (season == null) title else "$title Season $season"
        var cookies = gomoviesCookies ?: run {
            val initRes = app.get(api, timeout = 15_000L)
            initRes.cookies.filter { it.key.contains("gomovies", ignoreCase = true) }.also { gomoviesCookies = it }
        }
        var res = app.get("$api/search/$query", cookies = cookies, timeout = 15_000L)
        cookies = gomoviesCookies ?: res.cookies.filter { it.key.contains("gomovies", ignoreCase = true) }.also { gomoviesCookies = it }
        val doc = res.document
        val media = doc.select("div.$mediaSelector").map { Triple(it.attr("data-filmName"), it.attr("data-year"), it.select("a").attr("href")) }
            .let { el -> if (el.size == 1) el.firstOrNull() else el.find { if (season == null) (it.first.equals(title, true) || it.first.equals("$title ($year)", true)) && it.second.equals("$year") else it.first.equals("$title - Season $season", true) } ?: el.find { it.first.contains("$title", true) && it.second.equals("$year") } } ?: return
        val iframe = if (season == null) media.third else app.get(fixUrl(media.third, api), timeout = 15_000L).document.selectFirst("div#$episodeSelector a:contains(Episode ${slug.second})")?.attr("href") ?: return
        res = app.get(fixUrl(iframe, api), cookies = cookies, timeout = 15_000L)
        val url = res.document.select("meta[property=og:url]").attr("content")
        val headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        val (serverId, episodeId) = if (season == null) url.substringAfterLast("/") to "0" else url.substringBeforeLast("/").substringAfterLast("/") to url.substringAfterLast("/").substringBefore("-")
        val serverRes = app.get("$api/user/servers/$serverId?ep=$episodeId", cookies = cookies, headers = headers, timeout = 15_000L)
        val script = getAndUnpack(serverRes.text)
        val key = KEY_REGEX.find(script)?.groupValues?.get(1) ?: return
        serverRes.document.select("ul li").amap { el ->
            val server = el.attr("data-value")
            val encryptedData = app.get("$url?server=$server&_=$unixTimeMS", cookies = cookies, referer = url, headers = headers, timeout = 15_000L).text
            encryptedData.decrypt(key)?.forEach { video ->
                intArrayOf(2160, 1440, 1080, 720, 480, 360).filter { it <= video.max.toInt() }.forEach {
                    callback.invoke(newExtractorLink(name, name, video.src.split("360", limit = 3).joinToString(it.toString()), ExtractorLinkType.VIDEO) { this.referer = "$api/"; this.quality = it })
                }
            }
        }
        log("Success: Gomovies")
    }
    
    
    suspend fun invokeVidsrccc(
        tmdbId: Int?, imdbId: String?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ) {
        log("Start: Vidsrccc [tmdb=$tmdbId S${season}E${episode}]")
        val url = if (season == null) "${Cinemax21Provider.vidsrcccAPI}/v2/embed/movie/$tmdbId" else "${Cinemax21Provider.vidsrcccAPI}/v2/embed/tv/$tmdbId/$season/$episode"
        val script = app.get(url, timeout = 15_000L).document.selectFirst("script:containsData(userId)")?.data() ?: return
        val userId = script.substringAfter("userId = \"").substringBefore("\";")
        val v = script.substringAfter("v = \"").substringBefore("\";")
        val vrf = VidsrcHelper.encryptAesCbc("$tmdbId", "secret_$userId")
        val serverUrl = if (season == null) "${Cinemax21Provider.vidsrcccAPI}/api/$tmdbId/servers?id=$tmdbId&type=movie&v=$v&vrf=$vrf&imdbId=$imdbId" else "${Cinemax21Provider.vidsrcccAPI}/api/$tmdbId/servers?id=$tmdbId&type=tv&v=$v&vrf=$vrf&imdbId=$imdbId&season=$season&episode=$episode"
        app.get(serverUrl, timeout = 15_000L).text.let { tryParseJson<VidsrcccResponse>(it) }?.data?.amap {
            val sources = app.get("${Cinemax21Provider.vidsrcccAPI}/api/source/${it.hash}", timeout = 15_000L).text.let { tryParseJson<VidsrcccResult>(it) }?.data ?: return@amap
            when {
                it.name.equals("VidPlay") -> {
                    callback.invoke(newExtractorLink("VidPlay", "VidPlay", sources.source ?: return@amap, ExtractorLinkType.M3U8) { this.referer = "${Cinemax21Provider.vidsrcccAPI}/" })
                    sources.subtitles?.map { subtitleCallback.invoke(newSubtitleFile(it.label ?: return@map, it.file ?: return@map)) }
                }
                it.name.equals("UpCloud") -> {
                    val scriptData = app.get(sources.source ?: return@amap, referer = "${Cinemax21Provider.vidsrcccAPI}/", timeout = 15_000L).document.selectFirst("script:containsData(source =)")?.data()
                    val iframe = SOURCE_REGEX.find(scriptData ?: return@amap)?.groupValues?.get(1)?.fixUrlBloat()
                    val iframeRes = app.get(iframe ?: return@amap, referer = "https://lucky.vidbox.site/", timeout = 15_000L).text
                    val id = iframe.substringAfterLast("/").substringBefore("?")
                    val key = KEY_48_REGEX.find(iframeRes)?.groupValues?.get(0) ?: return@amap
                    app.get("${iframe.substringBeforeLast("/")}/getSources?id=$id&_k=$key", headers = mapOf("X-Requested-With" to "XMLHttpRequest"), referer = iframe, timeout = 15_000L).text.let { tryParseJson<UpcloudResult>(it) }?.sources?.amap file@{ source ->
                        callback.invoke(newExtractorLink("UpCloud", "UpCloud", source.file ?: return@file, ExtractorLinkType.M3U8) { this.referer = "${Cinemax21Provider.vidsrcccAPI}/" })
                    }
                }
            }
        }
    }

    suspend fun invokeWatchsomuch(
        imdbId: String? = null, season: Int? = null, episode: Int? = null, subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        log("Start: Watchsomuch [imdb=$imdbId S${season}E${episode}]")
        val id = imdbId?.removePrefix("tt")
        val epsId = app.post("${Cinemax21Provider.watchSomuchAPI}/Watch/ajMovieTorrents.aspx", data = mapOf("index" to "0", "mid" to "$id", "wsk" to "30fb68aa-1c71-4b8c-b5d4-4ca9222cfb45", "lid" to "", "liu" to ""), headers = mapOf("X-Requested-With" to "XMLHttpRequest"), timeout = 15_000L).text.let { tryParseJson<WatchsomuchResponses>(it) }?.movie?.torrents?.let { eps -> if (season == null) eps.firstOrNull()?.id else eps.find { it.episode == episode && it.season == season }?.id } ?: return
        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
        val subUrl = if (season == null) "${Cinemax21Provider.watchSomuchAPI}/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part=" else "${Cinemax21Provider.watchSomuchAPI}/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part=S${seasonSlug}E${episodeSlug}"
        app.get(subUrl, timeout = 15_000L).text.let { tryParseJson<WatchsomuchSubResponses>(it) }?.subtitles?.map { sub -> subtitleCallback.invoke(newSubtitleFile(sub.label?.substringBefore("&nbsp")?.trim() ?: "", fixUrl(sub.url ?: return@map null, Cinemax21Provider.watchSomuchAPI))) }
        log("Success: Watchsomuch")
    }

    suspend fun invokeMapple(
        tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ) {
        log("Start: Mapple [tmdb=$tmdbId S${season}E${episode}]")
        val mediaType = if (season == null) "movie" else "tv"
        val url = if (season == null) "${Cinemax21Provider.mappleAPI}/watch/$mediaType/$tmdbId" else "${Cinemax21Provider.mappleAPI}/watch/$mediaType/$season-$episode/$tmdbId"
        val data = if (season == null) """[{"mediaId":$tmdbId,"mediaType":"$mediaType","tv_slug":"","source":"mapple","sessionId":"session_1760391974726_qym92bfxu"}]""" else """[{"mediaId":$tmdbId,"mediaType":"$mediaType","tv_slug":"$season-$episode","source":"mapple","sessionId":"session_1760391974726_qym92bfxu"}]"""
        val res = app.post(url, requestBody = data.toRequestBody(RequestBodyTypes.TEXT.toMediaTypeOrNull()), headers = mapOf("Next-Action" to "403f7ef15810cd565978d2ac5b7815bb0ff20258a5"), timeout = 15_000L).text
        val videoLink = tryParseJson<MappleSources>(res.substringAfter("1:").trim())?.data?.stream_url
        callback.invoke(newExtractorLink("Mapple", "Mapple", videoLink ?: return, ExtractorLinkType.M3U8) { this.referer = "${Cinemax21Provider.mappleAPI}/"; this.headers = mapOf("Accept" to "*/*") })
        val subRes = app.get("${Cinemax21Provider.mappleAPI}/api/subtitles?id=$tmdbId&mediaType=$mediaType${if (season == null) "" else "&season=$season&episode=$episode"}", referer = "${Cinemax21Provider.mappleAPI}/", timeout = 15_000L).text
        tryParseJson<ArrayList<MappleSubtitle>>(subRes)?.map { subtitle -> subtitleCallback.invoke(newSubtitleFile(subtitle.display ?: "", fixUrl(subtitle.url ?: return@map, Cinemax21Provider.mappleAPI))) }
        log("Success: Mapple")
    }
    suspend fun invokeVidlink(
        tmdbId: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit,
    ) {
        log("Start: Vidlink [tmdb=$tmdbId S${season}E${episode}]")
        val type = if (season == null) "movie" else "tv"
        val url = if (season == null) "${Cinemax21Provider.vidlinkAPI}/$type/$tmdbId" else "${Cinemax21Provider.vidlinkAPI}/$type/$tmdbId/$season/$episode"
        val videoLink = try {
            app.get(url, interceptor = WebViewResolver(Regex("""${Cinemax21Provider.vidlinkAPI}/api/b/$type/A{32}"""), timeout = 30_000L)).text.let { tryParseJson<VidlinkSources>(it) }?.stream?.playlist
        } catch (e: Exception) {
            log("Fail: Vidlink WebView - ${e.message}", error = true)
            null
        }
        videoLink?.let {
            callback.invoke(newExtractorLink("Vidlink", "Vidlink", it, ExtractorLinkType.M3U8) { this.referer = "${Cinemax21Provider.vidlinkAPI}/" })
            log("Success: Vidlink")
        } ?: log("Fail: Vidlink - No video link found", error = true)
    }

    suspend fun invokeVidfast(
        tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ) {
        log("Start: Vidfast [tmdb=$tmdbId S${season}E${episode}]")
        val module = "hezushon/1000076901076321/0b0ce221/cfe60245-021f-5d4d-bacb-0d469f83378f/uva/jeditawev/b0535941d898ebdb81f575b2cfd123f5d18c6464/y/APA91zAOxU2psY2_BvBqEmmjG6QvCoLjgoaI-xuoLxBYghvzgKAu-HtHNeQmwxNbHNpoVnCuX10eEes1lnTcI2l_lQApUiwfx2pza36CZB34X7VY0OCyNXtlq-bGVCkLslfNksi1k3B667BJycQ67wxc1OnfCc5PDPrF0BA8aZRyMXZ3-2yxVGp"
        val type = if (season == null) "movie" else "tv"
        val url = if (season == null) "${Cinemax21Provider.vidfastAPI}/$type/$tmdbId" else "${Cinemax21Provider.vidfastAPI}/$type/$tmdbId/$season/$episode"
        val res = try {
            app.get(url, interceptor = WebViewResolver(Regex("""${Cinemax21Provider.vidfastAPI}/$module/JEwECseLZdY"""), timeout = 30_000L)).text
        } catch (e: Exception) {
            log("Fail: Vidfast WebView - ${e.message}", error = true)
            return
        }
        tryParseJson<ArrayList<VidFastServers>>(res)?.filter { it.description?.contains("Original audio") == true }?.forEachIndexed { index, server ->
                try {
                    val source = app.get("${Cinemax21Provider.vidfastAPI}/$module/Sdoi/${server.data}", referer = "${Cinemax21Provider.vidfastAPI}/", timeout = 15_000L).text.let { tryParseJson<VidFastSources>(it) }
                    source?.url?.let { streamUrl ->
                        callback.invoke(newExtractorLink("Vidfast", "Vidfast [${server.name}]", streamUrl, INFER_TYPE))
                        if (index == 0) source.tracks?.forEach { subtitle -> subtitleCallback.invoke(newSubtitleFile(subtitle.label ?: return@forEach, subtitle.file ?: return@forEach)) }
                    }
                } catch (e: Exception) {
                    log("Fail: Vidfast source ${server.name} - ${e.message}", error = true)
                }
            }
        log("Success: Vidfast")
    }

    suspend fun invokeWyzie(
        tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        log("Start: Wyzie [tmdb=$tmdbId S${season}E${episode}]")
        val url = if (season == null) "${Cinemax21Provider.wyzieAPI}/search?id=$tmdbId" else "${Cinemax21Provider.wyzieAPI}/search?id=$tmdbId&season=$season&episode=$episode"
        val res = app.get(url, timeout = 15_000L).text
        tryParseJson<ArrayList<WyzieSubtitle>>(res)?.map { subtitle -> subtitleCallback.invoke(newSubtitleFile(subtitle.display ?: return@map, subtitle.url ?: return@map)) }
        log("Success: Wyzie")
    }

    suspend fun invokeVixsrc(
        tmdbId: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit,
    ) {
        log("Start: Vixsrc [tmdb=$tmdbId S${season}E${episode}]")
        val proxy = "https://proxy.heistotron.uk"
        val type = if (season == null) "movie" else "tv"
        val url = if (season == null) "${Cinemax21Provider.vixsrcAPI}/$type/$tmdbId" else "${Cinemax21Provider.vixsrcAPI}/$type/$tmdbId/$season/$episode"
        val res = app.get(url, timeout = 15_000L).document.selectFirst("script:containsData(window.masterPlaylist)")?.data() ?: return
        val video1 = VIDEO1_REGEX.find(res)?.let {
                    val (token, expires, path) = it.destructured
                    "$path?token=$token&expires=$expires&h=1&lang=en"
                } ?: return
        val video2 = "$proxy/p/${base64Encode("$proxy/api/proxy/m3u8?url=${encode(video1)}&source=sakura|ananananananananaBatman!".toByteArray())}"
        listOf(VixsrcSource("Vixsrc [Alpha]", video1, url), VixsrcSource("Vixsrc [Beta]", video2, "${Cinemax21Provider.mappleAPI}/")).map {
            callback.invoke(newExtractorLink(it.name, it.name, it.url, ExtractorLinkType.M3U8) { this.referer = it.referer; this.headers = mapOf("Accept" to "*/*") })
        }
        log("Success: Vixsrc")
    }

    suspend fun invokeSuperembed(
        tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, api: String = "https://streamingnow.mov"
    ) {
        log("Start: Superembed [tmdb=$tmdbId S${season}E${episode}]")
        val path = if (season == null) "" else "&s=$season&e=$episode"
        val token = app.get("${Cinemax21Provider.superembedAPI}/directstream.php?video_id=$tmdbId&tmdb=1$path", timeout = 15_000L).url.substringAfter("?play=")
        val (server, id) = app.post("$api/response.php", data = mapOf("token" to token), headers = mapOf("X-Requested-With" to "XMLHttpRequest"), timeout = 15_000L).document.select("ul.sources-list li:contains(vipstream-S)").let { it.attr("data-server") to it.attr("data-id") }
        val playUrl = "$api/playvideo.php?video_id=$id&server_id=$server&token=$token&init=1"
        val playRes = app.get(playUrl, timeout = 15_000L).document
        val iframe = playRes.selectFirst("iframe.source-frame")?.attr("src")
        val json = app.get(iframe ?: return, timeout = 15_000L).text.substringAfter("Playerjs(").substringBefore(");")
        val video = VIDEO_FILE_REGEX.find(json)?.groupValues?.get(1)
        callback.invoke(newExtractorLink("Superembed", "Superembed", video ?: return, INFER_TYPE) { this.headers = mapOf("Accept" to "*/*") })
        SUBTITLE_FILE_REGEX.find(json)?.groupValues?.get(1)?.split(",")?.map {
            val (subLang, subUrl) = SUB_LANG_URL_REGEX.find(it)?.destructured ?: return@map
            subtitleCallback.invoke(newSubtitleFile(subLang.trim(), subUrl.trim()))
        }
        log("Success: Superembed")
    }

    suspend fun invokeVidrock(
        tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, subAPI: String = "https://sub.vdrk.site"
    ) {
        log("Start: Vidrock [tmdb=$tmdbId S${season}E${episode}]")
        val type = if (season == null) "movie" else "tv"
        val url = "${Cinemax21Provider.vidrockAPI}/$type/$tmdbId${if (type == "movie") "" else "/$season/$episode"}"
        val encryptData = VidrockHelper.encrypt(tmdbId, type, season, episode)
        app.get("${Cinemax21Provider.vidrockAPI}/api/$type/$encryptData", referer = url, timeout = 15_000L).text.let { tryParseJson<LinkedHashMap<String, HashMap<String, String>>>(it) }?.forEach { source ->
                val sourceUrl = source.value["url"] ?: return@forEach
                if (source.key == "source2") {
                    val json = app.get(sourceUrl, referer = "${Cinemax21Provider.vidrockAPI}/", timeout = 15_000L).text
                    tryParseJson<ArrayList<VidrockSource>>(json)?.reversed()?.forEach { it ->
                        val streamUrl = it.url ?: return@forEach
                        callback.invoke(newExtractorLink("Vidrock", "Vidrock [Source2 ${it.resolution ?: ""}]", streamUrl, INFER_TYPE) { this.quality = it.resolution ?: Qualities.Unknown.value; this.headers = mapOf("Range" to "bytes=0-", "Referer" to "${Cinemax21Provider.vidrockAPI}/") })
                    }
                } else {
                    parseMasterPlaylistAndExtractVariants(sourceUrl, source.key.capitalize(), callback)
                }
            }
        val subUrl = "$subAPI/$type/$tmdbId${if (type == "movie") "" else "/$season/$episode"}"
        val res = app.get(subUrl, timeout = 15_000L).text
        tryParseJson<ArrayList<VidrockSubtitle>>(res)?.forEach { subtitle ->
            subtitleCallback.invoke(newSubtitleFile(subtitle.label?.replace(DIGIT_CLEAN_REGEX, "")?.replace(HI_CLEAN_REGEX, "")?.trim() ?: return@forEach, subtitle.file ?: return@forEach))
        }
        log("Success: Vidrock")
    }

    private suspend fun parseMasterPlaylistAndExtractVariants(
        masterUrl: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val masterPlaylist = app.get(masterUrl, headers = mapOf(
                "Origin" to Cinemax21Provider.vidrockAPI,
                "Referer" to "${Cinemax21Provider.vidrockAPI}/"
            ), timeout = 15_000L).text
            val variantUrls = mutableListOf<Pair<String, String>>()
            var currentQuality = ""
            for (line in masterPlaylist.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                    val resolutionMatch = RESOLUTION_MATCH_REGEX.find(trimmed)
                    currentQuality = when {
                        resolutionMatch != null -> {
                            val height = resolutionMatch.groupValues[1].substringAfter("x").toIntOrNull() ?: 0
                            when {
                                height >= 1080 -> "1080p"
                                height >= 720 -> "720p"
                                height >= 480 -> "480p"
                                else -> "360p"
                            }
                        }
                        trimmed.contains("BANDWIDTH") -> {
                            val bwMatch = BANDWIDTH_MATCH_REGEX.find(trimmed)
                            val bw = bwMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0
                            when {
                                bw >= 5000000 -> "1080p"
                                bw >= 2500000 -> "720p"
                                bw >= 1000000 -> "480p"
                                else -> "360p"
                            }
                        }
                        else -> "Unknown"
                    }
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val variantUrl = if (trimmed.startsWith("http")) trimmed else masterUrl.substringBeforeLast("/") + "/" + trimmed
                    variantUrls.add(currentQuality to variantUrl)
                    currentQuality = ""
                }
            }
            if (variantUrls.isNotEmpty()) {
                variantUrls.forEach { (quality, variantUrl) ->
                    val qualityInt = when (quality) {
                        "1080p" -> Qualities.P1080.value
                        "720p" -> Qualities.P720.value
                        "480p" -> Qualities.P480.value
                        "360p" -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    callback.invoke(newExtractorLink("Vidrock", "Vidrock [$sourceName $quality]", variantUrl, ExtractorLinkType.M3U8) {
                        this.referer = "${Cinemax21Provider.vidrockAPI}/"
                        this.headers = mapOf("Origin" to Cinemax21Provider.vidrockAPI)
                        this.quality = qualityInt
                    })
                }
            } else {
                callback.invoke(newExtractorLink("Vidrock", "Vidrock [$sourceName]", masterUrl, ExtractorLinkType.M3U8) {
                    this.referer = "${Cinemax21Provider.vidrockAPI}/"
                    this.headers = mapOf("Origin" to Cinemax21Provider.vidrockAPI)
                })
            }
        } catch (e: Exception) {
            log("Fail: Vidrock variant parsing for $sourceName - ${e.message}", error = true)
            callback.invoke(newExtractorLink("Vidrock", "Vidrock [$sourceName]", masterUrl, ExtractorLinkType.M3U8) {
                this.referer = "${Cinemax21Provider.vidrockAPI}/"
                this.headers = mapOf("Origin" to Cinemax21Provider.vidrockAPI)
            })
        }
    }
   
    suspend fun invokeCinemaOS(
        imdbId: String? = null, tmdbId: Int? = null, title: String? = null, season: Int? = null, episode: Int? = null, year: Int? = null,
        callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        log("Start: CinemaOS [$title S${season}E${episode}]")
        val sourceHeaders = mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to cinemaOSApi,
            "Origin" to cinemaOSApi,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
            "sec-ch-ua" to "\"Google Chrome\";v=\"123\", \"Not:A-Brand\";v=\"8\", \"Chromium\";v=\"123\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "Content-Type" to "application/json"
        )
        val fixTitle = title?.replace(" ", "+")
        val cinemaOsSecretKeyRequest = CinemaOsSecretKeyRequest(tmdbId = tmdbId.toString(), seasonId = season?.toString() ?: "", episodeId = episode?.toString() ?: "")
        val secretHash = cinemaOSGenerateHash(cinemaOsSecretKeyRequest, season != null)
        val type = if (season == null) "movie" else "tv"
        val sourceUrl = if (season == null) "$cinemaOSApi/api/fuckit?type=$type&tmdbId=$tmdbId&imdbId=$imdbId&t=$fixTitle&ry=$year&secret=$secretHash" else "$cinemaOSApi/api/fuckit?type=$type&tmdbId=$tmdbId&imdbId=$imdbId&seasonId=$season&episodeId=$episode&t=$fixTitle&ry=$year&secret=$secretHash"

        try {
            val sourceResponse = app.get(sourceUrl, headers = sourceHeaders, timeout = 60).text.let { tryParseJson<CinemaOSReponse>(it) }
            val decryptedJson = cinemaOSDecryptResponse(sourceResponse?.data)
            val json = parseCinemaOSSources(decryptedJson.toString())
            val blockedServers = listOf("Maphisto", "Noah", "Bolt", "Zeus", "Nexus", "Apollo", "Kratos", "Flick", "Hollywood", "Flash", "Ophim", "Bollywood", "Apex", "Universe", "Hindi", "Bengali", "Tamil", "Telugu")
            val finalBlocked = blockedServers.filter { !it.equals("Rizz", ignoreCase = true) }
            val sortedSources = json.filter { val serverName = it["server"] ?: ""; val isBlocked = finalBlocked.any { blocked -> serverName.contains(blocked, ignoreCase = true) }; !isBlocked }.sortedByDescending { (it["server"] ?: "").contains("Rizz", ignoreCase = true) }

            sortedSources.forEach {
                val extractorLinkType = when { it["type"]?.contains("hls", true) == true -> ExtractorLinkType.M3U8; it["type"]?.contains("dash", true) == true -> ExtractorLinkType.DASH; it["type"]?.contains("mp4", true) == true -> ExtractorLinkType.VIDEO; else -> INFER_TYPE }
                val quality = if (it["quality"]?.isNotEmpty() == true && it["quality"]?.toIntOrNull() != null) getQualityFromName(it["quality"]) else Qualities.P1080.value
                callback.invoke(newExtractorLink("CinemaOS [${it["server"]}]", "CinemaOS [${it["server"]}] ${it["bitrate"]}", url = it["url"].toString(), type = extractorLinkType) { this.headers = mapOf("Referer" to cinemaOSApi); this.quality = quality })
            }
            log("Success: CinemaOS")
        } catch (e: Exception) { log("Fail: CinemaOS - ${e.message}", error = true) }
    }

    suspend fun invokePlayer4U(
        title: String? = null, season: Int? = null, episode: Int? = null, year: Int? = null, callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        log("Start: Player4U [$title S${season}E${episode}]")
        val queryWithEpisode = season?.let { "$title S${"%02d".format(it)}E${"%02d".format(episode)}" }
        val baseQuery = queryWithEpisode ?: title.orEmpty()
        val encodedQuery = baseQuery.replace(" ", "+")
        val pageRange = 0..4
        val deferredPages = pageRange.map { page -> async { val url = "$Player4uApi/embed?key=$encodedQuery" + if (page > 0) "&page=$page" else ""; runCatching { app.get(url, timeout = 20).document }.getOrNull()?.let { doc -> extractPlayer4uLinks(doc, season, episode, title.toString(), year) } ?: emptyList() } }
        val allLinks = deferredPages.awaitAll().flatten().toMutableSet()
        if (allLinks.isEmpty() && season == null) { val fallbackUrl = "$Player4uApi/embed?key=${title?.replace(" ", "+")}"; val fallbackDoc = runCatching { app.get(fallbackUrl, timeout = 20).document }.getOrNull(); if (fallbackDoc != null) allLinks += extractPlayer4uLinks(fallbackDoc, season, episode, title.toString(), year) }
        allLinks.distinctBy { it.name }.map { link -> async { try { val namePart = link.name.split("|").lastOrNull()?.trim().orEmpty(); val displayName = buildString { append("Player4U"); if (namePart.isNotEmpty()) append(" {$namePart}") }; val qualityMatch = PLAYER4U_QUALITY_REGEX.find(displayName)?.value?.uppercase() ?: "UNKNOWN"; val quality = getPlayer4UQuality(qualityMatch); val subPath = GO_SUB_PATH_REGEX.find(link.url)?.groupValues?.get(1) ?: return@async null; val iframeSrc = runCatching { app.get("$Player4uApi$subPath", timeout = 10, referer = Player4uApi).document.selectFirst("iframe")?.attr("src") }.getOrNull() ?: return@async null; getPlayer4uUrl(displayName, quality, "https://uqloads.xyz/e/$iframeSrc", Player4uApi, callback) } catch (_: Exception) { null } } }.awaitAll()
        log("Success: Player4U")
    }

    private fun extractPlayer4uLinks(document: Document, season:Int?, episode:Int?, title:String, year:Int?): List<Player4uLinkData> {
        return document.select(".playbtnx").mapNotNull { element ->
            val titleText = element.text().split(" | ").lastOrNull() ?: return@mapNotNull null
            if (season == null && episode == null) { if (year != null && (titleText.startsWith("$title $year", ignoreCase = true) || titleText.startsWith("$title ($year)", ignoreCase = true))) Player4uLinkData(name = titleText, url = element.attr("onclick")) else null } else { if (season != null && episode != null && titleText.startsWith("$title S${"%02d".format(season)}E${"%02d".format(episode)}", ignoreCase = true)) Player4uLinkData(name = titleText, url = element.attr("onclick")) else null }
        }
    }

    suspend fun invokeRiveStream(id: Int? = null, season: Int? = null, episode: Int? = null, callback: (ExtractorLink) -> Unit) {
        log("Start: RiveStream [id=$id S${season}E${episode}]")
        val headers = mapOf("User-Agent" to USER_AGENT)
        suspend fun <T> retry(times: Int = 3, block: suspend () -> T): T? { repeat(times - 1) { try { return block() } catch (_: Exception) {} }; return try { block() } catch (_: Exception) { null } }
        val sourceApiUrl = "$RiveStreamAPI/api/backendfetch?requestID=VideoProviderServices&secretKey=rive"
        val sourceList = retry { app.get(sourceApiUrl, headers, timeout = 15_000L).text.let { tryParseJson<RiveStreamSource>(it) } }
        val document = retry { app.get(RiveStreamAPI, headers, timeout = 20).document } ?: return
        val appScript = document.select("script").firstOrNull { it.attr("src").contains("_app") }?.attr("src") ?: return
        val js = retry { app.get("$RiveStreamAPI$appScript", timeout = 15_000L).text } ?: return
        val keyList = LET_C_KEYLIST_REGEX.findAll(js).firstOrNull { it.groupValues[1].length > 2 }?.groupValues?.get(1)?.let { array -> QUOTE_STRING_REGEX.findAll(array).map { it.groupValues[1] }.toList() } ?: emptyList()
        val secretKey = retry { app.get("https://rivestream.supe2372.workers.dev/?input=$id&cList=${keyList.joinToString(",")}", timeout = 15_000L).text } ?: return
        sourceList?.data?.forEach { source -> try { val streamUrl = if (season == null) "$RiveStreamAPI/api/backendfetch?requestID=movieVideoProvider&id=$id&service=$source&secretKey=$secretKey" else "$RiveStreamAPI/api/backendfetch?requestID=tvVideoProvider&id=$id&season=$season&episode=$episode&service=$source&secretKey=$secretKey"; val responseString = retry { app.get(streamUrl, headers, timeout = 10).text } ?: return@forEach; try { val riveData = tryParseJson<RiveStreamResponse>(responseString); val sources = riveData?.data?.sources ?: return@forEach; for (src in sources) { val label = if(src.source.contains("AsiaCloud",ignoreCase = true)) "RiveStream ${src.source}[${src.quality}]" else "RiveStream ${src.source}"; val quality = Qualities.P1080.value; val url = src.url; try { if (url.contains("proxy?url=")) { try { val fullyDecoded = URLDecoder.decode(url, "UTF-8"); val encodedUrl = fullyDecoded.substringAfter("proxy?url=").substringBefore("&headers="); val decodedUrl = URLDecoder.decode(encodedUrl, "UTF-8"); val encodedHeaders = fullyDecoded.substringAfter("&headers="); val headersMap = try { URLDecoder.decode(encodedHeaders, "UTF-8").let { tryParseJson<Map<String, String>>(it) } } catch (e: Exception) { null } ?: emptyMap(); val referer = headersMap["Referer"] ?: ""; val origin = headersMap["Origin"] ?: ""; val videoHeaders = mapOf("Referer" to referer, "Origin" to origin); val type = if (decodedUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else INFER_TYPE; callback.invoke(newExtractorLink(label, label, decodedUrl, type) { this.quality = quality; this.referer = referer; this.headers = videoHeaders }) } catch (e: Exception) {} } else { val type = if (url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else INFER_TYPE; callback.invoke(newExtractorLink("$label (VLC)", "$label (VLC)", url, type) { this.referer = ""; this.quality = quality }) } } catch (e: Exception) {} } } catch (e: Exception) {} } catch (e: Exception) {} }
        log("Success: RiveStream")
    }

    suspend fun invokeMoviebox2(
        title: String, year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        log("Start: Moviebox2 [$title S${season}E${episode}]")
        val apiUrl = "https://api.inmoviebox.com"
        val searchUrl = "$apiUrl/wefeed-mobile-bff/subject-api/search/v2"
        val jsonBody = """{"page": 1, "perPage": 10, "keyword": "$title"}"""
        val headersSearch = Moviebox2Helper.getHeaders(searchUrl, jsonBody)
        val searchRes = app.post(searchUrl, headers = headersSearch, requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull()), timeout = 15_000L).text.let { tryParseJson<Moviebox2SearchResponse>(it) }

        val matchedSubject = searchRes?.data?.results?.flatMap { it.subjects ?: arrayListOf() }?.find { subject ->
            val subjectYear = subject.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
            val isTitleMatch = subject.title?.contains(title, true) == true
            val isYearMatch = year == null || subjectYear == year
            val isTypeMatch = if (season != null) subject.subjectType == 2 else (subject.subjectType == 1 || subject.subjectType == 3)
            isTitleMatch && isYearMatch && isTypeMatch
        } ?: return

        val mainSubjectId = matchedSubject.subjectId ?: return
        
        val detailUrl = "$apiUrl/wefeed-mobile-bff/subject-api/get?subjectId=$mainSubjectId"
        val detailHeaders = Moviebox2Helper.getHeaders(detailUrl, null, "GET")
        val detailRes = app.get(detailUrl, headers = detailHeaders, timeout = 15_000L).text
        
        val subjectList = mutableListOf<Pair<String, String>>()
        subjectList.add(mainSubjectId to "Original Audio")
        try {
            val moviebox2Data = tryParseJson<Moviebox2DetailResponse>(detailRes)
            moviebox2Data?.data?.dubs?.forEach { dub ->
                val dubId = dub.subjectId
                val dubName = dub.lanName ?: "Dub"
                if (!dubId.isNullOrEmpty() && dubId != mainSubjectId) {
                    subjectList.add(dubId to dubName)
                }
            }
        } catch (e: Exception) { }

        val s = season ?: 0
        val e = episode ?: 0

        subjectList.forEach { (currentSubjectId, languageName) ->
            val playUrl = "$apiUrl/wefeed-mobile-bff/subject-api/play-info?subjectId=$currentSubjectId&se=$s&ep=$e"
            val headersPlay = Moviebox2Helper.getHeaders(playUrl, null, "GET")
            val playRes = app.get(playUrl, headers = headersPlay, timeout = 15_000L).text.let { tryParseJson<Moviebox2PlayResponse>(it) }
            val streams = playRes?.data?.streams ?: return@forEach

            streams.forEach { stream ->
                val streamUrl = stream.url ?: return@forEach
                val quality = getQualityFromName(stream.resolutions)
                val signCookie = stream.signCookie
                val baseHeaders = Moviebox2Helper.getHeaders(streamUrl, null, "GET").toMutableMap()
                if (!signCookie.isNullOrEmpty()) baseHeaders["Cookie"] = signCookie

                val sourceName = "Moviebox2 ($languageName)"
                
                callback.invoke(newExtractorLink(sourceName, sourceName, streamUrl, if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE) {
                    this.quality = quality; this.headers = baseHeaders
                })

                if (stream.id != null) {
                    val subUrlInternal = "$apiUrl/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$currentSubjectId&streamId=${stream.id}"
                    val headersSubInternal = Moviebox2Helper.getHeaders(subUrlInternal, null, "GET")
                    app.get(subUrlInternal, headers = headersSubInternal, timeout = 15_000L).text.let { tryParseJson<Moviebox2SubtitleResponse>(it) }?.data?.extCaptions?.forEach { cap ->
                        val lang = cap.language ?: cap.lanName ?: cap.lan ?: "Unknown"
                        val capUrl = cap.url ?: return@forEach
                        subtitleCallback.invoke(newSubtitleFile(lang, capUrl))
                    }
                    
                    val subUrlExternal = "$apiUrl/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$currentSubjectId&resourceId=${stream.id}&episode=0"
                    
                    val subHeaders = Moviebox2Helper.getHeaders(subUrlExternal, null, "GET").toMutableMap()
                    
                    app.get(subUrlExternal, headers = subHeaders, timeout = 15_000L).text.let { tryParseJson<Moviebox2SubtitleResponse>(it) }?.data?.extCaptions?.forEach { cap ->
                        val lang = cap.lan ?: cap.lanName ?: cap.language ?: "Unknown"
                        val capUrl = cap.url ?: return@forEach
                        subtitleCallback.invoke(newSubtitleFile(lang, capUrl))
                    }
                }
            }
        }
        log("Success: Moviebox2")
    }

    suspend fun invokeVidsrccx(
        tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        log("Start: Vidsrccx [tmdb=$tmdbId S${season}E${episode}]")
        try {
            val type = if (season == null) "movie" else "tv"
            val url = if (season == null) "${Cinemax21Provider.vidsrccxAPI}/embed/movie/$tmdbId"
                      else "${Cinemax21Provider.vidsrccxAPI}/embed/tv/$tmdbId/$season/$episode"
            loadExtractor(url, url, subtitleCallback, callback)
            log("Success: Vidsrccx")
        } catch (e: Exception) { log("Fail: Vidsrccx - ${e.message}", error = true) }
    }

    suspend fun invoke2embed(
        tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        log("Start: 2embed [tmdb=$tmdbId S${season}E${episode}]")
        try {
            val type = if (season == null) "movie" else "tv"
            val url = if (season == null) "https://www.2embed.cc/embed/$type/$tmdbId"
                      else "https://www.2embed.cc/embed/$type/$tmdbId/$season/$episode"
            loadExtractor(url, url, subtitleCallback, callback)
            log("Success: 2embed")
        } catch (e: Exception) { log("Fail: 2embed - ${e.message}", error = true) }
    }

    suspend fun invokeEzvid(
        tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        log("Start: Ezvid [tmdb=$tmdbId S${season}E${episode}]")
        try {
            val type = if (season == null) "movie" else "tv"
            val url = if (season == null) "https://ezvidapi.com/embed/$type/$tmdbId"
                      else "https://ezvidapi.com/embed/$type/$tmdbId/$season/$episode"
            val res = app.get(url, timeout = 15_000L).text
            val parsed = tryParseJson<Map<String, Any>>(res)
            val sources = parsed?.get("sources") as? List<Map<String, Any>>
            if (sources != null) {
                sources.forEach { source ->
                    val videoUrl = source["url"] as? String ?: return@forEach
                    val quality = source["quality"] as? String ?: "Unknown"
                    callback.invoke(newExtractorLink("Ezvid", "Ezvid [$quality]", videoUrl, INFER_TYPE) {
                        this.referer = "https://ezvidapi.com/"
                    })
                }
            } else {
                loadExtractor(url, "https://ezvidapi.com/", subtitleCallback, callback)
            }
            log("Success: Ezvid")
        } catch (e: Exception) { log("Fail: Ezvid - ${e.message}", error = true) }
    }

    suspend fun invokeAutoEmbed(
        tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        log("Start: AutoEmbed [tmdb=$tmdbId S${season}E${episode}]")
        try {
            val type = if (season == null) "movie" else "tv"
            val url = if (season == null) "https://autoembed.cc/embed/$type/$tmdbId"
                      else "https://autoembed.cc/embed/$type/$tmdbId?s=$season&e=$episode"
            loadExtractor(url, url, subtitleCallback, callback)
            log("Success: AutoEmbed")
        } catch (e: Exception) { log("Fail: AutoEmbed - ${e.message}", error = true) }
    }

    private object Moviebox2Helper {
        private val secretKeyDefault = base64Decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==")
        fun getHeaders(url: String, body: String? = null, method: String = "POST"): Map<String, String> {
            val timestamp = System.currentTimeMillis()
            val xClientToken = generateXClientToken(timestamp)
            val xTrSignature = generateXTrSignature(method, "application/json", if(method=="POST") "application/json; charset=utf-8" else "application/json", url, body, timestamp)
            return mapOf(
                "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; sdk_gphone64_x86_64; Build/BP22.250325.006; Cronet/133.0.6876.3)",
                "accept" to "application/json", "content-type" to "application/json", "x-client-token" to xClientToken, "x-tr-signature" to xTrSignature,
                "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"da2b99c821e6ea023e4be55b54d5f7d8","install_store":"ps","gaid":"d7578036d13336cc","brand":"google","model":"sdk_gphone64_x86_64","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
                "x-client-status" to "0"
            )
        }
        private fun md5(input: ByteArray): String { return MessageDigest.getInstance("MD5").digest(input).joinToString("") { "%02x".format(it) } }
        private fun generateXClientToken(timestamp: Long): String { val tsStr = timestamp.toString(); val reversed = tsStr.reversed(); val hash = md5(reversed.toByteArray()); return "$tsStr,$hash" }
        private fun generateXTrSignature(method: String, accept: String?, contentType: String?, url: String, body: String?, timestamp: Long): String {
            val parsed = Uri.parse(url); val path = parsed.path ?: ""; val query = if (parsed.queryParameterNames.isNotEmpty()) { parsed.queryParameterNames.sorted().joinToString("&") { key -> parsed.getQueryParameters(key).joinToString("&") { "$key=$it" } } } else ""
            val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path; val bodyBytes = body?.toByteArray(Charsets.UTF_8); val bodyHash = if (bodyBytes != null) md5(if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes) else ""; val bodyLength = bodyBytes?.size?.toString() ?: ""
            val canonical = "${method.uppercase()}\n${accept ?: ""}\n${contentType ?: ""}\n$bodyLength\n$timestamp\n$bodyHash\n$canonicalUrl"
            val secretBytes = base64DecodeArray(secretKeyDefault); val mac = Mac.getInstance("HmacMD5"); mac.init(SecretKeySpec(secretBytes, "HmacMD5")); val signature = base64Encode(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))
            return "$timestamp|2|$signature"
        }
        private fun base64DecodeArray(str: String): ByteArray { return android.util.Base64.decode(str, android.util.Base64.DEFAULT) }
    }
}
