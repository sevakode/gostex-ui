package tv.orbit.shield

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** Each torrent gets a private-named directory inside the user's chosen tree. */
internal object TorrDownloadGroups {
    private const val KEY = "downloads.torrserver.groups"
    private data class Child(val id: String, val name: String, val mime: String)

    private fun children(context: Context, tree: Uri, parent: String): List<Child> {
        val columns = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME, Docs.Document.COLUMN_MIME_TYPE)
        return context.contentResolver.query(Docs.buildChildDocumentsUriUsingTree(tree, parent), columns, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    if (size >= 10000) throw IOException("Too many documents")
                    add(Child(c.getString(0) ?: throw IOException("Missing document ID"), c.getString(1) ?: "", c.getString(2) ?: ""))
                }
            }.also { list -> if (list.map { it.id }.distinct().size != list.size) throw IOException("Ambiguous document IDs") }
        } ?: throw IOException("Cannot inspect destination")
    }

    private fun createDirectory(context: Context, tree: Uri, parentId: String, name: String, before: List<Child>): String {
        if (before.any { it.name.equals(name, ignoreCase = true) }) throw IOException("Destination already exists")
        val parent = Docs.buildDocumentUriUsingTree(tree, parentId)
        val created = Docs.createDocument(context.contentResolver, parent, Docs.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Cannot create directory")
        if (created.authority != tree.authority) throw IOException("Unexpected provider")
        val id = Docs.getDocumentId(created)
        if (before.any { it.id == id } || !portableSafDirectChild(context.contentResolver, tree, parentId, id)) throw IOException("Unexpected directory")
        val matches = children(context, tree, parentId).filter { it.name.equals(name, ignoreCase = true) }
        if (matches.size != 1 || matches.single().id != id || matches.single().name != name || matches.single().mime != Docs.Document.MIME_TYPE_DIR) {
            throw IOException("Ambiguous directory")
        }
        return id
    }

    /** Returns the document ID of the parent directory for the torrent file. */
    @Synchronized fun parent(context: Context, tree: Uri, entry: TorrCatalogEntry, parts: List<String>): String {
        require(parts.isNotEmpty())
        val prefs = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
        val groups = try { JSONArray(prefs.getString(KEY, "[]")) } catch (e: Exception) { throw IOException("Cannot read download groups", e) }
        if (groups.length() > 1000) throw IOException("Too many download groups")
        val treeText = tree.toString()
        val rootId = Docs.getTreeDocumentId(tree)
        val indices = (0 until groups.length()).filter { index ->
            val record = groups.getJSONObject(index)
            record.optString("tree") == treeText && record.optString("hash").equals(entry.hash, ignoreCase = true)
        }
        if (indices.size > 1) throw IOException("Ambiguous download group")
        val recordIndex = indices.singleOrNull()
        val record = recordIndex?.let { groups.getJSONObject(it) }
        val rootChildren = children(context, tree, rootId)
        val storedId = record?.getString("id")
        val storedName = record?.getString("name")
        val existing = rootChildren.filter { it.id == storedId && it.name == storedName && it.mime == Docs.Document.MIME_TYPE_DIR }
        val createNew = if (record == null) true else if (existing.size == 1 && portableSafDirectChild(context.contentResolver, tree, rootId, storedId!!)) false else {
            // A missing group can be recreated only after all unfinished jobs are resolved.
            // Do not reinterpret a different child as the original or touch moved data.
            val conflicts = rootChildren.any { it.id == storedId || it.name.equals(storedName, ignoreCase = true) }
            val unfinished = TorrDownloadStore.jobs.value.any {
                it.tree == treeText && it.hash.equals(entry.hash, ignoreCase = true) && it.state != "done"
            }
            if (conflicts || unfinished) throw IOException("Download folder was moved or still has unfinished jobs")
            true
        }
        var directoryId = if (createNew) {
            if (record == null && groups.length() >= 1000) throw IOException("Too many download groups")
            val suffix = UUID.randomUUID().toString()
            val title = entry.title.map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }.joinToString("")
                .trim().trim('.').take(45).trimEnd { Character.isHighSurrogate(it) }.ifBlank { "torrent" }
            val name = "$title [GOSTEX-$suffix]"
            val id = createDirectory(context, tree, rootId, name, rootChildren)
            val updated = JSONObject().put("tree", treeText).put("hash", entry.hash.lowercase()).put("id", id).put("name", name)
            if (recordIndex == null) groups.put(updated) else groups.put(recordIndex, updated)
            // If this fails, the empty directory is left alone rather than risking user files.
            if (!prefs.edit().putString(KEY, groups.toString()).commit()) throw IOException("Cannot save download group")
            id
        } else {
            storedId!!
        }
        for (component in parts.dropLast(1)) {
            val before = children(context, tree, directoryId)
            val matches = before.filter { it.name.equals(component, ignoreCase = true) }
            directoryId = when {
                matches.isEmpty() -> createDirectory(context, tree, directoryId, component, before)
                matches.size == 1 && matches.single().name == component && matches.single().mime == Docs.Document.MIME_TYPE_DIR &&
                    portableSafDirectChild(context.contentResolver, tree, directoryId, matches.single().id) -> matches.single().id
                else -> throw IOException("Destination directory conflict")
            }
        }
        return directoryId
    }
}
