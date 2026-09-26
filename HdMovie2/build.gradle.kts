// use an integer for version numbers
version = 13

cloudstream {
    language = "hi"
    // All of these properties are optional, you can safely remove them

    description = "Hdmovie2"
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
        "TvSeries",
        "Movie",
    )

    iconUrl = "https://raw.githubusercontent.com/MrXtron/CloudStream-Extension/refs/heads/main/Files/Icons/HDMovie2.png"

    isCrossPlatform = false
}

// Dependency block to resolve JSpecify compile-time type inference errors
dependencies {
    compileOnly("org.jspecify:jspecify:1.0.0")
}
