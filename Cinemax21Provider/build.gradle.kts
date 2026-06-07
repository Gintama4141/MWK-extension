// use an integer for version numbers
version = 5


cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them

    description = "CineMax21 — Streaming Movie and TV Series"
    authors = listOf("Miku", "MWK")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "AsianDrama",
        "TvSeries",
        "Movie",
    )


    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://cinemax21.cfd&size=%size%"

}
