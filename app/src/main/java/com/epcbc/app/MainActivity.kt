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
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.epcbc.app.ui.FinderOverlay
import com.epcbc.app.ui.LoginScreen
import com.epcbc.app.ui.HomeScreen
import com.epcbc.app.ui.HomeTile
import com.epcbc.app.ui.ProfileScreen
import com.epcbc.app.ui.ReceivingOverlay
import com.epcbc.app.ui.ScanScreen
import com.epcbc.app.ui.SearchScreen
import com.epcbc.app.ui.StRow
import com.epcbc.app.ui.StocktakeOverlay
import com.epcbc.app.ui.TxCartRow
import com.epcbc.app.ui.TxDraftOverlay
import com.epcbc.app.ui.TxJobRow
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

        // Шинэ уншилт бүрийг тооллогын + даалгаврын амьд тулгагч руу дамжуулна
        // (идэвхтэй ажилгүй үед hook-ууд дотроо шууд буцдаг — зардал 0).
        viewModel.onNewEpcs = {
            syncViewModel.onStocktakeScans(it)
            syncViewModel.onTxDraftScans(it)
        }

        // Уншигчийг АВТОМАТААР эхлүүлнэ (нүүрэнд товч байхгүй — 2026-08-02
        // дизайн): эхний frame зурагдсаны дараа, бэлэн биш үед л. Бүтэхгүй
        // бол нүүрний төлвийн мөр дээр дарж дахин оролдоно.
        window.decorView.post {
            if (!viewModel.readerReady) viewModel.initReader()
        }

        setContent {
            // Дэлгэцийн горим Профайлаас сонгогдоно (system/dark/light).
            val darkTheme = when (viewModel.themeMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            EpcBarcodeappTheme(darkTheme = darkTheme) {
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

                // Нүүр grid ↔ дэд дэлгэцүүд (2026-08-02 дизайн). Хүлээн авалт/
                // Тооллого overlay хэвээр (аль ч дэлгэцийн дээр нээгддэг).
                var screen by rememberSaveable { mutableStateOf("home") }
                // Тооллогын дутуу хайж буй бараа (Олох горимын нэр/үлдсэн тоонд).
                var finderPid by rememberSaveable { mutableStateOf<String?>(null) }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(Modifier.padding(innerPadding)) {
                    when (screen) {
                    "home" -> HomeScreen(
                        loggedIn = loggedIn,
                        readerReady = viewModel.readerReady,
                        readerStatus = viewModel.statusMessage,
                        outputPower = viewModel.outputPower,
                        tiles = listOf(
                            HomeTile("stocktake", "📋", "Тооллого",
                                subtitle = if (loggedIn) null else "нэвтрэх шаардлагатай", enabled = loggedIn),
                            HomeTile("receiving", "📦", "Хүлээн авалт",
                                subtitle = if (loggedIn) null else "нэвтрэх шаардлагатай", enabled = loggedIn),
                            HomeTile("search", "🔍", "Хайлт"),
                            HomeTile("check", "✅", "Шалгалт", subtitle = "файлтай тулгах"),
                            HomeTile("transfer", "🔄", "Шилжүүлэг",
                                subtitle = if (loggedIn) null else "нэвтрэх шаардлагатай", enabled = loggedIn),
                            HomeTile("writeoff", "🗑", "Актлалт",
                                subtitle = if (loggedIn) null else "нэвтрэх шаардлагатай", enabled = loggedIn),
                            HomeTile("sale", "🛒", "Борлуулалт",
                                subtitle = if (loggedIn) null else "нэвтрэх шаардлагатай", enabled = loggedIn),
                            HomeTile("saleReturn", "↩️", "Буцаалт",
                                subtitle = if (loggedIn) null else "нэвтрэх шаардлагатай", enabled = loggedIn),
                            HomeTile("settings", "⚙️", "Тохиргоо"),
                        ),
                        onRetryReader = { viewModel.initReader() },
                        onOpenProfile = { screen = "profile" },
                        onOpen = { key ->
                            when (key) {
                                "stocktake" -> {
                                    syncViewModel.showStocktake = true
                                    if (syncViewModel.stocktakes.isEmpty()) syncViewModel.refreshStocktakes()
                                }
                                "receiving" -> {
                                    syncViewModel.showReceiving = true
                                    if (syncViewModel.receipts.isEmpty()) syncViewModel.refreshReceipts()
                                }
                                "transfer" -> syncViewModel.openTxDraft("transfer")
                                "writeoff" -> syncViewModel.openTxDraft("other")
                                "sale" -> syncViewModel.openTxDraft("sale")
                                "saleReturn" -> syncViewModel.openTxDraft("return")
                                "search" -> screen = "search"
                                "check" -> screen = "check"
                                "settings" -> viewModel.showSettings = true
                            }
                        },
                    )
                    "search" -> SearchScreen(
                        loggedIn = loggedIn,
                        onBack = { screen = "home" },
                        onFind = { prefix ->
                            viewModel.finderTargetEpc = prefix
                            viewModel.showFinder = true
                        },
                    )
                    "profile" -> ProfileScreen(
                        email = syncViewModel.userEmail,
                        loggedIn = loggedIn,
                        isDark = darkTheme,
                        passwordBusy = syncViewModel.passwordBusy,
                        passwordMessage = syncViewModel.passwordMessage,
                        onToggleDark = { viewModel.setTheme(if (it) "dark" else "light") },
                        onChangePassword = { old, new -> syncViewModel.changePassword(old, new) },
                        onLogout = {
                            syncViewModel.logout()
                            screen = "home"
                        },
                        onLogin = {
                            syncViewModel.skipLogin = false
                            screen = "home"
                        },
                        onBack = {
                            syncViewModel.clearPasswordMessage()
                            screen = "home"
                        },
                    )
                    else -> Column {
                    BackHandler(onBack = { screen = "home" })
                    TextButton(onClick = { screen = "home" }) { Text("← Нүүр") }
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
                            // Сонгоход серверийн өмнөх байдал татагдаж, буфер шинэ
                            // ажилдаа цэвэрлэгдэнэ (өмнөх ажлын уншилт хутгалдахгүй).
                            syncViewModel.selectStocktake(st) { serverSeen ->
                                viewModel.resetForJob(serverSeen)
                            }
                        },
                        onRefreshProgress = { syncViewModel.refreshStocktakeProgress() },
                        onSubmit = {
                            syncViewModel.submitStocktakeScans(viewModel.scannedEpcs.toList()) {
                                viewModel.clearAfterSubmit()
                            }
                        },
                        onFindMissing = { pid ->
                            // Уншигчийн төмөр шүүлт = барааны бүтцийн угтвар;
                            // программын шүүлт = яг дутуу hex-үүд. Олдсон нь
                            // ердийн урсгалаараа тоологдоно (onNewEpcs).
                            val missing = syncViewModel.stMissingHexes(pid)
                            if (missing.isNotEmpty()) {
                                finderPid = pid
                                viewModel.finderMatchSet = missing.toSet()
                                viewModel.finderTargetEpc =
                                    com.epcbc.core.EpcDecoder.productPrefix(missing.first())
                                viewModel.showFinder = true
                            }
                        },
                        onDismiss = {
                            syncViewModel.showStocktake = false
                            syncViewModel.clearSyncMessages()
                        },
                    )
                }

                // Шилжүүлэг/Актлалт — ноорог-сагс, in-tree overlay (PTT товч ажиллана).
                // Мөр = бараа (нэр × ширхэг); таг бүр серверт хадгалагддаг тул апп
                // унтарсан ч сагс алдагдахгүй — ноорогоо дахин сонгоод үргэлжлүүлнэ.
                if (syncViewModel.showTxDraft && loggedIn) {
                    val txRows = syncViewModel.txCartByProduct.map { (pid, items) ->
                        val meta = syncViewModel.txProductMeta[pid]
                        TxCartRow(
                            productId = pid,
                            name = meta?.name ?: meta?.sku ?: meta?.gtin ?: pid.take(8),
                            sku = meta?.sku,
                            count = items.size,
                        )
                    }.sortedBy { it.name }
                    // Даалгаврын мөрүүд: уншсан = амьд локал тоолол (сервер + шинэ
                    // уншилт); дуусаагүй нь эхэндээ.
                    val jobRows = syncViewModel.txLines.map { (pid, expected) ->
                        val meta = syncViewModel.txLineMeta[pid]
                        TxJobRow(
                            productId = pid,
                            name = meta?.name ?: meta?.sku ?: meta?.gtin ?: pid.take(8),
                            sku = meta?.sku,
                            expected = expected,
                            picked = syncViewModel.txPickedLocal[pid] ?: 0,
                        )
                    }.sortedWith(compareBy({ it.picked >= it.expected }, { it.name }))
                    TxDraftOverlay(
                        type = syncViewModel.txType,
                        branches = syncViewModel.txBranches,
                        drafts = syncViewModel.txDrafts,
                        draftsLoading = syncViewModel.txDraftsLoading,
                        active = syncViewModel.activeDraft,
                        itemsLoading = syncViewModel.txItemsLoading,
                        rows = txRows,
                        jobRows = jobRows,
                        extraLocal = syncViewModel.txExtraLocal,
                        cartCount = syncViewModel.txCartCount,
                        pendingCount = viewModel.scannedEpcs.size,
                        submitBusy = syncViewModel.submitBusy,
                        message = syncViewModel.syncMessage,
                        error = syncViewModel.syncError,
                        branchName = { syncViewModel.txBranchName(it) },
                        onRefreshList = { syncViewModel.refreshTxDrafts() },
                        onCreate = { toBr, note ->
                            // Шинэ сагс = шинэ ажил: буфер цэвэрлэгдэнэ (өмнөх
                            // ажлын уншилт хутгалдахгүй).
                            syncViewModel.createTxDraft(toBr, note) { cartHexes ->
                                viewModel.resetForJob(cartHexes)
                            }
                        },
                        onSelect = { d ->
                            if (d == null) syncViewModel.selectTxDraft(null)
                            else syncViewModel.selectTxDraft(d) { cartHexes ->
                                viewModel.resetForJob(cartHexes)
                            }
                        },
                        onChangeDest = { syncViewModel.updateTxDest(it) },
                        onScanToCart = {
                            syncViewModel.txScanBuffer(viewModel.scannedEpcs.toList()) {
                                viewModel.clearAfterSubmit()
                            }
                        },
                        onRemoveProduct = { syncViewModel.txRemoveProduct(it) },
                        onSubmit = { syncViewModel.submitTxDraft() },
                        onCancelDraft = { syncViewModel.cancelTxDraft() },
                        onDismiss = {
                            syncViewModel.showTxDraft = false
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
                    // Тооллогын дутуу горимд: нэр + үлдсэн тоо (амьд — олдох
                    // бүрд тоолол өсч энэ тоо буурна). Target-ыг гараар засвал
                    // энгийн угтварын горимд буцна.
                    val finderRemaining = finderPid?.takeIf { viewModel.finderMatchSet != null }?.let { pid ->
                        val exp = syncViewModel.stExpectedByProduct[pid] ?: 0
                        val found = maxOf(
                            syncViewModel.stFoundLocal[pid] ?: 0,
                            syncViewModel.stFoundServer[pid] ?: 0,
                        )
                        (exp - found).coerceAtLeast(0)
                    }
                    val finderLabel = finderPid?.takeIf { viewModel.finderMatchSet != null }?.let { pid ->
                        syncViewModel.stProductMeta[pid]?.let { m -> m.name ?: m.sku }
                    }
                    FinderOverlay(
                        targetEpc = viewModel.finderTargetEpc,
                        onTargetChange = {
                            viewModel.finderTargetEpc = it
                            viewModel.finderMatchSet = null
                            finderPid = null
                        },
                        label = finderLabel,
                        remaining = finderRemaining,
                        active = viewModel.finderActive,
                        percent = viewModel.finderPercent,
                        rssi = viewModel.finderRssi,
                        lastSeenMs = viewModel.finderLastSeenMs,
                        message = viewModel.finderMessage,
                        onStart = { viewModel.startFinder() },
                        onStop = { viewModel.stopFinder() },
                        onDismiss = {
                            viewModel.dismissFinder()
                            finderPid = null
                        }
                    )
                }

                if (viewModel.showSettings) {
                    SettingsDialog(
                        currentPower = viewModel.outputPower,
                        currentMode = viewModel.continuousMode,
                        currentSound = viewModel.soundEnabled,
                        onApply = { p, c, s -> viewModel.applySettings(p, c, s) },
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
