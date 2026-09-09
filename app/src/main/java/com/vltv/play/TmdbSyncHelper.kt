package com.vltv.play

import androidx.room.withTransaction
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.SeriesTmdbProgresso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * TmdbSyncHelper
 *
 * Estratégia de matching (em cascata, da mais para a menos confiável):
 *
 *  1. Título original do TMDB com delimitador de palavra
 *     Ex: "From" → WHERE name LIKE '% From %' OR name LIKE 'From %' OR ...
 *     Evita que "From" bata em "Away from Home" ou "Origem"
 *
 *  2. Título PT do TMDB com delimitador de palavra
 *     Ex: "Casa do Dragão" → delimitado, com curinga de acento
 *     Evita que "Origem" bata em "A Origem da Eternidade"
 *
 *  3. Palavra mais longa do título original com delimitador de palavra (fallback)
 *
 *  4. Palavra mais longa do título PT com delimitador de palavra (último recurso)
 *
 * ORDER BY LENGTH(name) ASC em todas as queries garante que o nome mais
 * curto (mais limpo, sem prefixos extras) seja sempre preferido.
 *
 * Delimitador de palavra simulado no SQLite:
 *   name LIKE '% TOKEN %'    → token no meio
 *   name LIKE 'TOKEN %'      → token no início
 *   name LIKE '% TOKEN'      → token no final
 *   name = 'TOKEN'           → token é o nome inteiro
 *   (variantes com ':' e '-' para padrões de servidor IPTV como "BR: From")
 *
 * ─────────────────────────────────────────────────────────────────────────
 * CORREÇÃO (trava/ANR ao abrir o app pela 2ª vez):
 * Antes, cada updateVodTop10()/updateVodNovidade()/updateSeriesTop10()/
 * updateSeriesNovidade() era chamado individualmente dentro do loop, fora de
 * qualquer transação. No SQLite em modo WAL (Room) só existe UMA conexão de
 * escrita por vez; cada UPDATE solto abre/fecha sua própria transação
 * implícita. Com 100+ updates em sequência (10 trending + até 3 páginas x 20
 * itens x 2 tipos de "lançamentos"), essa única conexão de escrita ficava
 * ocupada tempo suficiente para travar outras leituras/escritas concorrentes
 * (Home, Novidades) — daí o ANR "VLTV não está respondendo".
 * Agora cada fase (Top10 e Novidades) roda dentro de db.withTransaction { },
 * agrupando todos os updates daquela fase em UMA única transação de escrita.
 * ─────────────────────────────────────────────────────────────────────────
 */
object TmdbSyncHelper {

    // ⚠️ CORREÇÃO (selos zerados — Top10=0 e Novidade=0 em filme E série):
    // este arquivo usava uma chave de API do TMDB DIFERENTE e fixa no
    // código, em vez da chave real configurada no app (TmdbConfig.API_KEY,
    // que vem do BuildConfig/segredo do GitHub Actions). Se essa chave
    // avulsa estiver vencida, revogada ou sem cota, TODA chamada ao TMDB
    // feita por este arquivo falha silenciosamente (cada função tem
    // try/catch que engole o erro e retorna vazio/nulo) — por isso Top10 E
    // Novidade davam zero ao mesmo tempo: as duas dependem de uma consulta
    // ao TMDB em algum momento (Top10 pra achar o tmdb_id do título da
    // Netflix; Novidade pra listar os lançamentos). Outras telas do app
    // (Filmes, Séries, Detalhes) já usavam TmdbConfig.API_KEY e por isso
    // continuavam funcionando normalmente — só este arquivo estava com a
    // chave errada.
    private val TMDB_KEY = TmdbConfig.API_KEY
    private const val NOVIDADE_ANO_MIN = 2025
    private const val TOP10_ANO_MIN = 2026
    private const val TOP10_ANO_MAX = 2026
    // ✅ Limite de séries checadas por ciclo (evita sobrecarregar a API do
    // TMDB com uma chamada de detalhes por série a cada sincronização).
    private const val LIMITE_SERIES_TEMPORADA_EPISODIO = 40

    // 🔎 DIAGNÓSTICO TEMPORÁRIO — guarda um resumo de cada fase da última
    // sincronização (quantos itens vieram da rede, quantos bateram com o
    // catálogo), pra descobrir exatamente ONDE a corrente quebra quando os
    // selos não aparecem. HomeActivity mostra isso num Toast. Remover
    // depois que os selos voltarem a funcionar de forma confiável.
    @Volatile
    var ultimoDiagnostico: String = "(sincronização ainda não rodou)"
        private set

    suspend fun sincronizar(db: AppDatabase) = withContext(Dispatchers.IO) {
        val diag = StringBuilder()
        try {
            diag.append(sincronizarTop10(db))
        } catch (e: Exception) {
            diag.append("TOP10 ERRO: ${e.javaClass.simpleName} ${e.message}")
            e.printStackTrace()
        }
        diag.append(" || ")
        try {
            diag.append(sincronizarNovidades(db))
        } catch (e: Exception) {
            diag.append("NOVIDADE ERRO: ${e.javaClass.simpleName} ${e.message}")
            e.printStackTrace()
        }
        try { sincronizarTemporadasEpisodios(db) } catch (e: Exception) { e.printStackTrace() }
        ultimoDiagnostico = diag.toString()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOP 10
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun sincronizarTop10(db: AppDatabase): String {
        // Fonte oficial: Top 10 semanal da Netflix Brasil.
        // A lista é publicada por país e contém o ranking real (1..10).
        val ranking = buscarTop10NetflixBrasil()

        var filmesAchados = 0
        var seriesAchadas = 0

        db.withTransaction {
            db.streamDao().clearVodTop10Flags()
            db.streamDao().clearSeriesTop10Flags()

            val idsVodUsados = mutableSetOf<Int>()
            val idsSeriesUsados = mutableSetOf<Int>()

            // Somente títulos de 2026 entram no Top 10 do aplicativo.
            // Títulos antigos do ranking oficial da Netflix são ignorados e
            // não são usados para preencher posições vazias.
            for (item in ranking.filmes) {
                if (!ehTop10Recente(item.item)) continue
                val id = encontrarVod(db, item.item, idsVodUsados)
                if (id != null) {
                    idsVodUsados.add(id)
                    db.streamDao().updateVodTop10(id, item.rank)
                    filmesAchados++
                }
            }

            for (item in ranking.series) {
                if (!ehTop10Recente(item.item)) continue
                val id = encontrarSerie(db, item.item, idsSeriesUsados)
                if (id != null) {
                    idsSeriesUsados.add(id)
                    db.streamDao().updateSeriesTop10(id, item.rank)
                    seriesAchadas++
                }
            }
        }

        return "TOP10: netflix_bruto=${ranking.filmes.size}f/${ranking.series.size}s achados_no_catalogo=${filmesAchados}f/${seriesAchadas}s" +
            (ultimoErroTop10Rede?.let { " [ERRO REDE: $it]" } ?: "")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NOVIDADES
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun sincronizarNovidades(db: AppDatabase): String {
        // Idem: rede primeiro, fora da transação.
        val filmesNovos = buscarLancamentosTmdb("movie", paginas = 3)
        val seriesNovas = buscarLancamentosTmdb("tv",    paginas = 3)

        var filmesAchados = 0
        var seriesAchadas = 0

        db.withTransaction {
            db.streamDao().clearVodNovidadeFlags()
            db.streamDao().clearSeriesNovidadeFlags()

            for (item in filmesNovos) {
                val ano = item.releaseDate.take(4).toIntOrNull() ?: 0
                if (ano < NOVIDADE_ANO_MIN) continue
                val id = encontrarVod(db, item, emptySet())
                if (id != null) { db.streamDao().updateVodNovidade(id, item.releaseDate); filmesAchados++ }
            }

            for (item in seriesNovas) {
                val ano = item.releaseDate.take(4).toIntOrNull() ?: 0
                if (ano < NOVIDADE_ANO_MIN) continue
                val id = encontrarSerie(db, item, emptySet())
                if (id != null) { db.streamDao().updateSeriesNovidade(id, item.releaseDate); seriesAchadas++ }
            }
        }

        return "NOVIDADE: tmdb_bruto=${filmesNovos.size}f/${seriesNovas.size}s achados_no_catalogo=${filmesAchados}f/${seriesAchadas}s"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NOVA TEMPORADA / NOVO EPISÓDIO / EM BREVE
    // ─────────────────────────────────────────────────────────────────────────
    // Diferente do Top10/Novidades (que batem uma lista do TMDB contra o
    // catálogo por título), aqui já se sabe o tmdb_id de cada série — então
    // é uma consulta de DETALHES por série (GET /tv/{id}), uma a uma.
    // last_episode_to_air = episódio mais recente já ao ar; comparando com
    // o que estava salvo da última vez, dá pra saber se subiu temporada
    // nova ou só mais um episódio. next_episode_to_air = próximo episódio
    // anunciado mas ainda não exibido — vira o selo "Em breve" quando a
    // data dele ainda está no futuro.
    private suspend fun sincronizarTemporadasEpisodios(db: AppDatabase) {
        val candidatas = db.streamDao()
            .getSeriesComTmdbIdParaChecarEpisodios(LIMITE_SERIES_TEMPORADA_EPISODIO)
            .filter { it.tmdb_id != null }
        if (candidatas.isEmpty()) return

        // Busca todos os detalhes na rede ANTES de abrir a transação — mesma
        // regra do resto do arquivo: transação só com operações de banco.
        val resultados = mutableListOf<Pair<SeriesTmdbProgresso, DetalhesSerieTmdb>>()
        for (c in candidatas) {
            val detalhes = buscarDetalhesSerieTmdb(c.tmdb_id!!) ?: continue
            resultados.add(c to detalhes)
        }
        if (resultados.isEmpty()) return

        val agora = System.currentTimeMillis()
        val hoje  = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        db.withTransaction {
            for ((progresso, detalhes) in resultados) {
                when {
                    // Baseline nunca gravada (0,0) — primeira vez que essa
                    // série é checada. Só salva o estado atual, sem marcar
                    // como "novidade" (senão TODA série marcaria selo na
                    // primeira sincronização depois de instalar o app).
                    progresso.tmdb_ultima_temporada == 0 && progresso.tmdb_ultimo_episodio == 0 ->
                        db.streamDao().atualizarProgressoSemAlerta(
                            progresso.series_id, detalhes.temporadaAtual, detalhes.episodioAtual
                        )

                    detalhes.temporadaAtual > progresso.tmdb_ultima_temporada ->
                        db.streamDao().marcarNovaTemporada(
                            progresso.series_id, detalhes.temporadaAtual, detalhes.episodioAtual, agora
                        )

                    detalhes.temporadaAtual == progresso.tmdb_ultima_temporada &&
                        detalhes.episodioAtual > progresso.tmdb_ultimo_episodio ->
                        db.streamDao().marcarNovoEpisodio(
                            progresso.series_id, detalhes.temporadaAtual, detalhes.episodioAtual, agora
                        )
                }

                val emBreve = detalhes.proximaData != null && detalhes.proximaData > hoje
                db.streamDao().atualizarProximaTemporada(
                    progresso.series_id, if (emBreve) detalhes.proximaData else null
                )
            }
        }
    }

    private data class DetalhesSerieTmdb(
        val temporadaAtual: Int,
        val episodioAtual: Int,
        val proximaData: String?
    )

    private fun buscarDetalhesSerieTmdb(tmdbId: Int): DetalhesSerieTmdb? {
        return try {
            val url = "https://api.themoviedb.org/3/tv/$tmdbId?api_key=$TMDB_KEY&language=pt-BR"
            val json = JSONObject(URL(url).readText())

            val ultimo = json.optJSONObject("last_episode_to_air") ?: return null
            val temporadaAtual = ultimo.optInt("season_number", 0)
            val episodioAtual  = ultimo.optInt("episode_number", 0)

            val proximo = json.optJSONObject("next_episode_to_air")
            val proximaData = proximo?.optString("air_date", "")?.takeIf { it.isNotEmpty() }

            DetalhesSerieTmdb(temporadaAtual, episodioAtual, proximaData)
        } catch (e: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MATCHING — 4 estratégias em cascata para VOD
    // ─────────────────────────────────────────────────────────────────────────
    private fun encontrarVod(db: AppDatabase, item: TmdbItem, excluir: Set<Int>): Int? {
        // Primeiro tenta o TMDB ID, quando já existe no catálogo.
        item.tmdbId?.let { tmdbId ->
            queryVodByTmdbId(db, tmdbId, excluir)?.let { return it }
        }

        // 1. Título original com delimitador de palavra (mais específico — evita colisão de traduções)
        if (item.tituloOrig.length >= 3) {
            val id = queryVodMultiPattern(db, wordBoundaryPatterns(item.tituloOrig), excluir)
            if (id != null) return id
        }
        // 2. Título PT com delimitador de palavra + curinga de acento
        if (item.tituloPt.length >= 4) {
            val id = queryVodMultiPattern(db, wordBoundaryPatterns(item.tituloPt, acentoCuringa = true), excluir)
            if (id != null) return id
        }
        // 3. Palavra mais longa do original com delimitador de palavra (fallback)
        palavraMaisLonga(item.tituloOrig)?.let { p ->
            val id = queryVodMultiPattern(db, wordBoundaryPatterns(p), excluir)
            if (id != null) return id
        }
        // 4. Palavra mais longa do PT com delimitador de palavra (último recurso)
        palavraMaisLonga(item.tituloPt)?.let { p ->
            val id = queryVodMultiPattern(db, wordBoundaryPatterns(p, acentoCuringa = true), excluir)
            if (id != null) return id
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MATCHING — 4 estratégias em cascata para SÉRIE
    // ─────────────────────────────────────────────────────────────────────────
    private fun encontrarSerie(db: AppDatabase, item: TmdbItem, excluir: Set<Int>): Int? {
        // Primeiro tenta o TMDB ID, quando já existe no catálogo.
        item.tmdbId?.let { tmdbId ->
            querySerieByTmdbId(db, tmdbId, excluir)?.let { return it }
        }

        // 1. Título original com delimitador de palavra
        if (item.tituloOrig.length >= 3) {
            val id = querySerieMultiPattern(db, wordBoundaryPatterns(item.tituloOrig), excluir)
            if (id != null) return id
        }
        // 2. Título PT com delimitador de palavra + curinga de acento
        if (item.tituloPt.length >= 4) {
            val id = querySerieMultiPattern(db, wordBoundaryPatterns(item.tituloPt, acentoCuringa = true), excluir)
            if (id != null) return id
        }
        // 3. Palavra mais longa do original com delimitador de palavra
        palavraMaisLonga(item.tituloOrig)?.let { p ->
            val id = querySerieMultiPattern(db, wordBoundaryPatterns(p), excluir)
            if (id != null) return id
        }
        // 4. Palavra mais longa do PT com delimitador de palavra
        palavraMaisLonga(item.tituloPt)?.let { p ->
            val id = querySerieMultiPattern(db, wordBoundaryPatterns(p, acentoCuringa = true), excluir)
            if (id != null) return id
        }
        return null
    }

    private fun queryVodByTmdbId(db: AppDatabase, tmdbId: Int, excluir: Set<Int>): Int? {
        val cursor = db.openHelper.readableDatabase.query(
            "SELECT stream_id FROM vod_streams WHERE tmdb_id = ? LIMIT 20",
            arrayOf(tmdbId.toString())
        )
        var resultado: Int? = null
        while (cursor.moveToNext()) {
            val id = cursor.getInt(0)
            if (!excluir.contains(id)) { resultado = id; break }
        }
        cursor.close()
        return resultado
    }

    private fun querySerieByTmdbId(db: AppDatabase, tmdbId: Int, excluir: Set<Int>): Int? {
        val cursor = db.openHelper.readableDatabase.query(
            "SELECT series_id FROM series_streams WHERE tmdb_id = ? LIMIT 20",
            arrayOf(tmdbId.toString())
        )
        var resultado: Int? = null
        while (cursor.moveToNext()) {
            val id = cursor.getInt(0)
            if (!excluir.contains(id)) { resultado = id; break }
        }
        cursor.close()
        return resultado
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Queries SQLite com múltiplos padrões (OR) — ORDER BY LENGTH(name) ASC
    //
    // Executa uma query com todos os padrões de word boundary via OR,
    // para não fazer múltiplos roundtrips ao banco por título.
    //
    // Usa readableDatabase: dentro da transação de escrita (withTransaction),
    // a própria conexão de escrita do Room atende a leitura também — não há
    // necessidade nem benefício de pedir writableDatabase aqui, e pedir
    // readableDatabase deixa a intenção clara (isto é um SELECT).
    // ─────────────────────────────────────────────────────────────────────────
    private fun queryVodMultiPattern(
        db: AppDatabase,
        patterns: List<String>,
        excluir: Set<Int>
    ): Int? {
        if (patterns.isEmpty()) return null
        val placeholders = patterns.joinToString(" OR ") { "name LIKE ?" }
        val sql = "SELECT stream_id FROM vod_streams WHERE ($placeholders) ORDER BY LENGTH(name) ASC LIMIT 20"
        val cursor = db.openHelper.readableDatabase.query(sql, patterns.toTypedArray())
        var resultado: Int? = null
        while (cursor.moveToNext()) {
            val id = cursor.getInt(0)
            if (!excluir.contains(id)) { resultado = id; break }
        }
        cursor.close()
        return resultado
    }

    private fun querySerieMultiPattern(
        db: AppDatabase,
        patterns: List<String>,
        excluir: Set<Int>
    ): Int? {
        if (patterns.isEmpty()) return null
        val placeholders = patterns.joinToString(" OR ") { "name LIKE ?" }
        val sql = "SELECT series_id FROM series_streams WHERE ($placeholders) ORDER BY LENGTH(name) ASC LIMIT 20"
        val cursor = db.openHelper.readableDatabase.query(sql, patterns.toTypedArray())
        var resultado: Int? = null
        while (cursor.moveToNext()) {
            val id = cursor.getInt(0)
            if (!excluir.contains(id)) { resultado = id; break }
        }
        cursor.close()
        return resultado
    }

    private fun ehTop10Recente(item: TmdbItem): Boolean {
        val ano = item.releaseDate.take(4).toIntOrNull() ?: return false
        return ano in TOP10_ANO_MIN..TOP10_ANO_MAX
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOP 10 OFICIAL — NETFLIX BRASIL
    // ─────────────────────────────────────────────────────────────────────────
    private data class NetflixRankedItem(val rank: Int, val item: TmdbItem)

    private data class NetflixTop10Brasil(
        val filmes: List<NetflixRankedItem>,
        val series: List<NetflixRankedItem>
    )

    // 🔎 DIAGNÓSTICO TEMPORÁRIO — motivo exato de falha na busca do Top10
    // Netflix (código HTTP, timeout, etc.), já que o catch abaixo engolia
    // isso e só devolvia listas vazias sem dizer por quê.
    @Volatile
    var ultimoErroTop10Rede: String? = null
        private set

    private suspend fun buscarTop10NetflixBrasil(): NetflixTop10Brasil {
        return try {
            val url = "https://www.netflix.com/tudum/top10/data/all-weeks-countries.tsv"
            val connection = URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 12000
            connection.readTimeout = 20000
            connection.requestMethod = "GET"
            // ⚠️ Sem User-Agent, alguns servidores (inclusive CDNs da
            // Netflix) recusam a requisição silenciosamente ou retornam
            // conteúdo vazio/erro — o que zeraria o Top10 mesmo com a API
            // key do TMDB certa. Um User-Agent de navegador comum evita isso.
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
            )

            val codigoHttp = connection.responseCode
            if (codigoHttp !in 200..299) {
                ultimoErroTop10Rede = "HTTP $codigoHttp ao buscar TSV da Netflix"
                connection.disconnect()
                return NetflixTop10Brasil(emptyList(), emptyList())
            }
            ultimoErroTop10Rede = null

            val linhas = mutableListOf<String>()
            var ultimaSemana = ""

            try {
                connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { sequence ->
                    sequence.drop(1).forEach { line ->
                        val cols = line.split('\t')
                        if (cols.size < 8) return@forEach
                        if (!cols[1].trim().equals("BR", ignoreCase = true)) return@forEach

                        val week = cols[2].trim()
                        when {
                            week > ultimaSemana -> {
                                ultimaSemana = week
                                linhas.clear()
                                linhas.add(line)
                            }
                            week == ultimaSemana -> linhas.add(line)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }

            val filmes = mutableListOf<NetflixRankedItem>()
            val series = mutableListOf<NetflixRankedItem>()

            for (line in linhas) {
                val cols = line.split('\t')
                if (cols.size < 8) continue

                val category = cols[3].trim()
                val rank = cols[4].trim().toIntOrNull() ?: continue
                val title = cols[5].trim()
                if (title.isBlank() || rank !in 1..10) continue

                val isSeries = category.equals("TV", ignoreCase = true)
                val item = TmdbItem(
                    tituloPt = title,
                    tituloOrig = title,
                    releaseDate = ""
                )
                val ranked = NetflixRankedItem(rank, item)
                if (isSeries) series.add(ranked) else filmes.add(ranked)
            }

            NetflixTop10Brasil(
                filmes = enriquecerComTmdb(filmes, "movie").sortedBy { it.rank }.take(10),
                series = enriquecerComTmdb(series, "tv").sortedBy { it.rank }.take(10)
            )
        } catch (e: Exception) {
            ultimoErroTop10Rede = "${e.javaClass.simpleName}: ${e.message}"
            e.printStackTrace()
            NetflixTop10Brasil(emptyList(), emptyList())
        }
    }

    private suspend fun enriquecerComTmdb(
        itens: List<NetflixRankedItem>,
        tipo: String
    ): List<NetflixRankedItem> = coroutineScope {
        itens.map { ranked ->
            async {
                val tmdb = buscarTituloNetflixNoTmdb(ranked.item.tituloPt, tipo)
                ranked.copy(item = tmdb ?: ranked.item)
            }
        }.awaitAll()
    }

    private fun buscarTituloNetflixNoTmdb(tituloNetflix: String, tipo: String): TmdbItem? {
        return try {
            val query = URLEncoder.encode(tituloNetflix, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/$tipo" +
                "?api_key=$TMDB_KEY&query=$query&language=pt-BR&region=BR&page=1"
            val json = JSONObject(URL(url).readText())
            val results = json.optJSONArray("results") ?: return null
            if (results.length() == 0) return null

            val alvo = normalizarTitulo(tituloNetflix)
            var melhor: JSONObject? = null
            var melhorPontuacao = 0

            for (i in 0 until minOf(results.length(), 10)) {
                val obj = results.getJSONObject(i)
                val pt = if (tipo == "movie") obj.optString("title", "") else obj.optString("name", "")
                val orig = if (tipo == "movie") obj.optString("original_title", "") else obj.optString("original_name", "")
                val nPt = normalizarTitulo(pt)
                val nOrig = normalizarTitulo(orig)
                val score = when {
                    alvo == nPt && alvo.isNotBlank() -> 100
                    alvo == nOrig && alvo.isNotBlank() -> 95
                    nPt.startsWith(alvo) || alvo.startsWith(nPt) -> 80
                    nOrig.startsWith(alvo) || alvo.startsWith(nOrig) -> 75
                    else -> 0
                }
                if (score > melhorPontuacao) {
                    melhorPontuacao = score
                    melhor = obj
                }
            }

            val obj = melhor ?: return null
            val pt = if (tipo == "movie") obj.optString("title", "") else obj.optString("name", "")
            val orig = if (tipo == "movie") obj.optString("original_title", "") else obj.optString("original_name", "")
            val tmdbId = obj.optInt("id", 0).takeIf { it > 0 }
            if (pt.isBlank() && orig.isBlank()) return null

            TmdbItem(
                tituloPt = pt.ifBlank { tituloNetflix },
                tituloOrig = orig.ifBlank { tituloNetflix },
                releaseDate = if (tipo == "movie") obj.optString("release_date", "") else obj.optString("first_air_date", ""),
                tmdbId = tmdbId
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizarTitulo(titulo: String): String {
        return titulo.lowercase(Locale.ROOT)
            .replace(Regex("[àáâãäå]"), "a")
            .replace(Regex("[èéêë]"), "e")
            .replace(Regex("[ìíîï]"), "i")
            .replace(Regex("[òóôõö]"), "o")
            .replace(Regex("[ùúûü]"), "u")
            .replace(Regex("[ç]"), "c")
            .replace(Regex("[ñ]"), "n")
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TMDB — usado pela seção de NOVIDADES.
    // ─────────────────────────────────────────────────────────────────────────
    private fun buscarLancamentosTmdb(tipo: String, paginas: Int): List<TmdbItem> {
        val resultado = mutableListOf<TmdbItem>()
        val dataMin = "$NOVIDADE_ANO_MIN-01-01"
        val campData = if (tipo == "movie") "primary_release_date.gte" else "first_air_date.gte"
        for (page in 1..paginas) {
            val url = "https://api.themoviedb.org/3/discover/$tipo" +
                "?api_key=$TMDB_KEY&language=pt-BR&region=BR" +
                "&sort_by=popularity.desc&$campData=$dataMin&page=$page"
            try {
                resultado.addAll(parseTmdbResults(JSONObject(URL(url).readText()), tipo))
            } catch (e: Exception) {
                break
            }
        }
        return resultado
    }

    private fun parseTmdbResults(json: JSONObject, tipo: String): List<TmdbItem> {
        val lista = mutableListOf<TmdbItem>()
        val results = json.optJSONArray("results") ?: return lista
        for (i in 0 until results.length()) {
            val obj = results.getJSONObject(i)
            val tituloPt: String
            val tituloOrig: String
            val releaseDate: String
            if (tipo == "movie") {
                tituloPt = obj.optString("title", "")
                tituloOrig = obj.optString("original_title", "")
                releaseDate = obj.optString("release_date", "")
            } else {
                tituloPt = obj.optString("name", "")
                tituloOrig = obj.optString("original_name", "")
                releaseDate = obj.optString("first_air_date", "")
            }
            if (tituloPt.isNotEmpty() || tituloOrig.isNotEmpty()) {
                lista.add(TmdbItem(
                    tituloPt = tituloPt,
                    tituloOrig = tituloOrig,
                    releaseDate = releaseDate,
                    tmdbId = obj.optInt("id", 0).takeIf { it > 0 }
                ))
            }
        }
        return lista
    }

    // ─────────────────────────────────────────────────────────────────────────
    // wordBoundaryPatterns
    //
    // Gera os padrões LIKE que simulam "word boundary" no SQLite.
    //
    // Para o token "From" (limpo de ruídos), gera:
    //   "% From %"   → token no meio do nome
    //   "From %"     → token no início do nome
    //   "% From"     → token no final do nome
    //   "From"       → nome exatamente igual ao token
    //   "% : From %" → token após separador de servidor (ex: "BR: From")
    //   ": From %"   → idem, no início
    //   "% - From %  → token após traço (ex: "4K - From")
    //   "- From %"   → idem, no início
    //
    // Se acentoCuringa=true, substitui letras acentuadas por "_" (qualquer char).
    // Isso faz "Casa do Drag_o" bater com "Casa do Dragao" e "Casa do Dragão".
    //
    // Remove ruídos antes de montar os padrões: ano entre parênteses, tags de
    // qualidade comuns (4K, HD, DUBLADO etc.).
    // ─────────────────────────────────────────────────────────────────────────
    private fun wordBoundaryPatterns(titulo: String, acentoCuringa: Boolean = false): List<String> {
        // 1. Limpar ruídos do título TMDB (ano, qualidade)
        val limpo = titulo
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(
                Regex("(?i)\\b(4K|FULL HD|HD|SD|DUBLADO|LEGENDADO|DUAL|BLURAY|BLU-RAY|WEB-DL|HEVC|H264|H265|UHD|FHD|HDR|REMUX)\\b"),
                ""
            )
            .trim()

        if (limpo.isBlank()) return emptyList()

        // 2. Aplicar curinga de acento se solicitado
        val token = if (acentoCuringa) aplicarCuringaAcento(limpo) else limpo

        // 3. Montar os padrões de word boundary
        // Separadores comuns no IPTV: espaço, ": ", " - ", "- "
        return listOf(
            "% $token %",   // token no meio
            "$token %",     // token no início
            "% $token",     // token no final
            token,          // nome exato
            "%: $token %",  // após "BR: " no meio
            ": $token %",   // após "BR: " no início
            "%: $token",    // após "BR: " no final
            "% - $token %", // após "- " (tags de qualidade) no meio
            "- $token %",   // após "- " no início
            "% - $token"    // após "- " no final
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Substitui letras acentuadas por "_" (curinga SQLite = qualquer 1 char)
    // Permite bater "Dragão" com "Dragao" e vice-versa.
    // ─────────────────────────────────────────────────────────────────────────
    private fun aplicarCuringaAcento(texto: String): String {
        return texto
            .replace(Regex("[àáâãäå]"), "_")
            .replace(Regex("[èéêë]"), "_")
            .replace(Regex("[ìíîï]"), "_")
            .replace(Regex("[òóôõö]"), "_")
            .replace(Regex("[ùúûü]"), "_")
            .replace(Regex("[ç]"), "_")
            .replace(Regex("[ñ]"), "_")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Extrai a palavra mais longa do título (mínimo 5 chars).
    // Remove acentos para busca mais robusta como fallback.
    // Ignora palavras curtas (artigos, preposições).
    // ─────────────────────────────────────────────────────────────────────────
    private fun palavraMaisLonga(titulo: String): String? {
        if (titulo.isBlank()) return null
        return titulo
            .split(" ")
            .filter { it.length >= 5 }
            .maxByOrNull { it.length }
            ?.replace(Regex("[àáâãäå]"), "a")
            ?.replace(Regex("[èéêë]"), "e")
            ?.replace(Regex("[ìíîï]"), "i")
            ?.replace(Regex("[òóôõö]"), "o")
            ?.replace(Regex("[ùúûü]"), "u")
            ?.replace(Regex("[ç]"), "c")
            ?.replace(Regex("[ñ]"), "n")
    }

    private data class TmdbItem(
        val tituloPt: String,
        val tituloOrig: String,
        val releaseDate: String,
        val tmdbId: Int? = null
    )
}
