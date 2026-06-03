# Changelog

Бүх анхаарал татахуйц өөрчлөлтийг энд бүртгэнэ.

Формат: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) загвар.

---

## [1.0.0] — 2026-06-03

Эхний production хувилбар. Бүх үндсэн функцууд бэлэн.

### Algorithm
- GS1 SGTIN-96 EPC → GTIN-13 barcode хөрвүүлэгч
- Бүх Partition (0-6) дэмжлэг
- Raw HEX fallback (стандарт бус EPC)
- ConcurrentHashMap memoization
- 500 Levi's жинхэнэ дата дээр 100% PASS (`EpcDecoderTest`)

### Үндсэн функцууд
- Chainway C5 UHF reader-аар RFID tag scanning (Auto + Single горим)
- Excel packing list (.xlsx/.xlsm) импорт — custom XLSX parser, гадаад dependency байхгүй
- Real-time match view: SKU тус бүрд `read/qty` + color coding (ногоон/улбар/шар/саарал)
- Дэлгэрэнгүй stats: Уншсан / Нийт, Бүрэн / Дутуу / Илүү / Орхигдсон (тоо ширхэгээр)
- Settings: Output power (1-30 dBm), scan mode, EPC prefix length, sound feedback
- Settings persistence (SharedPreferences) — апп хааж нээхэд сэргээгдэнэ

### SKU дэлгэрэнгүй overlay
- Бараа дээр дарж нээх Box overlay (Dialog биш — hardware key event дэмжих)
- Auto-prefix талбар (default-аар бичигдсэн)
- Live mode: гох товчоор уншуулахад тэр prefix-тэй tag-ууд автоматаар нэмэгдэнэ
- Hardware Gen2 Select filter — антенны түвшний шүүлтүүр (`setFilter(bank=1, ptr=32, ...)`)
- Scan state badge (● УНШИЖ БАЙНА / ⏸ ЗОГССОН)
- Footer-д ▶ Эхлүүлэх / ⏸ Зогсоох товч

### Tag Finder (Geiger mode)
- Тодорхой EPC-ийг RSSI signal strength-аар олох
- Hardware filter автоматаар target EPC дээр тогтоно
- Том percent display (0-100%) + color-coded bar
- Status мессеж 5 түвшинд (Маш ойр / Ойртож байна / ... / Сигнал хүлээж байна)
- Signal decay logic (800ms-д шинэчлэгдэхгүй бол хувь буурна)
- Stop эсвэл Close дарвал scan зогсож, filter цэвэрлэгдэнэ

### CSV Export
- 3 хүснэгттэй: SKU дүгнэлт, таарсан EPC (SKU-аар групплүүлсэн), орхигдсон EPC
- НИЙТ мөр хэсэг бүрд
- UTF-8 BOM — Excel-д монгол үсэг зөв харагдана
- SAF (Storage Access Framework) launcher-аар хэрэглэгч хадгалах байршил сонгоно
- Файлын нэр автоматаар `epc_export_YYYYMMDD_HHMMSS.csv`

### Performance
- **EpcStream** — LinkedBlockingQueue (1024 cap) буфер, SDK callback thread-аас push
- **drainTimer** — 100ms-д нэг батч UI шинэчилнэ (Compose recomposition минимум)
- **HashSet dedupe** — дубль EPC-уудыг O(1)-ээр шалгана
- **EpcDecoder memoization** — ConcurrentHashMap, давтсан decode-ыг олон удаа гүйцэтгэхгүй
- **LazyColumn stable keys** — Compose recomposition optimization
- **Sound batching** — 1 бипп / 100ms batch
- **R8 minification + resource shrinking** — release APK ~7MB

### UI / UX
- Compose UI + Material 3
- Dialog-уудыг Box overlay болгож хувиргасан → гох товч overlay нээлттэй үед ч ажиллана
- BackHandler — back товч даран overlay-уудыг хаах
- Backdrop click-to-dismiss
- Цагаан background dialog — гүйцэт харагдамжтай
- 0/qty төлвийг харуулна (уншигдаагүй бараа ч жагсаалтанд харагдана)
- Header-д version label (`v1.0.0`)

### Build
- versionCode = 1, versionName = "1.0.0"
- minSdk = 24 (Android 7.0)
- targetSdk = 36
- BuildConfig enabled
- ProGuard rules — Chainway SDK хадгалах
- isMinifyEnabled + isShrinkResources в release

---

## [Unreleased]

### Хийгдэх боломжтой
- App icon — бодит CHIPMO PNG-ээр (одоо vector approximation)
- Hardware ptr=0 fallback (firmware variants)
- Native XLSX writer (Apache POI эсвэл fastexcel)
- Multi-language UI (English)
- Background scan session
- Inventory history (өмнөх сканнууд хадгалах)
- Cloud sync (Firestore эсвэл REST)

---

## Project history (төслийн түүх)

| Огноо | Хувилбар | Тэмдэглэл |
|---|---|---|
| 2026-05-27 | 0.1 (POC) | Алгоритм + Kotlin POC, 500 sample PASS |
| 2026-05-28 | 0.2 | Android Studio project, Hello World on C5 |
| 2026-05-28 | 0.3 | First real EPC scan working |
| 2026-05-29 | 0.4 | Packing list import + match view |
| 2026-05-30 | 0.5 | Sound feedback, settings dialog, color-coded stats |
| 2026-05-31 | 0.6 | Tag finder (Geiger mode), hardware filter |
| 2026-06-01 | 0.7 | CSV export — 3 хүснэгт |
| 2026-06-02 | 0.8 | Performance optimization, settings persistence |
| **2026-06-03** | **1.0.0** | **Production release. Documentation.** |
