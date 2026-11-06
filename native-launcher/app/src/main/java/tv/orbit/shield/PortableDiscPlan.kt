package tv.orbit.shield

/** Files to copy for each selected PS1 launch entry. The first file in each list
 * is the launch descriptor/image; other files are required companions, with paths
 * preserved exactly relative to the same source root. */
internal data class PortableDiscPlan(val selected: Map<String, List<DiscImport.File>>, val files: List<DiscImport.File>)

internal object PortableDiscPlanner {
    fun build(files: List<DiscImport.File>, selectedPaths: List<String>, readText: (DiscImport.File) -> String,
              checkpoint: () -> Unit = {}, providerSupported: Boolean = true): PortableDiscPlan {
        require(files.size <= 10000 && selectedPaths.size <= 8192 && selectedPaths.distinct().size == selectedPaths.size)
        if (selectedPaths.isEmpty()) return PortableDiscPlan(emptyMap(), emptyList())
        val inspected = DiscImport.inspect(files, readText, checkpoint, providerSupported,
            includeDependencies = true, pathsToInspect = selectedPaths.toSet())
        val selected = linkedMapOf<String, List<DiscImport.File>>()
        val uniqueFiles = linkedMapOf<String, DiscImport.File>()
        val pathsById = mutableMapOf<String, String>()
        for (path in selectedPaths) {
            checkpoint()
            val dependencies = inspected.dependencies[path] ?: throw IllegalArgumentException("Selected disc is missing or invalid")
            require(dependencies.isNotEmpty() && dependencies.first().path == path)
            for (file in dependencies) {
                require(file.id.isNotBlank() && PortablePath.fromComponents(file.path.split('/')) != null)
                val previous = pathsById.putIfAbsent(file.id, file.path)
                require(previous == null || previous == file.path) { "Ambiguous document identity" }
                uniqueFiles.putIfAbsent(file.path, file)
            }
            selected[path] = dependencies.toList()
        }
        return PortableDiscPlan(selected.toMap(), uniqueFiles.values.toList())
    }
}
