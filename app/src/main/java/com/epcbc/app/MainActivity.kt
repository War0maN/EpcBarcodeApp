package com.epcbc.app

import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.epcbc.app.ui.FinderOverlay
import com.epcbc.app.ui.ScanScreen
import com.epcbc.app.ui.SettingsDialog
import com.epcbc.app.ui.SkuDetailOverlay
import com.epcbc.app.ui.theme.EpcBarcodeappTheme
import com.epcbc.core.EpcDecoder
import com.epcbc.core.MatchEngine
import com.epcbc.data.PackingListReader
import com.epcbc.scan.EpcStream
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private var reader: Any? = null  // Loaded lazily; typed loosely so init failures don't kill onCreate
    private var readerReady by mutableStateOf(false)
    private val scannedEpcs = mutableStateListOf<String>()
    private var isScanning by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Эхлэхийн тулд 'УНШИГЧ ЭХЛҮҮЛЭХ' товчийг дар")
    private val packingList = mutableStateListOf<PackingListReader.PackingItem>()
    private var packingListName by mutableStateOf<String?>(null)
    private var packingListStatus by mutableStateOf<String?>(null)

    // High-performance EPC buffer: SDK callback enqueues here (cheap),
    // a periodic timer drains it into the UI list at ~10 fps so the UI never falls behind.
    private val epcStream = EpcStream()
    private val seenEpcs: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    private var drainTimer: Timer? = null

    // Reflection method handles resolved once on the SDK callback thread and reused.
    // getEPC fires for every tag (150+/sec) — re-resolving it each time is pure waste.
    // @Volatile so the resolved handle publishes safely to any thread that calls in.
    @Volatile private var getEpcMethod: java.lang.reflect.Method? = null
    @Volatile private var getRssiMethod: java.lang.reflect.Method? = null

    // Scan settings
    private var outputPower by mutableStateOf(30)         // dBm, 1..30
    private var continuousMode by mutableStateOf(true)    // false = single tag scan per trigger
    private var prefixLength by mutableStateOf(20)        // hex chars used to group "same product"
    private var showSettings by mutableStateOf(false)
    private var soundEnabled by mutableStateOf(true)      // beep on every new EPC
    // The packing-list row the user tapped — rendered as a Box overlay at top level (NOT a Dialog),
    // so hardware trigger key events still reach the Activity's dispatchKeyEvent.
    private var selectedItem by mutableStateOf<PackingListReader.PackingItem?>(null)

    // Tag-finder ("Geiger" mode) state.
    private var showFinder by mutableStateOf(false)
    private var finderTargetEpc by mutableStateOf("")
    private var finderActive by mutableStateOf(false)
    private var finderRssi by mutableStateOf<Int?>(null)
    private var finderPercent by mutableStateOf(0)
    private var finderLastSeenMs by mutableStateOf(0L)
    private var finderMessage by mutableStateOf<String?>(null)

    // Sound feedback — a short pip whenever a new EPC arrives.
    private val toneGen: ToneGenerator? by lazy {
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 90) } catch (_: Throwable) { null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.i(TAG, "MainActivity.onCreate")

        // Load persisted settings + the previous scan session before anything reads them.
        loadSettings()
        restoreSession()

        setContent {
            EpcBarcodeappTheme {
                val pickFileLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? -> if (uri != null) loadPackingList(uri) }

                val saveFileLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("text/csv")
                ) { uri: Uri? -> if (uri != null) exportResultsToCsv(uri) }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ScanScreen(
                        modifier = Modifier.padding(innerPadding),
                        scannedEpcs = scannedEpcs,
                        readerReady = readerReady,
                        isScanning = isScanning,
                        status = statusMessage,
                        packingList = packingList,
                        packingListName = packingListName,
                        packingListStatus = packingListStatus,
                        onInitReader = { initReader() },
                        onToggle = { if (isScanning) stopScan() else startScan() },
                        onClear = {
                            scannedEpcs.clear()
                            seenEpcs.clear()
                            epcStream.drain()
                            saveSession()   // persist the cleared (empty) state
                        },
                        onOpenSettings = { showSettings = true },
                        outputPower = outputPower,
                        continuousMode = continuousMode,
                        prefixLength = prefixLength,
                        onImportPackingList = {
                            // XLSX MIME types vary; accept any document
                            pickFileLauncher.launch(arrayOf(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-excel",
                                "application/vnd.ms-excel.sheet.macroenabled.12",
                                "application/octet-stream",
                                "*/*"
                            ))
                        },
                        onClearPackingList = {
                            packingList.clear()
                            packingListName = null
                            packingListStatus = null
                        },
                        onExport = {
                            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                            saveFileLauncher.launch("epc_export_$ts.csv")
                        },
                        onOpenFinder = { showFinder = true },
                        onSelectItem = { selectedItem = it }
                    )
                }

                // SKU detail rendered as an in-tree overlay (NOT a Dialog window) so the
                // hardware trigger still reaches the Activity while it's open.
                selectedItem?.let { item ->
                    SkuDetailOverlay(
                        item = item,
                        allScannedEpcs = scannedEpcs,
                        prefixLength = prefixLength,
                        isScanning = isScanning,
                        onToggleScan = { if (isScanning) stopScan() else startScan() },
                        onFilterChange = { applyHardwareFilter(it) },
                        onDismiss = {
                            clearHardwareFilter()
                            selectedItem = null
                        }
                    )
                }

                // Tag-finder overlay — same in-tree Box pattern so the trigger still routes here.
                if (showFinder) {
                    FinderOverlay(
                        targetEpc = finderTargetEpc,
                        onTargetChange = { finderTargetEpc = it },
                        active = finderActive,
                        percent = finderPercent,
                        rssi = finderRssi,
                        lastSeenMs = finderLastSeenMs,
                        message = finderMessage,
                        onStart = { startFinder() },
                        onStop = { stopFinder() },
                        onDismiss = {
                            stopFinder()
                            finderMessage = null
                            showFinder = false
                        }
                    )
                }

                if (showSettings) {
                    SettingsDialog(
                        currentPower = outputPower,
                        currentMode = continuousMode,
                        currentPrefix = prefixLength,
                        currentSound = soundEnabled,
                        onApply = { p, c, l, s ->
                            outputPower = p
                            continuousMode = c
                            prefixLength = l
                            soundEnabled = s
                            applyPower(p)
                            saveSettings()       // persist across app restarts
                            showSettings = false
                        },
                        onDismiss = { showSettings = false }
                    )
                }
            }
        }
    }

    private fun exportResultsToCsv(uri: Uri) {
        thread(start = true, name = "ExportCsv") {
            try {
                // Group scanned EPCs by their converted barcode (shared with the live UI matcher).
                val readByBarcode = MatchEngine.groupByBarcode(scannedEpcs.toList())
                val packingByBarcode = packingList.associateBy { it.barcode }

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
                            // Only orphans need a re-decode (for the format column); they're typically few.
                            val format = try { EpcDecoder.decode(epc, true).format } catch (_: Throwable) { "UNKNOWN" }
                            orphanRows.add(OrphanRow(epc, barcode, format))
                        }
                    }
                }

                // Group same-product EPCs together: sort by SKU → barcode → EPC
                matchedRows.sortWith(compareBy(
                    { it.item.sku ?: "" },
                    { it.barcode },
                    { it.epc }
                ))
                orphanRows.sortWith(compareBy({ it.decodedBarcode }, { it.epc }))

                // Compute totals
                var totalExpected = 0
                var totalRead = 0
                var totalOver = 0
                for (item in packingList.toList()) {
                    val read = readByBarcode[item.barcode]?.size ?: 0
                    totalExpected += item.qty
                    totalRead += read.coerceAtMost(item.qty)
                    totalOver += (read - item.qty).coerceAtLeast(0)
                }
                val totalMissing = (totalExpected - totalRead).coerceAtLeast(0)

                contentResolver.openOutputStream(uri)?.use { out ->
                    val writer = out.bufferedWriter()
                    // BOM so Excel opens UTF-8 correctly with Mongolian text
                    writer.write("﻿")

                    // ─────────────────────────────────────────────────────────
                    // ХҮСНЭГТ 1: SKU тус бүрийн дүгнэлт
                    // ─────────────────────────────────────────────────────────
                    writer.write("=== ХҮСНЭГТ 1: SKU тус бүрийн дүгнэлт ===\n")
                    writer.write("SKU,Barcode,Item Name,Expected,Read,Status\n")
                    for (item in packingList.toList()) {
                        val read = readByBarcode[item.barcode]?.size ?: 0
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
                    // Totals row
                    val grandStatus = when {
                        totalRead == totalExpected && totalOver == 0 -> "БҮРЭН"
                        totalOver > 0 -> "Дутуу $totalMissing, Илүү $totalOver"
                        else -> "Дутуу $totalMissing"
                    }
                    writer.write("\"=== НИЙТ ===\",,,$totalExpected,$totalRead,\"$grandStatus\"\n")

                    // ─────────────────────────────────────────────────────────
                    // ХҮСНЭГТ 2: Packing list-тэй таарсан EPC (SKU-аар групплүүлсэн)
                    // ─────────────────────────────────────────────────────────
                    writer.write("\n=== ХҮСНЭГТ 2: Packing list-тэй таарсан EPC (SKU-аар групплүүлсэн) ===\n")
                    writer.write("EPC,Decoded Barcode,SKU,Item Name\n")
                    for (r in matchedRows) {
                        val sku = (r.item.sku ?: "").replace("\"", "\"\"")
                        val name = (r.item.name ?: "").replace("\"", "\"\"")
                        writer.write("${r.epc},${r.barcode},\"$sku\",\"$name\"\n")
                    }
                    writer.write("\"=== НИЙТ: ${matchedRows.size} ширхэг ===\",,,\n")

                    // ─────────────────────────────────────────────────────────
                    // ХҮСНЭГТ 3: Орхигдсон EPC (packing list-нд байхгүй)
                    // ─────────────────────────────────────────────────────────
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
                runOnUiThread {
                    statusMessage = "Export амжилттай: ${packingList.size} SKU, $matchedCount таарсан, $orphanCount орхигдсон"
                }
            } catch (e: Throwable) {
                Log.e(TAG, "exportResultsToCsv failed", e)
                runOnUiThread {
                    statusMessage = "АЛДАА export: ${e.message}"
                }
            }
        }
    }

    private fun loadPackingList(uri: Uri) {
        packingListStatus = "Файл уншиж байна..."
        thread(start = true, name = "PackingListReader") {
            try {
                val result = contentResolver.openInputStream(uri)?.use { stream ->
                    PackingListReader.read(stream)
                } ?: throw Exception("InputStream null")

                runOnUiThread {
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
                runOnUiThread {
                    packingListStatus = "АЛДАА: ${e.message}"
                }
            }
        }
    }

    private fun initReader() {
        try {
            statusMessage = "Уншигчийг идэвхжүүлж байна..."
            Log.i(TAG, "Calling RFIDWithUHFUART.getInstance()")
            val cls = Class.forName("com.rscja.deviceapi.RFIDWithUHFUART")
            val getInstance = cls.getMethod("getInstance")
            val instance = getInstance.invoke(null)
            Log.i(TAG, "Got instance: $instance")

            val initMethod = cls.getMethod("init", android.content.Context::class.java)
            val ok = initMethod.invoke(instance, this) as Boolean
            Log.i(TAG, "init() returned: $ok")

            if (ok) {
                reader = instance
                readerReady = true
                statusMessage = "Бэлэн. ЭХЛҮҮЛЭХ дар эсвэл хажуугийн PTT товчийг ашигла."
                // Wire up callback via reflection. Callback runs on SDK's thread — DO NOT touch UI here.
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

                                    // Tag-finder: if active and this is our target, update RSSI on UI.
                                    if (finderActive && finderTargetEpc.isNotBlank() &&
                                        epc.uppercase().startsWith(finderTargetEpc.uppercase())) {
                                        val rssi = extractRssi(tagInfo)
                                        if (rssi != null) {
                                            val now = System.currentTimeMillis()
                                            runOnUiThread {
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

                    // Try to apply default output power
                    applyPower(outputPower)

                    // Start a periodic drainer (10 fps) — keeps UI responsive even with 150+ tags/sec
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

    private fun startScan() {
        if (!readerReady || reader == null) {
            statusMessage = "Эхлээд УНШИГЧ ЭХЛҮҮЛЭХ дар"
            return
        }
        if (isScanning) return  // already running — don't start twice
        try {
            if (continuousMode) {
                val ok = reader!!.javaClass.getMethod("startInventoryTag").invoke(reader) as Boolean
                Log.i(TAG, "startInventoryTag -> $ok")
                isScanning = ok
                statusMessage = if (ok) "Уншиж байна... (${outputPower} dBm)" else "Эхлэх боломжгүй (returned false)"
            } else {
                // Single tag mode: briefly show "scanning" state so UI flashes "ЗОГСООХ".
                isScanning = true
                statusMessage = "Уншиж байна..."
                thread(start = true, name = "SingleScan") {
                    try {
                        val tagInfo = reader!!.javaClass.getMethod("inventorySingleTag").invoke(reader)
                        val epc = tagInfo?.javaClass?.getMethod("getEPC")?.invoke(tagInfo) as? String
                        runOnUiThread {
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
                        runOnUiThread {
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

    private fun startDrainTimer() {
        drainTimer?.cancel()
        drainTimer = Timer("EpcDrain", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    val batch = epcStream.drain()
                    if (batch.isEmpty()) return
                    val newOnes = ArrayList<String>(batch.size)
                    for (epc in batch) {
                        if (seenEpcs.add(epc)) newOnes.add(epc)
                    }
                    if (newOnes.isNotEmpty()) {
                        // One beep per 100ms batch (not per tag) — keeps audio responsive
                        // without becoming a constant whine when 100+ tags/sec arrive.
                        if (soundEnabled) {
                            try { toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 30) } catch (_: Throwable) {}
                        }
                        runOnUiThread {
                            scannedEpcs.addAll(0, newOnes)
                        }
                    }

                    // Tag-finder signal decay: if no RSSI update for >800ms, fade the percent
                    // toward zero so the user gets immediate feedback when the tag goes out of range.
                    if (finderActive && finderLastSeenMs > 0) {
                        val sinceMs = System.currentTimeMillis() - finderLastSeenMs
                        if (sinceMs > 800) {
                            runOnUiThread {
                                finderPercent = (finderPercent - 5).coerceAtLeast(0)
                                if (finderPercent == 0) finderRssi = null
                            }
                        }
                    }
                }
            }, 100, 100)
        }
    }

    /** The scan-session backing file: one EPC per line, newest first (display order). */
    private fun sessionFile() = java.io.File(filesDir, SESSION_FILE)

    /** Persist the current scanned-EPC list so it survives an app restart/crash. */
    private fun saveSession() {
        val snapshot = scannedEpcs.toList()
        thread(start = true, name = "SaveSession") {
            try {
                sessionFile().writeText(snapshot.joinToString("\n"))
            } catch (e: Throwable) {
                Log.e(TAG, "saveSession failed", e)
            }
        }
    }

    /** Restore the scanned-EPC list saved by a previous session, if any. */
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

    private fun loadSettings() {
        val p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        outputPower    = p.getInt("power", 30)
        continuousMode = p.getBoolean("continuous", true)
        prefixLength   = p.getInt("prefix_length", 20)
        soundEnabled   = p.getBoolean("sound", true)
        Log.i(TAG, "loadSettings: power=$outputPower mode=${if (continuousMode) "auto" else "single"} prefix=$prefixLength sound=$soundEnabled")
    }

    private fun saveSettings() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putInt("power", outputPower)
            .putBoolean("continuous", continuousMode)
            .putInt("prefix_length", prefixLength)
            .putBoolean("sound", soundEnabled)
            .apply()
    }

    /** Try common method names on the Chainway UHFTAGInfo to extract RSSI as an Int (dBm). */
    private fun extractRssi(tagInfo: Any): Int? {
        // Fast path: reuse the method that worked last time (resolved once, then cached).
        getRssiMethod?.let { m ->
            return parseRssi(try { m.invoke(tagInfo) } catch (_: Throwable) { null })
        }
        val candidates = listOf("getRssi", "getRSSI", "getRssiValue", "getRssiDbm")
        for (name in candidates) {
            try {
                val method = tagInfo.javaClass.getMethod(name)
                val parsed = parseRssi(method.invoke(tagInfo))
                if (parsed != null) {
                    getRssiMethod = method   // remember the winner for next time
                    return parsed
                }
            } catch (_: Throwable) { /* try next */ }
        }
        return null
    }

    /** Coerce a reflected RSSI value (Int/Float/Double/String like "-52dBm") to Int dBm. */
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

    /** Linear RSSI to 0..100 percent. Tunable range: -80 dBm (far) → -30 dBm (very close). */
    private fun rssiToPercent(rssi: Int): Int {
        val lo = -80
        val hi = -30
        return (((rssi - lo).toFloat() / (hi - lo)) * 100).toInt().coerceIn(0, 100)
    }

    private fun startFinder() {
        val target = finderTargetEpc.trim().uppercase()
        Log.i(TAG, "startFinder: target='$target' reader=$reader readerReady=$readerReady isScanning=$isScanning")
        if (target.isBlank()) {
            Log.w(TAG, "startFinder: target empty, ignoring")
            return
        }

        // Auto-initialize the reader if the user opened Finder before tapping УНШИГЧ ЭХЛҮҮЛЭХ.
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
            // applyHardwareFilter stops the current scan (if any), sets the filter, and
            // restarts the scan automatically. So afterwards, scanning is either still
            // running (if it was before) or stopped (if it wasn't).
            applyHardwareFilter(target)

            // Make sure scan is running so RSSI flows in.
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

    private fun stopFinder() {
        val r = reader
        finderActive = false
        finderRssi = null
        finderPercent = 0
        finderMessage = "Зогссон."

        // CRITICAL: stop the scan BEFORE clearing the filter. Otherwise reapplyFilter
        // sees isScanning=true and helpfully restarts inventory after the clear,
        // which floods the main screen with un-filtered EPCs (looking like the user
        // pressed ЭХЛҮҮЛЭХ behind the finder window).
        if (r != null && isScanning) {
            try { r.javaClass.getMethod("stopInventory").invoke(r) } catch (_: Throwable) {}
            isScanning = false
            statusMessage = "Зогссон. ${scannedEpcs.size} EPC уншсан."
        }
        // Now clear the filter — it will NOT restart the scan because isScanning is false.
        clearHardwareFilter()
    }

    /**
     * Helper: reconfigure the Gen2 Select filter. The reader requires the inventory to be
     * stopped while the filter is changed, otherwise the new filter only takes effect
     * sporadically (or not at all). So we stop, set, then restart if we were scanning.
     */
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

    /**
     * Apply a Gen2 Select hardware filter so the antenna only "hears" tags whose EPC begins
     * with the given hex pattern.
     */
    private fun applyHardwareFilter(maskHex: String) {
        val clean = maskHex.trim().uppercase().replace(Regex("[^0-9A-F]"), "")
        if (clean.isEmpty()) {
            clearHardwareFilter()
            return
        }
        val bitLength = clean.length * 4
        val paddedMask = if (clean.length % 2 == 0) clean else (clean + "0")
        reapplyFilter(1, 32, bitLength, paddedMask)
    }

    /** Disable the Gen2 Select filter so the reader resumes seeing all tags. */
    private fun clearHardwareFilter() {
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

    private fun stopScan() {
        try {
            val ok = reader?.javaClass?.getMethod("stopInventory")?.invoke(reader) as? Boolean ?: false
            Log.i(TAG, "stopInventory -> $ok")
            isScanning = false
            statusMessage = "Зогссон. ${scannedEpcs.size} EPC уншсан."
            saveSession()   // persist what we just read
        } catch (e: Throwable) {
            Log.e(TAG, "stopScan failed", e)
        }
    }

    override fun onPause() {
        // Covers home button, app-switch, and most process-kill paths — persist before we lose focus.
        saveSession()
        super.onPause()
    }

    /**
     * Catch the C5 PTT trigger BEFORE any dialog/TextField can swallow it.
     * Without this, if the SKU-detail dialog has the text field focused, the trigger key
     * goes to the input and the reader never sees it.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (isTriggerKey(code)) {
            return when (event.action) {
                KeyEvent.ACTION_DOWN -> onKeyDown(code, event)
                KeyEvent.ACTION_UP -> onKeyUp(code, event)
                else -> super.dispatchKeyEvent(event)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isTriggerKey(keyCode)) return super.onKeyDown(keyCode, event)
        // Filter out auto-repeat events from a held-down key — we want one press = one action.
        if ((event?.repeatCount ?: 0) != 0) return true
        if (!readerReady) return true

        if (continuousMode) {
            // Auto mode: toggle. Press once → start; press again → stop.
            if (isScanning) stopScan() else startScan()
        } else {
            // Single mode: one tag per press, regardless of how long the trigger is held.
            startScan()
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // We don't act on key-up anymore; both modes are press-driven only.
        if (isTriggerKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun isTriggerKey(code: Int): Boolean = code in setOf(
        139, 280, 281, 282, 283, 293, 294,
        KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2,
        KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4
    )

    override fun onDestroy() {
        try {
            drainTimer?.cancel()
            toneGen?.release()
            reader?.javaClass?.getMethod("stopInventory")?.invoke(reader)
            reader?.javaClass?.getMethod("free")?.invoke(reader)
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EpcApp"
        private const val PREFS_NAME = "epc_app_prefs"
        private const val SESSION_FILE = "epc_session.txt"
    }
}
