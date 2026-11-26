package com.reianix.reianix_local.ui.navigation

/** Pure policy: Navigation owns entry identity, not just a route pattern. */
internal enum class ReiAnixRouteAnimation { NONE, STANDARD, DETAILS }

/**
 * Recommendations share the same route pattern but are different back-stack
 * entries; their transitions must not disappear. Re-selecting the same entry
 * never starts another animation. Keep all non-detail routes on the original
 * visual style until frame measurements justify a targeted change.
 */
internal fun reiAnixRouteAnimation(
    fromRoute: String?,
    toRoute: String?,
    sameEntry: Boolean,
    systemAnimationsEnabled: Boolean,
): ReiAnixRouteAnimation {
    if (sameEntry || !systemAnimationsEnabled) return ReiAnixRouteAnimation.NONE
    if (fromRoute == ReiAnixRoutes.DETAILS ||
        toRoute == ReiAnixRoutes.DETAILS ||
        fromRoute == ReiAnixRoutes.ANILIST_DETAILS ||
        toRoute == ReiAnixRoutes.ANILIST_DETAILS
    ) return ReiAnixRouteAnimation.DETAILS
    return ReiAnixRouteAnimation.STANDARD
}

/** The four persistent tabs share a single motion contract in either direction. */
internal fun reiAnixIsPrimaryTabTransition(fromRoute: String?, toRoute: String?): Boolean {
    fun isPrimary(route: String?) = when (route) {
        ReiAnixRoutes.HOME, ReiAnixRoutes.LIBRARY, ReiAnixRoutes.SEARCH, ReiAnixRoutes.SETTINGS -> true
        else -> false
    }
    return fromRoute != toRoute && isPrimary(fromRoute) && isPrimary(toRoute)
}
