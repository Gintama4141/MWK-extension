package com.mwk.idlix

import com.fasterxml.jackson.annotation.JsonProperty

data class ResponseHash(
    @JsonProperty("embed_url") val embed_url: String,
    @JsonProperty("key") val key: String,
)

data class AesData(
    @JsonProperty("m") val m: String,
)

data class IdlixSearchResult(
    val title: String,
    val href: String,
    val poster: String?,
    val quality: String?,
    val year: Int? = null
)

data class IdlixEpisode(
    val href: String,
    val title: String,
    val episodeNum: Int?
)

data class IdlixSeason(
    val title: String,
    val episodes: List<IdlixEpisode>
)
