package com.epcbc.net

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Барааны хайлт — Хайлт дэлгэцэд. Нэр/SKU/баркодоор ilike хайж, олдсон
 * барааны аль нэг EPC-ээс УГТВАР бүтээнэ (Gen2 Select filter-ээр тэр
 * барааны бүх таг сонсогдоно — серийн дугаараас бусад хэсэг нь ижил).
 */
object ProductsApi {

    @Serializable
    data class Product(
        val id: String,
        val name: String? = null,
        val sku: String? = null,
        val gtin: String? = null,
    ) {
        val display: String get() = name ?: sku ?: gtin ?: id.take(8)
    }

    /**
     * Нэр/SKU/баркодоор хайна — 3 багана тус бүрд нэг ilike асуулга
     * (нэг or-асуулгаас найдвартай), id-аар нэгтгэнэ.
     */
    suspend fun search(query: String, limitPerCol: Int = 15): List<Product> {
        val q = "%${query.trim()}%"
        if (q == "%%") return emptyList()
        val out = LinkedHashMap<String, Product>()
        for (col in listOf("name", "sku", "gtin")) {
            Supa.client.postgrest.from("products")
                .select(Columns.raw("id, name, sku, gtin")) {
                    filter { ilike(col, q) }
                    limit(limitPerCol.toLong())
                }
                .decodeList<Product>()
                .forEach { p -> out.putIfAbsent(p.id, p) }
        }
        return out.values.toList()
    }

    @Serializable
    private data class EpcHexRow(@SerialName("epc_hex") val epcHex: String)

    /** Барааны аль нэг бүртгэлтэй EPC (угтвар бүтээхэд); байхгүй бол null. */
    suspend fun anyEpcHex(productId: String): String? =
        Supa.client.postgrest.from("epc_codes")
            .select(Columns.raw("epc_hex")) {
                filter { eq("product_id", productId) }
                limit(1)
            }
            .decodeList<EpcHexRow>()
            .firstOrNull()?.epcHex?.trim()?.uppercase()

    /** EPC-ээс барааны угтвар — нэг эх сурвалж: [EpcDecoder.productPrefix]. */
    fun productPrefix(epcHex: String): String = com.epcbc.core.EpcDecoder.productPrefix(epcHex)
}
