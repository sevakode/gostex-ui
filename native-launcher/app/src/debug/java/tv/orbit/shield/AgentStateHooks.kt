package tv.orbit.shield

internal object AgentStateHooks {
    fun attach(activity: MainActivity) = AgentStateProvider.attach(activity)
    fun detach(activity: MainActivity) = AgentStateProvider.detach(activity)
}
