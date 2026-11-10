package org.videolan.vlc.gui.video

fun main() {
    val tracks = listOf(GostexTrack("10", "LF - [Russian]", "rus"), GostexTrack("20", "HDr 18+ - [Russian]", "rus"), GostexTrack("-1", "Disabled", null))
    check(gostexMatchingTrack(GostexTrackChoice(false, "HDr 18+ - [Russian]", "rus"), tracks) == "20")
    check(gostexMatchingTrack(GostexTrackChoice(false, " Hdr 18+ - [russian] ", "rus"), tracks) == "20")
    check(gostexMatchingTrack(GostexTrackChoice(false, "HDr 18+ - [Russian]", "eng"), tracks) == null)
    check(gostexMatchingTrack(GostexTrackChoice(false, "HDr 18+ - [Russian]", "rus"), tracks + GostexTrack("30", "HDr 18+ - [Russian]", "rus")) == null)
    check(gostexMatchingTrack(GostexTrackChoice(false, "Unknown", null), tracks) == null)
    check(gostexMatchingTrack(GostexTrackChoice(false, "", null), tracks) == null)
    check(gostexMatchingTrack(GostexTrackChoice(true, "", null), tracks) == "-1")
    check(gostexMatchingTrack(GostexTrackChoice(false, "HDr 18+ - [Russian]", "rus"), listOf(GostexTrack("999", "HDr 18+ - [Russian]", "rus"))) == "999")
    println("PASS: exact track matching, ambiguous tracks, missing tracks and disabled subtitles")
}
