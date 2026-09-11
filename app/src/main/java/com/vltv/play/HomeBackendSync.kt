package com.vltv.play

import androidx.room.withTransaction
import com.vltv.play.data.AppDatabase

/**
 * Aplica o resultado já processado pelo vltv-backend (Top10/Novidades/
 * selos de série) nas MESMAS colunas que o TmdbSyncHelper local calcula
 * — assim a Home e todo o resto do app continuam lendo exatamente como
 * sempre leram (getTop10Vods, getNovidadesSeries, etc.), só que sem o
 * celular precisar bater no TMDB nem no Netflix Top10 sozinho.
 *
 * Chamado pelo SyncManager só quando HomeApiClient.buscarHome() devolve
 * algo (ou seja, o backend já processou esse domínio pelo menos uma
 * vez). Se vier null, o SyncManager cai de volta pro TmdbSyncHelper
 * local — nenhum comportamento muda pra quem ainda não configurou (ou
 * cujo backend está fora do ar no momento) o vltv-backend.
 */
object HomeBackendSync {

    suspend fun aplicar(db: AppDatabase, resultado: HomeApiClient.HomeCatalogo) {
        val dao = db.streamDao()
        val agora = System.currentTimeMillis()

        db.withTransaction {
            // ── Top10 ────────────────────────────────────────────────
            dao.clearVodTop10Flags()
            dao.clearSeriesTop10Flags()
            for ((streamId, rank) in resultado.top10FilmesRank) {
                dao.updateVodTop10(streamId, rank)
            }
            for ((seriesId, rank) in resultado.top10SeriesRank) {
                dao.updateSeriesTop10(seriesId, rank)
            }

            // ── Novidades ────────────────────────────────────────────
            dao.clearVodNovidadeFlags()
            dao.clearSeriesNovidadeFlags()
            for ((streamId, releaseDate) in resultado.novidadeFilmesData) {
                dao.updateVodNovidade(streamId, releaseDate)
            }
            for ((seriesId, releaseDate) in resultado.novidadeSeriesData) {
                dao.updateSeriesNovidade(seriesId, releaseDate)
            }

            // ── Selos de série (Nova Temporada / Novo Episódio / Em Breve) ──
            dao.limparBadgesSeriesBackend()
            for (badge in resultado.badgesSeries) {
                val seriesId = badge.optInt("series_id", -1)
                if (seriesId < 0) continue

                val novaTemporada = badge.optInt("is_nova_temporada", 0)
                val novoEpisodio = badge.optInt("is_novo_episodio", 0)
                val proximaTemporadaData = badge.optString("proxima_temporada_data", "").takeIf { it.isNotEmpty() }
                val proximoEpisodioData = badge.optString("proximo_episodio_data", "").takeIf { it.isNotEmpty() }

                if (novaTemporada == 1 || novoEpisodio == 1) {
                    // ⚠️ Aproximação consciente: o backend não manda o
                    // timestamp exato de quando a temporada/episódio
                    // novo apareceu (só o estado atual), então a janela
                    // de expiração do selo (JANELA_NOVIDADE_MS, 7 dias,
                    // em HomeActivity) passa a contar a partir de AGORA,
                    // renovando a cada sincronização enquanto o backend
                    // continuar reportando o selo como ativo. Na prática
                    // isso deixa o selo visível por mais tempo do que o
                    // cálculo 100% local fazia. Se algum dia isso incomodar,
                    // dá pra o backend passar a mandar esse timestamp
                    // também (bastaria guardar/expor tmdb_flag_marcado_em
                    // no /home) e trocar esse `agora` por ele.
                    dao.aplicarBadgeSerieDoBackend(seriesId, novaTemporada, novoEpisodio, agora)
                }
                dao.atualizarProximaTemporada(seriesId, proximaTemporadaData)
                dao.atualizarProximoEpisodio(seriesId, proximoEpisodioData)
            }
        }
    }
}
