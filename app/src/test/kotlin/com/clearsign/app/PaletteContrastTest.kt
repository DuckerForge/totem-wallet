package com.clearsign.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every palette must stay readable: these are the floors the design promises. */
class PaletteContrastTest {
    @Test fun allPalettesAreReadable() {
        assertEquals(10, Palettes.all.size)
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

    /**
     * Depth is a contract too.
     *
     * For a long time the five structural surfaces all lived inside 2% of the
     * luminance range — `cardSoft` sat 1.03:1 above the page, a card's own border
     * 1.03:1 above the card — so every screen read as one flat sheet and the only
     * thing separating a panel from the page was a hairline nobody could see.
     * Nothing caught it, because the old test only looked at text. This does.
     */
    @Test fun surfacesAreDistinguishable() {
        for (p in Palettes.all) {
            // `ground`..`ground2` is the page's own gradient, a continuous wash
            // rather than two touching surfaces, so it is exempt: a visible seam
            // there would look cheap. The panels above it are the real ladder.
            val ladder = listOf("ground2" to p.ground2, "cardSoft" to p.cardSoft, "card" to p.card, "cardHi" to p.cardHi)
            for (i in 0 until ladder.size - 1) {
                val (loName, lo) = ladder[i]
                val (hiName, hi) = ladder[i + 1]
                val step = contrastRatio(hi, lo)
                assertTrue(step >= 1.06, "${p.id}: $loName -> $hiName is ${"%.3f".format(step)}:1, too flat to see")
            }
            // A card sits a real step above the page, not a rumour of one.
            val panel = contrastRatio(p.card, p.ground)
            assertTrue(panel >= 1.25, "${p.id}: card over ground is ${"%.2f".format(panel)}:1")
            // A border has to be visible against the thing it borders.
            val edge = contrastRatio(p.stroke, p.card)
            assertTrue(edge >= 1.3, "${p.id}: stroke over card is ${"%.2f".format(edge)}:1")
        }
    }

    /** Secondary text stays readable on the highest surface, not just on the page. */
    @Test fun textSurvivesTheRaisedSurfaces() {
        for (p in Palettes.all) {
            val muted = contrastRatio(p.muted, p.cardHi)
            val ink = contrastRatio(p.ink, p.cardHi)
            assertTrue(muted >= 4.5, "${p.id}: muted on cardHi ${"%.2f".format(muted)} < 4.5")
            assertTrue(ink >= 9.0, "${p.id}: ink on cardHi ${"%.2f".format(ink)} < 9")
        }
    }

    @Test fun idsAreUniqueAndHaloIsFree() {
        assertEquals(Palettes.all.size, Palettes.all.map { it.id }.toSet().size)
        assertTrue(Palettes.halo.isFree)
        // Four candidate themes are free on purpose: a theme you cannot select
        // is a theme you cannot judge.
        assertTrue(Palettes.all.count { it.premium } == 5)
        assertTrue(Palettes.all.count { it.isFree } == 5)
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
