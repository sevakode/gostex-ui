package tv.orbit.shield

/** A name and size alone do not identify a grouped download after a crash. */
internal fun torrCanRecoverRenamed(
    originalId: String, candidateId: String, expectedName: String, candidateName: String,
    partialPresent: Boolean,
): Boolean = !partialPresent && candidateName == expectedName &&
    originalId.isNotBlank() && originalId == candidateId
