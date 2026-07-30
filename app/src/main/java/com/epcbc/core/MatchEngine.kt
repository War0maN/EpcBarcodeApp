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

    /**
     * Тулгалтын түлхүүр — серверийн хүлээн авалттай ИЖИЛ нормчлол: GS1 баркодыг
     * (8/12/13/14 орон) зүүн талаас 0-оор 14 орон болгож гүйцээнэ. Ингэснээр
     * packing list-ийн 12 оронтой UPC ↔ decoder-ийн 13/14 оронтой гаралт нэг
     * түлхүүрт буудаг (өмнө нь "888392280015" ≠ "0888392280015" болж дутуу
     * тоологддог байсан). GS1 биш утга (raw hex fallback г.м.) хэвээрээ.
     */
    fun key(barcode: String): String {
        val t = barcode.trim()
        return if (t.length in KEY_LENGTHS && t.all { it.isDigit() }) t.padStart(14, '0') else t
    }

    private val KEY_LENGTHS = setOf(8, 12, 13, 14)

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
     * Түлхүүр нь [key]-ээр нормчлогдсон — хайхдаа мөн key(барка)-аар хай.
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
            map.getOrPut(key(barcode)) { LinkedHashSet() }.add(epc)
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

        // Нэг баркод хэд хэдэн мөрөнд (өөр өөр хайрцагт) ирж болно. Уншилт
        // баркодоор бүлэглэгддэг тул мөр бүр нийт уншилтыг бүхэлд нь өөртөө
        // тооцоод давхардуулдаг байсан — нормчилсон түлхүүрээр нэгтгэж qty-г
        // нийлүүлнэ ("888…" ба "0888…" гэж бичигдсэн ижил бараа ч нийлнэ).
        val merged = packingList
            .groupBy { key(it.barcode) }
            .map { (_, items) ->
                if (items.size == 1) items[0]
                else items[0].copy(qty = items.sumOf { it.qty })
            }

        val rows = merged.map { item ->
            SkuResult(item, scannedByBarcode[key(item.barcode)]?.size ?: 0)
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
        val listedBarcodes = packingList.mapTo(HashSet()) { key(it.barcode) }
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
