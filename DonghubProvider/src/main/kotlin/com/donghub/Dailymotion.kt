package com.donghub

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.newExtractorLink

class Geodailymotion : Dailymotion() {
    override val name = "GeoDailymotion"
    override val mainUrl = "https://geo.dailymotion.com"
}

open class Dailymotion : ExtractorApi() {
    override val mainUrl = "https://www.dailymotion.com"
    override val name = "Dailymotion"
    override val requiresReferer = false
    private val baseUrl = "https://www.dailymotion.com"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = getEmbedUrl(url) ?: return
        val id = getVideoId(embedUrl) ?: return
        val metaDataUrl = "$baseUrl/player/metadata/video/$id"

        val response = try {
            app.get(metaDataUrl, referer = embedUrl).text
        } catch (_: Exception) {
            throw ErrorLoadingException("Failed to fetch Dailymotion metadata")
        }

        val metadata = tryParseJson<DmMetadata>(response)
        val qualities = metadata?.qualities
            ?: throw ErrorLoadingException("No qualities found in Dailymotion metadata")

        for ((qualityLabel, sources) in qualities) {
            val source = sources.firstOrNull { it.url != null } ?: continue
            val manifestUrl = source.url ?: continue

            try {
                val manifestResponse = app.get(manifestUrl, referer = embedUrl).text

                if (manifestResponse.contains("#EXT-X-STREAM-INF") || manifestResponse.contains("#EXTINF:")) {
                    generateM3u8(this.name, manifestUrl, qualityLabel).forEach(callback)
                } else {
                    val directUrls = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(manifestResponse)
                        .map { it.value }
                        .distinct()

                    if (directUrls.count() > 0) {
                        directUrls.forEach { nestedUrl ->
                            generateM3u8(this.name, nestedUrl, qualityLabel).forEach(callback)
                        }
                    } else {
                        val mp4Urls = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").findAll(manifestResponse)
                            .map { it.value }
                            .distinct()

                        mp4Urls.forEach { mp4Url ->
                            callback(newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = mp4Url,
                                type = INFER_TYPE
                            ) {
                                this.referer = embedUrl
                                this.quality = com.lagradost.cloudstream3.utils.getQualityFromName(qualityLabel)
                            })
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        extractSubtitles(response, subtitleCallback)
    }

    private suspend fun extractSubtitles(
        response: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val subtitlesObjRegex = Regex(""""subtitles"\s*:\s*(\{(?:[^{}]|"[^"]*")*?\})""")
        subtitlesObjRegex.find(response)?.let { match ->
            val subtitlesJson = match.groupValues[1]
                .replace(Regex(""""urls"\s*:\s*\["[^"]*"\]""")) { urlsMatch ->
                    val url = Regex("""\["([^"]+)"\]""").find(urlsMatch.value)?.groupValues?.get(1)
                        ?: return@replace urlsMatch.value
                    """"urls":[{"url":"$url"}]"""
                }
                .replace(Regex(""""urls"\s*:\s*\[([^\]]+)\]""")) { arrMatch ->
                    val items = arrMatch.groupValues[1].split(",").map { it.trim().trim('"') }
                    val objects = items.joinToString(",") { """{"url":"$it"}""" }
                    """"urls":[$objects]"""
                }
            val subsData = tryParseJson<Map<String, List<DmSubtitleEntry>>>(subtitlesJson)
            subsData?.forEach { (_, entries) ->
                entries.forEach { entry ->
                    entry.urls?.firstOrNull()?.url?.let { url ->
                        entry.label?.let { label ->
                            subtitleCallback(newSubtitleFile(label, url))
                        }
                    }
                }
            }
        }
    }

    private fun getEmbedUrl(url: String): String? {
        if (url.contains("/embed/") || url.contains("/video/")) return url
        if (url.contains("geo.dailymotion.com")) {
            val videoId = url.substringAfter("video=")
            return "$baseUrl/embed/video/$videoId"
        }
        return null
    }

    private fun getVideoId(url: String): String? {
        val match = Regex("/video/([kx][a-zA-Z0-9]+)").find(url) ?: return null
        return match.groupValues[1].ifEmpty { null }
    }

    data class DmMetadata(
        val qualities: Map<String, List<DmQuality>>? = null,
        val subtitles: Any? = null
    )

    data class DmQuality(
        val url: String? = null,
        val type: String? = null,
        val size: Int? = null
    )

    data class DmSubtitleEntry(
        val label: String? = null,
        val urls: List<DmSubUrl>? = null,
    )

    data class DmSubUrl(
        val url: String? = null,
    )
}
