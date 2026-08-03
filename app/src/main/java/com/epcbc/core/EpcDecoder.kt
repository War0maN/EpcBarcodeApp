package com.epcbc.core

/**
 * EPC (RFID Tag) → GS1 GTIN-13 barcode decoder.
 *
 * Default standard: GS1 SGTIN-96 (header byte = 0x30).
 * Falls back to raw HEX mode when header is unknown (configurable).
 *
 * Verified against real Levi's data (500/500 sample pairs):
 *   EPC 303549A3C052DC4CCE41F3B0 → 5400816848493 ✓
 *
 * Reference: GS1 EPC Tag Data Standard 1.13, Section 14.5 (SGTIN-96)
 */
object EpcDecoder {

    // SGTIN-96 partition table (Section 14.5.1 of GS1 TDS)
    private val PARTITIONS = listOf(
        Partition(40, 12,  4, 1),  // P=0
        Partition(37, 11,  7, 2),  // P=1
        Partition(34, 10, 10, 3),  // P=2
        Partition(30,  9, 14, 4),  // P=3
        Partition(27,  8, 17, 5),  // P=4
        Partition(24,  7, 20, 6),  // P=5  ← Levi's
        Partition(20,  6, 24, 7),  // P=6
    )

    data class Partition(
        val cpBits: Int, val cpDigits: Int,
        val irBits: Int, val irDigits: Int
    )

    data class DecodeResult(
        val format: String,
        val barcode: String?,
        /** Бүтэн GTIN-14 (сервертэй ижил хэлбэр); GS1 биш форматад null. */
        val gtin14: String? = null,
        val rawHex: String,
        val header: Int,
        val filterValue: Int? = null,
        val partition: Int? = null,
        val companyPrefix: String? = null,
        val itemReference: String? = null,
        val serial: String? = null,
        val indicator: Int? = null
    )

    // Bounded LRU memoization. Decoding the same EPC repeatedly (which is common — the
    // UI re-derives match data on every recomposition) is wasteful. But each unique tag
    // serial would otherwise live forever; a long scanning session of 100k+ distinct tags
    // would leak memory. So we cap the cache and evict the least-recently-used entry.
    // The cache key is "$hex|$allowRaw" so the two fallback modes are stored independently.
    private const val MAX_CACHE_ENTRIES = 20_000

    // access-order LinkedHashMap → eldest = least-recently-used. Not thread-safe on its own
    // (accessOrder mutates on get), so every touch is guarded by synchronized(cache) below.
    private val cache = object : LinkedHashMap<String, DecodeResult>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, DecodeResult>): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    fun decode(hexEpc: String, allowRawHexFallback: Boolean = true): DecodeResult {
        val hex = hexEpc.trim().uppercase()
        val cacheKey = if (allowRawHexFallback) "$hex|1" else "$hex|0"
        synchronized(cache) { cache[cacheKey] }?.let { return it }
        require(hex.matches(Regex("[0-9A-F]+"))) { "EPC must be hex: $hexEpc" }

        val result = if (hex.length == 24 && hex.startsWith("30")) {
            decodeSgtin96(hex)
        } else {
            val header = hex.take(2).toInt(16)
            DecodeResult(
                format = if (allowRawHexFallback) "RAW_HEX" else "UNKNOWN",
                barcode = if (allowRawHexFallback) hex else null,
                rawHex = hex,
                header = header
            )
        }
        synchronized(cache) { cache[cacheKey] = result }
        return result
    }

    private fun decodeSgtin96(hex: String): DecodeResult {
        val bin = hexToBinary(hex)
        val header = bin.substring(0, 8).toInt(2)
        val filter = bin.substring(8, 11).toInt(2)
        val partition = bin.substring(11, 14).toInt(2)
        val p = PARTITIONS.getOrNull(partition)
            ?: error("Invalid SGTIN-96 partition: $partition")

        val cpEnd = 14 + p.cpBits
        val irEnd = cpEnd + p.irBits
        val companyDec = bin.substring(14, cpEnd).toLong(2)
        val itemDec    = bin.substring(cpEnd, irEnd).toLong(2)
        val serialDec  = bin.substring(irEnd, 96).toLong(2)

        val companyStr = pad(companyDec, p.cpDigits)
        val itemStr    = pad(itemDec, p.irDigits)
        val indicator  = itemStr[0].digitToInt()
        val itemRefBody = itemStr.substring(1)

        // GTIN-14 = indicator + company prefix + item ref body + шалгах орон —
        // серверийн sgtin96_decode-той ЯГ ижил (хүлээн авалтын тулгалтын үндэс).
        // Өмнө нь indicator-ыг хаяж 13 оронтой barcode гаргадаг байсан.
        val data13 = "$indicator$companyStr$itemRefBody"
        check(data13.length == 13) { "GTIN-14 data length wrong: $data13 (cp=$companyStr ir=$itemRefBody)" }
        val gtin14 = data13 + gs1CheckDigit(data13)
        // Дэлгэцэд хэвшсэн хэлбэр: indicator 0 үед GTIN-13 (шалгах орон тэргүүлэх
        // 0-д үл хамаарах тул substring хүчинтэй), бусад үед бүтэн 14 орон.
        val barcode = if (indicator == 0) gtin14.substring(1) else gtin14

        return DecodeResult(
            format         = "SGTIN-96",
            barcode        = barcode,
            gtin14         = gtin14,
            rawHex         = hex,
            header         = header,
            filterValue    = filter,
            partition      = partition,
            companyPrefix  = companyStr,
            itemReference  = itemRefBody,
            serial         = serialDec.toString(),
            indicator      = indicator
        )
    }

    /**
     * GS1 шалгах орон — ДУРЫН урттай өгөгдөлд (баруун талын цифрээс 3,1,3,1…
     * жинтэй). Өмнөх хувилбар зүүнээс индекслэдэг байсан тул зөвхөн тэгш
     * урттай (GTIN-13-ийн 12 цифр) өгөгдөлд зөв байсан; GTIN-14-ийн 13 цифрт
     * жин нь урвуугаар буудаг байв.
     */
    fun gs1CheckDigit(digits: String): Int {
        var sum = 0
        for ((i, ch) in digits.reversed().withIndex()) {
            val d = ch.digitToInt()
            sum += if (i % 2 == 0) d * 3 else d
        }
        return (10 - (sum % 10)) % 10
    }

    /**
     * EPC-ээс БАРААГ тодорхойлох угтварыг бүтцээр нь тайрна (серийн өмнөх
     * хэсэг): SGTIN-96 (header 30) — сериал 38 бит → 14 hex; GID-96 (35) —
     * сериал 36 бит → 15 hex. Гараар тохируулдаг prefix-урт (хуучин 20)
     * серийн дээд битүүдийг давхар шүүж, нэг барааны зарим тагийг чимээгүй
     * алгасдаг байсныг энэ орлоно (2026-08-03).
     */
    fun productPrefix(epcHex: String): String {
        val h = epcHex.trim().uppercase()
        return when {
            h.startsWith("30") -> h.take(14)
            h.startsWith("35") -> h.take(15)
            else -> h.take(14)
        }
    }

    private fun hexToBinary(hex: String): String =
        hex.map { c -> c.digitToInt(16).toString(2).padStart(4, '0') }.joinToString("")

    private fun pad(value: Long, digits: Int): String =
        value.toString().padStart(digits, '0')
}
