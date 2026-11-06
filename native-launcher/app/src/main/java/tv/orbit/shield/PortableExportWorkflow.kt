package tv.orbit.shield

internal data class PortableExportResult(val snapshotId: String, val commitDocumentId: String,
                                         val generationName: String, val fileCount: Int, val bytes: Long)

/** Export orchestration. This entry point is synchronous and must run off the UI
 * thread under a shared export lock. Source metadata and bytes are rechecked before
 * commit; this still cannot lock files or prevent later changes, dishonest providers,
 * or concurrent ABA changes. */
internal object PortableExportWorkflow {
    fun export(live: PortableLiveLibrary, recapture: () -> PortableLiveLibrary,
               sources: Map<String, PortablePlanningSource>, coverSource: (String) -> PortablePlanningCover,
               contentTree: PortableWritableTree, stateDirectory: PortableStagingDirectory,
               checkActive: () -> Unit = {}, onSourceVerification: (Int, Int) -> Unit = { _, _ -> },
               onFile: (String, Int, Int) -> Unit = { _, _, _ -> }): PortableExportResult {
        checkActive()
        val plan = PortableSourcePlanner.prepare(live, sources, coverSource, checkActive)
        val id = PortableSnapshotCodec.newId()
        // Validate coverage, descriptor bindings, metadata and privacy before copying bytes.
        val placeholderName = "gostex-content-" + "0".repeat(32)
        val coverHashes = plan.covers.entries.associate { (key, fileId) ->
            fileId to (managedCoverDigest(key) ?: error("Invalid cover reference"))
        }
        val placeholder = PortableGeneratedContent("preflight", placeholderName, plan.inputs.map { input ->
            PortableCopiedContent(input.id, listOf(placeholderName) + input.path, "preflight",
                PortableCopyReceipt(input.expectedBytes, coverHashes[input.id] ?: "0".repeat(64)))
        })
        PortableSnapshotAssembler.assemble(id, live, placeholder, plan.variants, plan.covers)
        val copied = PortableGenerationWriter.copy(contentTree, plan.inputs, plan.protectedDocumentIds,
            plan.validate, checkActive, onFile = onFile)
        val snapshot = PortableSnapshotAssembler.assemble(id, live, copied, plan.variants, plan.covers)
        val total = copied.files.fold(0L) { sum, file -> Math.addExact(sum, file.receipt.bytes) }
        val receipts = copied.files.associate { it.id to it.receipt }
        val commit = PortableSnapshotStore.write(stateDirectory, snapshot, checkActive, beforeCommit = {
            check(recapture() == live) { "Library changed during export" }
            plan.inputs.forEachIndexed { index, input ->
                checkActive()
                onSourceVerification(index, plan.inputs.size)
                plan.validate(input)
                portableVerifySourceMatches(input.openSource, receipts.getValue(input.id), checkActive)
                plan.validate(input)
            }
        })
        return PortableExportResult(id, commit.documentId, copied.generationName, copied.files.size, total)
    }
}
