package com.epcbc.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Instrumented test (needs the device's XmlPullParser implementation, which isn't available
 * in plain JVM unit tests). Builds a minimal in-memory .xlsx and checks parsing, column
 * mapping by header name, and the skip rules.
 */
@RunWith(AndroidJUnit4::class)
class PackingListReaderTest {

    private val sharedStrings = listOf(
        "SKU", "Barcode", "Item Name", "Qty",   // 0..3 headers
        "ABC", "5400816848493", "Jeans",         // 4..6 row 2
        "DEF", "1234567890128", "Shirt"          // 7..9 row 3
    )

    private val workbookXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <workbook><sheets><sheet name="TestSheet" sheetId="1"/></sheets></workbook>
    """.trimIndent()

    private val sharedStringsXml = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><sst>")
        for (s in sharedStrings) append("<si><t>$s</t></si>")
        append("</sst>")
    }

    // A=SKU(s) B=Barcode(s) C=Name(s) D=Qty(number). Row 4 has no barcode; row 5 has qty 0.
    private val sheetXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <worksheet><sheetData>
        <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c></row>
        <row r="2"><c r="A2" t="s"><v>4</v></c><c r="B2" t="s"><v>5</v></c><c r="C2" t="s"><v>6</v></c><c r="D2"><v>5</v></c></row>
        <row r="3"><c r="A3" t="s"><v>7</v></c><c r="B3" t="s"><v>8</v></c><c r="C3" t="s"><v>9</v></c><c r="D3"><v>3</v></c></row>
        <row r="4"><c r="A4" t="s"><v>7</v></c><c r="D4"><v>2</v></c></row>
        <row r="5"><c r="B5" t="s"><v>5</v></c><c r="D5"><v>0</v></c></row>
        </sheetData></worksheet>
    """.trimIndent()

    private fun buildXlsx(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            fun put(name: String, content: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            put("xl/workbook.xml", workbookXml)
            put("xl/sharedStrings.xml", sharedStringsXml)
            put("xl/worksheets/sheet1.xml", sheetXml)
        }
        return bos.toByteArray()
    }

    @Test fun reads_valid_rows_and_maps_columns_by_header() {
        val result = PackingListReader.read(ByteArrayInputStream(buildXlsx()))

        assertEquals("TestSheet", result.sheetName)
        assertEquals(2, result.items.size)
        assertEquals(2, result.skipped)   // row 4 (no barcode) + row 5 (qty 0)

        val first = result.items[0]
        assertEquals("ABC", first.sku)
        assertEquals("5400816848493", first.barcode)
        assertEquals("Jeans", first.name)
        assertEquals(5, first.qty)

        val second = result.items[1]
        assertEquals("DEF", second.sku)
        assertEquals("1234567890128", second.barcode)
        assertEquals(3, second.qty)
    }

    @Test fun missing_sheet_returns_warning() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("xl/workbook.xml"))
            zos.write(workbookXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val result = PackingListReader.read(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(0, result.items.size)
        assertNull(result.items.firstOrNull())
        assertEquals(true, result.warnings.isNotEmpty())
    }
}
