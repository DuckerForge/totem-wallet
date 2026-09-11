package com.clearsign.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every palette must stay readable: these are the floors the design promises. */
class PaletteContrastTest {
    @Test fun allPalettesAreReadable() {
        assertEquals(4, Palettes.all.size)
        for (p in Palettes.all) {
            for (ground in listOf(p.ground, p.ground2)) {
                val muted = contrastRatio(p.muted, ground)
                val accent = contrastRatio(p.accent, ground)
                val accent2 = contrastRatio(p.accent2, ground)
                val ink = contrastRatio(p.ink, ground)
                val amber = contrastRatio(p.amber, ground)
                val red = contrastRatio(p.red, ground)
                assertTrue(muted >= 4.5, "${p.id}: muted ${"%.2f".format(muted)} < 4.5")
                assertTrue(accent >= 7.0, "${p.id}: accent ${"%.2f".format(accent)} < 7")
                assertTrue(accent2 >= 7.0, "${p.id}: accent2 ${"%.2f".format(accent2)} < 7")
                assertTrue(ink >= 12.0, "${p.id}: ink ${"%.2f".format(ink)} < 12")
                assertTrue(amber >= 7.0, "${p.id}: amber ${"%.2f".format(amber)} < 7")
                assertTrue(red >= 4.5, "${p.id}: red ${"%.2f".format(red)} < 4.5")
            }
        }
    }

    @Test fun idsAreUniqueAndHaloIsFree() {
        assertEquals(Palettes.all.size, Palettes.all.map { it.id }.toSet().size)
        assertTrue(Palettes.halo.isFree)
        assertTrue(Palettes.all.filter { it.premium }.size == 3)
        assertEquals(Palettes.halo, Palettes.byId("nope"))
    }

    @Test fun everyPaletteHasTypeAndStyle() {
        for (p in Palettes.all) {
            assertTrue(p.radiusScale in 0.1f..1f, p.id)
            assertTrue(p.iconStroke in 0.7f..1.5f, p.id)
            assertTrue(p.fonts.tracking in 0f..1f, p.id)
        }
        assertEquals(setOf(ReceiptStyle.CARDS, ReceiptStyle.PAPER, ReceiptStyle.TERMINAL), Palettes.all.map { it.receiptStyle }.toSet())
        assertEquals(Palettes.phosphor.fonts.display, Palettes.phosphor.fonts.mono)
        assertTrue(Palettes.ember.fonts.display != Palettes.halo.fonts.display)
    }
}
