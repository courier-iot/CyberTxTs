package com.stellarite.apps.data

/** An app Stellarite recommends on the home page, installed from a verified F-Droid-style index. */
data class Suggestion(
    val key: String,
    val title: String,
    val sub: String,
    val blurb: String,
    val repoUrl: String,
    val pkg: String,
)

object Suggestions {
    val ALL = listOf(
        Suggestion(
            key = FDROID_PREFIX + "org.fdroid.fdroid",
            title = "F-Droid",
            sub = "Official app · f-droid.org",
            blurb = "The free and open-source app store. Downloaded from F-Droid's own repository once its " +
                "signature checks out. Made by the F-Droid project, not Stellarite.",
            repoUrl = SourceLinks.FDROID_URL,
            pkg = "org.fdroid.fdroid",
        ),
    )
}
