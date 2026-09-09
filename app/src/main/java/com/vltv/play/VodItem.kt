package com.vltv.play

// Modelo simples para suportar tanto Firebase quanto Xtream
data class VodItem(
    val id: String = "",
    val name: String = "",
    val streamIcon: String = "",
    val containerExtension: String = "mp4",
    val rating: String = "",
    // ✅ flags pros selos da Home (estilo Netflix). isTop10/isNovidade
    // valem pra filme e série; as outras quatro só existem pra série
    // (filme não tem temporada/episódio).
    val isSerie: Boolean = false,
    val isTop10: Boolean = false,
    val isNovidade: Boolean = false,
    val isNovaTemporada: Boolean = false,
    val isNovoEpisodio: Boolean = false,
    val isNovaTemporadaEmBreve: Boolean = false,
    // ✅ NOVO: "Novo Episódio Em Breve" — o próximo episódio anunciado é
    // da MESMA temporada já em andamento. Diferente de
    // isNovaTemporadaEmBreve, que é quando o próximo episódio já
    // pertence a uma temporada nova (ex: Reacher solta episódio semanal
    // → este selo; Lanternas encerra temporada e anuncia a próxima →
    // isNovaTemporadaEmBreve).
    val isNovoEpisodioEmBreve: Boolean = false,
    // ✅ logo (clearlogo) do título vinda do TMDB, usada no banner de
    // destaque e nos cards das fileiras por baixo do pôster.
    val logoUrl: String? = null,
    // ✅ progresso assistido em porcentagem (0-100), usado só no card
    // largo de "Continuar Assistindo". -1 = sem barra de progresso.
    val progressoAssistido: Int = -1
)
