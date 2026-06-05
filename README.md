# CHIPMO EPC → Barcode Converter

**Chainway C5 UHF RFID** гар уншигч төхөөрөмжид зориулагдсан Android аппликейшн. Импортын жижиглэн худалдааны бараа хүлээн авах үед RFID tag-уудыг packing list-ийн barcode-той автоматаар тулгаж, тоо ширхэг, дутуу/илүү, орхигдсон tag-уудыг хянана.

![Version](https://img.shields.io/badge/version-1.0.0-blue)
![Platform](https://img.shields.io/badge/platform-Chainway%20C5-orange)
![Stack](https://img.shields.io/badge/stack-Kotlin%20%2B%20Compose-purple)

---

## Юу хийдэг вэ?

- 📡 C5-ийн UHF антеннаар RFID tag-уудыг live уншина (Auto / Single горим)
- 🔄 **GS1 SGTIN-96** стандартаар EPC → GTIN-13 barcode болгож хувиргана (500 жинхэнэ дата дээр 100% PASS)
- 📊 Excel packing list-той тулгаж бараа бүрийн **уншсан / нийт** тоог live харуулна
- 🎯 Дутуу tag-ийг агуулахад **RSSI signal strength**-аар олох "Geiger mode"
- 🔍 EPC Gen2 Select **hardware filter** — антенны түвшинд зөвхөн тодорхой бараа уншина
- 📥 Үр дүнг **CSV** болгож гаргаж Excel-д шууд нээх (3 хүснэгтэй: SKU дүгнэлт, таарсан EPC, орхигдсон EPC)

---

## Дэлгэрэнгүй баримтжуулалт

| Файл | Хэн зориулсан | Агуулга |
|---|---|---|
| **[USER_GUIDE.md](USER_GUIDE.md)** | Хэрэглэгч | Алхам бүрчилсэн заавар: апп нээх, packing list оруулах, scan хийх, олох, export |
| **[DEVELOPER.md](DEVELOPER.md)** | Хөгжүүлэгч | Архитектур, гол класс, Chainway SDK интеграц, build instructions |
| **[CHANGELOG.md](CHANGELOG.md)** | Бүгд | Хувилбарын түүх, шинэ функцууд |

---

## Quick Start (хэрэглэгчид)

1. C5 төхөөрөмж дээр аппыг суулга (APK)
2. Апп нээгээд **УНШИГЧ ЭХЛҮҮЛЭХ** товч дар
3. **Файл сонгох** → packing list (xlsx) оруулах
4. C5-ийн хажуугийн **гох товч** даран tag-уудыг сканнердана
5. Үр дүнгийн доор **📥 Excel рүү гаргах** → CSV хадгалах

---

## Quick Start (хөгжүүлэгчид)

```bash
# Clone repo, нээх Android Studio дээр
cd EpcBarcodeApp

# Unit tests
./gradlew test

# Debug build + install on C5
./gradlew installDebug

# Release APK (signed)
./gradlew assembleRelease
```

Илүү дэлгэрэнгүйг **[DEVELOPER.md](DEVELOPER.md)** үзнэ үү.

---

## Тех stack

| Хэсэг | Технологи |
|---|---|
| **Хэл** | Kotlin 2.0+ |
| **UI** | Jetpack Compose + Material 3 |
| **RFID SDK** | Chainway DeviceAPI_ver20250209 (.aar) |
| **XLSX import** | Custom ZipInputStream + XmlPullParser parser (зэргийн dependency) |
| **Min SDK** | API 24 (Android 7.0) |
| **Target SDK** | API 36 |
| **Build tool** | Gradle 9.x + R8 |

---

## Файлын бүтэц

```
EpcBarcodeApp/
├── app/
│   ├── src/main/java/com/epcbc/
│   │   ├── app/           Compose UI + MainActivity
│   │   ├── core/          EpcDecoder, MatchEngine
│   │   ├── scan/          EpcStream
│   │   └── data/          PackingListReader
│   ├── src/test/          JUnit unit tests
│   ├── libs/              Chainway DeviceAPI .aar
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── README.md            (энэ файл)
├── USER_GUIDE.md
├── DEVELOPER.md
└── CHANGELOG.md
```

---

## Лиценз

Дотоодын хувийн хэрэглээний программ. CHIPMO компанийн зориулалттай.
