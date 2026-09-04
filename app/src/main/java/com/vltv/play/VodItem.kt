package com.vltv.play

// Modelo simples para suportar tanto Firebase quanto Xtream
data class VodItem(
    val id: String = "",
    val name: String = "",
    val streamIcon: String = "",
    val containerExtension: String = "mp4",
    val rating: String = "",
    val isNovidade: Boolean = false,
    val isTop10: Boolean = false
)
