package com.epcbc.scan

import com.rscja.deviceapi.RFIDWithUHFUART

/**
 * Compile-time probe: if this file compiles, the Chainway SDK .aar is properly linked.
 * Not used at runtime yet — Step 5 will wire it up to a real reader instance.
 */
object RfidProbe {
    /** Returns the fully-qualified name of the Chainway UHF reader class. */
    fun sdkClassName(): String = RFIDWithUHFUART::class.java.name
}
