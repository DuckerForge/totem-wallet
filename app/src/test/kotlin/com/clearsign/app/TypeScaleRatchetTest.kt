package com.clearsign.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Il cricchetto della scala tipografica.
 *
 * `HaloType` ha sette ruoli. Ogni `fontSize = 12.5.sp` scritto a mano e' una
 * dimensione in piu' che nessuno ha deciso. Questo test conta quelle a mano
 * nei sorgenti dell'app e fallisce se il numero sale: si puo' solo scendere,
 * e il tetto si abbassa a ogni pagina rimessa in ordine.
 */
class TypeScaleRatchetTest {
    /** Il tetto di oggi. Abbassarlo quando si toglie, mai alzarlo. */
    private val ceiling = 697

    @Test fun `le dimensioni scritte a mano non crescono`() {
        // I test girano con la cartella di lavoro sul modulo `app`: si cerca
        // `src/main/kotlin/com/clearsign/app` da qui in su, e anche sotto `app/`.
        val src = generateSequence(File(".").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "src/main/kotlin/com/clearsign/app"), File(it, "app/src/main/kotlin/com/clearsign/app")) }
            .first { it.isDirectory }
        val files = src.listFiles { f -> f.name.endsWith(".kt") }.orEmpty()
        assertTrue(files.isNotEmpty(), "sorgenti non trovati da ${File(".").absolutePath}")
        val count = files.sumOf { f -> Regex("fontSize\\s*=\\s*\\d").findAll(f.readText()).count() }
        assertTrue(count <= ceiling, "fontSize scritti a mano: $count, il tetto e' $ceiling. Usa HaloType.")
    }
}
