package com.stellarite.apps.ui

import com.stellarite.apps.data.ArchiveApp

enum class ArchivePhase { DATA_WARNING, SYNC, READY, ERROR }
enum class UpdatedRange { ANY, YEAR, MONTH }
enum class ArchiveSort { AZ, ZA, NEWEST }

data class ArchiveFilters(
    val fitsDevice: Boolean = true,
    val hideAntiFeatures: Boolean = false,
    /** null means all categories. */
    val category: String? = null,
    val updated: UpdatedRange = UpdatedRange.ANY,
    val sort: ArchiveSort = ArchiveSort.AZ,
)

/** State of the archive screen for one large repository. A non-null value means it is showing. */
data class ArchiveUi(
    val repoUrl: String,
    val label: String,
    val phase: ArchivePhase = ArchivePhase.SYNC,
    /** 0 = downloading, 1 = verifying, 2 = building the list. */
    val step: Int = 0,
    val bytes: Long = 0,
    val total: Long = -1,
    /** Size of the download, shown in the mobile-data warning; -1 when unknown. */
    val sizeBytes: Long = -1,
    val verified: Boolean = false,
    val error: String? = null,
    val all: List<ArchiveApp> = emptyList(),
    val shown: List<ArchiveApp> = emptyList(),
    val categories: List<String> = emptyList(),
    val query: String = "",
    val filters: ArchiveFilters = ArchiveFilters(),
    val showFilters: Boolean = false,
    /** Package ids ticked for the catalog (starts as the source's existing picks). */
    val selected: Set<String> = emptySet(),
)

/** One row on the Archives tab. */
data class ArchiveCard(
    val repoUrl: String,
    val label: String,
    val blurb: String,
    /** App count once the index has been read; null before the first sync. */
    val count: Int?,
    val verified: Boolean?,
    val syncedAt: Long,
    val picked: Int,
)
