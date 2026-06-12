package com.donghuastream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.app
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

    companion object {
        private val AES_PASSWORD = "F1r3b4Ll_GDP~5H"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = Regex("""/embed/([a-f0-9-]+)""").find(url)?.groupValues?.get(1) ?: return

        if (extractFromApi(url, subtitleCallback, callback)) return
        extractFromPl(videoId, url, subtitleCallback, callback)
    }

    private suspend fun extractFromApi(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try { app.get(url).document } catch (_: Exception) { return false }
        val packedScript = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return false
        val packedCode = Regex("""eval\(.*?\)\)""", RegexOption.DOT_MATCHES_ALL).find(packedScript)?.value ?: return false
        val unpackedJs = JsUnpacker(packedCode).unpack() ?: return false
        val token = Regex("""kaken="([^"]*)"""").find(unpackedJs)?.groupValues?.getOrNull(1) ?: return false
        val responseText = app.get("$mainUrl/api/?$token", timeout = 30_000).text
        val apiResponse = tryParseJson<PlayStreamApiResponse>(responseText) ?: return false
        if (apiResponse.status != "ok") return false
        val query = apiResponse.query ?: return false
        val encryptedId = query.id
        if (encryptedId.isNullOrBlank() || encryptedId.length < 20) return false
        val salt = query.download ?: ""
        val decryptedJson = try {
            AesHelper.cryptoAESHandler(encryptedId, salt.toByteArray(), false)
        } catch (_: Exception) { null }
        if (decryptedJson.isNullOrBlank()) return false
        val videoData = tryParseJson<PlayStreamVideoData>(decryptedJson) ?: return false
        emitLinks(videoData.sources, videoData.tracks, subtitleCallback, callback)
        return true
    }

    private suspend fun extractFromPl(
        videoId: String,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val apiUrl = "$mainUrl/pl/$videoId"
        val headers = mapOf("Referer" to url, "Origin" to mainUrl)
        val responseText = try { app.get(apiUrl, timeout = 30_000, headers = headers).text } catch (_: Exception) { return }
        val encryptedData = tryParseJson<StreamplayEncryptedResponse>(responseText) ?: return
        val decryptedJson = decryptEVP(encryptedData) ?: return
        val videoResponse = tryParseJson<Root>(decryptedJson) ?: return
        emitLinks(videoResponse.sources, videoResponse.tracks, subtitleCallback, callback)
    }

    private suspend fun emitLinks(
        sources: List<Source>?,
        tracks: List<Track>?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        sources?.forEach { source ->
            val sourceUrl = httpsify(source.file)
            if (sourceUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, sourceUrl, referer = mainUrl).forEach(callback)
            } else if (sourceUrl.contains(".mp4")) {
                callback.invoke(
                    newExtractorLink(name, source.label, sourceUrl, INFER_TYPE) {
                        this.referer = ""
                        this.quality = getQualityFromName(source.label)
                    }
                )
            }
        }
        tracks?.forEach { track ->
            subtitleCallback.invoke(newSubtitleFile(lang = track.label, url = track.file))
        }
    }

    private fun decryptEVP(encrypted: StreamplayEncryptedResponse): String? {
        return try {
            val password = AES_PASSWORD.toByteArray(Charsets.UTF_8)
            val salt = hexToBytes(encrypted.s)
            val ciphertext = android.util.Base64.decode(encrypted.ct, android.util.Base64.DEFAULT)
            val iv = hexToBytes(encrypted.iv)
            val md5 = java.security.MessageDigest.getInstance("MD5")
            md5.update(password); md5.update(salt); val d1 = md5.digest()
            md5.update(d1); md5.update(password); md5.update(salt); val d2 = md5.digest()
            val key = d1 + d2
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toIntOrNull(16)?.toByte() ?: 0.toByte() }.toByteArray()
    }

    data class StreamplayEncryptedResponse(
        val ct: String, val iv: String, @param:JsonProperty("s") val s: String
    )
    data class PlayStreamApiResponse(
        val query: PlayStreamApiQuery?, val status: String?, val message: String?
    )
    data class PlayStreamApiQuery(
        val source: String?, val id: String?, val download: String?
    )
    data class PlayStreamVideoData(
        val sources: List<Source>?, val tracks: List<Track>?
    )
}
