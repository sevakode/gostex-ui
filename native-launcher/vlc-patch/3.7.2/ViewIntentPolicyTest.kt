/* Copyright (C) 2026 GOSTEX contributors. GPL-2.0-or-later. */
package org.videolan.vlc.gui.video

// Models scheduling around the real URI decision; Android lifecycle integration is tested separately.
private class PlaybackHarness(var uri: String?, var paused: Boolean, var active: Boolean) {
    var pending = false
    var forceLoads = 0
    fun intent(isView: Boolean, next: String?) {
        val newView = gostexNewViewRequest(isView, uri, next)
        if (isView && next != null) pending = newView
        if (active && next != null && next != uri) {
            uri = next
            if (newView || !paused) {
                if (newView) paused = false
                pending = false
                forceLoads++
            }
        }
    }
    fun start() {
        if (pending) { paused = false; forceLoads++; pending = false }
    }
}

fun main() {
    check(!gostexNewViewRequest(true, "A", "A"))
    check(!gostexNewViewRequest(true, "A", null))
    check(!gostexNewViewRequest(false, "A", "B"))
    check(gostexNewViewRequest(true, "A", "B"))
    val paused = PlaybackHarness("A", true, true)
    paused.intent(true, "A")
    check(paused.paused && paused.forceLoads == 0)
    paused.intent(true, "B")
    check(paused.uri == "B" && !paused.paused && paused.forceLoads == 1)
    paused.intent(true, "B")
    check(paused.forceLoads == 1)
    val stopped = PlaybackHarness("A", true, false)
    stopped.intent(true, "B")
    check(stopped.pending && stopped.paused && stopped.forceLoads == 0)
    stopped.start()
    check(!stopped.pending && !stopped.paused && stopped.forceLoads == 1)
    val sameStopped = PlaybackHarness("A", true, false)
    sameStopped.intent(true, "A")
    sameStopped.start()
    check(sameStopped.paused && sameStopped.forceLoads == 0)
    val superseded = PlaybackHarness("A", true, false)
    superseded.intent(true, "B")
    superseded.intent(true, "A")
    superseded.start()
    check(superseded.paused && superseded.forceLoads == 0)
    println("PASS: distinct VIEW intent, same media, paused and stopped scheduling")
}
