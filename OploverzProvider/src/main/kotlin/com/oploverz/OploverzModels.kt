package com.oploverz

import com.lagradost.cloudstream3.*

// /api/series + /api/series?q= + main page -> { meta: OplMeta, data: [OplSeries] }
data class OplSeriesList(
    val meta: OplMeta? = null,
    val data: List<OplSeries> = emptyList()
)

data class OplMeta(
    val currentPage: Int? = null,
    val lastPage: Int? = null,
    val perPage: Int? = null,
    val total: Int? = null,
    val firstPage: Int? = null
)

data class OplSeries(
    val id: Int? = null,
    val seriesId: Int? = null,
    val title: String? = null,
    val japaneseTitle: String? = null,
    val slug: String? = null,
    val description: String? = null,
    val releaseDate: String? = null,
    val status: String? = null,
    val poster: String? = null,
    val duration: String? = null,
    val releaseType: String? = null,
    val score: Double? = null,
    val genres: List<OplGenre>? = null,
    val censored: Boolean? = null,
    val mature: Boolean? = null,
    val hot: Boolean? = null,
    val batchDownloadUrl: Any? = null,
    val totalEpisodes: Int? = null
)

data class OplGenre(
    val id: Int? = null,
    val name: String? = null,
    val slug: String? = null
)

// /api/series/<slug> -> { data: OplSeries }
data class OplSeriesDetail(
    val data: OplSeries? = null
)

// /api/series/<slug>/episodes -> { meta: OplMeta, data: [OplEpisode] }
data class OplEpisodeList(
    val meta: OplMeta? = null,
    val data: List<OplEpisode> = emptyList()
)

data class OplEpisode(
    val id: Int? = null,
    val subbed: String? = null,
    val title: String? = null,
    val episodeNumber: String? = null,
    val downloadUrl: List<OplDownloadFormat>? = null,
    val streamUrl: List<OplStream>? = null,
    val content: String? = null,
    val releasedAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val series: Any? = null
)

data class OplStream(
    val source: String? = null,
    val url: String? = null
)

data class OplDownloadFormat(
    val format: String? = null,
    val resolutions: List<OplResolution>? = null
)

data class OplResolution(
    val quality: String? = null,
    val downloadLinks: List<OplDownloadLink>? = null
)

data class OplDownloadLink(
    val host: String? = null,
    val url: String? = null
)

// /api/episodes/<id> -> { data: OplEpisode }
data class OplSingleEpisode(
    val data: OplEpisode? = null
)
