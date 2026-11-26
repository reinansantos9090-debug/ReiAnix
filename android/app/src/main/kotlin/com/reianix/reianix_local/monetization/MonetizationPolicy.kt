package com.reianix.reianix_local.monetization

internal object MonetizationPolicy {
    const val PRODUCT = "reianix_premium"
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val FREE_ART_COUNT = 3
    fun suppressAds(premium: Boolean, until: Long, now: Long): Boolean =
        premium || until > now
    fun rewardUntil(now: Long): Long = now + DAY_MS
    fun artKey(banner: Boolean, id: String) = (if (banner) "banner:" else "wallpaper:") + id
    fun artAvailable(premium: Boolean, index: Int, key: String, unlocked: Set<String>) =
        premium || index in 0 until FREE_ART_COUNT || key in unlocked
}
