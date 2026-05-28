package com.cinemax21

import android.util.Base64
import com.cinemax21.Cinemax21Provider.Companion.anilistAPI
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max

var gomoviesCookies: Map<String, String>? = null

val mimeType = arrayOf(
    "video/x-matroska",
    "video/mp4",
    "video/x-msvideo"
)

fun getEpisodeSlug(
    season: Int? = null,
    episode: Int? = null,
): Pair<String, String> {
    return if (season == null && episode == null) {
        "" to ""
    } else {
        (if (season!! < 10) "0$season" else "$season") to (if (episode!! < 10) "0$episode" else "$episode")
    }
}

fun String.xorDecrypt(key: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < this.length) {
        var j = 0
        while (j < key.length && i < this.length) {
            sb.append((this[i].code xor key[j].code).toChar())
            j++
            i++
        }
    }
    return sb.toString()
}

fun String?.createSlug(): String? {
    return this?.filter { it.isWhitespace() || it.isLetterOrDigit() }
        ?.trim()
        ?.replace("\\s+".toRegex(), "-")
        ?.lowercase()
}

fun String.fixUrlBloat(): String {
    return this.replace("\"", "").replace("\\", "")
}

fun getBaseUrl(url: String): String {
    return URI(url).let {
        "${it.scheme}://${it.host}"
    }
}

fun fixUrl(url: String, domain: String): String {
    if (url.startsWith("http")) {
        return url
    }
    if (url.isEmpty()) {
        return ""
    }
    val startsWithNoHttp = url.startsWith("//")
    if (startsWithNoHttp) {
        return "https:$url"
    } else {
        if (url.startsWith('/')) {
            return domain + url
        }
        return "$domain/$url"
    }
}

fun base64UrlEncode(input: ByteArray): String {
    return base64Encode(input)
        .replace("+", "-")
        .replace("/", "_")
        .replace("=", "")
}

fun getLanguage(str: String): String {
    return if (str.contains("(in_ID)")) "Indonesian" else str
}

fun encode(input: String): String = URLEncoder.encode(input, "utf-8").replace("+", "%20")

fun decode(input: String): String = URLDecoder.decode(input, "utf-8")

fun isUpcoming(dateString: String?): Boolean {
    return try {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateTime = dateString?.let { format.parse(it)?.time } ?: return false
        unixTimeMS < dateTime
    } catch (t: Throwable) {
        logError(t)
        false
    }
}

fun getQualityFromName(str: String?): Int {
    if (str == null) return Qualities.Unknown.value
    return when {
        str.contains("2160") || str.contains("4K") -> Qualities.P2160.value
        str.contains("1080") || str.contains("FHD") -> Qualities.P1080.value
        str.contains("720") || str.contains("HD") -> Qualities.P720.value
        str.contains("480") || str.contains("SD") -> Qualities.P480.value
        str.contains("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}

object VidrockHelper {
    private const val Ww = "x7k9mPqT2rWvY8zA5bC3nF6hJ2lK4mN9"

    fun encrypt(
        r: Int?,
        e: String,
        t: Int?,
        n: Int?
    ): String {
        val s = if (e == "tv") "${r}_${t}_${n}" else r.toString()
        val keyBytes = Ww.toByteArray(Charsets.UTF_8)
        val ivBytes = Ww.substring(0, 16).toByteArray(Charsets.UTF_8)

        val secretKey = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
        val encrypted = cipher.doFinal(s.toByteArray(Charsets.UTF_8))
        return base64UrlEncode(encrypted)
    }
}

object VidsrcHelper {

    fun encryptAesCbc(plainText: String, keyText: String): String {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val keyBytes = sha256.digest(keyText.toByteArray(Charsets.UTF_8))
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val iv = ByteArray(16) { 0 }
        val ivSpec = IvParameterSpec(iv)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)

        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return base64UrlEncode(encrypted)
    }

}

fun generateHashedString(): String {
    val s = "a8f7e9c2d4b6a1f3e8c9d2t4a7f6e9c2d4z6a1f3e8c9d2b4a7f5e9c2d4b6a1f3"
    val a = "2"
    val algorithm = "HmacSHA512"
    val keySpec = SecretKeySpec(s.toByteArray(StandardCharsets.UTF_8), algorithm)
    val mac = Mac.getInstance(algorithm)
    mac.init(keySpec)

    val input = "crypto_rotation_v${a}_seed_2025"
    val hmacBytes = mac.doFinal(input.toByteArray(StandardCharsets.UTF_8))
    val hex = hmacBytes.joinToString("") { "%02x".format(it) }

    val repeated = hex.repeat(3)
    val result = repeated.substring(0, max(s.length, 128))

    return result
}

fun cinemaOSGenerateHash(t: CinemaOsSecretKeyRequest, isSeries: Boolean): String {
    val c = generateHashedString()
    val m: String = if (isSeries) "content_v3::contentId=${t.tmdbId}::partId=${t.episodeId}::seriesId=${t.seasonId}::environment=production" else "content_v3::contentId=${t.tmdbId}::environment=production"

    val hmac384 = Mac.getInstance("HmacSHA384")
    hmac384.init(SecretKeySpec(c.toByteArray(Charsets.UTF_8), "HmacSHA384"))
    hmac384.update(m.toByteArray(Charsets.UTF_8))
    val x = hmac384.doFinal().joinToString("") { "%02x".format(it) }

    val hmac512 = Mac.getInstance("HmacSHA512")
    hmac512.init(SecretKeySpec(x.toByteArray(Charsets.UTF_8), "HmacSHA512"))
    hmac512.update(c.takeLast(64).toByteArray(Charsets.UTF_8))
    val finalDigest = hmac512.doFinal().joinToString("") { "%02x".format(it) }

    return finalDigest
}

fun cinemaOSDecryptResponse(e: CinemaOSReponseData?): Any {
    val encrypted = e?.encrypted
    val cin = e?.cin
    val mao = e?.mao
    val salt = e?.salt

    val keyBytes = "a1b2c3d4e4f6477658455678901477567890abcdef1234567890abcdef123456".toByteArray()
    val ivBytes = hexStringToByteArray(cin.toString())
    val authTagBytes = hexStringToByteArray(mao.toString())
    val encryptedBytes = hexStringToByteArray(encrypted.toString())
    val saltBytes = hexStringToByteArray(salt.toString())

    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val spec = PBEKeySpec(keyBytes.map { it.toInt().toChar() }.toCharArray(), saltBytes, 100000, 256)
    val tmp = factory.generateSecret(spec)
    val key = SecretKeySpec(tmp.encoded, "AES")

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val gcmSpec = GCMParameterSpec(128, ivBytes)
    cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
    val decryptedBytes = cipher.doFinal(encryptedBytes + authTagBytes)
    
    return String(decryptedBytes)
}

fun hexStringToByteArray(hex: String): ByteArray {
    val len = hex.length
    require(len % 2 == 0) { "Hex string must have even length" }
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        i += 2
    }
    return data
}

fun parseCinemaOSSources(jsonString: String): List<Map<String, String>> {
    val json = JSONObject(jsonString)
    val sourcesObject = json.getJSONObject("sources")
    val sourcesList = mutableListOf<Map<String, String>>()

    val keys = sourcesObject.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val source = sourcesObject.getJSONObject(key)

        if (source.has("qualities")) {
            val qualities = source.getJSONObject("qualities")
            val qualityKeys = qualities.keys()
            while (qualityKeys.hasNext()) {
                val qualityKey = qualityKeys.next()
                val qualityObj = qualities.getJSONObject(qualityKey)
                val sourceMap = mutableMapOf<String, String>()
                sourceMap["server"] = source.optString("server", key)
                sourceMap["url"] = qualityObj.optString("url", "")
                sourceMap["type"] = qualityObj.optString("type", "")
                sourceMap["speed"] = source.optString("speed", "")
                sourceMap["bitrate"] = source.optString("bitrate", "")
                sourceMap["quality"] = qualityKey
                sourcesList.add(sourceMap)
            }
        } else {
            val sourceMap = mutableMapOf<String, String>()
            sourceMap["server"] = source.optString("server", key)
            sourceMap["url"] = source.optString("url", "")
            sourceMap["type"] = source.optString("type", "")
            sourceMap["speed"] = source.optString("speed", "")
            sourceMap["bitrate"] = source.optString("bitrate", "")
            sourceMap["quality"] = source.optString("quality", "")
            sourcesList.add(sourceMap)
        }
    }
    return sourcesList
}

suspend fun getPlayer4uUrl(
    name: String,
    selectedQuality: Int,
    url: String,
    referer: String?,
    callback: (ExtractorLink) -> Unit
) {
    val response = app.get(url, referer = referer)
    var script = getAndUnpack(response.text).takeIf { it.isNotEmpty() }
        ?: response.document.selectFirst("script:containsData(sources:)")?.data()
    if (script == null) {
        val iframeUrl = Regex("""<iframe src="(.*?)"""").find(response.text)?.groupValues?.getOrNull(1) ?: return
        val iframeResponse = app.get(iframeUrl, referer = null, headers = mapOf("Accept-Language" to "en-US,en;q=0.5"))
        script = getAndUnpack(iframeResponse.text).takeIf { it.isNotEmpty() } ?: return
    }

    val m3u8 = Regex("\"hls2\":\\s*\"(.*?m3u8.*?)\"").find(script)?.groupValues?.getOrNull(1).orEmpty()
    callback(newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
        this.quality = selectedQuality
    })
}

fun getPlayer4UQuality(quality: String): Int {
    return when (quality) {
        "4K", "2160P" -> Qualities.P2160.value
        "FHD", "1080P" -> Qualities.P1080.value
        "HQ", "HD", "720P", "DVDRIP", "TVRIP", "HDTC", "PREDVD" -> Qualities.P720.value
        "480P" -> Qualities.P480.value
        "360P", "CAM" -> Qualities.P360.value
        "DS" -> Qualities.P144.value
        "SD" -> Qualities.P480.value
        "WEBRIP" -> Qualities.P720.value
        "BLURAY", "BRRIP" -> Qualities.P1080.value
        "HDRIP" -> Qualities.P1080.value
        "TS" -> Qualities.P480.value
        "R5" -> Qualities.P480.value
        "SCR" -> Qualities.P480.value
        "TC" -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }
}

object DramaHelper {
    val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Connection" to "keep-alive",
        "Referer" to "https://dramafull.cc/"
    )

    fun normalizeQuery(title: String): String {
        return title
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .trim()
            .replace("\\s+".toRegex(), " ")
    }

    fun isFuzzyMatch(original: String, result: String): Boolean {
        val cleanOrg = original.lowercase().replace(Regex("[^a-z0-9]"), "")
        val cleanRes = result.lowercase().replace(Regex("[^a-z0-9]"), "")

        if (cleanOrg.length < 5 || cleanRes.length < 5) {
            return cleanOrg == cleanRes
        }

        return cleanOrg.contains(cleanRes) || cleanRes.contains(cleanOrg)
    }
}
