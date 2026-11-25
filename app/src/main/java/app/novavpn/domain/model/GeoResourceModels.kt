package app.novavpn.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class GeoSourceType {
    LOCAL_FILE,
    URL
}

@Serializable
enum class GeoResourceStatus {
    READY,
    UPDATING,
    ERROR
}

@Serializable
data class GeoResourceItem(
    val id: String,
    val name: String,
    val sourceType: GeoSourceType,
    val sourceUrl: String? = null,
    val localFileName: String,
    val sizeBytes: Long = 0L,
    val sha256: String = "",
    val lastUpdatedMs: Long = 0L,
    val status: GeoResourceStatus = GeoResourceStatus.READY,
    val lastError: String? = null
)

data class GeoBatchUpdateSummary(
    val totalUrlResources: Int,
    val updatedCount: Int,
    val failedCount: Int,
    val skippedDuplicate: Boolean = false
)
