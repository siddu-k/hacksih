package com.sriox.vasateysec.utils

/**
 * Supabase backend config shared by the Android app.
 * Same project as webmodel/js/supabase.js. Anon key is public by design
 * (Supabase RLS governs access); bucket must be public-read.
 */
object SupabaseConfig {
    const val URL = "https://pfgvazrkvfaixmfonvmd.supabase.co"
    const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBmZ3ZhenJrdmZhaXhtZm9udm1kIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTEzOTA2NjAsImV4cCI6MjEwNjk2NjY2MH0.NYejq2f66erlWH3Zg7CdPkI_wkLs4t65exhRwc_4Nuo"
    const val PHOTO_BUCKET = "emergency-photos"
}
