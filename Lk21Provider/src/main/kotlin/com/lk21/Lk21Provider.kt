package com.lk21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.security.MessageDigest

class Lk21Provider : MainAPI() {
    override var mainUrl = "https://tv10.lk21official.cc"
    override var name = "LK21"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/latest" to "Latest",
        "$mainUrl/populer" to "Populer",
        "$mainUrl/genre/action" to "Action",
        "$mainUrl/genre/horror" to "Horror",
        "$mainUrl/genre/animation" to "Anime",
        "$mainUrl/country/south-korea" to "Korea",
        "$mainUrl/country/japan" to "Jepang",
        "$mainUrl/country/china" to "Cina",
        "$mainUrl/country/thailand" to "Thailand",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}/page/$page"
        val document = app.get(url).document
        val items = document.select("article a[itemprop=url]").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = fixUrl(attr("href"))
        val title = selectFirst("h3.poster-title")?.text()?.trim() ?: return null
        val poster = selectFirst("img")?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        }?.let { fixUrlNull(it) }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article a[itemprop=url]").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: ""

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.select("img").firstOrNull()?.let {
                it.attr("src").ifEmpty { it.attr("data-src") }
            }

        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.select("div.entry-content p, .gmr-desc p, p:contains(film)").firstOrNull()?.text()?.trim()

        val tags = document.select("a[href*='/genre/']").mapNotNull { it.text().trim().ifEmpty { null } }
            .ifEmpty { document.select("span:contains(Action), span:contains(Comedy), span:contains(Drama), span:contains(Horror)").mapNotNull { it.text().trim().ifEmpty { null } } }

        val yearText = document.selectFirst("a[href*='/year/']")?.text()?.trim()
            ?: document.body().text().let { Regex("(\\d{4})").findAll(it).map { it.value }.firstOrNull { it.toIntOrNull() in 1900..2030 } }
        val year = yearText?.toIntOrNull()

        val quality = document.selectFirst("span:contains(HD), span:contains(CAM), span:contains(WebDL)")?.text()?.trim()
        val durationText = document.selectFirst("span:contains(h), span:contains(min)")?.text()
            ?: Regex("(\\d+)h\\s*(\\d+)?m?").find(document.text())?.value
        val runtime = durationText?.let { parseDuration(it) }

        val ratingText = document.selectFirst("span:contains(.), .gmr-rating, .rating")?.text()?.trim()
            ?: Regex("(\\d\\.\\d)").find(document.text())?.value

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
            this.tags = tags
            this.year = year
            this.duration = runtime
            this.score = Score.from10(ratingText)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val iframes = document.select("iframe[src]")
        if (iframes.isNotEmpty()) {
            iframes.forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && !src.contains("youtube", true) && !src.contains("google", true)) {
                    loadExtractor(fixUrl(src), subtitleCallback, callback)
                }
            }
            return true
        }

        val playerLinks = document.select("#player-list a[data-url]")
        if (playerLinks.isNotEmpty()) {
            playerLinks.forEach { link ->
                val encryptedUrl = link.attr("data-url").ifBlank { return@forEach }
                val realUrl = decryptStreamUrl(encryptedUrl, referer = data)
                if (realUrl != null) {
                    loadExtractor(realUrl, subtitleCallback, callback)
                }
            }
            return true
        }

        return true
    }

    private suspend fun decryptStreamUrl(encryptedId: String, referer: String): String? {
        return try {
            val rootDomain = "lk21official.cc"
            val baseUrl = "https://sinta.$rootDomain"

            val challengeUrl = "$baseUrl/challenge.php?id=${URLEncoder.encode(encryptedId, "UTF-8")}"
            val challengeResponse = app.get(challengeUrl, referer = referer).text
            val challengeJson = JSONObject(challengeResponse)
            val challenge = challengeJson.getString("challenge")
            val difficulty = challengeJson.getInt("difficulty")

            val nonce = solvePow(challenge, difficulty)

            val verifyData = JSONObject().apply {
                put("challenge", challenge)
                put("nonce", nonce)
                put("id", encryptedId)
                put("fp", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36|Win32|1920x1080|-420|8")
            }.toString()

            val verifyResponse = app.post(
                url = "$baseUrl/verify.php",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Referer" to referer
                ),
                requestBody = verifyData.toRequestBody("application/json".toMediaTypeOrNull())
            ).text

            val verifyJson = JSONObject(verifyResponse)
            if (verifyJson.optBoolean("success", false)) {
                verifyJson.optString("url").ifBlank { null }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun solvePow(challenge: String, difficulty: Int): Int {
        val prefix = "0".repeat(difficulty)
        var nonce = 0
        val digest = MessageDigest.getInstance("SHA-256")
        while (true) {
            val hash = digest.digest("$challenge$nonce".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            if (hash.startsWith(prefix)) return nonce
            nonce++
        }
    }

    private fun parseDuration(text: String): Int? {
        val regex = Regex("(?:(\\d+)h)?\\s*(?:(\\d+)m)?")
        val match = regex.find(text) ?: return null
        val hours = match.groupValues[1].toIntOrNull() ?: 0
        val minutes = match.groupValues[2].toIntOrNull() ?: 0
        return (hours * 60 + minutes) * 60
    }
}
