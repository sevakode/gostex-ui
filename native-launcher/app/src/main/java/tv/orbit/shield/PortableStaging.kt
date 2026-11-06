package tv.orbit.shield

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal data class PortableStagingEntry(val id: String, val name: String, val mime: String)

/** The provider must implement SAF's create-new-document contract faithfully. */
internal interface PortableStagingDirectory {
    fun children(): List<PortableStagingEntry>
    fun create(name: String, mime: String): PortableStagingEntry
    fun isChild(id: String): Boolean
    fun openWrite(id: String): OutputStream
    fun openRead(id: String): InputStream
}
internal data class PortableStagedFile(val documentId: String, val name: String, val receipt: PortableCopyReceipt)

/** Create a fresh temporary document and verify the copy before returning it.
 * Never opens a previously listed or protected document for writing. The caller
 * owns failed staging documents: this function deliberately never deletes them.
 * No previous backup is changed and this is not a committed manifest. */
internal fun portableStageFile(
    directory: PortableStagingDirectory,
    openSource: () -> InputStream,
    maxBytes: Long,
    protectedDocumentIds: Set<String>,
    checkActive: () -> Unit = {},
    newName: () -> String = { "gostex-stage-${UUID.randomUUID().toString().replace("-", "")}.tmp" },
): PortableStagedFile {
    require(maxBytes >= 0) { "Negative copy limit" }
    checkActive()
    val before = directory.children()
    check(before.map { it.id }.distinct().size == before.size) { "Ambiguous destination directory" }
    val name = newName()
    require(name.matches(Regex("gostex-(?:stage-[a-f0-9]{32}\\.tmp|(?:state|commit)-[a-f0-9]{32}\\.json)"))) { "Invalid staging name" }
    check(before.none { it.name == name }) { "Staging name already exists" }
    checkActive()
    val created = directory.create(name, "application/octet-stream")
    check(created.id.isNotBlank() && created.name == name && created.mime != "vnd.android.document/directory") {
        "Provider returned an unexpected document"
    }
    check(created.id !in protectedDocumentIds && before.none { it.id == created.id }) { "Provider returned an existing document" }
    check(directory.isChild(created.id)) { "Provider returned a document outside the selected folder" }
    val matches = directory.children().filter { it.name == name }
    check(matches.size == 1 && matches.single().id == created.id) { "Ambiguous staged document" }
    checkActive()
    val destination = object : PortableCopyDestination {
        override fun openNew() = directory.openWrite(created.id)
        override fun openForVerification() = directory.openRead(created.id)
    }
    val receipt = portableVerifiedCopy(openSource, destination, maxBytes, checkActive)
    return PortableStagedFile(created.id, name, receipt)
}
