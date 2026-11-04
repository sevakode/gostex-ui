package tv.orbit.shield

import kotlin.random.Random

/** The painting supplies its eligible treatments; the program never applies an unrelated effect. */
internal class ArtEffectSequence(seed: Long) {
    private val sessionSeed = seed
    private val calm = setOf(RevealStyle.SCENE, RevealStyle.INK, RevealStyle.TILES, RevealStyle.FOCUS, RevealStyle.STILL)

    fun at(mode: RevealStyle, scene: Long, direction: ArtDirection): ArtTreatment {
        require(!direction.isInterlude) { "An independent act has no painting reveal" }
        val eligible = when (mode) {
            RevealStyle.MIXED -> direction.treatments
            RevealStyle.CALM -> direction.treatments.filter { it.style in calm }.ifEmpty { listOf(ArtTreatment()) }
            else -> return direction.treatments.firstOrNull { it.style == mode } ?: when (mode) {
                RevealStyle.FOCUS, RevealStyle.BLOCKS, RevealStyle.WEAVE -> ArtTreatment(mode, 32, 18)
                else -> ArtTreatment(mode.takeIf { it in ArtTreatment.PAINTING_STYLES } ?: RevealStyle.SCENE)
            }
        }
        val random = Random(sessionSeed xor (scene * -7046029254386353131L) xor direction.hashCode().toLong())
        return eligible[random.nextInt(eligible.size)]
    }
}

/** Three paintings followed by an independent act; disabling acts retains every painting slot. */
internal data class ArtProgramPosition(val painting: Long, val interlude: Long?) {
    companion object {
        fun at(scene: Long, includeInterludes: Boolean): ArtProgramPosition {
            require(scene >= 0)
            if (!includeInterludes) return ArtProgramPosition(scene, null)
            val group = scene / 4
            return if (scene % 4 == 3L) ArtProgramPosition(scene - group - 1, group)
                else ArtProgramPosition(scene - group, null)
        }
    }
}
