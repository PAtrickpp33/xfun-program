package com.didi.dimina.common

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniProgramViewportGeometryTest {
    @Test
    fun customNavigationMatchesNativeViewAndRetainsStatusRegion() {
        // Android 15 emulator: window 1920px, bottom inset 63px, density 2.625.
        // The custom-navigation WebView measures 1857px high (707 logical px).
        assertEquals(707, MiniProgramViewportGeometry.height(731, 24, 24, true))
    }

    @Test
    fun standardNavigationKeepsExistingSafeAreaHeight() {
        assertEquals(683, MiniProgramViewportGeometry.height(731, 24, 24, false))
    }

    @Test
    fun customNavigationStillExcludesThreeButtonNavigationBar() {
        assertEquals(752, MiniProgramViewportGeometry.height(800, 32, 48, true))
    }

    @Test
    fun tinyWindowNeverReportsNegativeHeight() {
        assertEquals(0, MiniProgramViewportGeometry.height(20, 24, 48, true))
        assertEquals(0, MiniProgramViewportGeometry.height(20, 24, 48, false))
    }
}
