package com.billmongan.herdrdroid.board

import com.billmongan.herdrdroid.transcript.TextItem
import com.billmongan.herdrdroid.transcript.Transcript
import com.billmongan.herdrdroid.transcript.UserItem
import org.json.JSONObject

/** Only the assistant turn after this phone's request can supply a board reply. */
internal class BoardTranscript(private val request: BoardRequest) {
    private val requestHeading = "HerdrDroid board request ${request.id}."
    private val text = StringBuilder()
    private var afterRequest = false
    private var tooLong = false

    fun append(items: List<JSONObject>): BoardReply? {
        for (record in items) {
            when (val item = Transcript.toItem(record)) {
                is UserItem -> {
                    result()?.let { return it }
                    text.clear()
                    tooLong = false
                    afterRequest = item.text.lineSequence().firstOrNull()?.trimEnd() == requestHeading
                }
                is TextItem -> if (afterRequest && !tooLong) {
                    if (text.length + item.text.length >= 1_048_576) {
                        text.clear()
                        tooLong = true
                    } else text.appendLine(item.text)
                }
                else -> Unit
            }
        }
        return null
    }

    fun result(): BoardReply? =
        if (afterRequest && !tooLong) BoardProtocol.parseReply(text.toString(), request) else null
}
