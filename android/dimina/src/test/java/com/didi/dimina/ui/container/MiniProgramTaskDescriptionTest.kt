package com.didi.dimina.ui.container

import com.didi.dimina.bean.MiniProgramTaskAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiniProgramTaskDescriptionTest {
    @Test
    fun `the host's label wins over the mini program name`() {
        assertEquals(
            "Perps",
            MiniProgramTaskDescription.label(MiniProgramTaskAppearance(label = " Perps "), "Parley Perps"),
        )
    }

    @Test
    fun `a missing or blank label falls back to the mini program name`() {
        assertEquals("Parley Perps", MiniProgramTaskDescription.label(null, "Parley Perps"))
        assertEquals("Parley Perps", MiniProgramTaskDescription.label(MiniProgramTaskAppearance(label = "  "), " Parley Perps "))
    }

    @Test
    fun `without any name the card title is left alone`() {
        assertNull(MiniProgramTaskDescription.label(null, ""))
        assertNull(MiniProgramTaskDescription.label(MiniProgramTaskAppearance(label = " "), " "))
    }

    @Test
    fun `primary colors are made opaque and 0 keeps the system default`() {
        assertEquals(0, MiniProgramTaskDescription.primaryColor(0))
        assertEquals(0xFF132D24.toInt(), MiniProgramTaskDescription.primaryColor(0x00132D24))
        assertEquals(0xFF132D24.toInt(), MiniProgramTaskDescription.primaryColor(0x80132D24.toInt()))
        assertEquals(0xFF132D24.toInt(), MiniProgramTaskDescription.primaryColor(0xFF132D24.toInt()))
    }
}
