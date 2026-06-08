package com.onetouchtv

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

data class OneTouchTVParser(
    val day: List<Day>? = emptyList(),
    val week: List<Week>? = emptyList(),
    val month: List<Month>? = emptyList()
) {
    data class Day(
        val _id: String? = null,
        val id: String? = null,
        val title: String? = null,
        val image: String? = null,
        val country: String? = null,
        val type: String? = null,
        val year: String? = null,
        val popularity: Int = 0,
        val status: String? = null,
        val releaseDate: String? = null,
        val isSub: Boolean = false
    )

    data class Week(
        val _id: String? = null,
        val id: String? = null,
        val title: String? = null,
        val image: String? = null,
        val country: String? = null,
        val type: String? = null,
        val year: String? = null,
        val popularity: Int = 0,
        val status: String? = null,
        val releaseDate: String? = null,
        val isSub: Boolean = false
    )

    data class Month(
        val _id: String? = null,
        val id: String? = null,
        val title: String? = null,
        val image: String? = null,
        val country: String? = null,
        val type: String? = null,
        val year: String? = null,
        val popularity: Int = 0,
        val status: String? = null,
        val releaseDate: String? = null,
        val isSub: Boolean = false
    )
}

data class SourceItem(
    val type: String?,
    val contentId: String?,
    val id: String?,
    val name: String?,
    val quality: String?,
    val url: String?,
    val headers: Map<String, String>
)

data class TrackItem(
    val file: String?,
    val name: String?,
    val isDefault: Boolean,
    val kind: String?,
    val format: String?
)

data class ParserResponse(
    val result: ParserResult? = null,
    val sources: List<SourceItem>? = null,
    val track: List<TrackItem>? = null,
    val tracks: List<TrackItem>? = null
)
data class ParserResult(
    val sources: List<SourceItem>? = null,
    val track: List<TrackItem>? = null,
    val tracks: List<TrackItem>? = null
)

fun parseSourcesAndTracks(decryptedJson: String): Pair<List<SourceItem>, List<TrackItem>> {
    val sourcesList = mutableListOf<SourceItem>()
    val tracksList = mutableListOf<TrackItem>()
    val root = tryParseJson<ParserResponse>(decryptedJson)
    val result = root?.result
    val sourcesArray = result?.sources ?: root?.sources
    if (sourcesArray != null) {
        for (s in sourcesArray) {
            sourcesList.add(s)
        }
    }
    val tracksArray = result?.track ?: result?.tracks ?: root?.track ?: root?.tracks
    if (tracksArray != null) {
        for (t in tracksArray) {
            tracksList.add(t)
        }
    }
    return Pair(sourcesList, tracksList)
}

data class Search(
    val status: Long,
    val result: List<SearchResult>
)

data class SearchResult(
    val id: String? = null,
    val loklokContentId: String? = null,
    val isSub: Boolean = false,
    val title: String? = null,
    val image: String? = null,
    val type: String? = null,
    val year: String? = null,
    val source: String? = null,
    val status: String? = null,
    val loklokCategory: Long? = null,
    val episodes: List<Any?> = emptyList(),
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val otherTitles: List<String> = emptyList()
)

data class MediaResult(
    val randomSlideShow: List<RandomSlideShow>?,
    val recents: List<Recent>?,
    val result: ResultWrapper?
)

data class ResultWrapper(
    val randomSlideShow: List<RandomSlideShow>?,
    val recents: List<Recent>?
)

data class RandomSlideShow(
    @param:JsonProperty("_id") val id: String?,
    @param:JsonProperty("id") val id2: String?,
    val title: String?,
    val image: String?,
    val country: String?,
    val type: String?,
    val year: String?,
    val popularity: Long?,
    val description: String?,
    val status: String?,
    val releaseDate: String?,
    val isSub: Boolean?
)

data class Recent(
    @param:JsonProperty("_id") val id: String?,
    @param:JsonProperty("id") val id2: String?,
    val title: String?,
    val image: String?,
    val country: String?,
    val type: String?,
    val year: String?,
    val popularity: Long?,
    val description: String?,
    val status: String?,
    val releaseDate: String?,
    val isSub: Boolean?
)

data class CleanMedia(
    val id: String?,
    val title: String?,
    val image: String?,
    val country: String?,
    val type: String?,
    val year: String?,
    val status: String?,
    val isSub: Boolean?
)
