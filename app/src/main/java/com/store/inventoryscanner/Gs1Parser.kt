package com.store.inventoryscanner

/**
 * Lightweight GS1 Application Identifier parser.
 *
 * This does NOT decode the barcode image. ML Kit still performs the Code 128
 * image decoding. This class only normalizes a decoded GS1-128 payload and
 * extracts common AIs used by inventory labels.
 */
object Gs1Parser {
    data class Result(
        val raw: String,
        val gtin: String? = null,
        val serial: String? = null,
        val lot: String? = null,
        val expiry: String? = null,
        val productionDate: String? = null
    )

    private val fixedLengths = mapOf(
        "00" to 18, // SSCC
        "01" to 14, // GTIN
        "11" to 6,  // production date
        "13" to 6,  // packaging date
        "15" to 6,  // best before
        "17" to 6   // expiry
    )

    fun parse(input: String): Result {
        var s = input.trim()
        if (s.startsWith("\u001D")) s = s.substring(1)

        // Some scanners expose the GS character as a visible placeholder.
        s = s.replace("\\u001D", "\u001D")

        var pos = 0
        var gtin: String? = null
        var serial: String? = null
        var lot: String? = null
        var expiry: String? = null
        var production: String? = null

        while (pos + 2 <= s.length) {
            if (s[pos] == '\u001D') {
                pos++
                continue
            }

            val ai = s.substring(pos, pos + 2)
            pos += 2

            val fixed = fixedLengths[ai]
            if (fixed != null) {
                if (pos + fixed > s.length) break
                val value = s.substring(pos, pos + fixed)
                pos += fixed

                when (ai) {
                    "01" -> gtin = value
                    "11", "13" -> if (production == null) production = value
                    "15", "17" -> if (expiry == null) expiry = value
                }
                continue
            }

            val variable = when {
                ai == "10" -> "lot"
                ai == "21" -> "serial"
                else -> null
            }

            if (variable == null) {
                // Unknown AI: stop rather than guessing field boundaries.
                break
            }

            val end = s.indexOf('\u001D', pos).let { if (it < 0) s.length else it }
            val value = s.substring(pos, end)
            pos = if (end < s.length) end + 1 else end

            when (variable) {
                "lot" -> lot = value
                "serial" -> serial = value
            }
        }

        return Result(
            raw = input,
            gtin = gtin,
            serial = serial,
            lot = lot,
            expiry = expiry,
            productionDate = production
        )
    }

    fun lookupKeys(input: String): List<String> {
        val raw = input.trim()
        val parsed = parse(raw)
        val keys = linkedSetOf<String>()

        if (raw.isNotEmpty()) keys += raw
        parsed.gtin?.let { gtin ->
            keys += gtin
            if (gtin.length == 14 && gtin.all(Char::isDigit)) {
                keys += gtin.trimStart('0').ifEmpty { "0" }
                if (gtin.startsWith("0")) keys += gtin.substring(1)
            }
        }

        return keys.toList()
    }
}
