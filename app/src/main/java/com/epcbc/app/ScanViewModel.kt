package com.epcbc.app

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.epcbc.core.EpcDecoder
import com.epcbc.core.MatchEngine
import com.epcbc.data.PackingListReader
import com.epcbc.scan.EpcStream
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.thread

/**
 * Holds ALL scan state and SDK/business logic. Living in a ViewModel means the state — the
 * scanned EPC list, the initialized reader, scanning/finder state — survives a configuration
 * change (screen rotation). Previously this all lived in MainActivity and was torn down +
 * rebuilt on every rotation, which also freed the hardware reader. Now the reader is freed
 * only in [onCleared] (real finish), not on rotation.
 *
 * State is exposed as Compose snapshot state (mutableStateOf / mutableStateListOf), so the
 * Composables in MainActivity observe it directly. SDK callbacks arrive on the SDK's own
 * thread; UI-visible state is always mutated back on the main thread via [runOnMain].
 */
class ScanViewModel(app: Application) : AndroidViewModel(app) {

    // ── Reader / scan state ──────────────────────────────────────────────
    private var reader: Any? = null
    var readerReady by mutableStateOf(false); private set
    var isScanning by mutableStateOf(false); private set
    var statusMessage by mutableStateOf("Эхлэхийн тулд 'УНШИГЧ ЭХЛҮҮЛЭХ' товчийг дар"); private set

    val scannedEpcs = mutableStateListOf<String>()
    val packingList = mutableStateListOf<PackingListReader.PackingItem>()
    var packingListName by mutableStateOf<String?>(null); private set
    var packingListStatus by mutableStateOf<String?>(null); private set

    // ── Scan settings ────────────────────────────────────────────────────
    var outputPower by mutableStateOf(30); private set
    var continuousMode by mutableStateOf(true); private set
    var soundEnabled by mutableStateOf(true); private set

    /** Дэлгэцийн горим: system | dark | light (Профайлаас солино, хадгалагдана). */
    var themeMode by mutableStateOf("system"); private set
    fun setTheme(mode: String) {
        themeMode = mode
        saveSettings()
    }

    /**
     * Шинэ (давхардаагүй) EPC ирэх бүрд main thread дээр дуудагдах hook —
     * тооллого зэрэг горимууд амьд тулгалтаа O(багц)-аар хийнэ. MainActivity
     * онооно; scannedEpcs-ийг бүхэлд нь дахин боловсруулахаас хамаагүй хөнгөн.
     */
    var onNewEpcs: ((List<String>) -> Unit)? = null

    // ── Pure-UI toggles (set directly by the UI) ─────────────────────────
    var showSettings by mutableStateOf(false)
    var selectedItem by mutableStateOf<PackingListReader.PackingItem?>(null)
    var showFinder by mutableStateOf(false)
    var finderTargetEpc by mutableStateOf("")

    // ── Tag-finder runtime state ─────────────────────────────────────────
    /**
     * Олох горимын нарийн тааруулга: null биш үед зөвхөн ЭНЭ олонлогийн
     * hex-үүд Geiger-т тооцогдоно (тооллогын дутуу гэх мэт — уншигчийн
     * төмөр шүүлт барааны угтвараар, программын шүүлт яг дутуу дээр).
     * null үед хуучин зан төлөв: finderTargetEpc угтвараар.
     */
    var finderMatchSet by mutableStateOf<Set<String>?>(null)

    var finderActive by mutableStateOf(false); private set
    var finderRssi by mutableStateOf<Int?>(null); private set
    var finderPercent by mutableStateOf(0); private set
    var finderLastSeenMs by mutableStateOf(0L); private set
    var finderMessage by mutableStateOf<String?>(null); private set

    // ── Internals ────────────────────────────────────────────────────────
    private val epcStream = EpcStream()
    private val seenEpcs: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    private var drainTimer: Timer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var getEpcMethod: java.lang.reflect.Method? = null
    @Volatile private var getRssiMethod: java.lang.reflect.Method? = null

    private val toneGen: ToneGenerator? by lazy {
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 90) } catch (_: Throwable) { null }
    }

    init {
        loadSettings()
        restoreSession()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    // ── Packing list import ──────────────────────────────────────────────
    fun loadPackingList(uri: Uri) {
        packingListStatus = "Файл уншиж байна..."
        thread(start = true, name = "PackingListReader") {
            try {
                val result = getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                    PackingListReader.read(stream)
                } ?: throw Exception("InputStream null")

                runOnMain {
                    packingList.clear()
                    packingList.addAll(result.items)
                    packingListName = uri.lastPathSegment?.substringAfterLast('/')
                    packingListStatus = if (result.warnings.isEmpty()) {
                        "${result.items.size} мөр амжилттай орлоо (${result.skipped} skip)"
                    } else {
                        "${result.items.size} мөр + ${result.warnings.size} warning"
                    }
                    Log.i(TAG, "Packing list loaded: ${result.items.size} items, warnings=${result.warnings}")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "loadPackingList failed", e)
                runOnMain { packingListStatus = "АЛДАА: ${e.message}" }
            }
        }
    }

    fun clearPackingList() {
        packingList.clear()
        packingListName = null
        packingListStatus = null
    }

    fun clearScans() {
        scannedEpcs.clear()
        seenEpcs.clear()
        epcStream.drain()
        // Хуучин "Зогссон. N EPC уншсан." мессеж үлдвэл тоолууртай зөрж
        // төөрөгдүүлдэг — цэвэрлэсэн тухай шинэ мессеж тавина.
        statusMessage = if (readerReady) "Цэвэрлэв. Сканд бэлэн." else "Цэвэрлэв."
        saveSession()
    }

    /**
     * Шинэ ажил (тооллого г.м.) сонгоход буферээ цэвэрлэж, серверт аль хэдийн
     * бүртгэгдсэн уншилтуудыг "үзсэн"-д тооцно. Хоёр асуудлыг зэрэг шийднэ:
     * (а) өмнөх ажлын үлдэгдэл уншилт шинэ ажилд илгээгдэхгүй;
     * (б) аль хэдийн тоологдсон таг дахин уншигдахад Илгээх тоолуур хий
     *     дэмий өсөхгүй (сервер угаас алгасах ч 10 багц дэмий явахгүй).
     */
    fun resetForJob(serverSeen: Collection<String>) {
        scannedEpcs.clear()
        seenEpcs.clear()
        epcStream.drain()
        seenEpcs.addAll(serverSeen)
        statusMessage = if (serverSeen.isEmpty()) "Ажилд бэлэн — сканд ор."
            else "Ажилд бэлэн — өмнө бүртгэгдсэн ${serverSeen.size} уншилт сэргэлээ."
        saveSession()
    }

    /**
     * Серверт амжилттай илгээсний дараах цэвэрлэлт: илгээх буфер (жагсаалт)
     * хоосорно, харин seenEpcs ХЭВЭЭР — ойролцоох ижил тагууд шууд дахин
     * орж ирээд "Илгээх (N)" тоолуурыг хий дэмий өсгөхгүй (сервер давхардлыг
     * алгасдаг ч тоолуур 0-ээс эхлэх нь ойлгомжтой). Цэвэрлэх товч хоёуланг
     * нь арилгадаг хэвээр.
     */
    fun clearAfterSubmit() {
        scannedEpcs.clear()
        statusMessage = "Илгээсэн. Шинэ скан хүлээж байна."
        saveSession()
    }

    // ── CSV export ───────────────────────────────────────────────────────
    fun exportResultsToCsv(uri: Uri) {
        thread(start = true, name = "ExportCsv") {
            try {
                // Group scanned EPCs by their converted barcode (shared with the live UI matcher).
                // Түлхүүрүүд MatchEngine.key-ээр нормчлогдсон тул lookup талдаа мөн key().
                val readByBarcode = MatchEngine.groupByBarcode(scannedEpcs.toList())
                val packingByBarcode = packingList.associateBy { MatchEngine.key(it.barcode) }

                // Split scanned EPCs into "matched" (in packing list) and "orphan" (not in list)
                data class MatchedRow(val epc: String, val barcode: String, val item: PackingListReader.PackingItem)
                data class OrphanRow(val epc: String, val decodedBarcode: String, val format: String)

                val matchedRows = mutableListOf<MatchedRow>()
                val orphanRows = mutableListOf<OrphanRow>()
                for ((barcode, epcsForBarcode) in readByBarcode) {
                    val item = packingByBarcode[barcode]
                    for (epc in epcsForBarcode) {
                        if (item != null) {
                            matchedRows.add(MatchedRow(epc, barcode, item))
                        } else {
                            val format = try { EpcDecoder.decode(epc, true).format } catch (_: Throwable) { "UNKNOWN" }
                            orphanRows.add(OrphanRow(epc, barcode, format))
                        }
                    }
                }

                // Group same-product EPCs together: sort by SKU → barcode → EPC
                matchedRows.sortWith(compareBy({ it.item.sku ?: "" }, { it.barcode }, { it.epc }))
                orphanRows.sortWith(compareBy({ it.decodedBarcode }, { it.epc }))

                // Compute totals
                var totalExpected = 0
                var totalRead = 0
                var totalOver = 0
                for (item in packingList.toList()) {
                    val read = readByBarcode[MatchEngine.key(item.barcode)]?.size ?: 0
                    totalExpected += item.qty
                    totalRead += read.coerceAtMost(item.qty)
                    totalOver += (read - item.qty).coerceAtLeast(0)
                }
                val totalMissing = (totalExpected - totalRead).coerceAtLeast(0)

                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    val writer = out.bufferedWriter()
                    // BOM so Excel opens UTF-8 correctly with Mongolian text
                    writer.write("﻿")

                    // ХҮСНЭГТ 1: SKU тус бүрийн дүгнэлт
                    writer.write("=== ХҮСНЭГТ 1: SKU тус бүрийн дүгнэлт ===\n")
                    writer.write("SKU,Barcode,Item Name,Expected,Read,Status\n")
                    for (item in packingList.toList()) {
                        val read = readByBarcode[MatchEngine.key(item.barcode)]?.size ?: 0
                        val status = when {
                            read == 0 -> "Уншигдаагүй"
                            read == item.qty -> "Бүрэн"
                            read > item.qty -> "Илүү (+${read - item.qty})"
                            else -> "Дутуу (-${item.qty - read})"
                        }
                        val sku = (item.sku ?: "").replace("\"", "\"\"")
                        val name = (item.name ?: "").replace("\"", "\"\"")
                        writer.write("\"$sku\",${item.barcode},\"$name\",${item.qty},$read,\"$status\"\n")
                    }
                    val grandStatus = when {
                        totalRead == totalExpected && totalOver == 0 -> "БҮРЭН"
                        totalOver > 0 -> "Дутуу $totalMissing, Илүү $totalOver"
                        else -> "Дутуу $totalMissing"
                    }
                    writer.write("\"=== НИЙТ ===\",,,$totalExpected,$totalRead,\"$grandStatus\"\n")

                    // ХҮСНЭГТ 2: Packing list-тэй таарсан EPC (SKU-аар групплүүлсэн)
                    writer.write("\n=== ХҮСНЭГТ 2: Packing list-тэй таарсан EPC (SKU-аар групплүүлсэн) ===\n")
                    writer.write("EPC,Decoded Barcode,SKU,Item Name\n")
                    for (r in matchedRows) {
                        val sku = (r.item.sku ?: "").replace("\"", "\"\"")
                        val name = (r.item.name ?: "").replace("\"", "\"\"")
                        writer.write("${r.epc},${r.barcode},\"$sku\",\"$name\"\n")
                    }
                    writer.write("\"=== НИЙТ: ${matchedRows.size} ширхэг ===\",,,\n")

                    // ХҮСНЭГТ 3: Орхигдсон EPC (packing list-нд байхгүй)
                    writer.write("\n=== ХҮСНЭГТ 3: Орхигдсон EPC (packing list-нд байхгүй) ===\n")
                    writer.write("EPC,Decoded Barcode,Format\n")
                    for (r in orphanRows) {
                        writer.write("${r.epc},${r.decodedBarcode},${r.format}\n")
                    }
                    writer.write("\"=== НИЙТ: ${orphanRows.size} ширхэг ===\",,\n")

                    writer.flush()
                } ?: throw Exception("OutputStream null")

                val matchedCount = matchedRows.size
                val orphanCount = orphanRows.size
                runOnMain {
                    statusMessage = "Export амжилттай: ${packingList.size} SKU, $matchedCount таарсан, $orphanCount орхигдсон"
                }
            } catch (e: Throwable) {
                Log.e(TAG, "exportResultsToCsv failed", e)
                runOnMain { statusMessage = "АЛДАА export: ${e.message}" }
            }
        }
    }

    // ── Reader lifecycle ─────────────────────────────────────────────────
    fun initReader() {
        try {
            statusMessage = "Уншигчийг идэвхжүүлж байна..."
            Log.i(TAG, "Calling RFIDWithUHFUART.getInstance()")
            val cls = Class.forName("com.rscja.deviceapi.RFIDWithUHFUART")
            val getInstance = cls.getMethod("getInstance")
            val instance = getInstance.invoke(null)
            Log.i(TAG, "Got instance: $instance")

            val initMethod = cls.getMethod("init", android.content.Context::class.java)
            val ok = initMethod.invoke(instance, getApplication()) as Boolean
            Log.i(TAG, "init() returned: $ok")

            if (ok) {
                reader = instance
                readerReady = true
                statusMessage = "Бэлэн. ЭХЛҮҮЛЭХ дар эсвэл хажуугийн PTT товчийг ашигла."
                try {
                    val callbackInterface = Class.forName("com.rscja.deviceapi.interfaces.IUHFInventoryCallback")
                    val callback = java.lang.reflect.Proxy.newProxyInstance(
                        callbackInterface.classLoader,
                        arrayOf(callbackInterface)
                    ) { _, method, args ->
                        if (method.name == "callback" && args != null && args.isNotEmpty()) {
                            val tagInfo = args[0]
                            try {
                                val epcMethod = getEpcMethod
                                    ?: tagInfo.javaClass.getMethod("getEPC").also { getEpcMethod = it }
                                val epc = epcMethod.invoke(tagInfo) as? String
                                if (epc != null) {
                                    epcStream.push(epc)   // O(1) enqueue, never blocks

                                    val epcUp = epc.uppercase()
                                    val finderHit = finderMatchSet?.contains(epcUp)
                                        ?: (finderTargetEpc.isNotBlank() &&
                                            epcUp.startsWith(finderTargetEpc.uppercase()))
                                    if (finderActive && finderHit) {
                                        val rssi = extractRssi(tagInfo)
                                        if (rssi != null) {
                                            val now = System.currentTimeMillis()
                                            runOnMain {
                                                finderRssi = rssi
                                                finderPercent = rssiToPercent(rssi)
                                                finderLastSeenMs = now
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "callback EPC read failed", e)
                            }
                        }
                        null
                    }
                    val setCb = cls.getMethod("setInventoryCallback", callbackInterface)
                    setCb.invoke(instance, callback)
                    Log.i(TAG, "Callback wired")

                    applyPower(outputPower)
                    startDrainTimer()
                } catch (e: Throwable) {
                    Log.e(TAG, "Callback wiring failed", e)
                    statusMessage = "АНХААР: callback холбогдоогүй — ${e.message}"
                }
            } else {
                statusMessage = "АНХААР: init() false буцлаа. Reader физикийн төлвийг шалга."
            }
        } catch (e: Throwable) {
            Log.e(TAG, "initReader failed", e)
            statusMessage = "АЛДАА: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun toggleScan() { if (isScanning) stopScan() else startScan() }

    /**
     * Уншигчийг сэргээх: UHF модулийг өөр апп эзэмшээд буцахад хуучин handle
     * үхмэл болдог. free (хуучин handle-ээ суллах) → init (шинээр авах) →
     * нэг удаа дахин эхлүүлэх. Давтан сэргээлт үүсэхээс recovering туг +
     * startScan(allowRecover=false) хоёр давхар хамгаална.
     */
    private var recovering = false
    private fun recoverReaderAndRetry() {
        if (recovering) return
        recovering = true
        statusMessage = "Уншигч алдагдсан — дахин холбож байна…"
        thread(start = true, name = "ReaderRecover") {
            try { reader?.javaClass?.getMethod("free")?.invoke(reader) } catch (e: Throwable) {
                Log.w(TAG, "recover free failed (үргэлжилнэ)", e)
            }
            runOnMain {
                reader = null
                readerReady = false
                initReader() // init + callback + power + drain timer бүгд дахин
                if (readerReady) {
                    startScan(allowRecover = false)
                } else {
                    statusMessage = "Уншигч сэргэсэнгүй — UHF ашигладаг өөр аппыг хааж, УНШИГЧ ЭХЛҮҮЛЭХ дар."
                }
                recovering = false
            }
        }
    }

    /** Hardware trigger (C5 PTT) pressed. Mirrors the old onKeyDown semantics. */
    fun onTriggerDown() {
        if (!readerReady) return
        if (continuousMode) {
            if (isScanning) stopScan() else startScan()
        } else {
            startScan()
        }
    }

    private fun startScan(allowRecover: Boolean = true) {
        if (!readerReady || reader == null) {
            statusMessage = "Эхлээд УНШИГЧ ЭХЛҮҮЛЭХ дар"
            return
        }
        if (isScanning) return
        try {
            if (continuousMode) {
                val ok = reader!!.javaClass.getMethod("startInventoryTag").invoke(reader) as Boolean
                Log.i(TAG, "startInventoryTag -> $ok")
                isScanning = ok
                if (ok) {
                    statusMessage = "Уншиж байна... (${outputPower} dBm)"
                } else if (allowRecover) {
                    // UHF модулийг өөр апп (AppCenter-ийн UHF г.м.) түр эзэмшвэл
                    // бидний handle хүчингүй болж start false буцдаг — автоматаар
                    // free+init хийгээд дахин эхлүүлнэ (өмнө нь аппыг бүрэн хаах
                    // шаардлагатай болдог байсан).
                    recoverReaderAndRetry()
                } else {
                    statusMessage = "Эхлэх боломжгүй — UHF ашигладаг өөр аппыг хааж, УНШИГЧ ЭХЛҮҮЛЭХ дар."
                }
            } else {
                isScanning = true
                statusMessage = "Уншиж байна..."
                thread(start = true, name = "SingleScan") {
                    try {
                        val tagInfo = reader!!.javaClass.getMethod("inventorySingleTag").invoke(reader)
                        val epc = tagInfo?.javaClass?.getMethod("getEPC")?.invoke(tagInfo) as? String
                        runOnMain {
                            if (epc != null) {
                                epcStream.push(epc)
                                statusMessage = "1 ширхэг уншигдсан"
                            } else {
                                statusMessage = "Tag олдсонгүй"
                            }
                            isScanning = false
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "single scan failed", e)
                        runOnMain {
                            statusMessage = "АЛДАА: ${e.message}"
                            isScanning = false
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "startScan failed", e)
            statusMessage = "АЛДАА: ${e.message}"
            isScanning = false
        }
    }

    private fun stopScan() {
        try {
            val ok = reader?.javaClass?.getMethod("stopInventory")?.invoke(reader) as? Boolean ?: false
            Log.i(TAG, "stopInventory -> $ok")
            isScanning = false
            statusMessage = "Зогссон. ${scannedEpcs.size} EPC уншсан."
            saveSession()
        } catch (e: Throwable) {
            Log.e(TAG, "stopScan failed", e)
        }
    }

    private fun startDrainTimer() {
        drainTimer?.cancel()
        drainTimer = Timer("EpcDrain", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    val batch = epcStream.drain()
                    if (batch.isEmpty()) {
                        decayFinderIfStale()
                        return
                    }
                    val newOnes = ArrayList<String>(batch.size)
                    for (epc in batch) {
                        if (seenEpcs.add(epc)) newOnes.add(epc)
                    }
                    if (newOnes.isNotEmpty()) {
                        if (soundEnabled) {
                            try { toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 30) } catch (_: Throwable) {}
                        }
                        runOnMain {
                            scannedEpcs.addAll(0, newOnes)
                            onNewEpcs?.invoke(newOnes)
                        }
                    }
                    decayFinderIfStale()
                }
            }, 100, 100)
        }
    }

    /** Tag-finder signal decay: fade percent toward zero when the tag hasn't been seen for >800ms. */
    private fun decayFinderIfStale() {
        if (finderActive && finderLastSeenMs > 0) {
            val sinceMs = System.currentTimeMillis() - finderLastSeenMs
            if (sinceMs > 800) {
                runOnMain {
                    finderPercent = (finderPercent - 5).coerceAtLeast(0)
                    if (finderPercent == 0) finderRssi = null
                }
            }
        }
    }

    // ── Settings + session persistence ───────────────────────────────────
    fun applySettings(power: Int, continuous: Boolean, sound: Boolean) {
        outputPower = power
        continuousMode = continuous
        soundEnabled = sound
        applyPower(power)
        saveSettings()
        showSettings = false
    }

    private fun loadSettings() {
        val p = getApplication<Application>().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        outputPower = p.getInt("power", 30)
        continuousMode = p.getBoolean("continuous", true)
        soundEnabled = p.getBoolean("sound", true)
        themeMode = p.getString("theme", "system") ?: "system"
        Log.i(TAG, "loadSettings: power=$outputPower mode=${if (continuousMode) "auto" else "single"} sound=$soundEnabled")
    }

    private fun saveSettings() {
        getApplication<Application>().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE).edit()
            .putInt("power", outputPower)
            .putBoolean("continuous", continuousMode)
            .putBoolean("sound", soundEnabled)
            .putString("theme", themeMode)
            .apply()
    }

    private fun sessionFile() = java.io.File(getApplication<Application>().filesDir, SESSION_FILE)

    fun saveSession() {
        val snapshot = scannedEpcs.toList()
        thread(start = true, name = "SaveSession") {
            try {
                sessionFile().writeText(snapshot.joinToString("\n"))
            } catch (e: Throwable) {
                Log.e(TAG, "saveSession failed", e)
            }
        }
    }

    private fun restoreSession() {
        try {
            val f = sessionFile()
            if (!f.exists()) return
            val lines = f.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return
            scannedEpcs.clear()
            scannedEpcs.addAll(lines)
            seenEpcs.clear()
            seenEpcs.addAll(lines)
            statusMessage = "Өмнөх session сэргээв: ${lines.size} EPC. Үргэлжлүүлэх эсвэл Цэвэрлэх."
            Log.i(TAG, "restoreSession: ${lines.size} EPCs")
        } catch (e: Throwable) {
            Log.e(TAG, "restoreSession failed", e)
        }
    }

    // ── RSSI helpers ─────────────────────────────────────────────────────
    private fun extractRssi(tagInfo: Any): Int? {
        getRssiMethod?.let { m ->
            return parseRssi(try { m.invoke(tagInfo) } catch (_: Throwable) { null })
        }
        val candidates = listOf("getRssi", "getRSSI", "getRssiValue", "getRssiDbm")
        for (name in candidates) {
            try {
                val method = tagInfo.javaClass.getMethod(name)
                val parsed = parseRssi(method.invoke(tagInfo))
                if (parsed != null) {
                    getRssiMethod = method
                    return parsed
                }
            } catch (_: Throwable) { /* try next */ }
        }
        return null
    }

    private fun parseRssi(result: Any?): Int? = when (result) {
        is Int -> result
        is Float -> result.toInt()
        is Double -> result.toInt()
        is String -> {
            val cleaned = result.trim().removeSuffix("dBm").trim()
            cleaned.toDoubleOrNull()?.toInt()
                ?: cleaned.split(".").firstOrNull()?.toIntOrNull()
        }
        else -> null
    }

    private fun rssiToPercent(rssi: Int): Int {
        val lo = -80
        val hi = -30
        return (((rssi - lo).toFloat() / (hi - lo)) * 100).toInt().coerceIn(0, 100)
    }

    // ── Tag finder ───────────────────────────────────────────────────────
    fun startFinder() {
        val target = finderTargetEpc.trim().uppercase()
        Log.i(TAG, "startFinder: target='$target' reader=$reader readerReady=$readerReady isScanning=$isScanning")
        if (target.isBlank()) {
            Log.w(TAG, "startFinder: target empty, ignoring")
            return
        }

        if (reader == null || !readerReady) {
            Log.w(TAG, "startFinder: reader not ready, attempting init")
            initReader()
        }

        val r = reader
        if (r == null) {
            Log.e(TAG, "startFinder: reader still null after init attempt")
            finderMessage = "АЛДАА: Уншигч идэвхжсэнгүй. Энэ цонхыг хааж 'УНШИГЧ ЭХЛҮҮЛЭХ' дар."
            statusMessage = "АЛДАА: Уншигч идэвхжсэнгүй"
            return
        }

        try {
            applyHardwareFilter(target)
            if (!isScanning) {
                val ok = r.javaClass.getMethod("startInventoryTag").invoke(r) as Boolean
                Log.i(TAG, "startFinder: startInventoryTag -> $ok")
                if (ok) {
                    isScanning = true
                    statusMessage = "Уншиж байна... (Tag finder)"
                } else {
                    finderMessage = "АЛДАА: scan эхлүүлж чадсангүй"
                    return
                }
            } else {
                statusMessage = "Уншиж байна... (Tag finder)"
            }
            finderRssi = null
            finderPercent = 0
            finderLastSeenMs = 0L
            finderActive = true
            finderMessage = "Хайж байна — C5-аа эргэлдүүл."
            Log.i(TAG, "startFinder: finderActive = true")
        } catch (e: Throwable) {
            Log.e(TAG, "startFinder threw", e)
            finderMessage = "АЛДАА: ${e.javaClass.simpleName}: ${e.message}"
            statusMessage = "АЛДАА: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun stopFinder() {
        val r = reader
        finderActive = false
        finderRssi = null
        finderPercent = 0
        finderMessage = "Зогссон."

        if (r != null && isScanning) {
            try { r.javaClass.getMethod("stopInventory").invoke(r) } catch (_: Throwable) {}
            isScanning = false
            statusMessage = "Зогссон. ${scannedEpcs.size} EPC уншсан."
        }
        clearHardwareFilter()
    }

    fun dismissFinder() {
        stopFinder()
        finderMessage = null
        finderMatchSet = null
        showFinder = false
    }

    // ── Hardware Gen2 Select filter ──────────────────────────────────────
    private fun reapplyFilter(bank: Int, ptr: Int, length: Int, mask: String) {
        val r = reader ?: return
        val wasScanning = isScanning
        if (wasScanning) {
            try { r.javaClass.getMethod("stopInventory").invoke(r) } catch (_: Throwable) {}
            isScanning = false
        }
        try {
            val method = r.javaClass.getMethod(
                "setFilter",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            val ok = method.invoke(r, bank, ptr, length, mask) as Boolean
            Log.i(TAG, "setFilter(bank=$bank, ptr=$ptr, len=$length, mask='$mask') -> $ok")
        } catch (e: Throwable) {
            Log.e(TAG, "setFilter failed", e)
        }
        if (wasScanning) {
            try {
                val ok = r.javaClass.getMethod("startInventoryTag").invoke(r) as Boolean
                isScanning = ok
            } catch (_: Throwable) {}
        }
    }

    fun applyHardwareFilter(maskHex: String) {
        val clean = maskHex.trim().uppercase().replace(Regex("[^0-9A-F]"), "")
        if (clean.isEmpty()) {
            clearHardwareFilter()
            return
        }
        val bitLength = clean.length * 4
        val paddedMask = if (clean.length % 2 == 0) clean else (clean + "0")
        reapplyFilter(1, 32, bitLength, paddedMask)
    }

    fun clearHardwareFilter() {
        reapplyFilter(1, 0, 0, "")
    }

    private fun applyPower(dBm: Int) {
        val r = reader ?: return
        try {
            val ok = r.javaClass.getMethod("setPower", Int::class.javaPrimitiveType).invoke(r, dBm.coerceIn(1, 30)) as Boolean
            Log.i(TAG, "setPower($dBm) -> $ok")
        } catch (e: Throwable) {
            Log.e(TAG, "setPower failed", e)
        }
    }

    // ── Teardown — runs only on real finish, NOT on rotation ─────────────
    override fun onCleared() {
        try {
            drainTimer?.cancel()
            toneGen?.release()
            reader?.javaClass?.getMethod("stopInventory")?.invoke(reader)
            reader?.javaClass?.getMethod("free")?.invoke(reader)
        } catch (_: Throwable) {}
        super.onCleared()
    }

    companion object {
        private const val TAG = "EpcApp"
        private const val PREFS_NAME = "epc_app_prefs"
        private const val SESSION_FILE = "epc_session.txt"
    }
}
