package com.epcbc.app

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.epcbc.app.ui.FinderOverlay
import com.epcbc.app.ui.LoginScreen
import com.epcbc.app.ui.ReceivingOverlay
import com.epcbc.app.ui.ScanScreen
import com.epcbc.app.ui.ServerBar
import com.epcbc.app.ui.StRow
import com.epcbc.app.ui.StocktakeOverlay
import com.epcbc.app.ui.SettingsDialog
import com.epcbc.app.ui.SkuDetailOverlay
import com.epcbc.app.ui.theme.EpcBarcodeappTheme
import com.epcbc.net.Supa
import io.github.jan.supabase.auth.status.SessionStatus

/**
 * Thin UI host. All scan state and SDK/business logic lives in [ScanViewModel], which survives
 * configuration changes — so rotating the screen no longer rebuilds everything or frees the
 * hardware reader. The Activity keeps only what genuinely belongs to it: the Compose tree,
 * the file-picker / file-saver launchers, and hardware-trigger key dispatch.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ScanViewModel by viewModels()
    private val syncViewModel: SyncViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.i(TAG, "MainActivity.onCreate")

        // Шинэ уншилт бүрийг тооллогын амьд тулгагч руу дамжуулна (идэвхтэй
        // тооллого сонгоогүй үед hook дотроо шууд буцдаг — зардал 0).
        viewModel.onNewEpcs = { syncViewModel.onStocktakeScans(it) }

        setContent {
            EpcBarcodeappTheme {
                val pickFileLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? -> if (uri != null) viewModel.loadPackingList(uri) }

                val saveFileLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("text/csv")
                ) { uri: Uri? -> if (uri != null) viewModel.exportResultsToCsv(uri) }

                // Supabase session: нэвтрээгүй (мөн офлайн-г сонгоогүй) бол Login дэлгэц.
                val sessionStatus by syncViewModel.sessionStatus.collectAsState()
                val loggedIn = Supa.isConfigured && sessionStatus is SessionStatus.Authenticated
                val needLogin = Supa.isConfigured &&
                    !loggedIn &&
                    sessionStatus !is SessionStatus.Initializing &&
                    !syncViewModel.skipLogin

                if (needLogin) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        Column(Modifier.padding(innerPadding)) {
                            LoginScreen(
                                busy = syncViewModel.authBusy,
                                error = syncViewModel.authError,
                                notConfigured = !Supa.isConfigured,
                                onLogin = { e, p -> syncViewModel.login(e, p) },
                                modifier = Modifier.weight(1f),
                            )
                            androidx.compose.material3.TextButton(
                                onClick = { syncViewModel.skipLogin = true },
                                modifier = Modifier.padding(16.dp)
                            ) { androidx.compose.material3.Text("Офлайн горимоор үргэлжлүүлэх →") }
                        }
                    }
                    return@EpcBarcodeappTheme
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(Modifier.padding(innerPadding)) {
                    ServerBar(
                        loggedIn = loggedIn,
                        email = syncViewModel.userEmail,
                        activeJobNumber = syncViewModel.activeReceipt?.jobNumber,
                        onOpenReceiving = {
                            syncViewModel.showReceiving = true
                            if (syncViewModel.receipts.isEmpty()) syncViewModel.refreshReceipts()
                        },
                        onOpenStocktake = {
                            syncViewModel.showStocktake = true
                            if (syncViewModel.stocktakes.isEmpty()) syncViewModel.refreshStocktakes()
                        },
                        onLogin = { syncViewModel.skipLogin = false },
                        onLogout = { syncViewModel.logout() },
                    )
                    ScanScreen(
                        modifier = Modifier,
                        scannedEpcs = viewModel.scannedEpcs,
                        readerReady = viewModel.readerReady,
                        isScanning = viewModel.isScanning,
                        status = viewModel.statusMessage,
                        packingList = viewModel.packingList,
                        packingListName = viewModel.packingListName,
                        packingListStatus = viewModel.packingListStatus,
                        serverJobNumber = syncViewModel.activeReceipt?.jobNumber,
                        onInitReader = { viewModel.initReader() },
                        onToggle = { viewModel.toggleScan() },
                        onClear = { viewModel.clearScans() },
                        onOpenSettings = { viewModel.showSettings = true },
                        outputPower = viewModel.outputPower,
                        continuousMode = viewModel.continuousMode,
                        prefixLength = viewModel.prefixLength,
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
                        onClearPackingList = { viewModel.clearPackingList() },
                        onExport = {
                            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                            saveFileLauncher.launch("epc_export_$ts.csv")
                        },
                        onOpenFinder = { viewModel.showFinder = true },
                        onSelectItem = { viewModel.selectedItem = it }
                    )
                    }
                }

                // Хүлээн авалт — in-tree overlay (Dialog биш: PTT товч Activity-д хүрнэ).
                if (syncViewModel.showReceiving && loggedIn) {
                    ReceivingOverlay(
                        receipts = syncViewModel.receipts,
                        receiptsLoading = syncViewModel.receiptsLoading,
                        activeReceipt = syncViewModel.activeReceipt,
                        progress = syncViewModel.progress,
                        progressLoading = syncViewModel.progressLoading,
                        submitBusy = syncViewModel.submitBusy,
                        scannedCount = viewModel.scannedEpcs.size,
                        message = syncViewModel.syncMessage,
                        error = syncViewModel.syncError,
                        onRefreshReceipts = { syncViewModel.refreshReceipts() },
                        onSelect = { syncViewModel.selectReceipt(it) },
                        onRefreshProgress = { syncViewModel.refreshProgress() },
                        onSubmit = {
                            // Амжилттай илгээгдмэгц илгээх буфер 0 болно (fix: тоолуур
                            // хэвээр үлдэж "дахин илгээх үү?" гэсэн төөрөгдөл үүсгэдэг байсан).
                            syncViewModel.submitScans(viewModel.scannedEpcs.toList()) {
                                viewModel.clearAfterSubmit()
                            }
                        },
                        onDismiss = {
                            syncViewModel.showReceiving = false
                            syncViewModel.clearSyncMessages()
                        },
                    )
                }

                // Тооллого — in-tree overlay (Dialog биш: PTT товч Activity-д хүрнэ).
                // Мөр = бараа; found = max(локал амьд, серверийн явц) — нэг
                // төхөөрөмжид хоёулаа тэнцүү, олон төхөөрөмжид сервер илүү мэднэ.
                if (syncViewModel.showStocktake && loggedIn) {
                    val stRows = syncViewModel.stExpectedByProduct.map { (pid, exp) ->
                        val meta = syncViewModel.stProductMeta[pid]
                        StRow(
                            productId = pid,
                            name = meta?.name ?: meta?.sku ?: meta?.gtin ?: pid.take(8),
                            sku = meta?.sku,
                            expected = exp,
                            found = maxOf(
                                syncViewModel.stFoundLocal[pid] ?: 0,
                                syncViewModel.stFoundServer[pid] ?: 0,
                            ),
                        )
                    }.sortedWith(compareBy({ it.found >= it.expected }, { it.name }))
                    StocktakeOverlay(
                        stocktakes = syncViewModel.stocktakes,
                        stocktakesLoading = syncViewModel.stocktakesLoading,
                        active = syncViewModel.activeStocktake,
                        expectedLoading = syncViewModel.stExpectedLoading,
                        rows = stRows,
                        extraLocal = syncViewModel.stExtraLocal,
                        pendingCount = viewModel.scannedEpcs.size,
                        submitBusy = syncViewModel.submitBusy,
                        progressLoading = syncViewModel.progressLoading,
                        message = syncViewModel.syncMessage,
                        error = syncViewModel.syncError,
                        onRefreshList = { syncViewModel.refreshStocktakes() },
                        onSelect = { st ->
                            syncViewModel.selectStocktake(st, viewModel.scannedEpcs.toList())
                        },
                        onRefreshProgress = { syncViewModel.refreshStocktakeProgress() },
                        onSubmit = {
                            syncViewModel.submitStocktakeScans(viewModel.scannedEpcs.toList()) {
                                viewModel.clearAfterSubmit()
                            }
                        },
                        onDismiss = {
                            syncViewModel.showStocktake = false
                            syncViewModel.clearSyncMessages()
                        },
                    )
                }

                // SKU detail rendered as an in-tree overlay (NOT a Dialog window) so the
                // hardware trigger still reaches the Activity while it's open.
                viewModel.selectedItem?.let { item ->
                    SkuDetailOverlay(
                        item = item,
                        allScannedEpcs = viewModel.scannedEpcs,
                        prefixLength = viewModel.prefixLength,
                        isScanning = viewModel.isScanning,
                        onToggleScan = { viewModel.toggleScan() },
                        onFilterChange = { viewModel.applyHardwareFilter(it) },
                        onDismiss = {
                            viewModel.clearHardwareFilter()
                            viewModel.selectedItem = null
                        }
                    )
                }

                // Tag-finder overlay — same in-tree Box pattern so the trigger still routes here.
                if (viewModel.showFinder) {
                    FinderOverlay(
                        targetEpc = viewModel.finderTargetEpc,
                        onTargetChange = { viewModel.finderTargetEpc = it },
                        active = viewModel.finderActive,
                        percent = viewModel.finderPercent,
                        rssi = viewModel.finderRssi,
                        lastSeenMs = viewModel.finderLastSeenMs,
                        message = viewModel.finderMessage,
                        onStart = { viewModel.startFinder() },
                        onStop = { viewModel.stopFinder() },
                        onDismiss = { viewModel.dismissFinder() }
                    )
                }

                if (viewModel.showSettings) {
                    SettingsDialog(
                        currentPower = viewModel.outputPower,
                        currentMode = viewModel.continuousMode,
                        currentPrefix = viewModel.prefixLength,
                        currentSound = viewModel.soundEnabled,
                        onApply = { p, c, l, s -> viewModel.applySettings(p, c, l, s) },
                        onDismiss = { viewModel.showSettings = false }
                    )
                }
            }
        }
    }

    override fun onPause() {
        // Covers home button, app-switch, and most process-kill paths — persist before we lose focus.
        viewModel.saveSession()
        super.onPause()
    }

    /**
     * Catch the C5 PTT trigger BEFORE any dialog/TextField can swallow it.
     * Without this, if the SKU-detail overlay has the text field focused, the trigger key
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
        // Filter out auto-repeat events from a held-down key — one press = one action.
        if ((event?.repeatCount ?: 0) != 0) return true
        viewModel.onTriggerDown()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // We don't act on key-up; both scan modes are press-driven only.
        if (isTriggerKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun isTriggerKey(code: Int): Boolean = code in setOf(
        139, 280, 281, 282, 283, 293, 294,
        KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2,
        KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4
    )

    companion object {
        private const val TAG = "EpcApp"
    }
}
