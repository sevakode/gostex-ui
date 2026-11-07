package tv.orbit.shield

import java.util.Locale

/** Display names never change file identity or the order returned by TorrServer. */
internal fun mediaFileLabels(files: List<TorrCatalogFile>): List<String> {
    fun key(value: String) = value.lowercase(Locale.ROOT)
    val names = files.map { file -> file.path.substringAfterLast('/').ifBlank { file.path } }
    val nameCounts = names.groupingBy(::key).eachCount()
    val candidates = files.indices.map { index ->
        if (nameCounts.getValue(key(names[index])) > 1) files[index].path else names[index]
    }
    val labelCounts = candidates.groupingBy(::key).eachCount()
    return candidates.mapIndexed { index, label ->
        if (labelCounts.getValue(key(label)) > 1) "$label · ${files[index].id}" else label
    }
}
