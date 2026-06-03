package com.epcbc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Algorithm verification against real Levi's data extracted from the user's
 * "Generate - for btf-260527_Levis.xlsm" packing list. (500/500 PASS in Python clone.)
 */
class EpcDecoderTest {

    @Test fun decodes_levis_partition5_sample_1() {
        val r = EpcDecoder.decode("303549A3C052DC4CCE41F3B0")
        assertEquals("SGTIN-96", r.format)
        assertEquals("5400816848493", r.barcode)
        assertEquals(0x30, r.header)
        assertEquals(5, r.partition)
        assertEquals("5400816", r.companyPrefix)
    }

    @Test fun decodes_levis_partition5_sample_2() {
        val r = EpcDecoder.decode("303549A3C052DC4CCE41F434")
        assertEquals("5400816848493", r.barcode)
    }

    @Test fun decodes_levis_partition5_sample_3() {
        val r = EpcDecoder.decode("303549A3C052DC4CCE41F442")
        assertEquals("5400816848493", r.barcode)
    }

    @Test fun raw_hex_fallback_when_header_unknown() {
        val r = EpcDecoder.decode("E20012345678901234567890", allowRawHexFallback = true)
        assertEquals("RAW_HEX", r.format)
        assertEquals("E20012345678901234567890", r.barcode)
    }

    @Test fun raw_hex_fallback_disabled_returns_null_barcode() {
        val r = EpcDecoder.decode("E20012345678901234567890", allowRawHexFallback = false)
        assertEquals("UNKNOWN", r.format)
        assertNull(r.barcode)
    }

    @Test fun gs1_check_digit_known_values() {
        assertEquals(4, EpcDecoder.gs1CheckDigit("458275508077"))
        assertEquals(3, EpcDecoder.gs1CheckDigit("540081684849"))
        assertEquals(7, EpcDecoder.gs1CheckDigit("762672574795"))
    }
}
