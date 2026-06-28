package com.kawanfilm
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import java.net.URI
class Movearnpre : Dingtezuni() {
    override var name = "Movearnpre"
    override var mainUrl = "https://movearnpre.com"
}
class Mivalyo : Dingtezuni() {
    override var name = "Earnvids"
    override var mainUrl = "https://mivalyo.com"
}
class Ryderjet : Dingtezuni() {
    override var name = "Ryderjet"
    override var mainUrl = "https://ryderjet.com"
}
class Bingezove : Dingtezuni() {
    override var name = "Earnvids"
    override var mainUrl = "https://bingezove.com"
}
open class Dingtezuni : ExtractorApi() {
    override val name = "Earnvids"
    override val mainUrl = "https://dingtezuni.com"
    override val requiresReferer = true
    companion object {
        private val M3U8_SRC_REGEX = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
    }
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
        )
        val response = app.get(getEmbedUrl(url), referer = referer, timeout = 15_000L)
        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            var result = getAndUnpack(response.text)
            if (result.contains("var links")) result = result.substringAfter("var links")
            result
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        } ?: return
        M3U8_SRC_REGEX.findAll(script).forEach { m3u8Match ->
            M3u8Helper.generateM3u8(
                name,
                fixUrl(m3u8Match.groupValues[1]),
                referer = "$mainUrl/",
                headers = headers
            ).forEach(callback)
        }
    }
    private fun getEmbedUrl(url: String): String {
        return when {
            url.contains("/d/") -> url.replace("/d/", "/v/")
            url.contains("/download/") -> url.replace("/download/", "/v/")
            url.contains("/file/") -> url.replace("/file/", "/v/")
            else -> url.replace("/f/", "/v/")
        }
    }
}
open class Gofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false
    private val mainApi = "https://api.gofile.io"

    companion object {
        private const val TOKEN_TTL = 5 * 60 * 1000L
        private var tokenCache: String? = null
        private var websiteTokenCache: String? = null
        private var tokenTimestamp: Long = 0L
        private val QUALITY_REGEX = Regex("(\\d{3,4})[pP]")
        private val ID_REGEX = Regex("/(?:\\?c=|d/)([\\da-zA-Z-]+)")
        private val WT_REGEX = Regex("fetchData.wt\\s*=\\s*\"([^\"]+)")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = ID_REGEX.find(url)?.groupValues?.get(1) ?: return
        val token = getToken() ?: return
        val websiteToken = getWebsiteToken() ?: return
        app.get("$mainApi/getContent?contentId=$id&token=$token&wt=$websiteToken", timeout = 15_000L)
            .text.let { tryParseJson<Source>(it) }?.data?.contents?.forEach {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        it.value["link"] ?: return,
                    ) {
                        this.quality = getQuality(it.value["name"])
                        this.headers = mapOf("Cookie" to "accountToken=$token")
                    }
                )
            }
    }

    private suspend fun getToken(): String? {
        val now = System.currentTimeMillis()
        if (tokenCache != null && now - tokenTimestamp < TOKEN_TTL) return tokenCache
        tokenCache = app.get("$mainApi/createAccount", timeout = 15_000L).text.let { tryParseJson<Account>(it) }?.data?.get("token")
        tokenTimestamp = now
        return tokenCache
    }

    private suspend fun getWebsiteToken(): String? {
        if (websiteTokenCache != null) return websiteTokenCache
        websiteTokenCache = app.get("$mainUrl/dist/js/alljs.js", timeout = 15_000L).text.let {
            WT_REGEX.find(it)?.groupValues?.get(1)
        }
        return websiteTokenCache
    }

    private fun getQuality(str: String?): Int {
        return QUALITY_REGEX.find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
    data class Account(
        @JsonProperty("data") val data: HashMap<String, String>? = null,
    )
    data class Data(
        @JsonProperty("contents") val contents: HashMap<String, HashMap<String, String>>? = null,
    )
    data class Source(
        @JsonProperty("data") val data: Data? = null,
    )
}
class Hglink : StreamWishExtractor() {
    override val name = "Hglink"
    override val mainUrl = "https://hglink.to"
}
class Ghbrisk : StreamWishExtractor() {
    override val name = "Ghbrisk"
    override val mainUrl = "https://ghbrisk.com"
}
class Dhcplay : StreamWishExtractor() {
    override var name = "DHC Play"
    override var mainUrl = "https://dhcplay.com"
}
class Vidshare : VidStack() {
    override var name = "Vidshare"
    override var mainUrl = "https://vidshare.rpmvid.com"
    override var requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        super.getUrl(url, referer, subtitleCallback) { link ->
            callback.invoke(
                newExtractorLink(
                    link.source,
                    link.name,
                    link.url.replace("https://", "http://")
                ) {
                    this.referer = link.referer
                    this.quality = link.quality
                    this.isM3u8 = link.isM3u8
                    this.headers = link.headers
                }
            )
        }
    }
}
