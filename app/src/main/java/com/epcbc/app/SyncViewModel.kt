package com.epcbc.app

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.epcbc.net.ReceivingApi
import com.epcbc.net.StocktakeApi
import com.epcbc.net.Supa
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Сервер (Supabase) талын бүх төлөв: нэвтрэлт + хүлээн авалтын ажил + скан илгээлт.
 * Уншигчийн логик [ScanViewModel]-д хэвээр — энэ хоёр VM бие даасан.
 * Бүх алдааг Монгол мессежээр UI-д гаргана (вебтэй ижил зарчим).
 */
class SyncViewModel : ViewModel() {

    /** Auth төлөв — Compose-д collectAsState-ээр сонсоно. */
    val sessionStatus: StateFlow<SessionStatus> get() = Supa.client.auth.sessionStatus

    var authBusy by mutableStateOf(false); private set
    var authError by mutableStateOf<String?>(null); private set

    /** "Офлайн үргэлжлүүлэх" сонгосон эсэх (session-ий турш л хадгалагдана). */
    var skipLogin by mutableStateOf(false)

    /** Хүлээн авалтын overlay нээлттэй эсэх (in-tree Box — Dialog биш). */
    var showReceiving by mutableStateOf(false)

    // Хүлээн авалт
    val isConfigured: Boolean get() = Supa.isConfigured
    var receipts by mutableStateOf<List<ReceivingApi.Receipt>>(emptyList()); private set
    var receiptsLoading by mutableStateOf(false); private set
    var activeReceipt by mutableStateOf<ReceivingApi.Receipt?>(null); private set
    var progress by mutableStateOf<List<ReceivingApi.ProgressItem>>(emptyList()); private set
    var progressLoading by mutableStateOf(false); private set
    var submitBusy by mutableStateOf(false); private set
    var syncMessage by mutableStateOf<String?>(null); private set
    var syncError by mutableStateOf<String?>(null); private set

    val userEmail: String? get() = Supa.userEmail

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            authError = "Имэйл болон нууц үгээ оруулна уу."
            return
        }
        authBusy = true
        authError = null
        viewModelScope.launch {
            try {
                Supa.client.auth.signInWith(Email) {
                    this.email = email.trim()
                    this.password = password
                }
            } catch (e: Exception) {
                Log.w(TAG, "login failed", e)
                authError = friendlyAuthError(e)
            } finally {
                authBusy = false
            }
        }
    }

    // ── Нууц үг солих (Профайл дэлгэцээс) ──
    var passwordBusy by mutableStateOf(false); private set
    var passwordMessage by mutableStateOf<String?>(null); private set

    fun changePassword(oldPassword: String, newPassword: String) {
        val email = Supa.userEmail
        if (email == null) {
            passwordMessage = "Нэвтрээгүй байна."
            return
        }
        if (newPassword.length < 6) {
            passwordMessage = "Нууц үг дор хаяж 6 тэмдэгт байх ёстой."
            return
        }
        passwordBusy = true
        passwordMessage = null
        viewModelScope.launch {
            try {
                // Хуучин нууц үгээ дахин нэвтэрч баталгаажуулна — дундын
                // төхөөрөмж дээр орхигдсон session-ээр өөр хүн сольчихоос
                // сэргийлнэ (ижил хэрэглэгчээр тул session хэвийн үлдэнэ).
                try {
                    Supa.client.auth.signInWith(Email) {
                        this.email = email
                        this.password = oldPassword
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "changePassword: old password check failed", e)
                    passwordMessage = "Хуучин нууц үг буруу байна."
                    return@launch
                }
                Supa.client.auth.updateUser { password = newPassword }
                passwordMessage = "Нууц үг солигдлоо."
            } catch (e: Exception) {
                Log.w(TAG, "changePassword failed", e)
                passwordMessage = "Алдаа: ${e.message ?: "холболт"}"
            } finally {
                passwordBusy = false
            }
        }
    }

    fun clearPasswordMessage() { passwordMessage = null }

    fun logout() {
        viewModelScope.launch {
            try {
                Supa.client.auth.signOut()
            } catch (e: Exception) {
                Log.w(TAG, "logout failed", e)
            }
            activeReceipt = null
            receipts = emptyList()
            progress = emptyList()
            selectStocktake(null)
            stocktakes = emptyList()
            showStocktake = false
            syncMessage = null
            syncError = null
        }
    }

    /** Нээлттэй хүлээн авалтын ажлуудыг татна. */
    fun refreshReceipts() {
        receiptsLoading = true
        syncError = null
        viewModelScope.launch {
            try {
                receipts = ReceivingApi.listOpenReceipts()
            } catch (e: Exception) {
                Log.w(TAG, "listOpenReceipts failed", e)
                syncError = "Ажлын жагсаалт татахад алдаа: ${e.message ?: "холболт"}"
            } finally {
                receiptsLoading = false
            }
        }
    }

    /** Ажил сонгоход явцыг нь шууд татна. */
    fun selectReceipt(r: ReceivingApi.Receipt?) {
        activeReceipt = r
        progress = emptyList()
        syncMessage = null
        if (r != null) refreshProgress()
    }

    fun refreshProgress() {
        val r = activeReceipt ?: return
        progressLoading = true
        viewModelScope.launch {
            try {
                progress = ReceivingApi.fetchProgress(r.id)
            } catch (e: Exception) {
                Log.w(TAG, "fetchProgress failed", e)
                syncError = "Явц татахад алдаа: ${e.message ?: "холболт"}"
            } finally {
                progressLoading = false
            }
        }
    }

    /**
     * Уншсан EPC-үүдийг идэвхтэй ажил руу илгээнэ (500-аар багц, idempotent).
     * Дууссаны дараа явцыг дахин татаж, ангиллын тоог Монголоор харуулна.
     * [onSuccess] зөвхөн бүх багц амжилттай хүрсэн үед дуудагдана — дуудагч
     * тал илгээх буфераа цэвэрлэхэд ашиглана (алдаанд буфер хэвээр үлдэж,
     * дахин илгээж болно).
     */
    fun submitScans(hexes: List<String>, onSuccess: () -> Unit = {}) {
        val r = activeReceipt ?: return
        if (hexes.isEmpty()) {
            syncMessage = "Илгээх скан алга."
            return
        }
        submitBusy = true
        syncError = null
        syncMessage = null
        viewModelScope.launch {
            try {
                val counts = ReceivingApi.submitScans(r.id, hexes)
                syncMessage = formatCounts(counts)
                onSuccess()
                progress = ReceivingApi.fetchProgress(r.id)
            } catch (e: Exception) {
                Log.w(TAG, "submitScans failed", e)
                syncError = "Илгээхэд алдаа: ${e.message ?: "холболт"} — дахин илгээхэд аюулгүй (давхардахгүй)."
            } finally {
                submitBusy = false
            }
        }
    }

    fun clearSyncMessages() {
        syncMessage = null
        syncError = null
    }

    // ══════════════ ТООЛЛОГО (Ү6) ══════════════
    // Хэв маяг хүлээн авалттай ижил, нэг ялгаа: snapshot-ыг эхэнд нь бүтнээр
    // татаж, тулгалтыг ТӨХӨӨРӨМЖ ДЭЭР амьд хийнэ — компьютергүй ажилтан
    // явцаа (1/23, дутуу, жагсаалтад байхгүй) сүлжээгүйгээр ч хардаг.
    // Сервер эцсийн үнэн хэвээр: Илгээхэд idempotent RPC ангилж хадгална.

    var showStocktake by mutableStateOf(false)

    var stocktakes by mutableStateOf<List<StocktakeApi.Stocktake>>(emptyList()); private set
    var stocktakesLoading by mutableStateOf(false); private set
    var activeStocktake by mutableStateOf<StocktakeApi.Stocktake?>(null); private set
    var stExpectedLoading by mutableStateOf(false); private set

    /** hex → productId (snapshot). Compose state БИШ — том, зөвхөн lookup. */
    private var stExpectedByHex: Map<String, String> = emptyMap()
    /** productId → snapshot дахь ширхэг. */
    var stExpectedByProduct: Map<String, Int> = emptyMap(); private set
    /** productId → нэр/SKU (дэлгэцэд). */
    var stProductMeta: Map<String, StocktakeApi.ProductInfo> = emptyMap(); private set

    /** Локал тулгалтад аль хэдийн тооцсон hex-үүд (давхардал 1 л удаа). */
    private val stSeenLocal = HashSet<String>()
    /** productId → локал олдсон тоо (амьд, мөр бүр эндээс уншина). */
    val stFoundLocal = mutableStateMapOf<String, Int>()
    /** Snapshot-д байхгүй уншилтын тоо (илүү/танигдаагүй байж магадгүй). */
    var stExtraLocal by mutableStateOf(0); private set
    /** productId → серверийн found (Явц татахад; дэлгэц max(локал, сервер)). */
    val stFoundServer = mutableStateMapOf<String, Int>()

    fun refreshStocktakes() {
        stocktakesLoading = true
        syncError = null
        viewModelScope.launch {
            try {
                stocktakes = StocktakeApi.listOpenStocktakes()
            } catch (e: Exception) {
                Log.w(TAG, "listOpenStocktakes failed", e)
                syncError = "Тооллогын жагсаалт татахад алдаа: ${e.message ?: "холболт"}"
            } finally {
                stocktakesLoading = false
            }
        }
    }

    /**
     * Тооллого сонгоход snapshot + СЕРВЕРИЙН ӨМНӨХ БАЙДЛЫГ бүтнээр татна:
     * явц (бараагаар) + аль хэдийн бүртгэгдсэн hex-үүд. Апп хаагдаж дахин
     * нээгдсэн ч өмнө тоолсноосоо үргэлжилнэ (2026-08-02 хэрэглэгчийн хүсэлт —
     * өмнө нь 0/N болж харагдаад төөрөгдүүлдэг байсан; дата серверт бүрэн
     * байсаар байсан). Бүгд сонгох мөчид НЭГ удаа — скан үед сүлжээ хэрэггүй.
     * [onServerState] — бүртгэгдсэн hex-үүдийг ScanViewModel-д "үзсэн" гэж
     * тэмдэглүүлэх (дахин уншигдвал Илгээх тоолуур хий дэмий өсөхгүй).
     */
    fun selectStocktake(st: StocktakeApi.Stocktake?, onServerState: ((List<String>) -> Unit)? = null) {
        activeStocktake = st
        stExpectedByHex = emptyMap()
        stExpectedByProduct = emptyMap()
        stProductMeta = emptyMap()
        stSeenLocal.clear()
        stFoundLocal.clear()
        stFoundServer.clear()
        stExtraLocal = 0
        syncMessage = null
        if (st == null) return
        stExpectedLoading = true
        viewModelScope.launch {
            try {
                val items = StocktakeApi.fetchExpectedItems(st.id)
                stExpectedByHex = items.associate { it.epcHex.trim().uppercase() to it.productId }
                stExpectedByProduct = items.groupingBy { it.productId }.eachCount()
                stProductMeta = StocktakeApi.fetchProducts(stExpectedByProduct.keys)

                // Серверийн явцаар локал тоолуураа СУУЛГАНА (0-ээс биш) —
                // шинэ уншилт үүн дээр нэмэгдэж явна.
                for (r in StocktakeApi.fetchProgress(st.id)) {
                    stFoundLocal[r.productId] = r.found
                    stFoundServer[r.productId] = r.found
                }
                val scanned = StocktakeApi.fetchScannedHexes(st.id)
                for (s in scanned) {
                    stSeenLocal.add(s.epcHex.trim().uppercase())
                    if (s.outcome != "found") stExtraLocal++
                }
                onServerState?.invoke(scanned.map { it.epcHex.trim().uppercase() })
            } catch (e: Exception) {
                Log.w(TAG, "selectStocktake load failed", e)
                syncError = "Тооллогын жагсаалт татахад алдаа: ${e.message ?: "холболт"}"
                activeStocktake = null
            } finally {
                stExpectedLoading = false
            }
        }
    }

    /**
     * Уншигчаас шинэ EPC ирэх бүрд (мөн seed-д) дуудагдана — O(багц) амьд
     * тулгалт. Багц 100мс тутам жижиг ирдэг тул UI гацахгүй; дэлгэц таг биш
     * БАРААГААР зурагддаг тул 10k таг ч жагсаалт нь барааны тоогоор л байна.
     */
    fun onStocktakeScans(hexes: List<String>) {
        if (activeStocktake == null || hexes.isEmpty()) return
        for (raw in hexes) {
            val hex = raw.trim().uppercase()
            if (!stSeenLocal.add(hex)) continue
            val prod = stExpectedByHex[hex]
            if (prod != null) stFoundLocal[prod] = (stFoundLocal[prod] ?: 0) + 1
            else stExtraLocal++
        }
    }

    fun refreshStocktakeProgress() {
        val st = activeStocktake ?: return
        progressLoading = true
        viewModelScope.launch {
            try {
                val rows = StocktakeApi.fetchProgress(st.id)
                stFoundServer.clear()
                for (r in rows) stFoundServer[r.productId] = r.found
            } catch (e: Exception) {
                Log.w(TAG, "stocktake fetchProgress failed", e)
                syncError = "Явц татахад алдаа: ${e.message ?: "холболт"}"
            } finally {
                progressLoading = false
            }
        }
    }

    /** Буферт хуримтлагдсан уншилтуудыг илгээнэ (500-аар багц, idempotent). */
    fun submitStocktakeScans(hexes: List<String>, onSuccess: () -> Unit = {}) {
        val st = activeStocktake ?: return
        if (hexes.isEmpty()) {
            syncMessage = "Илгээх скан алга."
            return
        }
        submitBusy = true
        syncError = null
        syncMessage = null
        viewModelScope.launch {
            try {
                val counts = StocktakeApi.submitScans(st.id, hexes)
                syncMessage = formatStocktakeCounts(counts)
                onSuccess()
                val rows = StocktakeApi.fetchProgress(st.id)
                stFoundServer.clear()
                for (r in rows) stFoundServer[r.productId] = r.found
            } catch (e: Exception) {
                Log.w(TAG, "submitStocktakeScans failed", e)
                syncError = "Илгээхэд алдаа: ${e.message ?: "холболт"} — дахин илгээхэд аюулгүй (давхардахгүй)."
            } finally {
                submitBusy = false
            }
        }
    }

    private fun formatStocktakeCounts(counts: Map<String, Int>): String {
        val labels = mapOf(
            "found" to "Тоологдсон",
            "not_expected" to "Илүү",
            "unknown" to "Бүртгэлгүй",
            "skipped" to "Алгассан (давхардал)",
        )
        val parts = counts.filterValues { it > 0 }
            .map { (k, v) -> "${labels[k] ?: k}: $v" }
        return if (parts.isEmpty()) "Шинэ скан алга." else "Илгээлээ — " + parts.joinToString(" · ")
    }

    private fun formatCounts(counts: Map<String, Int>): String {
        val labels = mapOf(
            "matched" to "Тохирсон",
            "already_registered" to "Өмнө нь бүртгэлтэй",
            "unknown_gtin" to "Танигдаагүй GTIN",
            "not_on_list" to "Жагсаалтад байхгүй",
            "undecodable" to "Задрахгүй",
            "serial_conflict" to "Serial зөрчил",
            "skipped" to "Алгассан (давхардал)",
        )
        val parts = counts.filterValues { it > 0 }
            .map { (k, v) -> "${labels[k] ?: k}: $v" }
        return if (parts.isEmpty()) "Шинэ скан алга." else "Илгээлээ — " + parts.joinToString(" · ")
    }

    private fun friendlyAuthError(e: Exception): String {
        val m = e.message ?: ""
        return when {
            m.contains("Invalid login credentials", ignoreCase = true) ->
                "Имэйл эсвэл нууц үг буруу байна."
            m.contains("Unable to resolve host", ignoreCase = true) ||
                m.contains("Failed to connect", ignoreCase = true) ->
                "Сүлжээнд холбогдож чадсангүй — интернетээ шалгана уу."
            else -> "Нэвтрэхэд алдаа гарлаа: $m"
        }
    }

    companion object {
        private const val TAG = "EpcAppSync"
    }
}
