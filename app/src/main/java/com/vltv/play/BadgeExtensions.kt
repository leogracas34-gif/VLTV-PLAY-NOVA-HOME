package com.vltv.play

import com.vltv.play.data.SeriesEntity
import com.vltv.play.data.VodEntity

/**
 * Texto do selo pra exibir no card, ou null se não há selo ativo agora.
 * Usa PainelBadgeSync.badgeVigente() pra respeitar o prazo de validade
 * (o selo "expira" sozinho, sem precisar de nenhuma rotina de limpeza).
 */
fun VodEntity.badgeAtual(): String? {
    val ativo = PainelBadgeSync.badgeVigente(badge_type, badge_timestamp) ?: return null
    return if (ativo == "novidade") "NOVIDADE" else null
}

fun SeriesEntity.badgeAtual(): String? {
    val ativo = PainelBadgeSync.badgeVigente(badge_type, badge_timestamp) ?: return null
    return when (ativo) {
        "novidade" -> "NOVIDADE"
        "novo_episodio" -> "NOVO EPISÓDIO"
        "nova_temporada" -> "NOVA TEMPORADA"
        else -> null
    }
}
