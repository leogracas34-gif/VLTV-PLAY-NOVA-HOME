package com.vltv.play

// Modelo simples para suportar tanto Firebase quanto Xtream
data class VodItem(
    val id: String = "",
    val name: String = "",
    val streamIcon: String = "",
    val containerExtension: String = "mp4",
    val rating: String = "",
    // ✅ NOVO: flags pros selos da Home (estilo Netflix). isTop10/
    // isNovidade valem pra filme e série; as outras três só existem pra
    // série (filme não tem temporada/episódio).
    val isSerie: Boolean = false,
    val isTop10: Boolean = false,
    val isNovidade: Boolean = false,
    val isNovaTemporada: Boolean = false,
    val isNovoEpisodio: Boolean = false,
    val isNovaTemporadaEmBreve: Boolean = false
)
