package com.finaxis.platform.lifecycle.adapter.inbound.web

/**
 * A strict RFC 4180 reader for tests, written independently of the production writer so that a
 * round trip proves the encoding rather than mirroring it. Records are separated by CRLF (a lone
 * LF outside quotes is refused, as the export promises CRLF), cells may be quoted, and a quoted
 * cell holds commas, CR, LF and doubled quotes.
 */
internal object CsvTestParser {
    /** Parses [text] into records of cells; an empty trailing record is not returned. */
    fun parse(text: String): List<List<String>> = Reader(text).read()

    private class Reader(
        private val text: String,
    ) {
        private val records = mutableListOf<List<String>>()
        private var row = mutableListOf<String>()
        private val cell = StringBuilder()
        private var quoted = false
        private var started = false
        private var index = 0

        fun read(): List<List<String>> {
            while (index < text.length) {
                val c = text[index]
                if (quoted) inQuotes(c) else outsideQuotes(c)
                index++
            }
            check(!quoted) { "unterminated quoted cell" }
            if (started || row.isNotEmpty()) endRecord()
            return records
        }

        private fun inQuotes(c: Char) {
            when {
                c == '"' && text.getOrNull(index + 1) == '"' -> {
                    cell.append('"')
                    index++
                }

                c == '"' -> {
                    quoted = false
                }

                else -> {
                    cell.append(c)
                }
            }
        }

        private fun outsideQuotes(c: Char) {
            when {
                c == ',' -> {
                    endCell()
                }

                c == '"' -> {
                    check(!started) { "a quote inside an unquoted cell at $index" }
                    quoted = true
                    started = true
                }

                c == '\r' && text.getOrNull(index + 1) == '\n' -> {
                    endRecord()
                    index++
                }

                c == '\n' || c == '\r' -> {
                    error("a bare line break outside quotes at $index")
                }

                else -> {
                    cell.append(c)
                    started = true
                }
            }
        }

        private fun endCell() {
            row += cell.toString()
            cell.clear()
            started = false
        }

        private fun endRecord() {
            endCell()
            records += row
            row = mutableListOf()
        }
    }
}
