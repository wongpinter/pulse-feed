package com.simon.harmonichackernews.feed.parser

import kotlin.time.Instant

/**
 * Robust date parser for RSS (RFC 822 / 1123) and Atom/JSON Feed (ISO 8601).
 * Normalizes input to ISO-8601 and delegates calendar math to [kotlin.time.Instant.parse].
 */
internal object FeedDateParser {
    private val MONTH_MAP = mapOf(
        "jan" to "01", "feb" to "02", "mar" to "03", "apr" to "04",
        "may" to "05", "jun" to "06", "jul" to "07", "aug" to "08",
        "sep" to "09", "oct" to "10", "nov" to "11", "dec" to "12",
    )

    private val TIMEZONE_OFFSETS = mapOf(
        "gmt" to "Z", "utc" to "Z", "ut" to "Z", "z" to "Z",
        "est" to "-05:00", "edt" to "-04:00",
        "cst" to "-06:00", "cdt" to "-05:00",
        "mst" to "-07:00", "mdt" to "-06:00",
        "pst" to "-08:00", "pdt" to "-07:00",
    )

    // Regex for RFC 822 / 1123 dates:
    // e.g. "Mon, 27 Oct 2026 14:30:00 GMT" or "27 Oct 2026 14:30:00 +0200" or "27 Oct 26 14:30:00 Z"
    private val RFC_822_REGEX = Regex(
        """(?:[A-Za-z]{3},\s+)?(\d{1,2})\s+([A-Za-z]{3})\s+(\d{2,4})\s+(\d{2}:\d{2}(?::\d{2})?)(?:\s+([A-Za-z]+|[+-]\d{4}))?""",
    )

    fun parseEpochMs(rawDate: String?): Long {
        if (rawDate.isNullOrBlank()) return 0L
        val trimmed = rawDate.trim()

        // 1. Try ISO 8601 first (Atom, JSON Feed)
        val isoParsed = tryParseIso(trimmed)
        if (isoParsed != 0L) return isoParsed

        // 2. Try RFC 822 / 1123 (RSS 2.0)
        val rfcParsed = tryParseRfc822(trimmed)
        if (rfcParsed != 0L) return rfcParsed

        // 3. Epoch seconds or millis fallback
        return trimmed.toLongOrNull()?.let { num ->
            if (num < 100_000_000_000L) num * 1_000L else num
        } ?: 0L
    }

    private fun tryParseIso(raw: String): Long = runCatching {
        var normalized = raw
        // Normalize timezone offset without colon: +0000 -> +00:00
        val matchOffset = Regex("""([+-]\d{2})(\d{2})$""").find(normalized)
        if (matchOffset != null) {
            normalized = normalized.substring(0, matchOffset.range.first) +
                matchOffset.groupValues[1] + ":" + matchOffset.groupValues[2]
        }
        // Normalize single digit day or month if present: 2026-1-2 -> 2026-01-02
        if (!normalized.contains('T') && normalized.length == 10) {
            normalized += "T00:00:00Z"
        }
        Instant.parse(normalized).toEpochMilliseconds()
    }.getOrDefault(0L)

    private fun tryParseRfc822(raw: String): Long {
        val match = RFC_822_REGEX.find(raw) ?: return 0L
        val day = match.groupValues[1].padStart(2, '0')
        val monthStr = match.groupValues[2].lowercase()
        val month = MONTH_MAP[monthStr] ?: return 0L
        var year = match.groupValues[3]
        if (year.length == 2) year = "20$year"
        var time = match.groupValues[4]
        if (time.length == 5) time = "$time:00" // HH:mm -> HH:mm:ss

        val rawTz = match.groupValues[5].trim().lowercase()
        val offset = when {
            rawTz.isEmpty() -> "Z"
            TIMEZONE_OFFSETS.containsKey(rawTz) -> TIMEZONE_OFFSETS.getValue(rawTz)
            Regex("""^[+-]\d{4}$""").matches(rawTz) -> {
                rawTz.substring(0, 3) + ":" + rawTz.substring(3, 5)
            }
            else -> "Z"
        }

        val isoCandidate = "$year-$month-${day}T$time$offset"
        return runCatching { Instant.parse(isoCandidate).toEpochMilliseconds() }.getOrDefault(0L)
    }
}
