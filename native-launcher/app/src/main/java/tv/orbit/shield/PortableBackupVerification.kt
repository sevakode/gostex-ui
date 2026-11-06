package tv.orbit.shield

internal data class PortableBackupInspection(
    val committed: PortableCommittedSnapshot,
    val content: PortableContentVerification,
)

/** Validate the complete metadata generation first; then inspect referenced files.
 * Missing files remain explicit issues; this never restores or modifies live data. */
internal fun inspectPortableBackup(
    stateDirectory: PortableStagingDirectory,
    contentTree: PortableContentTree,
    commitDocumentId: String,
    checkActive: () -> Unit = {},
    onProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
): PortableBackupInspection {
    val committed = PortableSnapshotStore.readCommitted(stateDirectory, commitDocumentId, checkActive)
    val content = PortableContentVerifier.verify(contentTree, committed.snapshot.index, checkActive, onProgress)
    return PortableBackupInspection(committed, content)
}
