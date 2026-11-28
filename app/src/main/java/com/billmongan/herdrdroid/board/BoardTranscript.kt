package com.billmongan.herdrdroid.board

import com.billmongan.herdrdroid.transcript.TextItem
import com.billmongan.herdrdroid.transcript.Transcript
import com.billmongan.herdrdroid.transcript.UserItem
import org.json.JSONObject

/** Only the assistant turn after this phone's request can supply a board reply. */
internal class BoardTranscript(private val request: BoardRequest) {
    private val requestHeading = "HerdrDroid board request ${request.id}."
    private val fragments = mutableListOf<String>()
    private val keyedFragments = mutableMapOf<String, Int>()
    private var textLength = 0
    private var requestKey: String? = null
    private var afterRequest = false
    private var tooLong = false

    fun append(items: List<JSONObject>): BoardReply? {
        for (record in items) {
            when (val item = Transcript.toItem(record)) {
                is UserItem -> {
                    val matchesRequest = item.text.lineSequence().firstOrNull()?.trimEnd() == requestHeading
                    if (matchesRequest && item.key.isNotEmpty() && item.key == requestKey) continue
                    result()?.let { return it }
                    clearFragments()
                    tooLong = false
                    afterRequest = matchesRequest
                    requestKey = item.key.takeIf { matchesRequest && it.isNotEmpty() }
                }
                is TextItem -> if (afterRequest && !tooLong) {
                    val existing = keyedFragments[item.key]
                    val oldLength = if (existing == null) 0 else fragments[existing].length + 1
                    val remaining = textLength - oldLength
                    if (item.text.length >= 1_048_576 - remaining) {
                        clearFragments()
                        tooLong = true
                    } else {
                        textLength = remaining + item.text.length + 1
                        if (existing != null) fragments[existing] = item.text
                        else {
                            if (item.key.isNotEmpty()) keyedFragments[item.key] = fragments.size
                            fragments.add(item.text)
                        }
                    }
                }
                else -> Unit
            }
        }
        return null
    }

    private fun clearFragments() {
        fragments.clear()
        keyedFragments.clear()
        textLength = 0
    }

    fun result(): BoardReply? =
        if (afterRequest && !tooLong) BoardProtocol.parseReply(fragments.joinToString("\n"), request) else null
}
