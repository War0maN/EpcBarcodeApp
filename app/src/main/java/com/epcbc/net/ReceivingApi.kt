package com.epcbc.net

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Хүлээн авалт — вебийн src/lib/receiving.ts-тэй ИЖИЛ өгөгдлийн урсгал:
 *   нээлттэй ажил (receipts) татах → уншсан hex-үүдийг receive_scans RPC-ээр
 *   500-аар багцалж илгээх (idempotent — дахин илгээхэд давхардахгүй) →
 *   receipt_progress view-ээс явц татах.
 * Бүх эрх/тенантын шалгалт DB талд (RLS + RPC доторх шалгалт).
 */
object ReceivingApi {

    @Serializable
    data class JobInfo(
        @SerialName("job_number") val jobNumber: String,
        @SerialName("arrival_date") val arrivalDate: String,
        val supplier: String? = null,
        val note: String? = null,
    )

    @Serializable
    data class BranchInfo(val name: String)

    @Serializable
    data class Receipt(
        val id: String,
        val status: String,
        @SerialName("created_at") val createdAt: String,
        val jobs: JobInfo? = null,
        val branches: BranchInfo? = null,
    ) {
        val jobNumber: String get() = jobs?.jobNumber ?: "?"
        val branchName: String get() = branches?.name ?: "?"
    }

    /** Нээлттэй (скан хүлээж буй) хүлээн авалтын ажлууд. */
    suspend fun listOpenReceipts(): List<Receipt> =
        Supa.client.postgrest.from("receipts")
            .select(
                Columns.raw("id, status, created_at, jobs(job_number, arrival_date, supplier, note), branches(name)")
            ) {
                filter { eq("status", "open") }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<Receipt>()

    @Serializable
    data class ProgressRow(
        @SerialName("product_id") val productId: String,
        val expected: Int,
        val scanned: Int,
        val generated: Int,
    )

    @Serializable
    data class ProductInfo(
        val id: String,
        val name: String? = null,
        val sku: String? = null,
        val gtin: String? = null,
    )

    /** Явцын мөр + барааны нэр (UI-д шууд харуулахад бэлэн). */
    data class ProgressItem(
        val productId: String,
        val name: String,
        val expected: Int,
        val scanned: Int,
        val generated: Int,
    )

    /** Явцын тойм (бараагаар, нэртэй нь) — receipt_progress view + products. */
    suspend fun fetchProgress(receiptId: String): List<ProgressItem> {
        val rows = Supa.client.postgrest.from("receipt_progress")
            .select(Columns.raw("product_id, expected, scanned, generated")) {
                filter { eq("receipt_id", receiptId) }
            }
            .decodeList<ProgressRow>()
        if (rows.isEmpty()) return emptyList()

        val products = Supa.client.postgrest.from("products")
            .select(Columns.raw("id, name, sku, gtin")) {
                filter { isIn("id", rows.map { it.productId }) }
            }
            .decodeList<ProductInfo>()
            .associateBy { it.id }

        return rows.map { r ->
            val p = products[r.productId]
            ProgressItem(
                productId = r.productId,
                name = p?.name ?: p?.sku ?: p?.gtin ?: r.productId.take(8),
                expected = r.expected,
                scanned = r.scanned,
                generated = r.generated,
            )
        }.sortedBy { it.name }
    }

    /**
     * Уншсан hex-үүдийг 500-аар багцалж receive_scans RPC руу илгээнэ.
     * RPC idempotent тул тасалдвал/дахин илгээвэл аюулгүй (давхардал алгасна).
     * Буцаах утга: ангилал бүрийн тоо (matched, already_registered, ...).
     */
    suspend fun submitScans(receiptId: String, hexes: List<String>): Map<String, Int> {
        val total = mutableMapOf<String, Int>()
        for (chunk in hexes.chunked(500)) {
            val result = Supa.client.postgrest.rpc(
                "receive_scans",
                buildJsonObject {
                    put("p_receipt", receiptId)
                    put("p_hexes", buildJsonArray { chunk.forEach { add(it) } })
                }
            )
            val counts = Json.parseToJsonElement(result.data).jsonObject
            for ((k, v) in counts) {
                total[k] = (total[k] ?: 0) + v.jsonPrimitive.int
            }
        }
        return total
    }
}
