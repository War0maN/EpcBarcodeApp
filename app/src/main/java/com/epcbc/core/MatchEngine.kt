package com.epcbc.core

/**
 * Matches incoming EPC streams against an imported packing list and tracks per-SKU read counts.
 */
class MatchEngine(
    initialPackingList: List<PackingItem>,
    private val allowRawHexFallback: Boolean = true,
) {

    data class PackingItem(
        val sku: String?,
        val barcode: String,
        val itemName: String? = null,
        val qty: Int
    )

    data class SkuState(
        val item: PackingItem,
        val seenEpcs: MutableSet<String> = LinkedHashSet(),
    ) {
        val readCount: Int get() = seenEpcs.size
        val isComplete: Boolean get() = readCount >= item.qty
        val isOver: Boolean get() = readCount > item.qty
    }

    private val _orphanEpcs = LinkedHashSet<String>()
    val orphanEpcs: Set<String> get() = _orphanEpcs

    private val byBarcode: MutableMap<String, SkuState> = LinkedHashMap()

    init {
        for (item in initialPackingList) {
            byBarcode[item.barcode] = SkuState(item)
        }
    }

    fun feedEpc(epcHex: String): Boolean {
        val result = EpcDecoder.decode(epcHex, allowRawHexFallback)
        val key = result.barcode ?: return false
        val state = byBarcode[key]
        if (state == null) {
            _orphanEpcs.add(epcHex.uppercase())
            return false
        }
        state.seenEpcs.add(epcHex.uppercase())
        return true
    }

    fun snapshot(): List<SkuState> = byBarcode.values.toList()

    fun statsFor(barcode: String): SkuState? = byBarcode[barcode]

    fun totals(): Totals {
        var expected = 0; var read = 0; var complete = 0; var over = 0
        for (s in byBarcode.values) {
            expected += s.item.qty
            read += s.readCount
            if (s.isComplete) complete++
            if (s.isOver) over++
        }
        return Totals(expected, read, complete, over, byBarcode.size, _orphanEpcs.size)
    }

    data class Totals(
        val expected: Int,
        val read: Int,
        val completedSkus: Int,
        val overReadSkus: Int,
        val totalSkus: Int,
        val orphanEpcs: Int
    )
}
