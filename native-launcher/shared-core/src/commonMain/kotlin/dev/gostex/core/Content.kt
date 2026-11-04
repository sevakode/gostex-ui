package dev.gostex.core

/** Content identity is opaque; adapters retain the source's stable identifier. */
enum class ContentKind { GAME, APP, MEDIA }

/** Data describing an action. Only a platform adapter may execute it. */
sealed interface LaunchTarget {
    /** Arguments are separate values, never a shell command to concatenate or interpret. */
    data class Process(
        val executable: String,
        val arguments: List<String> = emptyList(),
        val workingDirectory: String? = null
    ) : LaunchTarget
}

data class ContentItem(
    val id: String,
    val title: String,
    val description: String,
    val kind: ContentKind,
    val target: LaunchTarget
)

/** Existing Android card identities must survive future adapters without translation. */
fun gameContentKey(id: String): String = "game:$id"

fun appContentKey(packageName: String): String = "app:$packageName"
