package com.mwk.shared.data

import com.fasterxml.jackson.annotation.JsonProperty

data class ResponseHash(
    @JsonProperty("embed_url") val embed_url: String,
    @JsonProperty("key") val key: String,
)

data class AesData(
    @JsonProperty("m") val m: String,
)
