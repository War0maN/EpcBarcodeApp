package com.epcbc.app

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.epcbc.net.ReceivingApi
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
