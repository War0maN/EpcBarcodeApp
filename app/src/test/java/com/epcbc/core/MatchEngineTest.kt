package com.epcbc.core

import com.epcbc.data.PackingListReader.PackingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the canonical matcher. EPC→barcode fixtures are the real Levi's partition-5
 * samples also used by [EpcDecoderTest] (all three decode to the same GTIN-13).
 */
class MatchEngineTest {

    private val epc1 = "303549A3C052DC4CCE41F3B0"
    private val epc2 = "303549A3C052DC4CCE41F434"
    private val epc3 = "303549A3C052DC4CCE41F442"
    private val barcode = "5400816848493"

    private fun item(barcode: String, qty: Int) =
        PackingItem(sku = "SKU", barcode = barcode, name = "Name", qty = qty, rowIndex = 1)

    @Test fun groupByBarcode_groups_same_product_together() {
        val grouped = MatchEngine.groupByBarcode(listOf(epc1, epc2, epc3))
        assertEquals(setOf(epc1, epc2, epc3), grouped[barcode])
    }

    @Test fun groupByBarcode_dedupes_identical_epcs() {
        val grouped = MatchEngine.groupByBarcode(listOf(epc1, epc1, epc2))
        assertEquals(2, grouped[barcode]?.size)
    }

    @Test fun summarize_complete_sku() {
        val s = MatchEngine.summarize(listOf(item(barcode, 3)), listOf(epc1, epc2, epc3))
        assertEquals(3, s.matchedPieces)
        assertEquals(0, s.missingPieces)
        assertEquals(0, s.overPieces)
        assertEquals(3, s.totalExpected)
        assertEquals(0, s.orphanCount)
        assertTrue(s.rows[0].isComplete)
        assertFalse(s.rows[0].isMissing)
    }

    @Test fun summarize_missing_pieces() {
        val s = MatchEngine.summarize(listOf(item(barcode, 5)), listOf(epc1, epc2))
        assertEquals(2, s.matchedPieces)
        assertEquals(3, s.missingPieces)
        assertTrue(s.rows[0].isMissing)
    }

    @Test fun summarize_over_read_is_capped_for_matched_but_flagged() {
        val s = MatchEngine.summarize(listOf(item(barcode, 2)), listOf(epc1, epc2, epc3))
        assertEquals(2, s.matchedPieces)   // capped at qty
        assertEquals(1, s.overPieces)
        assertEquals(0, s.missingPieces)
        assertTrue(s.rows[0].isOver)
    }

    @Test fun summarize_orphans_when_barcode_not_in_list() {
        val s = MatchEngine.summarize(listOf(item("9999999999999", 1)), listOf(epc1, epc2))
        assertEquals(0, s.matchedPieces)
        assertEquals(2, s.orphanCount)
        assertEquals(1, s.missingPieces)   // the listed SKU read nothing
    }

    @Test fun summarize_empty_scan_is_all_missing() {
        val s = MatchEngine.summarize(listOf(item(barcode, 4)), emptyList())
        assertEquals(0, s.matchedPieces)
        assertEquals(4, s.missingPieces)
        assertEquals(0, s.orphanCount)
    }
}
