package com.reianix.reianix_local.data.tracemoe

import android.content.Context

internal object ReiAnixTraceMoeConsent {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("reianix_trace_moe_consent_v1", Context.MODE_PRIVATE)
    fun allowed(context: Context): Boolean = prefs(context).getBoolean("allow_episode_frames", false)
    fun setAllowed(context: Context, allowed: Boolean): Boolean =
        prefs(context).edit().putBoolean("allow_episode_frames", allowed).commit()
}
