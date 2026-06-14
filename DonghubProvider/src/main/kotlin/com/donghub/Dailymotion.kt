package com.donghub

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8

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
        } catch (e: Exception) {
            throw ErrorLoadingException("Failed to fetch Dailymotion metadata: ${e.message}")
        }

        val metadata = tryParseJson<DmMetadata>(response)
        val qualities = metadata?.qualities
            ?: throw ErrorLoadingException("No qualities found in Dailymotion metadata")

        var linkFound = false

        for ((qualityLabel, sources) in qualities) {
            val source = sources.firstOrNull { it.url != null } ?: continue
            val manifestUrl = source.url ?: continue

            try {
                generateM3u8(this.name, manifestUrl, qualityLabel).forEach {
                    callback(it)
                    linkFound = true
                }
            } catch (_: Exception) {}
        }

        if (!linkFound) {
            val m3u8Urls = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(response)
                .map { it.value.replace("\\u002F", "/") }
                .distinct()

            for (m3u8Url in m3u8Urls) {
                try {
                    generateM3u8(this.name, m3u8Url, "auto").forEach {
                        callback(it)
                        linkFound = true
                    }
                } catch (_: Exception) {}
            }
        }

        if (!linkFound) {
            throw ErrorLoadingException("No playable streams found")
        }

        extractSubtitles(response, subtitleCallback)
    }

    private suspend fun extractSubtitles(
        response: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val subtitlesObjRegex = Regex(""""subtitles"\s*:\s*\{[^{}]*"data"\s*:\s*\{([^}]+)\}[^{}]*\}""")
        subtitlesObjRegex.find(response)?.let { match ->
            val dataBlock = match.groupValues[1]
            val langRegex = Regex(""""([^"]+)"\s*:\s*\{[^}]*"urls"\s*:\s*\[([^\]]+)\]""")
            langRegex.findAll(dataBlock).forEach { langMatch ->
                val langCode = langMatch.groupValues[1]
                val urlsBlock = langMatch.groupValues[2]
                val urlRegex = Regex(""""([^"]+)"""")
                val url = urlRegex.find(urlsBlock)?.groupValues?.get(1) ?: return@forEach
                val label = langCode.replace("-auto", " (auto)")
                subtitleCallback(newSubtitleFile(label, url))
            }
        }
    }

    private fun getEmbedUrl(url: String): String? {
        if (url.contains("/embed/") || url.contains("/video/")) return url
        if (url.contains("geo.dailymotion.com")) {
            val videoId = url.substringAfter("video=").substringBefore("&")
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
}
