package com.samehadaku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class SamehadakuProvider : MainAPI() {
    override var mainUrl = "https://v2.samehadaku.how"
    override var name = "Samehadaku"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/anime-terbaru/" to "Anime Terbaru",
        "https://v2.samehadaku.how/anime-movie/" to "Daftar Anime",
        "$mainUrl/anime-movie/" to "Movie",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.replace("%d", page.toString())
        val document = app.get(url, timeout = 15_000L).document
        val items = document.select("div.animepost").mapNotNull { it.toSearchResult() }
            .ifEmpty {
                document.select("div.post-show ul li").mapNotNull { it.toSearchResultAlt() }
            }
            .distinctBy { it.url }
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(".animposx > a") ?: return null
        val href = anchor.attr("abs:href").takeIf { it.contains("/anime/") } ?: return null
        val title = anchor.selectFirst(".data .title h2")?.text()?.trim() ?: return null
        val poster = fixUrlNull(anchor.selectFirst(".content-thumb img.nmsa, .content-thumb img.anmsa")?.attr("abs:src"))
        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = poster
        }
    }

    private fun Element.toSearchResultAlt(): SearchResponse? {
        val anchor = selectFirst("div.thumb > a") ?: selectFirst("a[href*=\"/anime/\"]") ?: return null
        val href = anchor.attr("abs:href").takeIf { it.contains("/anime/") } ?: return null
        val title = selectFirst("div.dtla > h2.entry-title > a")?.text()?.trim()
            ?: anchor.attr("title").takeIf { it.isNotBlank() } ?: return null
        val poster = fixUrlNull(selectFirst("img")?.attr("abs:src") ?: selectFirst("img")?.attr("abs:data-src"))
        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", timeout = 15_000L).document
        return document.select("div.animepost, article.animpost").mapNotNull { el ->
            val anchor = el.selectFirst(".animposx > a")
                ?: el.selectFirst("a[href*=\"/anime/\"]") ?: return@mapNotNull null
            val href = anchor.attr("abs:href").takeIf { it.contains("/anime/") } ?: return@mapNotNull null
            val title = anchor.selectFirst(".data .title h2")?.text()?.trim()
                ?: anchor.attr("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val poster = fixUrlNull(anchor.selectFirst("img.nmsa, img.anmsa")?.attr("abs:src"))
            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) { this.posterUrl = poster }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 15_000L).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Unknown"
        val poster = fixUrlNull(
            document.selectFirst("div.infoanime > div.thumb > img.nmsa, div.infoanime > div.thumb > img.anmsa")
                ?.attr("abs:src")
        )
        val description = document.selectFirst("div.infoanime > div.infox > div.desc > div.entry-content")?.text()?.trim()
        val tags = document.select("div.infoanime > div.infox > div.genre-info > a[rel=tag]").map { it.text().trim() }

        val episodes = document.select("div.lstepsiode.listeps > ul > li").mapNotNull { li ->
            val link = li.selectFirst("div.epsleft > span.lchx > a, div.epsright > span.eps > a")
                ?: return@mapNotNull null
            val epHref = link.attr("abs:href")
            val epTitle = li.selectFirst("div.epsleft > span.lchx > a")?.text()?.trim() ?: "Episode"
            val epNum = li.selectFirst("div.epsright > span.eps > a")?.text()
                ?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
            newEpisode(fixUrl(epHref)) {
                this.name = epTitle
                this.episode = epNum
                this.posterUrl = poster
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    companion object {
        private val IFRAME_SRC_REGEX = Regex("""src=["']([^"']+)["']""")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, timeout = 15_000L).document
        val serverOptions = document.select("#server > ul > li > div.east_player_option")

        if (serverOptions.isEmpty()) {
            throw ErrorLoadingException("Tidak ada server di Samehadaku")
        }

        var foundAny = false
        for (opt in serverOptions) {
            val post = opt.attr("data-post")
            val nume = opt.attr("data-nume")
            val type = opt.attr("data-type").ifBlank { "schtml" }
            if (post.isBlank() || nume.isBlank()) continue

            try {
                val ajaxHtml = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to data
                    ),
                    timeout = 15_000L
                ).text

                val iframeSrc = IFRAME_SRC_REGEX.find(ajaxHtml)?.groupValues?.get(1)?.let { fixUrl(it) }
                    ?: continue

                foundAny = true
                loadExtractor(iframeSrc, data, subtitleCallback, callback)
            } catch (_: Exception) {
                // try next server
            }
        }

        if (!foundAny) throw ErrorLoadingException("Tidak ada sumber video di Samehadaku")
        return true
    }
}
