package com.vltv.play

import androidx.room.withTransaction
import com.vltv.play.data.AppDatabase
import org.json.JSONObject
import java.net.URL

/**
 * PainelBadgeSync
 *
 * Motor das faixas "Novidade", "Novo Episódio" e "Nova Temporada" que
 * aparecem nos cards da Home — estilo Netflix, mas baseadas no que
 * realmente ENTROU/MUDOU NO SEU PAINEL (Xtream), e não na data de
 * lançamento do TMDB (isso já existe separadamente em TmdbSyncHelper e
 * continua controlando só o selo "TOP 10").
 *
 * ─────────────────────────────────────────────────────────────────────────
 * COMO FUNCIONA
 *
 * FILMES (VOD) — "Novidade":
 *   Na primeira vez que um stream_id aparece numa sincronização, gravamos
 *   first_seen_at = agora e badge_type = "novidade". Da próxima vez que
 *   esse mesmo stream_id aparecer, ele já tem first_seen_at != 0 e é
 *   ignorado — ou seja, o selo só "nasce" uma vez, na entrada real no
 *   painel, e desaparece sozinho depois de BADGE_TTL_MS (ver badgeVigente).
 *
 * SÉRIES — "Novidade" / "Novo Episódio" / "Nova Temporada":
 *   1. Série nova no painel (nunca vista antes) → badge_type = "novidade".
 *   2. Série já conhecida, mas o "last_modified" que a API do Xtream
 *      devolve mudou desde a última vez que conferimos → é candidata a
 *      episódio/temporada nova. Pra essas, chamamos get_series_info (uma
 *      chamada por série) e comparamos a contagem de temporadas e de
 *      episódios com a última contagem conhecida:
 *        - temporadas aumentaram → "nova_temporada"
 *        - só episódios aumentaram → "novo_episodio"
 *      Isso é limitado a MAX_SERIES_INFO_POR_CICLO chamadas por
 *      sincronização (a sync roda a cada 10 min — o que sobrar continua
 *      marcado como pendente e é resolvido nos próximos ciclos), pra não
 *      pesar nem no app nem no servidor do painel.
 *
 * Os campos ficam guardados diretamente em vod_streams/series_streams
 * (ver AppDatabase.kt) e SOBREVIVEM às re-sincronizações de catálogo, porque
 * este arquivo sempre lê o estado atual do banco ANTES de decidir o que
 * mudou, e o SyncManager chama isso logo depois de inserir os lotes de
 * VOD/série (nunca antes) — a ideia é a mesma já usada pelo TmdbSyncHelper
 * pro Top 10/Novidades por TMDB, só que agora pro selo de painel.
 * ─────────────────────────────────────────────────────────────────────────
 */
object PainelBadgeSync {

    // Depois desse tempo, o selo para de aparecer sozinho — não precisa de
    // nenhuma rotina de limpeza, é só um cálculo na hora de exibir
    // (badgeVigente). 21 dias é o mesmo padrão usado por vários serviços de
    // streaming para o selo "Novidade".
    private const val BADGE_TTL_MS = 21L * 24 * 60 * 60 * 1000L

    // Limite de chamadas get_series_info por ciclo de sincronização — evita
    // pesar o app/servidor quando muitas séries mudam de uma vez (ex: logo
    // após reindexar o painel inteiro). O restante é resolvido nos próximos
    // ciclos automáticos (a cada 10 min).
    private const val MAX_SERIES_INFO_POR_CICLO = 12

    // ─────────────────────────────────────────────────────────────────────
    // FILMES
    // ─────────────────────────────────────────────────────────────────────
    suspend fun sincronizarBadgesVod(db: AppDatabase, itensAtuais: List<Pair<Int, Long>>) {
        if (itensAtuais.isEmpty()) return
        val agora = System.currentTimeMillis()
        val estados = try { db.streamDao().getVodBadgeStates().associateBy { it.stream_id } }
            catch (e: Exception) { e.printStackTrace(); return }

        db.withTransaction {
            for ((id, _) in itensAtuais) {
                val estado = estados[id]
                if (estado == null || estado.first_seen_at == 0L) {
                    db.streamDao().updateVodBadge(id, agora, "novidade", agora)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // SÉRIES
    // ─────────────────────────────────────────────────────────────────────
    suspend fun sincronizarBadgesSeries(
        db: AppDatabase,
        dns: String,
        user: String,
        pass: String,
        itensAtuais: List<Pair<Int, Long>> // (series_id, last_modified vindo da API agora)
    ) {
        if (itensAtuais.isEmpty()) return
        val agora = System.currentTimeMillis()
        val lastModifiedPorId = itensAtuais.toMap()
        val estados = try { db.streamDao().getSeriesBadgeStates().associateBy { it.series_id } }
            catch (e: Exception) { e.printStackTrace(); return }

        val novas = mutableListOf<Int>()
        val paraChecar = mutableListOf<Int>()

        for ((id, lastModifiedAtual) in itensAtuais) {
            val estado = estados[id]
            when {
                estado == null || estado.first_seen_at == 0L -> novas.add(id)
                lastModifiedAtual > estado.badge_checked_last_modified -> paraChecar.add(id)
            }
        }

        // Séries novas: selo "novidade" imediato, contagens ficam pra
        // quando a série mudar de verdade pela primeira vez.
        if (novas.isNotEmpty()) {
            db.withTransaction {
                for (id in novas) {
                    db.streamDao().updateSeriesBadge(
                        id, agora, 0, 0, "novidade", agora,
                        lastModifiedPorId[id] ?: agora
                    )
                }
            }
        }

        // Séries que já existiam e mudaram: verifica se foi temporada ou
        // episódio novo, limitado por ciclo.
        for (id in paraChecar.take(MAX_SERIES_INFO_POR_CICLO)) {
            val estado = estados[id] ?: continue
            val lastModifiedAtual = lastModifiedPorId[id] ?: estado.badge_checked_last_modified
            try {
                val (novaContagemTemporadas, novaContagemEpisodios) =
                    buscarContagemSerie(dns, user, pass, id) ?: continue

                val tipoBadge = when {
                    // Primeira verificação real dessa série (ainda não
                    // tínhamos contagem) — só grava a base, sem acusar selo
                    // falso de "novo episódio" por falta de comparação.
                    estado.season_count == 0 && estado.episode_count == 0 -> ""
                    novaContagemTemporadas > estado.season_count -> "nova_temporada"
                    novaContagemEpisodios > estado.episode_count -> "novo_episodio"
                    else -> ""
                }

                db.streamDao().updateSeriesBadge(
                    id,
                    estado.first_seen_at,
                    novaContagemTemporadas,
                    novaContagemEpisodios,
                    if (tipoBadge.isNotEmpty()) tipoBadge else estado.badge_type,
                    if (tipoBadge.isNotEmpty()) agora else estado.badge_timestamp,
                    lastModifiedAtual
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun buscarContagemSerie(dns: String, user: String, pass: String, seriesId: Int): Pair<Int, Int>? {
        return try {
            val url = "$dns/player_api.php?username=$user&password=$pass&action=get_series_info&series_id=$seriesId"
            val json = JSONObject(URL(url).readText())

            val temporadas = json.optJSONArray("seasons")?.length() ?: 0

            var episodios = 0
            val episodesObj = json.optJSONObject("episodes")
            episodesObj?.keys()?.forEach { chaveTemporada ->
                episodios += episodesObj.optJSONArray(chaveTemporada)?.length() ?: 0
            }

            Pair(temporadas, episodios)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Retorna o texto do selo pra exibir, ou null se não há selo ativo
     * (nunca teve, ou já passou do prazo). Chamado pelas extensões
     * badgeAtual() em BadgeExtensions.kt.
     */
    fun badgeVigente(badgeType: String, badgeTimestamp: Long): String? {
        if (badgeType.isBlank() || badgeTimestamp == 0L) return null
        if (System.currentTimeMillis() - badgeTimestamp >= BADGE_TTL_MS) return null
        return badgeType
    }
}
