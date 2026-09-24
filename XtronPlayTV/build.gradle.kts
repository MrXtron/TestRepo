// use an integer for version numbers
version = 18


cloudstream {
    language = "hi"
    // All of these properties are optional, you can safely remove them
    description = "Ultimate hub for Indian Daily Soaps & Dramas from BollyZone and DesiSerials"
    authors = listOf("Xtron")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    iconUrl = "https://raw.githubusercontent.com/MrXtron/CloudStream-Extension/refs/heads/main/Files/Icons/XtronPlayTV.png"

    isCrossPlatform = false
}
