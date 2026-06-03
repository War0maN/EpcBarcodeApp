package com.epcbc.core

/**
 * Build EPC Gen2 Select-command parameters (bank/ptr/length/mask) from a packing list
 * and/or a target SKU.
 *
 * Chainway SDK call:
 *   uhfReader.setFilter(bank, ptr, length, maskHex)
 *
 * NOTE on `ptr`: Some Chainway firmwares count ptr from start of EPC bank (CRC+PC included),
 * others from start of EPC data (after CRC+PC). Verify on C5 Demo app and set EPC_PTR_OFFSET
 * to 0 or 32 accordingly.
 */
object FilterBuilder {

    /** Set to 32 if firmware counts ptr from EPC-bank start (CRC+PC included). */
    var EPC_PTR_OFFSET: Int = 0

    const val BANK_RESERVED = 0
    const val BANK_EPC      = 1
    const val BANK_TID      = 2
    const val BANK_USER     = 3

    data class Filter(val bank: Int, val ptr: Int, val length: Int, val maskHex: String) {
        override fun toString() = "Filter(bank=$bank ptr=$ptr len=$length mask=$maskHex)"
    }

    /**
     * Auto-derive filter(s) from a list of GTIN-13 barcodes.
     * Returns one Filter per unique 7-digit company prefix.
     */
    fun fromPackingList(barcodes: Collection<String>): List<Filter> {
        val cps = barcodes
            .filter { it.length == 13 && it.all(Char::isDigit) }
            .map { gtin -> companyPrefix7DigitsFromGtin13(gtin) }
            .toSet()
        return cps.map { cp -> filterByCompanyPrefix7(cp) }
    }

    fun filterByCompanyPrefix7(companyPrefix7: String): Filter {
        require(companyPrefix7.length == 7 && companyPrefix7.all(Char::isDigit)) {
            "Company prefix must be 7 digits"
        }
        val maskHex = encode24BitCompanyPrefixToHex(companyPrefix7)
        return Filter(BANK_EPC, EPC_PTR_OFFSET + 14, 24, maskHex)
    }

    fun filterBySku(companyPrefix7: String, itemReference5: String, indicator: Int = 0): Filter {
        require(itemReference5.length == 5 && itemReference5.all(Char::isDigit))
        val cpBits = companyPrefix7.toLong().toString(2).padStart(24, '0')
        val itemDec = (indicator.toString() + itemReference5).toLong()
        val irBits = itemDec.toString(2).padStart(20, '0')
        val full44 = cpBits + irBits
        val padded = full44.padEnd(48, '0')
        val maskHex = bitsToHex(padded)
        return Filter(BANK_EPC, EPC_PTR_OFFSET + 14, 44, maskHex)
    }

    fun filterByFullEpc(hexEpc: String): Filter {
        val hex = hexEpc.uppercase()
        require(hex.length % 2 == 0)
        return Filter(BANK_EPC, EPC_PTR_OFFSET + 0, hex.length * 4, hex)
    }

    private fun companyPrefix7DigitsFromGtin13(gtin13: String): String {
        return gtin13.substring(1, 8)
    }

    private fun encode24BitCompanyPrefixToHex(cp7: String): String {
        val bits = cp7.toLong().toString(2).padStart(24, '0')
        return bitsToHex(bits)
    }

    private fun bitsToHex(bits: String): String {
        require(bits.length % 4 == 0)
        return bits.chunked(4).joinToString("") { it.toInt(2).toString(16).uppercase() }
    }
}
