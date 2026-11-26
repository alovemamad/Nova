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
enum class GeoResourceFormat {
    UNKNOWN,
    V2RAY_GEOIP,
    V2RAY_GEOSITE,
    TEXT_RULES
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
    val lastError: String? = null,
    val format: GeoResourceFormat = GeoResourceFormat.UNKNOWN,
    val entryCount: Int = 0,
    val sampleTags: List<String> = emptyList()
)

data class GeoBatchUpdateSummary(
    val totalUrlResources: Int,
    val updatedCount: Int,
    val failedCount: Int,
    val skippedDuplicate: Boolean = false
)
