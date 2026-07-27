package com.epcbc.net

import com.epcbc.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest

/**
 * Supabase клиент (singleton) — вебтэй (rfid-epc) ИЖИЛ backend.
 * Нэвтрэлт нь вебийн акаунтаар, RLS/эрхийн бүх дүрэм автоматаар үйлчилнэ.
 * URL/key нь local.properties → BuildConfig-оор ирнэ (commit хийгдэхгүй).
 */
object Supa {
    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            install(Auth) // session нь Android дээр автоматаар хадгалагдана (SharedPreferences)
            install(Postgrest)
        }
    }

    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    /** Нэвтэрсэн хэрэглэгчийн имэйл (байхгүй бол null). */
    val userEmail: String?
        get() = client.auth.currentUserOrNull()?.email
}
