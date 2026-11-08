package com.didi.dimina.common

internal object MiniProgramViewportGeometry {
    fun height(fullHeight: Int, statusInset: Int, bottomInset: Int, customNavigation: Boolean): Int {
        // Dimina's outer Scaffold already removes the Android bottom inset.
        // Custom navigation renders beneath the status bar and reserves that
        // region in the page, so subtracting it here would shorten content twice.
        val topInset = if (customNavigation) 0 else statusInset
        return (fullHeight - bottomInset - topInset).coerceAtLeast(0)
    }
}
