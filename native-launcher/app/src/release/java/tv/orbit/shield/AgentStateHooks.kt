package tv.orbit.shield

/** The experimental shell state provider is deliberately absent from release builds. */
internal object AgentStateHooks {
    fun attach(@Suppress("UNUSED_PARAMETER") activity: MainActivity) = Unit
    fun detach(@Suppress("UNUSED_PARAMETER") activity: MainActivity) = Unit
}
