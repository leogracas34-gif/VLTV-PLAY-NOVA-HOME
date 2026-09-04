package com.vltv.play

/**
 * Modelo de dados para os itens da tela de Novidades.
 * TMDB puro — disponibilidade no servidor verificada no adapter em paralelo.
 */
data class NovidadeItem(
    val idTMDB: Int,
    val stream_id: Int = 0,       // preenchido pelo adapter se achar no banco (VodEntity)
    val series_id: Int = 0,       // preenchido pelo adapter se achar no banco (SeriesEntity)
    val titulo: String,
    val sinopse: String,
    val imagemFundoUrl: String,   // backdrop (Bombando/Top10) ou poster (Em Breve)
    val tagline: String,          // data de estreia ou posição Top 10
    val isSerie: Boolean = false,
    val isEmBreve: Boolean = false,
    val isTop10: Boolean = false,
    val posicaoTop10: Int = 0
)
