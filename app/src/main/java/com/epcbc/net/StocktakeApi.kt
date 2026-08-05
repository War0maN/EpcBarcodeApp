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
 * Тооллого — вебийн src/lib/stocktake.ts-тэй ИЖИЛ өгөгдлийн урсгал:
 *   нээлттэй тооллого татах → snapshot (stocktake_items)-ыг бүтнээр нь татаж
 *   ТӨХӨӨРӨМЖ ДЭЭР амьд тулгана (hex-ээр шууд — задлахгүй, GID ч хамрагдана) →
 *   уншсан hex-үүдийг stocktake_scan RPC-ээр 500-аар багцалж илгээнэ
 *   (idempotent, source='device') → stocktake_progress view-ээс серверийн явц.
 * Бүх эрх/тенант/салбарын шалгалт DB талд (RLS + RPC доторх шалгалт).
 */
object StocktakeApi {

    @Serializable
    data class BranchInfo(val name: String)

    @Serializable
    data class Stocktake(
        val id: String,
        val number: String,
        val status: String,
        val note: String? = null,
        @SerialName("created_at") val createdAt: String,
        @SerialName("closed_at") val closedAt: String? = null,
        val branches: BranchInfo? = null,
    ) {
        val branchName: String get() = branches?.name ?: "?"
    }

    private const val ST_COLS = "id, number, status, note, created_at, closed_at, branches(name)"

    /** Нээлттэй (скан хүлээж буй) тооллогууд. */
    suspend fun listOpenStocktakes(): List<Stocktake> =
        Supa.client.postgrest.from("stocktakes")
            .select(Columns.raw(ST_COLS)) {
                filter { eq("status", "open") }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<Stocktake>()

    /** Хаагдсан тооллогууд (түүх) — сүүлийнх нь эхэндээ, хязгаартай. */
    suspend fun listClosedStocktakes(limit: Long = 30): List<Stocktake> =
        Supa.client.postgrest.from("stocktakes")
            .select(Columns.raw(ST_COLS)) {
                filter { eq("status", "closed") }
                order("closed_at", Order.DESCENDING)
                limit(limit)
            }
            .decodeList<Stocktake>()

    /** Нэг тооллого (шинээр үүсгэснийг шууд идэвхжүүлэхэд). */
    suspend fun fetchStocktake(id: String): Stocktake =
        Supa.client.postgrest.from("stocktakes")
            .select(Columns.raw(ST_COLS)) { filter { eq("id", id) } }
            .decodeSingle<Stocktake>()

    /** Шинэ тооллого эхлүүлнэ (вебтэй ижил RPC — ST дугаар, snapshot,
     *  "нэг салбарт нэг нээлттэй" шалгалт бүгд DB талд). */
    suspend fun createStocktake(branchId: String, note: String?): String {
        val result = Supa.client.postgrest.rpc(
            "create_stocktake",
            buildJsonObject {
                put("p_branch", branchId)
                put("p_note", note)
            }
        )
        return Json.parseToJsonElement(result.data).jsonPrimitive.content
    }

    @Serializable
    data class ExtraScan(
        @SerialName("epc_hex") val epcHex: String,
        @SerialName("epc_id") val epcId: String? = null,
        @SerialName("product_id") val productId: String? = null,
        /** Скан хийх ҮЕИЙН төлөв/салбар (хөлдсөн); хуучин датад null. */
        @SerialName("scan_status") val scanStatus: String? = null,
        @SerialName("scan_branch") val scanBranch: String? = null,
    )

    /** "Илүү" уншилтууд (зөвхөн бүртгэлтэй EPC — unknown энд ОРДОГГҮЙ,
     *  сервер тусад нь ангилдаг). Яагаад илүү болохыг төлөв/салбар нь хэлнэ. */
    suspend fun fetchExtraScans(stocktakeId: String): List<ExtraScan> {
        val out = ArrayList<ExtraScan>()
        val page = 1000
        var from = 0L
        while (true) {
            val chunk = Supa.client.postgrest.from("stocktake_scans")
                .select(Columns.raw("epc_hex, epc_id, product_id, scan_status, scan_branch")) {
                    filter {
                        eq("stocktake_id", stocktakeId)
                        eq("outcome", "not_expected")
                    }
                    order("epc_hex", Order.ASCENDING)
                    range(from, from + page - 1)
                }
                .decodeList<ExtraScan>()
            out.addAll(chunk)
            if (chunk.size < page) break
            from += page
        }
        return out
    }

    @Serializable
    data class MissingRow(
        @SerialName("epc_hex") val epcHex: String,
        @SerialName("product_id") val productId: String,
    )

    /** Дутуу (тоологдоогүй) EPC-үүд — stocktake_missing view, cap-тай. */
    suspend fun fetchMissing(stocktakeId: String, cap: Long = 3000): List<MissingRow> {
        val out = ArrayList<MissingRow>()
        val page = 1000L
        var from = 0L
        while (from < cap) {
            val chunk = Supa.client.postgrest.from("stocktake_missing")
                .select(Columns.raw("epc_hex, product_id")) {
                    filter { eq("stocktake_id", stocktakeId) }
                    order("epc_hex", Order.ASCENDING)
                    range(from, from + page - 1)
                }
                .decodeList<MissingRow>()
            out.addAll(chunk)
            if (chunk.size < page) break
            from += page
        }
        return out
    }

    @Serializable
    data class EpcInfo(
        val id: String,
        val status: String,
        @SerialName("branch_id") val branchId: String? = null,
        @SerialName("product_id") val productId: String,
    )

    /** EPC-ийн одоогийн төлөв/салбар (scan_status null хуучин датад fallback). */
    suspend fun fetchEpcInfo(ids: Collection<String>): Map<String, EpcInfo> {
        val map = HashMap<String, EpcInfo>()
        for (chunk in ids.chunked(200)) {
            Supa.client.postgrest.from("epc_codes")
                .select(Columns.raw("id, status, branch_id, product_id")) {
                    filter { isIn("id", chunk) }
                }
                .decodeList<EpcInfo>()
                .forEach { map[it.id] = it }
        }
        return map
    }

    /** Хаагдсан тооллогын "Илүү" тоо (мөр татахгүй — зөвхөн тоолно). */
    suspend fun countExtras(stocktakeId: String): Long =
        Supa.client.postgrest.from("stocktake_scans")
            .select(Columns.raw("epc_hex")) {
                filter {
                    eq("stocktake_id", stocktakeId)
                    eq("outcome", "not_expected")
                }
                count(io.github.jan.supabase.postgrest.query.Count.EXACT)
                limit(1)
            }
            .countOrNull() ?: 0

    @Serializable
    data class ItemRow(
        @SerialName("epc_hex") val epcHex: String,
        @SerialName("product_id") val productId: String,
    )

    /**
     * Snapshot-ыг БҮТНЭЭР нь татна (hex → бараа). Supabase нэг хүсэлтэд
     * 1000 мөрөөр хязгаарладаг тул хуудаслана — олон мянган ширхэгтэй
     * тооллогод заавал. Мөр бүр ~60 байт тул 20k ширхэг ч санах ойд жижиг.
     */
    suspend fun fetchExpectedItems(stocktakeId: String): List<ItemRow> {
        val out = ArrayList<ItemRow>()
        val page = 1000
        var from = 0L
        while (true) {
            val chunk = Supa.client.postgrest.from("stocktake_items")
                .select(Columns.raw("epc_hex, product_id")) {
                    filter { eq("stocktake_id", stocktakeId) }
                    order("epc_hex", Order.ASCENDING)
                    range(from, from + page - 1)
                }
                .decodeList<ItemRow>()
            out.addAll(chunk)
            if (chunk.size < page) break
            from += page
        }
        return out
    }

    @Serializable
    data class ProductInfo(
        val id: String,
        val name: String? = null,
        val sku: String? = null,
        val gtin: String? = null,
    )

    /** Барааны нэр/SKU (id-аар, 200-аар багцалж — URL хэт уртсахгүй). */
    suspend fun fetchProducts(ids: Collection<String>): Map<String, ProductInfo> {
        val map = HashMap<String, ProductInfo>()
        for (chunk in ids.chunked(200)) {
            Supa.client.postgrest.from("products")
                .select(Columns.raw("id, name, sku, gtin")) {
                    filter { isIn("id", chunk) }
                }
                .decodeList<ProductInfo>()
                .forEach { map[it.id] = it }
        }
        return map
    }

    @Serializable
    data class ScanRow(
        @SerialName("epc_hex") val epcHex: String,
        val outcome: String,
    )

    /**
     * Энэ тооллогод аль хэдийн бүртгэгдсэн БҮХ уншилт (found/not_expected/
     * unknown) — апп дахин нээгдэхэд өмнөх байдлаас үргэлжлүүлэхэд.
     * fetchExpectedItems-тэй ижил хуудаслана (1000-ын хязгаар).
     */
    suspend fun fetchScannedHexes(stocktakeId: String): List<ScanRow> {
        val out = ArrayList<ScanRow>()
        val page = 1000
        var from = 0L
        while (true) {
            val chunk = Supa.client.postgrest.from("stocktake_scans")
                .select(Columns.raw("epc_hex, outcome")) {
                    filter { eq("stocktake_id", stocktakeId) }
                    order("epc_hex", Order.ASCENDING)
                    range(from, from + page - 1)
                }
                .decodeList<ScanRow>()
            out.addAll(chunk)
            if (chunk.size < page) break
            from += page
        }
        return out
    }

    @Serializable
    data class ProgressRow(
        @SerialName("product_id") val productId: String,
        val expected: Int,
        val found: Int,
    )

    /** Серверийн явц (бараагаар) — stocktake_progress view. */
    suspend fun fetchProgress(stocktakeId: String): List<ProgressRow> =
        Supa.client.postgrest.from("stocktake_progress")
            .select(Columns.raw("product_id, expected, found")) {
                filter { eq("stocktake_id", stocktakeId) }
            }
            .decodeList<ProgressRow>()

    /**
     * Уншсан hex-үүдийг 500-аар багцалж stocktake_scan RPC руу илгээнэ.
     * source='device' — гар оролтоос (вебийн 'manual') тайланд ялгагдана.
     * RPC idempotent тул тасалдвал/дахин илгээвэл аюулгүй (давхардал алгасна).
     */
    suspend fun submitScans(stocktakeId: String, hexes: List<String>): Map<String, Int> {
        val total = mutableMapOf<String, Int>()
        for (chunk in hexes.chunked(500)) {
            val result = Supa.client.postgrest.rpc(
                "stocktake_scan",
                buildJsonObject {
                    put("p_stocktake", stocktakeId)
                    put("p_hexes", buildJsonArray { chunk.forEach { add(it) } })
                    put("p_source", "device")
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
