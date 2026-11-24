package app.novavpn.domain.model

data class PsiphonRegionInfo(
    val code: String,
    val nameEn: String,
    val nameFa: String,
    val popular: Boolean = false
)

object PsiphonRegions {
    val ALL: List<PsiphonRegionInfo> = listOf(
        PsiphonRegionInfo("AUTO", "Best Auto", "خودکار (بهترین)", popular = true),
        PsiphonRegionInfo("DE", "Germany", "آلمان", popular = true),
        PsiphonRegionInfo("NL", "Netherlands", "هلند", popular = true),
        PsiphonRegionInfo("US", "United States", "آمریکا", popular = true),
        PsiphonRegionInfo("GB", "United Kingdom", "انگلستان", popular = true),
        PsiphonRegionInfo("CA", "Canada", "کانادا", popular = true),
        PsiphonRegionInfo("FR", "France", "فرانسه", popular = true),
        PsiphonRegionInfo("CH", "Switzerland", "سوئیس", popular = true),
        PsiphonRegionInfo("SE", "Sweden", "سوئد", popular = true),
        PsiphonRegionInfo("FI", "Finland", "فنلاند", popular = true),
        PsiphonRegionInfo("SG", "Singapore", "سنگاپور", popular = true),
        PsiphonRegionInfo("JP", "Japan", "ژاپن", popular = true),
        PsiphonRegionInfo("AT", "Austria", "اتریش"),
        PsiphonRegionInfo("AU", "Australia", "استرالیا"),
        PsiphonRegionInfo("BE", "Belgium", "بلژیک"),
        PsiphonRegionInfo("BG", "Bulgaria", "بلغارستان"),
        PsiphonRegionInfo("BR", "Brazil", "برزیل"),
        PsiphonRegionInfo("CZ", "Czechia", "جمهوری چک"),
        PsiphonRegionInfo("DK", "Denmark", "دانمارک"),
        PsiphonRegionInfo("EE", "Estonia", "استونی"),
        PsiphonRegionInfo("ES", "Spain", "اسپانیا"),
        PsiphonRegionInfo("HR", "Croatia", "کرواسی"),
        PsiphonRegionInfo("HU", "Hungary", "مجارستان"),
        PsiphonRegionInfo("IE", "Ireland", "ایرلند"),
        PsiphonRegionInfo("IN", "India", "هند"),
        PsiphonRegionInfo("IT", "Italy", "ایتالیا"),
        PsiphonRegionInfo("LV", "Latvia", "لتونی"),
        PsiphonRegionInfo("NO", "Norway", "نروژ"),
        PsiphonRegionInfo("PL", "Poland", "لهستان"),
        PsiphonRegionInfo("RO", "Romania", "رومانی"),
        PsiphonRegionInfo("RS", "Serbia", "صربستان"),
        PsiphonRegionInfo("SK", "Slovakia", "اسلواکی"),
        PsiphonRegionInfo("UA", "Ukraine", "اوکراین")
    )

    val POPULAR: List<PsiphonRegionInfo> = ALL.filter { it.popular }

    fun find(code: String?): PsiphonRegionInfo {
        val norm = code?.trim()?.uppercase().orEmpty()
        return ALL.firstOrNull { it.code == norm } ?: ALL.first()
    }
}
