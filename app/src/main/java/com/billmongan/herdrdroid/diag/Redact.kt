package com.billmongan.herdrdroid.diag

/**
 * Takes secrets out of text the app hands to the user to share (1.4.0, the debug bundle): API
 * keys and tokens in their usual shapes, private key blocks, JWTs, "password=..." style
 * assignments, web addresses (kept as their scheme only, as [DiagLog.hideUrl] does) and e-mail
 * addresses. What's left is the screen's shape, which is what a parser bug needs.
 */
object Redact {
    private val RULES: List<Pair<Regex, String>> = listOf(
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----""") to "[private key redacted]",
        // Before the keyword rule, so "Authorization: Bearer x" loses x, not just the word Bearer.
        Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/-]{12,}=*""") to "Bearer [redacted]",
        // A keyword with ':' or '=' (not plain prose such as "the token here"), and first of the
        // value rules, so it never matches inside a placeholder another rule put in (from a review).
        Regex("""(?i)\b(password|passwd|passphrase|secret|token|api[_-]?key)(\s*[:=]\s*)("[^"]*"|'[^']*'|[^\s\[]\S*)""") to "$1$2[redacted]",
        Regex("""\b(?:sk|pk|rk)-[A-Za-z0-9_-]{16,}""") to "[key redacted]",
        Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}|\bgithub_pat_[A-Za-z0-9_]{20,}""") to "[token redacted]",
        Regex("""\bAKIA[0-9A-Z]{16}\b""") to "[key redacted]",
        Regex("""\bxox[abprs]-[A-Za-z0-9-]{10,}""") to "[token redacted]",
        Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""") to "[token redacted]",
        Regex("""\b(?:wss?|https?)://\S+""", RegexOption.IGNORE_CASE) to "",
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to "[e-mail redacted]",
        // A long unbroken run of base64 or hex, as a stray key or token would be.
        Regex("""\b[A-Za-z0-9+/]{40,}={0,2}""") to "[long value redacted]",
    )

    fun text(s: String): String {
        var out = s
        for ((re, with) in RULES) {
            out = if (with.isEmpty()) re.replace(out) { m -> DiagLog.hideUrl(m.value) } else re.replace(out, with)
        }
        return out
    }
}
