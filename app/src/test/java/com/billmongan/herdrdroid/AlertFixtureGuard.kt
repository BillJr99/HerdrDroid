package com.billmongan.herdrdroid

import com.billmongan.herdrdroid.herdr.CARD_CHANGED_EXIT
import com.billmongan.herdrdroid.herdr.CARD_PASSED_MARK
import com.billmongan.herdrdroid.herdr.CARD_REFUSED_MARK
import com.billmongan.herdrdroid.herdr.cardGuardScreenMatches
import com.billmongan.herdrdroid.herdr.cardOccupantDigest
import com.billmongan.herdrdroid.ssh.ExecResult
import org.json.JSONObject

/** Fake transport's host-side guard, checked against its current occupant and live viewport. */
internal fun runAlertFixtureGuard(command: String, paneId: String, pane: String, screen: String,
    send: (String) -> ExecResult): ExecResult? {
    val marker = " && echo $CARD_PASSED_MARK && "
    if (marker !in command) return null
    val quotedPane = Regex.escape("'$paneId'")
    val args = Regex("""$quotedPane '([0-9a-f]{64})' '((?:r:[0-9]+:[0-9]+:)?[0-9a-f]{64})'""")
        .findAll(command.substringBefore(marker)).last()
    val current = JSONObject(pane)
    if (cardOccupantDigest(current) != args.groupValues[1] ||
        !cardGuardScreenMatches(screen, args.groupValues[2], current.optString("agent"))) {
        return ExecResult(CARD_CHANGED_EXIT, CARD_REFUSED_MARK, "")
    }
    var result = ExecResult(0, "", "")
    for (part in command.substringAfter(marker).split(" && ")) {
        result = send(part)
        if (result.exit != 0) break
    }
    return result.copy(out = "$CARD_PASSED_MARK\n${result.out}")
}
