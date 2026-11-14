/* Copyright (C) 2026 GOSTEX contributors. GPL-2.0-or-later. */
package org.videolan.vlc.gui.video

// Host simulation invokes the production guard. It does not execute Android or LibVLC callbacks.
private class TrackHarness {
    val guard = GostexTrackSelectionGuard()
    var audio = "default-audio"
    var subtitle = "default-subtitle"
    fun pending(audio: Boolean, result: String): () -> Unit {
        val token = guard.snapshot()
        return {
            if (guard.mayApply(audio, token)) {
                if (audio) this.audio = result else subtitle = result
            }
        }
    }
    fun manual(audio: Boolean, result: String) {
        guard.choose(audio)
        if (audio) this.audio = result else subtitle = result
    }
}

fun main() {
    run {
        val h = TrackHarness()
        val oldAudio = h.pending(true, "old-audio")
        val oldSubtitle = h.pending(false, "old-subtitle")
        h.manual(true, "user-audio")
        oldAudio(); oldSubtitle()
        check(h.audio == "user-audio" && h.subtitle == "old-subtitle")
        h.manual(false, "off")
        h.pending(false, "late-subtitle")()
        check(h.subtitle == "off")
    }
    run {
        val h = TrackHarness()
        val audio = h.pending(true, "media-A-audio")
        val subtitle = h.pending(false, "media-A-subtitle")
        h.guard.newMedia()
        h.audio = "media-B-audio"; h.subtitle = "media-B-subtitle"
        audio(); subtitle()
        check(h.audio == "media-B-audio" && h.subtitle == "media-B-subtitle")
        h.pending(true, "media-B-restored")()
        check(h.audio == "media-B-restored")
    }
    run {
        val h = TrackHarness()
        val oldRestore = h.pending(false, "saved-subtitle")
        h.manual(false, "external-subtitle")
        oldRestore()
        check(h.subtitle == "external-subtitle")
        h.guard.newMedia()
        h.pending(false, "next-media-preference")()
        check(h.subtitle == "next-media-preference")
    }
    println("PASS: late discovery, independent manual choice, old media and restoration event sequences")
}
