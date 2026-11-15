package com.rummikub.app

/**
 * Public configuration of the app. None of this is secret: the Supabase publishable key is meant
 * to ship inside apps, and the database is protected by Row Level Security.
 */
object AppConfig {
    const val SUPABASE_URL = "https://wanqpgxhyoybrpbzsjgu.supabase.co"
    const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_JWGs8g44Y-bCUmgPq9DPjA_UwM8OzOl"

    /** Web client ID of the Google OAuth client (see android/app-config.properties). */
    val GOOGLE_WEB_CLIENT_ID: String get() = BuildConfig.GOOGLE_WEB_CLIENT_ID

    /** Accounts are on as soon as Google sign-in is configured; otherwise the app plays anonymously as before. */
    val accountsEnabled: Boolean get() = GOOGLE_WEB_CLIENT_ID.isNotBlank()
}
