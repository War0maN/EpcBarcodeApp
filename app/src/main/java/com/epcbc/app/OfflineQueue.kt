package com.epcbc.app

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.epcbc.net.ReceivingApi
import com.epcbc.net.StocktakeApi
import com.epcbc.net.TxDraftApi
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * ОФЛАЙН БУФЕР: сүлжээгүй үед "Илгээх/Сагслах/Хүлээн авах" дарахад тухайн
 * багц ДИСКЭНД дараалалд хадгалагдаж (апп унтарсан ч алдагдахгүй), сүлжээ
 * сэргэхэд автоматаар дарааллаараа илгээгдэнэ. Бүх скан RPC idempotent тул
 * давхар илгээлт аюулгүй (давхардал серверт "already" гэж тоологдоно).
 *
 * Алдааны ангилал:
 *  - СҮЛЖЭЭНИЙ алдаа (timeout, DNS, IO) → дараалал хэвээр, дараа дахин.
 *  - Серверийн ТАТГАЛЗАЛ (RestException — эрхгүй, ажил хаагдсан г.м.) →
 *    дахин оролдоод утгагүй тул тухайн багц хасагдаж, шалтгаан нь
 *    [lastMessage]-ээр гарна (чимээгүй алга болохгүй).
 */
object OfflineQueue {

    @Serializable
    data class Entry(
        val kind: String,      // receiving | stocktake | txdraft | txreceive
        val targetId: String,  // receipt / stocktake / draft / transaction id
        val hexes: List<String>,
        val at: Long,          // хадгалсан мөч (мэдээллийн чанартай)
    )

    private const val TAG = "OfflineQueue"
    private var file: File? = null
    private val mutex = Mutex()
    private var entries: MutableList<Entry> = mutableListOf()

    /** Дараалалд хүлээж буй багцын тоо — нүүрний индикатор эндээс уншина. */
    var count by mutableStateOf(0); private set

    /** Сүүлийн flush-ийн мэдээ (амжилт/татгалзал) — уншаад null болгоно. */
    var lastMessage by mutableStateOf<String?>(null)

    /** Апп асахад нэг удаа — дискээс дарааллаа сэргээнэ. */
    fun init(dir: File) {
        if (file != null) return
        file = File(dir, "offline_queue.json")
        entries = runCatching {
            file!!.takeIf { it.exists() }?.readText()
                ?.let { Json.decodeFromString<List<Entry>>(it) }
        }.getOrNull().orEmpty().toMutableList()
        count = entries.size
        if (entries.isNotEmpty()) Log.i(TAG, "restored ${entries.size} pending batches")
    }

    private fun save() {
        runCatching { file?.writeText(Json.encodeToString(entries)) }
            .onFailure { Log.w(TAG, "save failed", it) }
        count = entries.size
    }

    /** Багц дараалалд орно (дискэнд шууд бичигдэнэ). */
    suspend fun enqueue(kind: String, targetId: String, hexes: List<String>) {
        mutex.withLock {
            entries.add(Entry(kind, targetId, hexes, System.currentTimeMillis()))
            save()
        }
    }

    /**
     * Дарааллыг эхнээс нь илгээнэ. Сүлжээний алдаанд зогсоно (үлдсэн нь
     * хэвээр); серверийн татгалзалд багцыг хасаад үргэлжилнэ.
     * @return true = дараалал хоосон боллоо.
     */
    suspend fun flush(): Boolean = mutex.withLock {
        if (entries.isEmpty()) return@withLock true
        var sentTags = 0
        var dropped: String? = null
        while (entries.isNotEmpty()) {
            val e = entries.first()
            try {
                when (e.kind) {
                    "receiving" -> ReceivingApi.submitScans(e.targetId, e.hexes)
                    "stocktake" -> StocktakeApi.submitScans(e.targetId, e.hexes)
                    "txdraft" -> TxDraftApi.scan(e.targetId, e.hexes)
                    "txreceive" -> TxDraftApi.receiveScan(e.targetId, e.hexes)
                    else -> Log.w(TAG, "unknown kind: ${e.kind}")
                }
                entries.removeAt(0)
                sentTags += e.hexes.size
                save()
            } catch (ex: RestException) {
                Log.w(TAG, "flush: server rejected ${e.kind}/${e.targetId}", ex)
                dropped = ex.message?.take(120)
                entries.removeAt(0)
                save()
            } catch (ex: Exception) {
                Log.w(TAG, "flush: network error — will retry later", ex)
                break
            }
        }
        if (sentTags > 0 || dropped != null) {
            lastMessage = buildString {
                if (sentTags > 0) append("Офлайн буферээс $sentTags уншилт илгээгдлээ — Шинэчлэх товчоор явцаа харна уу")
                if (dropped != null) {
                    if (isNotEmpty()) append("\n")
                    append("Зарим багцыг сервер татгалзав: $dropped")
                }
            }
        }
        entries.isEmpty()
    }
}
