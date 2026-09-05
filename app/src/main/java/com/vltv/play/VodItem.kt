package com.vltv.play

// Modelo simples para suportar tanto Firebase quanto Xtream
data class VodItem(
    val id: String = "",
    val name: String = "",
    val streamIcon: String = "",
    val containerExtension: String = "mp4",
    val rating: String = "",
    val isNovidade: Boolean = false,
    val isTop10: Boolean = false,
    val logoUrl: String? = null,
    // ✅ NOVO: texto do selo de painel a exibir no card — "NOVIDADE",
    // "NOVO EPISÓDIO", "NOVA TEMPORADA", ou null se não há selo ativo.
    // Ver BadgeExtensions.kt / PainelBadgeSync.kt. Mantido separado de
    // isNovidade (que segue existindo por compatibilidade) porque agora um
    // mesmo item pode ter um entre 3 textos diferentes, não só sim/não.
    val badgeLabel: String? = null
)
