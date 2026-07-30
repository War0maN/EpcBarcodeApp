package com.epcbc.core

import com.epcbc.data.PackingListReader.PackingItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Хөрвүүлэлт ↔ тулгалт серверийн хүлээн авалттай ижил ажиллах баталгаа.
 * EPC hex векторуудыг rfid-epc вебийн sgtin96FromGtin-ээр үүсгэсэн бөгөөд
 * серверийн sgtin96_decode тэдгээрийг GTIN-14 болгож задалдаг нь батлагдсан.
 *
 * Гол регресс: packing list-д 12 оронтой UPC ("888392280015") бичигдсэн үед
 * decoder 13 оронтой "0888392280015" гаргаж string тэнцэхгүй → бүх UPC бараа
 * "дутуу" тоологддог байсан (талбай дээр 172-ын оронд 114 таарсан).
 */
class MatchKeyTest {

    // rfid-epc веб encoder-ийн гаралт (2026-07-30):
    //   888392280015  (UPC-12, Oakley)  serial 101/102
    //   8056262130209 (EAN-13, Ray-Ban) serial 900000001
    private val upcEpc1 = "30343639201B584000000065"
    private val upcEpc2 = "30343639201B584000000066"
    private val eanEpc = "3035EBB7180CB70035A4E901"

    @Test
    fun `decoder emits full gtin14 matching server decode`() {
        assertEquals("00888392280015", EpcDecoder.decode(upcEpc1).gtin14)
        assertEquals("08056262130209", EpcDecoder.decode(eanEpc).gtin14)
    }

    @Test
    fun `display barcode keeps familiar gtin13 for indicator zero`() {
        assertEquals("0888392280015", EpcDecoder.decode(upcEpc1).barcode)
        assertEquals("8056262130209", EpcDecoder.decode(eanEpc).barcode)
    }

    @Test
    fun `key normalizes upc12 ean13 gtin14 to one form`() {
        assertEquals("00888392280015", MatchEngine.key("888392280015"))
        assertEquals("00888392280015", MatchEngine.key("0888392280015"))
        assertEquals("00888392280015", MatchEngine.key("00888392280015"))
        assertEquals("08056262130209", MatchEngine.key("8056262130209"))
        // GS1 биш утга (raw hex fallback) хэвээрээ
        assertEquals("30343639201B584000000065", MatchEngine.key("30343639201B584000000065"))
    }

    @Test
    fun `upc12 packing row matches scans of its own tags`() {
        val packing = listOf(
            PackingItem(sku = "AOO-TEST", barcode = "888392280015", name = "Oakley test", qty = 2, rowIndex = 0),
            PackingItem(sku = "RB-TEST", barcode = "8056262130209", name = "Ray-Ban test", qty = 2, rowIndex = 1),
        )
        val summary = MatchEngine.summarize(packing, listOf(upcEpc1, upcEpc2, eanEpc))
        assertEquals(3, summary.matchedPieces)   // өмнөх кодоор 1 л таардаг байсан
        assertEquals(1, summary.missingPieces)   // Ray-Ban 1/2
        assertEquals(0, summary.overPieces)
        assertEquals(0, summary.orphanCount)
    }

    @Test
    fun `gs1 check digit works for odd length gtin14 data`() {
        // GTIN-14 = 13 өгөгдлийн цифр + шалгах орон. "0888392280015"-ийн
        // шалгах орон 4 → GTIN-14 = 00888392280015 биш! Тайлбар: тэргүүлэх
        // 0-ууд нийлбэрт нөлөөгүй тул check("0088839228001") == check("888392280015"-ийн эхний 11)...
        // Энгийнээр: GS1-ийн жишээ 629104150021 → 3.
        assertEquals(3, EpcDecoder.gs1CheckDigit("629104150021"))
        // Тэргүүлэх 0 нэмэхэд шалгах орон өөрчлөгдөхгүй (зүүнээс биш баруунаас жинлэдэг).
        assertEquals(
            EpcDecoder.gs1CheckDigit("888392280015".dropLast(1)),
            EpcDecoder.gs1CheckDigit("00" + "888392280015".dropLast(1)),
        )
    }
}
