package com.honor.share.protocol

data class DestinationFile(
    val name: String,
    val size: Long,
    val sha256: String?,
    val relativePath: String = name,
)

enum class ConflictAction {
    REPLACE,
    KEEP_BOTH,
    SKIP,
}

data class FileConflict(
    val incoming: PackageFile,
    val existingName: String,
)

data class ComparisonResult(
    val alreadyPresent: List<PackageFile>,
    val needsTransfer: List<PackageFile>,
    val conflicts: List<FileConflict>,
) {
    val skipFileIds: List<String> get() = alreadyPresent.map { it.fileId }
    val neededBytes: Long get() = needsTransfer.sumOf { it.size } + conflicts.sumOf { it.incoming.size }
    val filesTotal: Int get() = alreadyPresent.size + needsTransfer.size + conflicts.size

    fun withResolutions(actions: Map<String, ConflictAction>): ComparisonResult {
        if (conflicts.isEmpty()) return this
        val extraSkip = mutableListOf<PackageFile>()
        val extraTransfer = mutableListOf<PackageFile>()
        val remaining = mutableListOf<FileConflict>()
        for (conflict in conflicts) {
            when (actions[conflict.incoming.fileId]) {
                ConflictAction.SKIP -> extraSkip += conflict.incoming.copy(status = PackageFileStatus.SKIPPED)
                ConflictAction.REPLACE, ConflictAction.KEEP_BOTH -> extraTransfer += conflict.incoming
                null -> remaining += conflict
            }
        }
        return ComparisonResult(
            alreadyPresent = alreadyPresent + extraSkip,
            needsTransfer = needsTransfer + extraTransfer,
            conflicts = remaining,
        )
    }
}

object ComparisonEngine {
    /**
     * Identity is cryptographic when hashes exist. Filename alone never proves equality.
     * A destination file matches an incoming file only when both SHA-256 hashes are present and equal.
     * Same name + different or missing hash is a conflict, not a skip.
     */
    fun compare(incoming: List<PackageFile>, destination: List<DestinationFile>): ComparisonResult {
        val destByHash = destination
            .mapNotNull { file -> file.sha256?.lowercase()?.let { it to file } }
            .toMap()
        val destByName = destination.associateBy { it.name }
        val already = mutableListOf<PackageFile>()
        val needs = mutableListOf<PackageFile>()
        val conflicts = mutableListOf<FileConflict>()
        val claimed = mutableSetOf<String>()

        for (file in incoming) {
            val hash = file.hash?.lowercase()
            val destMatch = if (hash != null) destByHash[hash] else null
            if (destMatch != null && destMatch.name !in claimed) {
                already += file.copy(status = PackageFileStatus.SKIPPED)
                claimed += destMatch.name
                continue
            }
            val sameName = destByName[file.name]
            if (sameName != null && sameName.name !in claimed) {
                val sameHash = hash != null && sameName.sha256 != null &&
                    Checksums.equalsHex(hash, sameName.sha256)
                if (sameHash) {
                    already += file.copy(status = PackageFileStatus.SKIPPED)
                    claimed += sameName.name
                } else {
                    conflicts += FileConflict(file, sameName.name)
                    claimed += sameName.name
                }
            } else {
                needs += file
            }
        }
        return ComparisonResult(already, needs, conflicts)
    }
}
