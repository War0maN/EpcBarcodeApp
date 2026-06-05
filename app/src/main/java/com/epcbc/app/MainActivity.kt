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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.epcbc.app.ui.FinderOverlay
import com.epcbc.app.ui.ScanScreen
import com.epcbc.app.ui.SettingsDialog
import com.epcbc.app.ui.SkuDetailOverlay
import com.epcbc.app.ui.theme.EpcBarcodeappTheme

/**
 * Thin UI host. All scan state and SDK/business logic lives in [ScanViewModel], which survives
 * configuration changes — so rotating the screen no longer rebuilds everything or frees the
 * hardware reader. The Activity keeps only what genuinely belongs to it: the Compose tree,
 * the file-picker / file-saver launchers, and hardware-trigger key dispatch.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.i(TAG, "MainActivity.onCreate")

        setContent {
            EpcBarcodeappTheme {
                val pickFileLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? -> if (uri != null) viewModel.loadPackingList(uri) }

                val saveFileLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("text/csv")
                ) { uri: Uri? -> if (uri != null) viewModel.exportResultsToCsv(uri) }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ScanScreen(
                        modifier = Modifier.padding(innerPadding),
                        scannedEpcs = viewModel.scannedEpcs,
                        readerReady = viewModel.readerReady,
                        isScanning = viewModel.isScanning,
                        status = viewModel.statusMessage,
                        packingList = viewModel.packingList,
                        packingListName = viewModel.packingListName,
                        packingListStatus = viewModel.packingListStatus,
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
