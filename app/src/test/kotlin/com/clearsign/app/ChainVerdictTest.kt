package com.clearsign.app

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ChainVerdictTest {
    @Test
    fun `a status with an error is a failure`() {
        assertEquals(false, SolanaRpc.chainVerdict(JSONObject("""{"slot":1,"confirmations":null,"err":{"InstructionError":[2,{"Custom":6001}]},"confirmationStatus":"finalized"}""")))
    }

    @Test
    fun `confirmed or finalized without error has landed`() {
        assertEquals(true, SolanaRpc.chainVerdict(JSONObject("""{"slot":1,"err":null,"confirmationStatus":"confirmed"}""")))
        assertEquals(true, SolanaRpc.chainVerdict(JSONObject("""{"slot":1,"err":null,"confirmationStatus":"finalized"}""")))
    }

    @Test
    fun `processed only, or unknown, is not a verdict`() {
        assertEquals(null, SolanaRpc.chainVerdict(JSONObject("""{"slot":1,"err":null,"confirmationStatus":"processed"}""")))
        assertEquals(null, SolanaRpc.chainVerdict(null))
    }
}
