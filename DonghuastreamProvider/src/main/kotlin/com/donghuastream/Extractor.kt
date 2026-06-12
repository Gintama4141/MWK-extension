package com.donghuastream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlin.Triple
import com.donghuastream.Source
import com.donghuastream.Track

open class Vtbe : ExtractorApi() {
    override var name = "Vtbe"
    override var mainUrl = "https://vtbe.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url, referer = mainUrl).document
        val extractedpack = response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().orEmpty()
        val unPacked = JsUnpacker(extractedpack).unpack() ?: return null
        val link = Regex("""sources:\[\{file:"(.*?)"""").find(unPacked)?.groupValues?.get(1) ?: return null
        return listOf(
            newExtractorLink(this.name, this.name, url = link, ExtractorLinkType.M3U8) {
                this.referer = referer.orEmpty()
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

class wishfast : StreamWishExtractor() {
    override var mainUrl = "https://wishfast.top"
    override var name = "StreamWish"
}

class waaw : StreamSB() {
    override var mainUrl = "https://waaw.to"
}

class FileMoonSx : Filesim() {
    override val mainUrl = "https://filemoon.sx"
    override val name = "FileMoonSx"
}

class Okru : ExtractorApi() {
    override var name = "Okru"
    override var mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = "$mainUrl/").document
        val playerInit = document.selectFirst("data[name=flashvars]")?.attr("value") ?: return
        val metadata = tryParseJson<OkruMetadata>(base64Decode(playerInit))
        val m3u8 = metadata?.metadata?.hlsMasterUrl ?: metadata?.metadata?.hlsTrailerUrl
        if (!m3u8.isNullOrBlank()) {
            M3u8Helper.generateM3u8(name, m3u8, referer.orEmpty()).forEach(callback)
        }
        metadata?.metadata?.subtitles?.forEach { sub ->
            subtitleCallback.invoke(newSubtitleFile(sub.label ?: "Unknown", sub.url))
        }
    }

    data class OkruMetadata(val metadata: OkruVideoData?)
    data class OkruVideoData(
        val hlsMasterUrl: String?,
        val hlsTrailerUrl: String?,
        val subtitles: List<OkruSubtitle>?
    )
    data class OkruSubtitle(val label: String?, val url: String)
}

open class Ultrahd : ExtractorApi() {
    override var name = "Ultrahd Streamplay"
    override var mainUrl = "https://ultrahd.streamplay.co.in"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = mainUrl)
        val html = response.text
        val ajaxUrl = Regex("""\$\s*\.\s*ajax\(\s*\{\s*url:\s*"(.*?)"""").find(html)?.groupValues?.get(1) ?: return
        val root = app.get(ajaxUrl).text.let { tryParseJson<Root>(it) } ?: return
        root.sources.forEach { source ->
            val m3u8 = httpsify(source.file)
            if (m3u8.contains(".mp4")) {
                callback.invoke(
                    newExtractorLink("Ultrahd Streamplay", "Ultrahd Streamplay", url = m3u8, INFER_TYPE) {
                        this.referer = ""
                        this.quality = getQualityFromName("")
                    }
                )
            } else {
                M3u8Helper.generateM3u8(this.name, m3u8, referer.orEmpty()).forEach(callback)
            }
        }
        root.tracks.forEach { track ->
            subtitleCallback.invoke(newSubtitleFile(track.label, track.file))
        }
    }
}

class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer ?: "$mainUrl/").document
        val playerScript = document.selectFirst("script:containsData(jwplayer)")?.data() ?: return
        val sourceRegex = """"file"\s*:\s*"(https:[^"]+\.(?:mp4|m3u8)[^"]*)"""".toRegex()
        for ((index, source) in sourceRegex.findAll(playerScript).withIndex()) {
            val fileUrl = source.groupValues[1].replace("\\/", "/")
            if (fileUrl.contains(".mp4")) {
                callback.invoke(
                    newExtractorLink(name, "$name Video Server $index", url = fileUrl, INFER_TYPE) {
                        this.referer = ""
                        this.quality = getQualityFromName("")
                    }
                )
            } else {
                M3u8Helper.generateM3u8(name, fileUrl, mainUrl).forEach(callback)
            }
        }
        val trackRegex = """"file"\s*:\s*"(https:[^"]+\.vtt[^"]*)"\s*,\s*"label"\s*:\s*"([^"]+)"""".toRegex()
        for (track in trackRegex.findAll(playerScript)) {
            val fileUrl = track.groupValues[1].replace("\\/", "/")
            val label = track.groupValues[2]
            subtitleCallback.invoke(newSubtitleFile(label, fileUrl))
        }
    }
}

open class PlayStreamplay : ExtractorApi() {
    override var name = "All sub player"
    override var mainUrl = "https://play.streamplay.co.in"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, timeout = 30_000).document
        val unavailableMsg = doc.selectFirst("#message")?.text()
        if (unavailableMsg?.contains("unavailable", ignoreCase = true) == true) return

        val packedScript = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return
        val packedCode = Regex("""eval\(.*?\)\)""", RegexOption.DOT_MATCHES_ALL).find(packedScript)?.value ?: return
        val unpackedJs = JsUnpacker(packedCode).unpack() ?: return
        val token = Regex("""kaken="(.*?)"""").find(unpackedJs)?.groupValues?.getOrNull(1) ?: return
        val responseText = app.get("$mainUrl/api/?$token", timeout = 30_000).text
        val responseJson = tryParseJson<PlayStreamResponse>(responseText) ?: return
        
        val decryptedResponse = decryptResponse(responseJson)
        val m3u8Url = decryptedResponse.sources.find { it.file.isNotBlank() }?.file
        if (!m3u8Url.isNullOrEmpty()) {
            M3u8Helper.generateM3u8(name, m3u8Url, mainUrl).forEach(callback)
        }
        decryptedResponse.tracks.forEach { subtitle ->
            subtitleCallback.invoke(newSubtitleFile(lang = subtitle.label, url = subtitle.file))
        }
    }

    private fun decryptResponse(encryptedResponse: PlayStreamResponse): PlayStreamResponse {
        val (encryptedJson, iv, salt) = extractEncryptionData(encryptedResponse)
        if (encryptedJson.isNullOrEmpty()) {
            return encryptedResponse
        }
        
        try {
            val decryptedJson = decryptAes(encryptedJson, iv, salt)
            return tryParseJson(decryptedJson) ?: return encryptedResponse
        } catch (e: Exception) {
            return encryptedResponse
        }
    }

    private fun extractEncryptionData(response: PlayStreamResponse): Triple<String?, String?, String?> {
        val encryptedJson = response.query?.id
        val iv = response.query?.source
        val salt = response.query?.download
        return Triple(encryptedJson, iv, salt)
    }

    private fun decryptAes(encryptedData: String, iv: String?, salt: String?): String {
        val keyMaterial = salt ?: ""
        return AesHelper.cryptoAESHandler(
            encryptedData,
            keyMaterial.toByteArray(),
            false
        ) ?: encryptedData
    }

    data class PlayStreamResponse(
        val query: PlayStreamQuery?,
        val status: String,
        val message: String,
        @param:JsonProperty("embed_url")
        val embedUrl: String,
        @param:JsonProperty("download_url")
        val downloadUrl: String,
        val title: String,
        val poster: String,
        val filmstrip: String,
        val sources: List<Source>,
        val tracks: List<Track>,
    )
    data class PlayStreamQuery(val source: String, val id: String, val download: String)
}
