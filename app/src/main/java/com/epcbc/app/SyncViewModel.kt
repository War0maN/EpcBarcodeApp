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
import com.epcbc.net.TxDraftApi
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
            selectHistoryStocktake(null)
            stocktakes = emptyList()
            stClosed = emptyList()
            showStocktake = false
            selectTxDraft(null)
            txDrafts = emptyList()
            showTxDraft = false
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
    /** productId → хүлээгдэх hex-үүд (дутуугаа нэрээр нь олоход). */
    private var stExpectedHexesByProduct: Map<String, List<String>> = emptyMap()

    /** Тухайн барааны ОДООГООР дутуу hex-үүд (хүлээгдэх − тоологдсон). */
    fun stMissingHexes(productId: String): List<String> =
        (stExpectedHexesByProduct[productId] ?: emptyList()).filter { it !in stSeenLocal }
    /** productId → нэр/SKU (дэлгэцэд). */
    var stProductMeta: Map<String, StocktakeApi.ProductInfo> = emptyMap(); private set

    /** Локал тулгалтад аль хэдийн тооцсон hex-үүд (давхардал 1 л удаа). */
    private val stSeenLocal = HashSet<String>()
    /** productId → локал олдсон тоо (амьд, мөр бүр эндээс уншина). */
    val stFoundLocal = mutableStateMapOf<String, Int>()
    /** "Илүү" — СЕРВЕРИЙН ангилалаар (not_expected = бүртгэлтэй ч snapshot-д
     *  байхгүй EPC; бүртгэлгүй unknown ЭНД ОРОХГҮЙ — вебтэй ижил семантик).
     *  Тооллого сонгоход өмнөх байдлаас, Илгээх бүрд шинэчлэгдэнэ. */
    var stExtraServer by mutableStateOf(0); private set
    /** productId → серверийн found (Явц татахад; дэлгэц max(локал, сервер)). */
    val stFoundServer = mutableStateMapOf<String, Int>()

    // "Илүү"-гийн дэлгэрэнгүй (дарж харах — вебийн Илүү модалтай ижил)
    var stExtrasLoading by mutableStateOf(false); private set
    var stExtrasScans by mutableStateOf<List<StocktakeApi.ExtraScan>>(emptyList()); private set
    var stExtrasEpc by mutableStateOf<Map<String, StocktakeApi.EpcInfo>>(emptyMap()); private set
    var stExtrasMeta by mutableStateOf<Map<String, StocktakeApi.ProductInfo>>(emptyMap()); private set

    fun loadStocktakeExtras(stocktakeId: String? = null) {
        val id = stocktakeId ?: activeStocktake?.id ?: return
        stExtrasLoading = true
        viewModelScope.launch {
            try {
                val scans = StocktakeApi.fetchExtraScans(id)
                // Хуучин (хөлдөөгүй) сканд одоогийн төлөв/салбараар нөхнө.
                val needEpc = scans.filter { it.scanStatus == null && it.epcId != null }.mapNotNull { it.epcId }
                val epcMap = if (needEpc.isEmpty()) emptyMap() else StocktakeApi.fetchEpcInfo(needEpc)
                val pids = (scans.mapNotNull { it.productId } + epcMap.values.map { it.productId }).toSet()
                stExtrasMeta = if (pids.isEmpty()) emptyMap() else StocktakeApi.fetchProducts(pids)
                stExtrasEpc = epcMap
                stExtrasScans = scans
            } catch (e: Exception) {
                Log.w(TAG, "loadStocktakeExtras failed", e)
                syncError = "Илүүгийн жагсаалт татахад алдаа: ${e.message ?: "холболт"}"
            } finally {
                stExtrasLoading = false
            }
        }
    }

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

    // ── Тооллого ЭХЛҮҮЛЭХ (2026-08-05 — вебтэй ижил урсгал төхөөрөмж дээр) ──
    var stBranches by mutableStateOf<List<TxDraftApi.Branch>>(emptyList()); private set
    var stCreateBusy by mutableStateOf(false); private set

    fun loadStBranches() {
        if (stBranches.isNotEmpty()) return
        viewModelScope.launch {
            try {
                stBranches = TxDraftApi.listBranches()
            } catch (e: Exception) {
                Log.w(TAG, "listBranches failed", e)
            }
        }
    }

    /** Шинэ тооллого үүсгээд ШУУД идэвхжүүлнэ (ажилтан цааш тоолоод явна). */
    fun createStocktake(branchId: String, note: String?, onServerState: ((List<String>) -> Unit)? = null) {
        // Найрсаг урьдчилсан шалгалт (вебтэй ижил) — сервер ямар ч байсан хориглоно.
        val open = stocktakes.firstOrNull { it.status == "open" && it.branchName == stBranches.firstOrNull { b -> b.id == branchId }?.name }
        if (open != null) {
            syncError = "Энэ салбарт нээлттэй тооллого (${open.number}) байна — эхлээд түүнийгээ хаана уу."
            return
        }
        stCreateBusy = true
        syncError = null
        viewModelScope.launch {
            try {
                val id = StocktakeApi.createStocktake(branchId, note?.trim()?.ifEmpty { null })
                refreshStocktakes()
                val st = StocktakeApi.fetchStocktake(id)
                syncMessage = "Тооллого ${st.number} эхэллээ."
                selectStocktake(st, onServerState)
            } catch (e: Exception) {
                Log.w(TAG, "createStocktake failed", e)
                syncError = "Тооллого эхлүүлэхэд алдаа: ${e.message ?: "холболт"}"
            } finally {
                stCreateBusy = false
            }
        }
    }

    // ── Тооллогын ТҮҮХ (хаагдсан ажлууд — зөвхөн харах) ──
    // Түүхэнд snapshot-ын таг бүрийг ТАТАХГҮЙ (тэр нь л хүнд) — зөвхөн
    // бараагаар нэгтгэсэн явц + Илүүгийн тоо. Апп гацахгүй.
    var stClosed by mutableStateOf<List<StocktakeApi.Stocktake>>(emptyList()); private set
    var stClosedLoading by mutableStateOf(false); private set
    var stHistory by mutableStateOf<StocktakeApi.Stocktake?>(null); private set
    var stHistoryLoading by mutableStateOf(false); private set
    var stHistoryProgress by mutableStateOf<List<StocktakeApi.ProgressRow>>(emptyList()); private set
    var stHistoryMeta by mutableStateOf<Map<String, StocktakeApi.ProductInfo>>(emptyMap()); private set
    var stHistoryExtra by mutableStateOf(0L); private set

    fun refreshClosedStocktakes() {
        stClosedLoading = true
        syncError = null
        viewModelScope.launch {
            try {
                stClosed = StocktakeApi.listClosedStocktakes()
            } catch (e: Exception) {
                Log.w(TAG, "listClosedStocktakes failed", e)
                syncError = "Түүх татахад алдаа: ${e.message ?: "холболт"}"
            } finally {
                stClosedLoading = false
            }
        }
    }

    fun selectHistoryStocktake(st: StocktakeApi.Stocktake?) {
        stHistory = st
        stHistoryProgress = emptyList()
        stHistoryMeta = emptyMap()
        stHistoryExtra = 0
        stExtrasScans = emptyList()
        stExtrasEpc = emptyMap()
        stExtrasMeta = emptyMap()
        if (st == null) return
        stHistoryLoading = true
        viewModelScope.launch {
            try {
                val rows = StocktakeApi.fetchProgress(st.id)
                stHistoryProgress = rows
                stHistoryMeta = StocktakeApi.fetchProducts(rows.map { it.productId }.toSet())
                stHistoryExtra = StocktakeApi.countExtras(st.id)
            } catch (e: Exception) {
                Log.w(TAG, "history load failed", e)
                syncError = "Түүх татахад алдаа: ${e.message ?: "холболт"}"
                stHistory = null
            } finally {
                stHistoryLoading = false
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
        stExpectedHexesByProduct = emptyMap()
        stProductMeta = emptyMap()
        stSeenLocal.clear()
        stFoundLocal.clear()
        stFoundServer.clear()
        stExtraServer = 0
        stExtrasScans = emptyList()
        stExtrasEpc = emptyMap()
        stExtrasMeta = emptyMap()
        syncMessage = null
        if (st == null) return
        stExpectedLoading = true
        viewModelScope.launch {
            try {
                val items = StocktakeApi.fetchExpectedItems(st.id)
                stExpectedByHex = items.associate { it.epcHex.trim().uppercase() to it.productId }
                stExpectedByProduct = items.groupingBy { it.productId }.eachCount()
                stExpectedHexesByProduct = items.groupBy({ it.productId }, { it.epcHex.trim().uppercase() })
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
                    // Зөвхөн not_expected = Илүү; unknown (бүртгэлгүй) тоолохгүй.
                    if (s.outcome == "not_expected") stExtraServer++
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
            // Snapshot-д байхгүй уншилтыг ЭНД тоолохгүй — бүртгэлтэй эсэхийг
            // сервер л мэднэ; Илгээх үед not_expected гэж ангилагдвал
            // stExtraServer нэмэгдэнэ (вебтэй ижил семантик).
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
                // Шинээр илэрсэн "Илүү"-г серверийн ангиллаас нэмнэ.
                stExtraServer += counts["not_expected"] ?: 0
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

    // ══════════════ ШАТ 2: Шилжүүлэг / Актлалт (ноорог-сагс) ══════════════
    // Уншсан таг бүр СЕРВЕРТ шууд сагслагдана (tx_draft_scan idempotent) —
    // апп унтарсан ч сагс алдагдахгүй, дахин нээгээд үргэлжилнэ. "Гүйлгээ
    // болгох" үед submit_tx_draft → create_transaction (шалгалт бүгд DB талд).

    var showTxDraft by mutableStateOf(false)
    /** transfer | other — нүүрний аль нүднээс орсноор тогтоно. */
    var txType by mutableStateOf("transfer"); private set

    var txBranches by mutableStateOf<List<TxDraftApi.Branch>>(emptyList()); private set
    var txDrafts by mutableStateOf<List<TxDraftApi.Draft>>(emptyList()); private set
    var txDraftsLoading by mutableStateOf(false); private set
    var activeDraft by mutableStateOf<TxDraftApi.Draft?>(null); private set
    var txItemsLoading by mutableStateOf(false); private set
    /** productId → сагсан дахь EPC-үүд (мөр нь бараагаар бүлэглэгдэж зурагдана). */
    var txCartByProduct by mutableStateOf<Map<String, List<TxDraftApi.CartItem>>>(emptyMap()); private set
    var txProductMeta by mutableStateOf<Map<String, StocktakeApi.ProductInfo>>(emptyMap()); private set

    val txCartCount: Int get() = txCartByProduct.values.sumOf { it.size }
    fun txBranchName(id: String?): String? = txBranches.firstOrNull { it.id == id }?.name

    // ── Даалгаврын амьд явц (Алхам 3) ──
    // Жагсаалттай ноорогт уншсан таг бүрийг ТӨХӨӨРӨМЖ ДЭЭР шууд бараатай нь
    // тулгана (SGTIN → GTIN-14 → MatchEngine.key; GID → object_class) —
    // сүлжээгүй ч мөр бүрийн явц амьд. Сервер эцсийн үнэн хэвээр: Сагслахад
    // tx_draft_scan ангилж (not_on_list/over_qty сагсанд оруулахгүй) хадгална.

    /** productId → даалгасан тоо (хоосон = жагсаалтгүй чөлөөт сагс). */
    var txLines: Map<String, Int> = emptyMap(); private set
    /** productId → амьд уншсан тоо (сервер + локал; дэлгэц эндээс уншина). */
    val txPickedLocal = mutableStateMapOf<String, Int>()
    /** Даалгаврын барааны нэр/SKU. */
    var txLineMeta: Map<String, TxDraftApi.LineProduct> = emptyMap(); private set
    /** Жагсаалтад тохироогүй уншилт (зөвлөмжийн тоолуур — сагсанд орохгүй). */
    var txExtraLocal by mutableStateOf(0); private set

    private var txGtinToPid: Map<String, String> = emptyMap()
    private var txClassToPid: Map<Long, String> = emptyMap()
    /** Локал тулгалтад тооцогдсон hex-үүд (давхардал 1 л удаа). */
    private val txSeen = HashSet<String>()

    /** Нүүрний нүднээс: төрөл тогтоож, жагсаалт + салбаруудыг татна. */
    fun openTxDraft(type: String) {
        txType = type
        showTxDraft = true
        activeDraft = null
        txCartByProduct = emptyMap()
        refreshTxDrafts()
        if (txBranches.isEmpty()) {
            viewModelScope.launch {
                try {
                    txBranches = TxDraftApi.listBranches()
                } catch (e: Exception) {
                    Log.w(TAG, "listBranches failed", e)
                }
            }
        }
    }

    fun refreshTxDrafts() {
        txDraftsLoading = true
        syncError = null
        viewModelScope.launch {
            try {
                txDrafts = TxDraftApi.listOpenDrafts(txType)
            } catch (e: Exception) {
                Log.w(TAG, "listOpenDrafts failed", e)
                syncError = "Ноорогийн жагсаалт татахад алдаа: ${e.message ?: "холболт"}"
            } finally {
                txDraftsLoading = false
            }
        }
    }

    /** Шинэ сагс үүсгээд шууд идэвхжүүлнэ. */
    fun createTxDraft(toBranch: String?, note: String?, onServerState: ((List<String>) -> Unit)? = null) {
        submitBusy = true
        syncError = null
        viewModelScope.launch {
            try {
                val id = TxDraftApi.createDraft(txType, toBranch, note)
                val d = TxDraftApi.Draft(
                    id = id, type = txType, toBranch = toBranch, note = note,
                    status = "open", createdAt = "",
                )
                selectTxDraftInternal(d, onServerState)
            } catch (e: Exception) {
                Log.w(TAG, "createDraft failed", e)
                syncError = "Сагс үүсгэхэд алдаа: ${e.message ?: "холболт"}"
            } finally {
                submitBusy = false
            }
        }
    }

    /**
     * Ноорог сонгож сагсыг нь татна. [onServerState]-д сагсан дахь hex-үүдийг
     * өгнө — ScanViewModel "үзсэн" гэж тэмдэглэснээр аль хэдийн сагсалсан таг
     * дахин уншигдвал Илгээх буферт дэмий орохгүй.
     */
    fun selectTxDraft(d: TxDraftApi.Draft?, onServerState: ((List<String>) -> Unit)? = null) {
        if (d == null) {
            activeDraft = null
            txCartByProduct = emptyMap()
            txLines = emptyMap()
            txPickedLocal.clear()
            txLineMeta = emptyMap()
            txGtinToPid = emptyMap()
            txClassToPid = emptyMap()
            txSeen.clear()
            txExtraLocal = 0
            syncMessage = null
            return
        }
        viewModelScope.launch { selectTxDraftInternal(d, onServerState) }
    }

    private suspend fun selectTxDraftInternal(
        d: TxDraftApi.Draft,
        onServerState: ((List<String>) -> Unit)?,
    ) {
        activeDraft = d
        txCartByProduct = emptyMap()
        syncMessage = null
        txItemsLoading = true
        try {
            val items = TxDraftApi.fetchItems(d.id)
            txCartByProduct = items.groupBy { it.productId ?: "?" }
            val missingMeta = txCartByProduct.keys - txProductMeta.keys
            if (missingMeta.isNotEmpty()) {
                txProductMeta = txProductMeta + StocktakeApi.fetchProducts(missingMeta)
            }

            // Даалгаврын жагсаалт + локал тулгалтын түлхүүрүүд.
            val lines = TxDraftApi.fetchLines(d.id)
            txLines = lines.associate { it.productId to it.expected }
            txPickedLocal.clear()
            txSeen.clear()
            txExtraLocal = 0
            if (lines.isNotEmpty()) {
                txLineMeta = TxDraftApi.fetchLineProducts(txLines.keys)
                txGtinToPid = txLineMeta.values
                    .filter { !it.gtin.isNullOrBlank() }
                    .associate { com.epcbc.core.MatchEngine.key(it.gtin!!) to it.id }
                txClassToPid = txLineMeta.values
                    .filter { it.objectClass != null }
                    .associate { it.objectClass!! to it.id }
                // Серверийн явцаар суулгана; сагсан дахь hex-үүд "үзсэн"-д орно.
                for (l in lines) txPickedLocal[l.productId] = l.picked
                for (it2 in items) txSeen.add(it2.epcHex.trim().uppercase())
            } else {
                txGtinToPid = emptyMap()
                txClassToPid = emptyMap()
            }
            onServerState?.invoke(items.map { it.epcHex.trim().uppercase() })
        } catch (e: Exception) {
            Log.w(TAG, "fetchItems failed", e)
            syncError = "Сагс татахад алдаа: ${e.message ?: "холболт"}"
            activeDraft = null
        } finally {
            txItemsLoading = false
        }
    }

    /**
     * Уншигчаас шинэ EPC ирэх бүрд дуудагдана (жагсаалттай даалгавар идэвхтэй
     * үед л ажиллана — бусад үед зардал 0). SGTIN тагийг GTIN-14-өөр,
     * GID тагийг object_class-аар нь даалгаврын мөртэй тулгана.
     */
    fun onTxDraftScans(hexes: List<String>) {
        if (!showTxDraft || activeDraft == null || txLines.isEmpty() || hexes.isEmpty()) return
        for (raw in hexes) {
            val hex = raw.trim().uppercase()
            if (!txSeen.add(hex)) continue
            val pid = matchLineProduct(hex)
            if (pid == null) {
                txExtraLocal++
                continue
            }
            val expected = txLines[pid] ?: 0
            val cur = txPickedLocal[pid] ?: 0
            // Даалгаснаас илүүг явцад оруулахгүй (сервер ч сагсанд оруулахгүй).
            if (cur < expected) txPickedLocal[pid] = cur + 1
            else txExtraLocal++
        }
    }

    /** EPC hex → даалгаврын бараа (SGTIN=GTIN, GID=object_class); олдохгүй бол null. */
    private fun matchLineProduct(hex: String): String? {
        if (hex.length != 24) return null
        return when {
            hex.startsWith("30") -> {
                val gtin = runCatching { com.epcbc.core.EpcDecoder.decode(hex).gtin14 }.getOrNull()
                gtin?.let { txGtinToPid[com.epcbc.core.MatchEngine.key(it)] }
            }
            hex.startsWith("35") ->
                // GID-96: 8 бит header + 28 бит manager (7 hex) + 24 бит class (6 hex).
                runCatching { hex.substring(9, 15).toLong(16) }.getOrNull()?.let { txClassToPid[it] }
            else -> null
        }
    }

    /** Очих салбар солино (зөвхөн transfer, submit хүртэл). */
    fun updateTxDest(toBranch: String?) {
        val d = activeDraft ?: return
        viewModelScope.launch {
            try {
                TxDraftApi.updateDraft(d.id, toBranch, d.note)
                activeDraft = d.copy(toBranch = toBranch)
            } catch (e: Exception) {
                Log.w(TAG, "updateDraft failed", e)
                syncError = "Хадгалахад алдаа: ${e.message ?: "холболт"}"
            }
        }
    }

    /** Буферт хуримтлагдсан уншилтуудыг сагслана (500-аар багц, idempotent). */
    fun txScanBuffer(hexes: List<String>, onSuccess: () -> Unit = {}) {
        val d = activeDraft ?: return
        if (hexes.isEmpty()) {
            syncMessage = "Сагслах скан алга."
            return
        }
        submitBusy = true
        syncError = null
        syncMessage = null
        viewModelScope.launch {
            try {
                val counts = TxDraftApi.scan(d.id, hexes)
                syncMessage = formatTxCounts(counts)
                onSuccess()
                selectTxDraftInternal(activeDraft ?: d, null)
            } catch (e: Exception) {
                Log.w(TAG, "txScanBuffer failed", e)
                syncError = "Сагслахад алдаа: ${e.message ?: "холболт"} — дахин илгээхэд аюулгүй (давхардахгүй)."
            } finally {
                submitBusy = false
            }
        }
    }

    /** Нэг барааны бүх ширхгийг сагснаас хасна. */
    fun txRemoveProduct(productId: String) {
        val d = activeDraft ?: return
        val ids = txCartByProduct[productId]?.map { it.epcId } ?: return
        viewModelScope.launch {
            try {
                TxDraftApi.removeItems(d.id, ids)
                selectTxDraftInternal(d, null)
            } catch (e: Exception) {
                Log.w(TAG, "removeItems failed", e)
                syncError = "Хасахад алдаа: ${e.message ?: "холболт"}"
            }
        }
    }

    /** Сагсыг жинхэнэ гүйлгээ болгоно. Амжилтад ноорог хаагдаж жагсаалт руу буцна. */
    fun submitTxDraft() {
        val d = activeDraft ?: return
        submitBusy = true
        syncError = null
        syncMessage = null
        viewModelScope.launch {
            try {
                val r = TxDraftApi.submit(d.id)
                val label = when (d.type) {
                    "transfer" -> "Шилжүүлэг"
                    "other" -> "Актлалт"
                    "sale" -> "Борлуулалт"
                    else -> "Буцаалт"
                }
                syncMessage = buildString {
                    append("$label ${r.txNumber ?: ""} үүслээ — ${r.submitted} ширхэг.")
                    if (r.pruned > 0) append(" (${r.pruned} хүчингүй таг хасагдав.)")
                }
                activeDraft = null
                txCartByProduct = emptyMap()
                refreshTxDrafts()
            } catch (e: Exception) {
                Log.w(TAG, "submitTxDraft failed", e)
                syncError = "Гүйлгээ үүсгэхэд алдаа: ${e.message ?: "холболт"}"
            } finally {
                submitBusy = false
            }
        }
    }

    /** Ноорог цуцална (гүйлгээ үүсэхгүй; таг-уудад юу ч болоогүй). */
    fun cancelTxDraft() {
        val d = activeDraft ?: return
        viewModelScope.launch {
            try {
                TxDraftApi.cancel(d.id)
                activeDraft = null
                txCartByProduct = emptyMap()
                syncMessage = "Ноорог цуцлагдлаа."
                refreshTxDrafts()
            } catch (e: Exception) {
                Log.w(TAG, "cancelTxDraft failed", e)
                syncError = "Цуцлахад алдаа: ${e.message ?: "холболт"}"
            }
        }
    }

    private fun formatTxCounts(counts: Map<String, Int>): String {
        val labels = mapOf(
            "added" to "Сагсанд орсон",
            "already" to "Өмнө орсон",
            // Шаардлага төрлөөс хамаардаг (return = Борлуулсан/Актлагдсан л
            // орно) тул нэр нь ерөнхий.
            "not_active" to "Төлөв тохирохгүй",
            "wrong_branch" to "Өөр салбарын",
            "no_access" to "Эрхгүй салбар",
            "unknown" to "Бүртгэлгүй",
            "not_on_list" to "Жагсаалтад байхгүй",
            "over_qty" to "Тоо гүйцсэн",
            "skipped" to "Алгассан",
        )
        val parts = counts.filterValues { it > 0 }
            .map { (k, v) -> "${labels[k] ?: k}: $v" }
        return if (parts.isEmpty()) "Шинэ скан алга." else parts.joinToString(" · ")
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
