package com.vltv.play.retro

import com.google.gson.annotations.SerializedName

/**
 * Representa um jogo retrô vindo do catálogo (games.json) hospedado na VPS.
 */
data class RetroGame(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("console") val console: String,
    @SerializedName("core") val core: String,
    @SerializedName("rom") val romUrl: String,
    @SerializedName("cover") val coverUrl: String?,
    @SerializedName("tier") val tier: String? = null,
    // ✅ NOVO: só é usado quando core = "psx" (PS1). É a URL do arquivo de
    // BIOS na VPS (ex: https://cdn.vltvplay.tech/retro/bios/scph5501.bin),
    // exigido pelo core do EmulatorJS pra rodar jogos de PS1. Pra qualquer
    // outro console, esse campo fica null e é simplesmente ignorado.
    @SerializedName("bios") val bios: String? = null
) {
    val tierEfetivo: String
        get() = tier?.lowercase()?.trim()?.takeIf { it.isNotBlank() } ?: "basico"
}
