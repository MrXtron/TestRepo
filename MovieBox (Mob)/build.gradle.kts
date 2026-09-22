// Use an integer for version numbers
version = 24

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    val cloudstream by configurations
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    cloudstream("com.lagradost:cloudstream3:pre-release")

}

cloudstream {
    language = "hi"

    description = "Multi Language Movies and Series Provider"
    authors = listOf("Xtron")

    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    requiresResources = true

    iconUrl =
        "https://raw.githubusercontent.com/MrXtron/CloudStream-Extension/refs/heads/main/Files/Icons/MovieBox.png"

    isCrossPlatform = false
}
