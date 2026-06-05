# CHIPMO EPC → Barcode — Developer Documentation

## Архитектурын тойм

```
┌─────────────────────────────────────────────┐
│                MainActivity                  │
│  - State holder (mutableStateOf)             │
│  - Reflection-based SDK calls                │
│  - dispatchKeyEvent (hardware trigger)       │
└────┬─────────┬──────────┬──────────┬────────┘
     │         │          │          │
     ▼         ▼          ▼          ▼
┌─────────┐ ┌────────┐ ┌──────────┐ ┌──────────┐
│ Compose │ │ Chain- │ │EpcStream │ │Packing-  │
│ UI      │ │ way    │ │+ drain   │ │ListReader│
│ Overlay │ │ SDK    │ │timer     │ │(custom)  │
└─────────┘ └────────┘ └──────────┘ └──────────┘
                 │
                 ▼
         ┌──────────────┐
         │ EpcDecoder   │  (LRU memoized)
         │ MatchEngine  │
         └──────────────┘
```

---

## Модуль ба гол класс

### `com.epcbc.core` (pure Kotlin, Android-аас хамааралгүй)

| Файл | Үүрэг |
|---|---|
| **EpcDecoder.kt** | EPC hex → GTIN-13 barcode хөрвүүлэгч. SGTIN-96 Partition 0-6 дэмжих. Bounded LRU-аар memoize (20k entry). Raw HEX fallback. |
| **MatchEngine.kt** | Stateless canonical matcher. `groupByBarcode()` + `summarize()`. UI (MatchView, SkuDetailOverlay) болон CSV export бүгд үүгээр тулгана — нэг л хэрэгжүүлэлт. |

### `com.epcbc.scan`

| Файл | Үүрэг |
|---|---|
| **EpcStream.kt** | LinkedBlockingQueue буфер. SDK callback-аас push, UI thread-аас drain. |

### `com.epcbc.data`

| Файл | Үүрэг |
|---|---|
| **PackingListReader.kt** | Pure Kotlin XLSX parser. ZipInputStream + XmlPullParser. Header нэрээр column таних (case-insensitive). |

### `com.epcbc.app`

| Файл | Үүрэг |
|---|---|
| **MainActivity.kt** | Compose UI host. Reflection-based Chainway SDK calls (method handle-ууд кэшлэгдсэн). Hardware trigger dispatching. Settings + scan-session persistence (restart дээр EPC сэргээнэ). |

---

## Chainway SDK интеграц

### Гол SDK класс
`com.rscja.deviceapi.RFIDWithUHFUART`

### Reflection ашиглах шалтгаан
- Build-time-д шууд import хийвэл R8 minification класс-ийг хасах эрсдэлтэй
- ProGuard rules `-keep class com.rscja.**` хадгална
- Reflection-аар "string class name"-ээр дуудах нь илүү аюулгүй

### SDK methods used

```kotlin
// Initialization
RFIDWithUHFUART.getInstance()
reader.init(context: Context): Boolean

// Power
reader.setPower(dBm: Int): Boolean       // 1..30

// Continuous scan
reader.startInventoryTag(): Boolean
reader.stopInventory(): Boolean

// Single tag
reader.inventorySingleTag(): UHFTAGInfo?

// Hardware filter (Gen2 Select)
reader.setFilter(bank: Int, ptr: Int, length: Int, mask: String): Boolean
// bank=1 (EPC), ptr=32 (CRC+PC хойш), length=bits, mask=hex

// Async callback
reader.setInventoryCallback(IUHFInventoryCallback)
// Callback proxy via java.lang.reflect.Proxy

// Cleanup
reader.free()
```

### Callback proxy
```kotlin
val callbackInterface = Class.forName("com.rscja.deviceapi.interfaces.IUHFInventoryCallback")
val callback = Proxy.newProxyInstance(callbackInterface.classLoader, arrayOf(callbackInterface)) { _, method, args ->
    if (method.name == "callback" && args != null) {
        val tagInfo = args[0]
        val epc = tagInfo.javaClass.getMethod("getEPC").invoke(tagInfo) as? String
        epc?.let { epcStream.push(it) }
    }
    null
}
```

---

## Performance pipeline

### EPC delivery
```
Chainway SDK callback thread
    ↓ epcStream.push(epc) — O(1), non-blocking
LinkedBlockingQueue (capacity=1024)
    ↓ Timer scheduleAtFixedRate(100ms)
drainTimer thread → queue.drain() → HashSet dedupe
    ↓ runOnUiThread { scannedEpcs.addAll(0, newOnes) }
SnapshotStateList recomposition (Compose)
```

### Memoization

**EpcDecoder.cache** (thread-safe):
```kotlin
private val cache = java.util.concurrent.ConcurrentHashMap<String, DecodeResult>()
// key = "$hex|$allowRawHexFallback"
```

Зорилго: ижил EPC олон удаа decode хийгдэхгүй (MatchView + EpcRow + SkuDetail-ээс тус тус дуудагдана).

### LazyColumn stable keys
```kotlin
items(rows, key = { it.first.barcode }) { ... }
items(scannedEpcs, key = { it }) { ... }
```

Compose recomposition optimization — мөр шилжсэн ч item composable дахин үүсгэхгүй.

### Sound batching
- 1 бипп / 100ms batch
- Олон tag/sec-д тасралтгүй чимээ болохгүй

---

## State management

### Single source of truth: MainActivity

```kotlin
class MainActivity : ComponentActivity() {
    private var reader: Any? = null  // Lazy SDK instance
    private val scannedEpcs = mutableStateListOf<String>()
    private val packingList = mutableStateListOf<PackingItem>()
    
    private var isScanning by mutableStateOf(false)
    private var readerReady by mutableStateOf(false)
    private var outputPower by mutableStateOf(30)
    // ... etc
}
```

Compose composables нь нэгээс илүү state-ийг ХАДГАЛАХГҮЙ — зөвхөн дамжуулдаг (top-down).

### Overlay rendering pattern

Compose Dialog нь өөрийн Window үүсгэдэг → hardware key event Activity-руу хүрэхгүй. Тиймээс **Box overlay** ашиглана:

```kotlin
Scaffold(...) { ... ScanScreen(...) }
selectedItem?.let { SkuDetailOverlay(...) }  // Box, not Dialog
if (showFinder) FinderOverlay(...)            // Box, not Dialog
if (showSettings) SettingsDialog(...)         // OK as Dialog (no trigger needed)
```

---

## Hardware trigger forwarding

```kotlin
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
```

Гох товчийг бүх view-аас өмнө Activity-д барина → Dialog focused input-ийг "swallow" хийхгүй.

### Key codes (Chainway C5)
```kotlin
setOf(139, 280, 281, 282, 283, 293, 294,
      KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2,
      KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4)
```

Хэрэв таны C5 firmware өөр key code ашиглаж байвал logcat-аас "KEY DOWN: code=" хайж шинэчилнэ.

---

## EPC хөрвүүлэх алгоритм

### SGTIN-96 структур
```
[Header: 8 bit]
[Filter: 3 bit]
[Partition: 3 bit]
[Company Prefix: variable bits → P digits]
[Item Reference: variable bits → (12-P) digits]
[Serial: remaining bits to 96]
```

### Partition хүснэгт (GS1 TDS)
| Partition | CP bits | CP digits | IR bits | IR digits |
|---|---|---|---|---|
| 0 | 40 | 12 | 4 | 1 |
| 1 | 37 | 11 | 7 | 2 |
| 2 | 34 | 10 | 10 | 3 |
| 3 | 30 | 9 | 14 | 4 |
| 4 | 27 | 8 | 17 | 5 |
| **5** | **24** | **7** | **20** | **6** (Levi's) |
| 6 | 20 | 6 | 24 | 7 |

### GTIN-13 угсралт
```kotlin
val gtin12 = companyPrefix + itemRefBody  // 12 digits
val checkDigit = gs1CheckDigit(gtin12)    // mod-10
val gtin13 = gtin12 + checkDigit
```

### Verification
500 Levi's sample data-аас 100% PASS (`EpcDecoderTest.kt`).

---

## Hardware Gen2 Select filter

### Үндсэн зарчим
Antennaна-ийн түвшинд EPC Gen2 Select коммандаар filter тавьна → tag угаас тохирох эсэхээ шалгаад л хариу өгнө.

### ptr convention
- **ptr=0** — EPC bank-ийн эхнээс (CRC+PC оруулсан)
- **ptr=32** — EPC content-ийн эхнээс (CRC+PC хойш) ← **бид үүнийг ашигладаг**

Хэрэв C5 firmware-д ptr=0 байдаг бол `applyHardwareFilter()` метод дотор `ptr` параметрийг 32 → 0 болгож солих.

### Stop/Set/Start pattern
setFilter нь scan running үед хүчин төгөлдөр болохгүй. `reapplyFilter()` helper нь:
1. inventory зогсооно
2. setFilter дуудна
3. wasScanning байсан бол startInventoryTag дахин эхлүүлнэ

---

## Settings persistence

### SharedPreferences
```kotlin
private const val PREFS_NAME = "epc_app_prefs"

private fun loadSettings() {
    val p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    outputPower    = p.getInt("power", 30)
    continuousMode = p.getBoolean("continuous", true)
    prefixLength   = p.getInt("prefix_length", 20)
    soundEnabled   = p.getBoolean("sound", true)
}

private fun saveSettings() {
    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
        .putInt("power", outputPower)
        .putBoolean("continuous", continuousMode)
        .putInt("prefix_length", prefixLength)
        .putBoolean("sound", soundEnabled)
        .apply()
}
```

`loadSettings()` — `onCreate()`-ийн эхэнд.
`saveSettings()` — SettingsDialog-ийн `onApply`-д.

---

## Build instructions

### Prerequisites
- Android Studio Hedgehog 2023.1+ (Compose 1.5+ дэмжих)
- JDK 17 (Embedded JDK хэрэглэх)
- Android SDK API 36
- Chainway DeviceAPI .aar (`app/libs/DeviceAPI_ver20250209_release.aar`)

### Debug build
```bash
./gradlew assembleDebug
./gradlew installDebug    # C5-д шууд суулгана (ADB connected)
```

### Release build
1. Keystore үүсгэх:
```bash
keytool -genkey -v -keystore epc_release.jks \
        -keyalg RSA -keysize 2048 -validity 9125 \
        -alias epc_key
```

2. `app/build.gradle.kts`-д signing config нэмэх эсвэл **Build → Generate Signed Bundle / APK** wizard ашиглах.

3. Build:
```bash
./gradlew assembleRelease
```

4. APK байршил: `app/build/outputs/apk/release/app-release.apk`

5. Install:
```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

### Tests
```bash
./gradlew test                          # All unit tests
./gradlew :app:testDebugUnitTest        # Debug variant only
```

500 Levi's sample data-аас EpcDecoder verify хийнэ.

### R8 (minification)
Release build-д R8 + resource shrinking идэвхтэй (`isMinifyEnabled = true`).

ProGuard rules (`proguard-rules.pro`):
```
-keep class com.rscja.** { *; }
-keep interface com.rscja.** { *; }
-dontwarn com.rscja.**
-keepclasseswithmembers class * { native <methods>; }
```

---

## Файл бүтэц

```
EpcBarcodeApp/
├── app/
│   ├── libs/
│   │   └── DeviceAPI_ver20250209_release.aar
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/epcbc/
│   │   │   │   ├── app/MainActivity.kt
│   │   │   │   ├── core/
│   │   │   │   │   ├── EpcDecoder.kt
│   │   │   │   │   └── MatchEngine.kt
│   │   │   │   ├── data/PackingListReader.kt
│   │   │   │   └── scan/
│   │   │   │       └── EpcStream.kt
│   │   │   ├── res/
│   │   │   │   ├── drawable/ic_launcher_*.xml
│   │   │   │   ├── mipmap-*/ic_launcher*.png/xml
│   │   │   │   └── values/
│   │   │   └── AndroidManifest.xml
│   │   ├── test/java/com/epcbc/core/
│   │   │   ├── EpcDecoderTest.kt
│   │   │   └── MatchEngineTest.kt
│   │   └── androidTest/java/com/epcbc/data/PackingListReaderTest.kt
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml
└── README.md, USER_GUIDE.md, DEVELOPER.md, CHANGELOG.md
```

---

## Дараа боломжит шинэ функцууд

### Шууд дарагдсан
- ⏳ **Hardware ptr=0 fallback** — firmware variant дэмжлэг
- ⏳ **Native XLSX writer** — CSV-н оронд бүрэн .xlsx export (Apache POI?)
- ⏳ **App icon — бодит PNG** — Image Asset Studio-гоор хэрэглэгчийн логог

### Урт хугацааны
- ⏳ **Background scan session** — апп background-д ч уншиж байх
- ⏳ **Multi-language** — English UI
- ⏳ **Multi-warehouse** — олон packing list зэрэг
- ⏳ **Cloud sync** — Firestore эсвэл REST API
- ⏳ **Inventory history** — өмнөх сканнуудыг хадгалах

---

## Холбоо барих

Хөгжүүлэгчийн асуулт байвал [README.md](README.md)-н issue tracker эсвэл шууд CHIPMO IT-д.
