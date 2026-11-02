package com.stellarite.apps.ui

import com.stellarite.apps.data.Source

/** One line in the preview under a detected GitHub account or repo. */
data class PreviewLine(val name: String, val detail: String)

/** State of the Add source screen. A non-null value means the screen is showing. */
data class AddSourceUi(
    val input: String = "",
    /** What the pasted link was recognised as, once it has been. */
    val detected: Source? = null,
    val checking: Boolean = false,
    val error: String? = null,
    val summary: String? = null,
    val preview: List<PreviewLine> = emptyList(),
)

/** Everything the Sources panel in Control Core shows. */
data class SourcesPanel(
    val homeTitle: String,
    val homeSub: String,
    val homeCount: Int,
    val sources: List<Source>,
    val counts: Map<String, Int>,
    val errors: Map<String, String>,
)
