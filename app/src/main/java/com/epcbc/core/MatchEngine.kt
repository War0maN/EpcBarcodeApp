package com.epcbc.core

import com.epcbc.data.PackingListReader.PackingItem

/**
 * Canonical EPC ↔ packing-list matcher.
 *
 * Pure and stateless: every call recomputes from the current (packingList, scannedEpcs)
 * snapshot. That fits Compose's recomposition model and — more importantly — gives the
 * scan screen, the SKU-detail overlay, and the CSV export ONE shared implementation of
 * "decode each EPC and group by barcode" instead of three copies that can drift apart.
 *
 * Decoding goes through [EpcDecoder], which is itself cached, so repeated calls over the
 * same EPC set are cheap.
 */
object MatchEngine {

    /** Read state for a single packing-list row. */
    data class SkuResult(val item: PackingItem, val read: Int) {
        val qty: Int get() = item.qty
        val isComplete: Boolean get() = read == qty
        val isOver: Boolean get() = read > qty
        val isMissing: Boolean get() = read < qty
    }

    /** Full match snapshot: per-row results plus piece-count totals. */
    data class Summary(
        val rows: List<SkuResult>,
        val matchedPieces: Int,
        val missingPieces: Int,
        val overPieces: Int,
        val totalExpected: Int,
        val orphanCount: Int,
        val scannedByBarcode: Map<String, Set<String>>,
    )

    /**
     * Decode each EPC and group the raw EPC strings by their converted barcode.
     * EPCs that fail to decode to a barcode are dropped.
     */
    fun groupByBarcode(
        epcs: List<String>,
        allowRawHexFallback: Boolean = true,
    ): Map<String, Set<String>> {
        val map = LinkedHashMap<String, MutableSet<String>>()
        for (epc in epcs) {
            val barcode = try {
                EpcDecoder.decode(epc, allowRawHexFallback).barcode
            } catch (_: Throwable) {
                null
            } ?: continue
            map.getOrPut(barcode) { LinkedHashSet() }.add(epc)
        }
        return map
    }

    /**
     * Compute the full match summary for a [packingList] against a set of [scannedEpcs].
     * Totals are counted by PIECE (not by SKU): a SKU expecting 5 that read 3 contributes
     * 3 matched + 2 missing.
     */
    fun summarize(
        packingList: List<PackingItem>,
        scannedEpcs: List<String>,
        allowRawHexFallback: Boolean = true,
    ): Summary {
        val scannedByBarcode = groupByBarcode(scannedEpcs, allowRawHexFallback)

        val rows = packingList.map { item ->
            SkuResult(item, scannedByBarcode[item.barcode]?.size ?: 0)
        }

        var matched = 0
        var missing = 0
        var over = 0
        var expected = 0
        for (r in rows) {
            expected += r.qty
            matched += r.read.coerceAtMost(r.qty)
            missing += (r.qty - r.read).coerceAtLeast(0)
            over += (r.read - r.qty).coerceAtLeast(0)
        }

        // Orphans = scanned EPCs whose decoded barcode is not in the packing list.
        val listedBarcodes = packingList.mapTo(HashSet()) { it.barcode }
        val orphanCount = scannedByBarcode.entries
            .filter { it.key !in listedBarcodes }
            .sumOf { it.value.size }

        return Summary(
            rows = rows,
            matchedPieces = matched,
            missingPieces = missing,
            overPieces = over,
            totalExpected = expected,
            orphanCount = orphanCount,
            scannedByBarcode = scannedByBarcode,
        )
    }
}
