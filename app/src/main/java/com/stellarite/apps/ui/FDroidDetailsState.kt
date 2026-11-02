package com.stellarite.apps.ui

import com.stellarite.apps.data.FDroidDetails

/** State of the extra sections on an F-Droid app's page. */
sealed interface FDroidDetailsState {
    data object Loading : FDroidDetailsState
    data object NeedsWifi : FDroidDetailsState
    data class Loaded(val details: FDroidDetails) : FDroidDetailsState
    data class Failed(val message: String) : FDroidDetailsState
}
