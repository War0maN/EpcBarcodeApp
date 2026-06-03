package com.epcbc.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Pure-Kotlin XLSX / XLSM packing-list reader. No external library needed — uses Android's
 * built-in ZipInputStream and XmlPullParser only.
 *
 * Reads the first worksheet of the workbook, treats row 1 as headers, and maps the columns
 * by name (case-insensitive, partial match). Recognised header keywords:
 *   - SKU       : "sku" | "item code"
 *   - Barcode   : "barcode" | "gtin" | "ean"
 *   - Name      : "name" | "item name" | "description"
 *   - Qty       : "qty" | "quantity" | "тоо"
 *
 * Rows without a barcode OR without a positive qty are skipped.
 */
object PackingListReader {

    data class PackingItem(
        val sku: String?,
        val barcode: String,
        val name: String?,
        val qty: Int,
        val rowIndex: Int
    )

    data class Result(
        val items: List<PackingItem>,
        val totalRows: Int,
        val skipped: Int,
        val sheetName: String?,
        val warnings: List<String>
    )

    fun read(input: InputStream): Result {
        var sharedStrings: List<String> = emptyList()
        var sheetXml: ByteArray? = null
        var sheetName: String? = null
        var workbookXml: ByteArray? = null

        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == "xl/sharedStrings.xml" -> sharedStrings = parseSharedStrings(zis)
                    name == "xl/workbook.xml" -> workbookXml = zis.readBytes()
                    // First sheet — typical names: "xl/worksheets/sheet1.xml"
                    name.startsWith("xl/worksheets/sheet1.") && sheetXml == null ->
                        sheetXml = zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }

        workbookXml?.let { sheetName = firstSheetName(ByteArrayInputStream(it)) }
        val sheet = sheetXml
            ?: return Result(emptyList(), 0, 0, sheetName, listOf("sheet1.xml not found in workbook"))

        return parseSheet(ByteArrayInputStream(sheet), sharedStrings, sheetName)
    }

    // ---- helpers ----------------------------------------------------------

    private fun parseSharedStrings(input: InputStream): List<String> {
        val strings = mutableListOf<String>()
        val parser = newParser(input)
        var current: StringBuilder? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "si" -> current = StringBuilder()
                    "t" -> if (current != null) {
                        val text = parser.nextText()
                        current.append(text)
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "si") {
                strings.add(current?.toString() ?: "")
                current = null
            }
            event = try { parser.next() } catch (e: Exception) { break }
        }
        return strings
    }

    private fun firstSheetName(input: InputStream): String? {
        val parser = newParser(input)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                return parser.getAttributeValue(null, "name")
            }
            event = try { parser.next() } catch (e: Exception) { break }
        }
        return null
    }

    /** Map column letter (e.g. "B", "AC") to zero-based index. */
    private fun columnIndex(ref: String): Int {
        var idx = 0
        for (c in ref) {
            if (!c.isLetter()) break
            idx = idx * 26 + (c.uppercaseChar() - 'A' + 1)
        }
        return idx - 1
    }

    private fun parseSheet(input: InputStream, sharedStrings: List<String>, sheetName: String?): Result {
        val warnings = mutableListOf<String>()
        val parser = newParser(input)
        // Collect rows as map of colIndex -> string value
        val rows = mutableListOf<Map<Int, String>>()
        var currentRow: MutableMap<Int, String>? = null
        var currentCellRef: String? = null
        var currentCellType: String? = null
        var inValue = false
        var valueBuf = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> currentRow = mutableMapOf()
                    "c" -> {
                        currentCellRef = parser.getAttributeValue(null, "r")
                        currentCellType = parser.getAttributeValue(null, "t")
                    }
                    "v" -> { inValue = true; valueBuf.setLength(0) }
                    "t" -> { inValue = true; valueBuf.setLength(0) } // inline string
                }
                XmlPullParser.TEXT -> if (inValue) valueBuf.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> {
                        if (inValue && currentRow != null && currentCellRef != null) {
                            val raw = valueBuf.toString()
                            val resolved = if (currentCellType == "s") {
                                val idx = raw.toIntOrNull() ?: -1
                                sharedStrings.getOrNull(idx) ?: ""
                            } else raw
                            val col = columnIndex(currentCellRef!!)
                            if (col >= 0) currentRow!![col] = resolved
                        }
                        inValue = false
                    }
                    "row" -> {
                        currentRow?.let { rows.add(it) }
                        currentRow = null
                    }
                }
            }
            event = try { parser.next() } catch (e: Exception) { break }
        }

        if (rows.isEmpty()) {
            return Result(emptyList(), 0, 0, sheetName, listOf("No rows found"))
        }

        // Header detection: first non-empty row
        val headerRowIdx = rows.indexOfFirst { it.isNotEmpty() }
        val header = rows[headerRowIdx]
        val colBarcode = findCol(header, listOf("barcode", "gtin", "ean", "баркод"))
        val colQty     = findCol(header, listOf("qty", "quantity", "тоо"))
        val colSku     = findCol(header, listOf("sku", "item code", "itemcode"))
        val colName    = findCol(header, listOf("name", "item name", "description"))

        if (colBarcode < 0) warnings.add("Barcode баганыг олж чадсангүй (header нэр шалга)")
        if (colQty < 0)     warnings.add("Qty баганыг олж чадсангүй (header нэр шалга)")

        val items = mutableListOf<PackingItem>()
        var skipped = 0
        for (i in (headerRowIdx + 1) until rows.size) {
            val row = rows[i]
            if (row.isEmpty()) { continue }
            val barcode = row[colBarcode]?.trim()?.takeIf { it.isNotEmpty() }
            val qtyRaw = row[colQty]?.trim()
            val qty = qtyRaw?.toDoubleOrNull()?.toInt()
            if (barcode == null || qty == null || qty <= 0) {
                skipped++
                continue
            }
            items.add(
                PackingItem(
                    sku = row[colSku]?.trim()?.takeIf { it.isNotEmpty() },
                    barcode = barcode,
                    name = row[colName]?.trim()?.takeIf { it.isNotEmpty() },
                    qty = qty,
                    rowIndex = i + 1
                )
            )
        }

        return Result(items, rows.size, skipped, sheetName, warnings)
    }

    private fun findCol(header: Map<Int, String>, keywords: List<String>): Int {
        for ((idx, value) in header) {
            val v = value.lowercase().trim()
            if (keywords.any { kw -> v.contains(kw) }) return idx
        }
        return -1
    }

    private fun newParser(input: InputStream): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")
        return parser
    }
}
