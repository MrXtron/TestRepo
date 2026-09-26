// use an integer for version numbers
version = 21

cloudstream {
    language = "hi"
    // All of these properties are optional, you can safely remove them
    description = "Ultimate hub for Indian Hindi Daily Soaps & Dramas"
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

    iconUrl = "https://raw.githubusercontent.com/MrXtron/CloudStream-Extension/refs/heads/main/Files/Icons/XtronTV.png"

    isCrossPlatform = false
}
