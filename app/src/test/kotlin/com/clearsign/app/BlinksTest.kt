package com.clearsign.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BlinksTest {
    @Test fun theThreeShapesOfALink() {
        assertEquals("https://api.example.com/donate", Blinks.actionUrl("https://dial.to/?action=solana-action%3Ahttps%3A%2F%2Fapi.example.com%2Fdonate"))
        assertEquals("https://api.example.com/donate", Blinks.actionUrl("solana-action:https://api.example.com/donate"))
        assertEquals("https://example.com/donate/alice", Blinks.actionUrl("https://example.com/donate/alice"))
        assertNull(Blinks.actionUrl("solana:DEM0ownerWa11etF0rTests0n1yNotARea1Key111jQ"))
    }

    @Test fun rulesMapPaths() {
        val rules = """{"rules":[{"pathPattern":"/donate/*","apiPath":"/api/donate/*"},{"pathPattern":"/vote","apiPath":"/api/vote"}]}"""
        assertEquals("/api/donate/alice", Blinks.applyRules(rules, "/donate/alice"))
        assertEquals("/api/vote", Blinks.applyRules(rules, "/vote"))
        assertNull(Blinks.applyRules(rules, "/other"))
    }

    @Test fun aCardFromTheSpec() {
        val o = JSONObject("""{"title":"Donate","icon":"https://x/i.png","description":"Give","label":"Donate","links":{"actions":[{"label":"1 SOL","href":"/api/donate?amount=1"},{"label":"Custom","href":"/api/donate?amount={amount}","parameters":[{"name":"amount","label":"SOL","type":"number","required":true}]}]}}""")
        val a = Blinks.parseAction("https://api.example.com/donate", o)
        assertNotNull(a)
        assertEquals(2, a!!.buttons.size)
        assertEquals("https://api.example.com/api/donate?amount=1", a.buttons[0].href)
        assertEquals("amount", a.buttons[1].params[0].name)
        assertEquals("https://api.example.com/api/donate?amount=0.5", Blinks.fill(a.buttons[1].href, mapOf("amount" to "0.5")))
    }
}
