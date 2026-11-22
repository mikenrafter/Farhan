package ly.com.tahaben.privileged_access_domain

/** The route the user prefers. [ASK] is the default: Farhan never picks a way into the phone the user did not choose. */
enum class RoutePreference {
    ASK, SELF_ADB, SHIZUKU;

    companion object {
        val DEFAULT = ASK

        /** An unknown stored value (an old or edited backup) falls back to [DEFAULT]. */
        fun fromStored(stored: String?): RoutePreference = entries.firstOrNull { it.name == stored } ?: DEFAULT
    }
}

sealed interface RouteChoice {
    data class Use(val route: Route) : RouteChoice
    data class AskUser(val options: List<Route>) : RouteChoice
}

object RouteChooser {
    /**
     * In order: the route the caller named for this one command; the stored preference when it names a route; the
     * route of the retained link (a second access runs over the first one's link with no pairing flow); otherwise
     * ask.
     */
    fun choose(requested: Route?, preference: RoutePreference, link: LinkState): RouteChoice = when {
        requested != null -> RouteChoice.Use(requested)
        preference == RoutePreference.SELF_ADB -> RouteChoice.Use(Route.SELF_ADB)
        preference == RoutePreference.SHIZUKU -> RouteChoice.Use(Route.SHIZUKU)
        link is LinkState.Connected -> RouteChoice.Use(link.route)
        else -> RouteChoice.AskUser(Route.entries)
    }
}
