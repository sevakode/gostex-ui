package tv.orbit.shield

/** A comparison, never a promise: codecs, peak rates, peers and disk remain independent. */
internal data class MoviePlaybackEstimate(
    val averageBitsPerSecond: Double,
    val peerBitsPerSecond: Double?,
    val assessment: PeerSpeedAssessment,
)
internal enum class PeerSpeedAssessment { UNKNOWN, BELOW_AVERAGE, LOW_MARGIN, HEADROOM }

internal object MoviePlaybackEstimator {
    private const val HEADROOM_FACTOR = 1.5

    fun estimate(sizeBytes: Long, durationSeconds: Long, peerBitsPerSecond: Double?): MoviePlaybackEstimate? {
        if (sizeBytes <= 0 || durationSeconds <= 0) return null
        val average = sizeBytes.toDouble() * 8.0 / durationSeconds.toDouble()
        if (!average.isFinite() || average <= 0) return null
        val speed = peerBitsPerSecond?.takeIf { it.isFinite() && it >= 0 }
        val assessment = when {
            speed == null -> PeerSpeedAssessment.UNKNOWN
            speed < average -> PeerSpeedAssessment.BELOW_AVERAGE
            speed < average * HEADROOM_FACTOR -> PeerSpeedAssessment.LOW_MARGIN
            else -> PeerSpeedAssessment.HEADROOM
        }
        return MoviePlaybackEstimate(average, speed, assessment)
    }
}
