package com.billmongan.herdrdroid.board

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BoardTranscript2210Test {
    private val request = BoardRequest(BoardRequestKind.EXPORT, null, id = "board-request")
    private val answer = """{"herdrdroid_board":1,"request":"board-request","board":"Example","as_of":"2026-01-01T00:00:00Z","cards":[],"deleted":[],"results":[]}"""
    private val block = "```json\n$answer\n```"
    private fun item(role: String, text: String) = JSONObject().put("t", role).put("text", text)

    @Test fun acceptsTheMatchingAssistantTurnAcrossReads() {
        val receipt = BoardTranscript(request)
        receipt.append(listOf(item("user", BoardProtocol.message(request)), item("text", "```json")))
        assertNull(receipt.result())
        receipt.append(listOf(item("text", "$answer\n```")))
        assertEquals("Example", receipt.result()?.board)
    }

    @Test fun ignoresToolOutputAndOtherTurns() {
        val receipt = BoardTranscript(request)
        receipt.append(listOf(item("user", "Other prompt"), item("text", block)))
        assertNull(receipt.result())
        receipt.append(listOf(item("user", BoardProtocol.message(request)), item("note", block)))
        assertNull(receipt.result())
        receipt.append(listOf(item("user", block), item("text", block)))
        assertNull(receipt.result())
    }
}
