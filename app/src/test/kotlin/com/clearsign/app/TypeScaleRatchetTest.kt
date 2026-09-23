package com.clearsign.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The type-scale ratchet. `HaloType` has seven roles; every hand-written `fontSize = 12.5.sp`
 * is one more size nobody decided. This counts them in the app sources and fails if the number rises: it can only go down.
 */
class TypeScaleRatchetTest {
    /** Today's ceiling. Lower it when one is removed, never raise it. */
    private val ceiling = 649

    @Test fun `le dimensioni scritte a mano non crescono`() {
        // Tests run with the working directory on the `app` module: look for
        // `src/main/kotlin/com/clearsign/app` from here up, and under `app/` too.
        val src = generateSequence(File(".").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "src/main/kotlin/com/clearsign/app"), File(it, "app/src/main/kotlin/com/clearsign/app")) }
            .first { it.isDirectory }
        val files = src.listFiles { f -> f.name.endsWith(".kt") }.orEmpty()
        assertTrue(files.isNotEmpty(), "sorgenti non trovati da ${File(".").absolutePath}")
        val count = files.sumOf { f -> Regex("fontSize\\s*=\\s*\\d").findAll(f.readText()).count() }
        assertTrue(count <= ceiling, "fontSize scritti a mano: $count, il tetto e' $ceiling. Usa HaloType.")
    }
}
