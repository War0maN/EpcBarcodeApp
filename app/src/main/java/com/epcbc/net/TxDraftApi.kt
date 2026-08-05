package com.epcbc.net

import io.github.jan.supabase.auth.auth
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull

/**
 * Шат 2 — Шилжүүлэг/Актлалтын НООРОГ-САГС (сервер талын tx_drafts).
 *   Уншсан таг бүр tx_draft_scan RPC-ээр серверт шууд сагслагдана (апп
 *   унтарсан ч сагс алдагдахгүй); "Гүйлгээ болгох" = submit_tx_draft →
 *   create_transaction (эрх/төлөв/салбарын бүх шалгалт DB талд, вебтэй ижил).
 *   Бичилт ЗӨВХӨН RPC-ээр — хүснэгтэд write policy огт байхгүй.
 */
object TxDraftApi {

    @Serializable
    data class Branch(val id: String, val name: String)

    /** Салбарууд (нэрс бүх хэрэглэгчид нээлттэй — очих салбар сонгоход). */
    suspend fun listBranches(): List<Branch> =
        Supa.client.postgrest.from("branches")
            .select(Columns.raw("id, name")) { order("name", Order.ASCENDING) }
            .decodeList<Branch>()

    @Serializable
    private data class UserBranchRow(@SerialName("branch_id") val branchId: String)

    /**
     * Өөрийн хуваарилагдсан салбарууд (user_branches). Хоосон = хуваарилалтгүй
     * буюу "бүх салбар" семантик (вебтэй ижил).
     */
    suspend fun listMyBranchIds(): List<String> {
        val uid = Supa.client.auth.currentUserOrNull()?.id ?: return emptyList()
        return Supa.client.postgrest.from("user_branches")
            .select(Columns.raw("branch_id")) { filter { eq("user_id", uid) } }
            .decodeList<UserBranchRow>()
            .map { it.branchId }
    }

    @Serializable
    data class Draft(
        val id: String,
        val type: String, // transfer | other
        @SerialName("from_branch") val fromBranch: String? = null,
        @SerialName("to_branch") val toBranch: String? = null,
        val note: String? = null,
        val status: String,
        @SerialName("created_at") val createdAt: String,
    )

    /** Өөрийн нээлттэй ноорогууд (RLS: өөрийнх л; админд бүгд). */
    suspend fun listOpenDrafts(type: String): List<Draft> =
        Supa.client.postgrest.from("tx_drafts")
            .select(Columns.raw("id, type, from_branch, to_branch, note, status, created_at")) {
                filter {
                    eq("status", "open")
                    eq("type", type)
                }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<Draft>()

    /**
     * Шинэ ноорог үүсгэнэ (эрхийн шалгалт RPC дотор). [fromBranch] өгвөл эх
     * салбар эхнээсээ түгжигдэнэ (from_locked) — өөр салбарын төөрсөн таг
     * анх уншигдаад сагсыг буруу салбараар түгжих эрсдэлгүй; null бол хуучин
     * зан төлөв (эхний тагаар тогтоно).
     */
    suspend fun createDraft(type: String, toBranch: String?, note: String?, fromBranch: String? = null): String {
        val result = Supa.client.postgrest.rpc(
            "create_tx_draft",
            buildJsonObject {
                put("p_type", type)
                if (toBranch != null) put("p_to_branch", toBranch) else put("p_to_branch", null as String?)
                put("p_note", note)
                if (fromBranch != null) put("p_from_branch", fromBranch)
            }
        )
        return Json.parseToJsonElement(result.data).jsonPrimitive.content
    }

    /** Очих салбар/тэмдэглэл солино (submit хүртэл). */
    suspend fun updateDraft(draftId: String, toBranch: String?, note: String?) {
        Supa.client.postgrest.rpc(
            "update_tx_draft",
            buildJsonObject {
                put("p_draft", draftId)
                if (toBranch != null) put("p_to_branch", toBranch) else put("p_to_branch", null as String?)
                put("p_note", note)
            }
        )
    }

    /**
     * Уншсан hex-үүдийг 500-аар багцалж сагслана. RPC idempotent (давхардал
     * "already" гэж тоологдоно) тул тасалдвал дахин илгээхэд аюулгүй.
     * Буцаана: added/already/not_active/wrong_branch/no_access/unknown/skipped.
     */
    suspend fun scan(draftId: String, hexes: List<String>): Map<String, Int> {
        val total = mutableMapOf<String, Int>()
        for (chunk in hexes.chunked(500)) {
            val result = Supa.client.postgrest.rpc(
                "tx_draft_scan",
                buildJsonObject {
                    put("p_draft", draftId)
                    put("p_hexes", buildJsonArray { chunk.forEach { add(it) } })
                }
            )
            val counts = Json.parseToJsonElement(result.data).jsonObject
            for ((k, v) in counts) total[k] = (total[k] ?: 0) + v.jsonPrimitive.int
        }
        return total
    }

    @Serializable
    data class EpcRef(@SerialName("product_id") val productId: String? = null)

    @Serializable
    data class CartItem(
        @SerialName("epc_id") val epcId: String,
        @SerialName("epc_hex") val epcHex: String,
        @SerialName("epc_codes") val epc: EpcRef? = null,
    ) {
        val productId: String? get() = epc?.productId
    }

    /** Сагсны бүх мөр (бараатай нь холбож) — 1000-аар хуудаслана. */
    suspend fun fetchItems(draftId: String): List<CartItem> {
        val out = ArrayList<CartItem>()
        val page = 1000
        var from = 0L
        while (true) {
            val chunk = Supa.client.postgrest.from("tx_draft_items")
                .select(Columns.raw("epc_id, epc_hex, epc_codes(product_id)")) {
                    filter { eq("draft_id", draftId) }
                    order("epc_hex", Order.ASCENDING)
                    range(from, from + page - 1)
                }
                .decodeList<CartItem>()
            out.addAll(chunk)
            if (chunk.size < page) break
            from += page
        }
        return out
    }

    @Serializable
    data class LineRow(
        @SerialName("product_id") val productId: String,
        val expected: Int,
        val picked: Int,
    )

    /** Даалгаврын жагсаалт + серверийн явц (tx_draft_progress view). */
    suspend fun fetchLines(draftId: String): List<LineRow> =
        Supa.client.postgrest.from("tx_draft_progress")
            .select(Columns.raw("product_id, expected, picked")) {
                filter { eq("draft_id", draftId) }
            }
            .decodeList<LineRow>()

    @Serializable
    data class LineProduct(
        val id: String,
        val name: String? = null,
        val sku: String? = null,
        val gtin: String? = null,
        /** GID-96 кодлолтой (баркодгүй) барааны Object Class — тагийг локал таньхад. */
        @SerialName("object_class") val objectClass: Long? = null,
    )

    /** Даалгаврын бараанууд — нэр + локал тулгалтын түлхүүрүүд (gtin/object_class). */
    suspend fun fetchLineProducts(ids: Collection<String>): Map<String, LineProduct> {
        val map = HashMap<String, LineProduct>()
        for (chunk in ids.chunked(200)) {
            Supa.client.postgrest.from("products")
                .select(Columns.raw("id, name, sku, gtin, object_class")) {
                    filter { isIn("id", chunk) }
                }
                .decodeList<LineProduct>()
                .forEach { map[it.id] = it }
        }
        return map
    }

    /** Сагснаас хасна (epc_id-аар). Сагс хоосорвол салбарын түгжээ тайлагдана. */
    suspend fun removeItems(draftId: String, epcIds: List<String>) {
        for (chunk in epcIds.chunked(500)) {
            Supa.client.postgrest.rpc(
                "tx_draft_remove",
                buildJsonObject {
                    put("p_draft", draftId)
                    put("p_epc_ids", buildJsonArray { chunk.forEach { add(it) } })
                }
            )
        }
    }

    data class SubmitResult(val txNumber: String?, val submitted: Int, val pruned: Int)

    /**
     * Сагсыг жинхэнэ гүйлгээ болгоно (create_transaction — TRF/ADJ дугаар,
     * event, эрхийн шалгалт бүгд DB талд). Сагсанд орсноос хойш хүчингүй
     * болсон таг автоматаар хасагдана (pruned).
     */
    suspend fun submit(draftId: String): SubmitResult {
        val result = Supa.client.postgrest.rpc(
            "submit_tx_draft",
            buildJsonObject { put("p_draft", draftId) }
        )
        val o = Json.parseToJsonElement(result.data).jsonObject
        return SubmitResult(
            txNumber = o["tx_number"]?.jsonPrimitive?.contentOrNull,
            submitted = o["submitted"]?.jsonPrimitive?.intOrNull ?: 0,
            pruned = o["pruned"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    /** Ноорог цуцална (status='cancelled' — юу ч устахгүй, гүйлгээ үүсэхгүй). */
    suspend fun cancel(draftId: String) {
        Supa.client.postgrest.rpc(
            "cancel_tx_draft",
            buildJsonObject { put("p_draft", draftId) }
        )
    }
}
