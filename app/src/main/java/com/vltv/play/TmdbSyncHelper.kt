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
 * TmdbSyncHelper — matching em cascata pra Top10/Novidades (ver
 * comentários originais mantidos abaixo). NOVIDADES NESTA VERSÃO:
 *
 * 1. Top 10 sem filtro de ano — o ranking real da Netflix já é "agora".
 *
 * 2. Selo "Em Breve" distingue temporada nova de episódio novo, com
 *    janela de ~30 dias.
 *
 * 3. vincularTmdbIdsFaltantes(): vincula tmdb_id retroativamente pra
 *    séries antigas que nunca passaram pelo Top10/Novidades.
 *
 * 4. ✅ REVERTIDO: a busca de logo (clearlogo do TMDB) — que chegou a
 *    rodar ao vivo no HomeRowAdapter e depois foi movida pra cá — foi
 *    removida por completo a pedido. Os cards da Home voltam a mostrar
 *    só o nome em texto, sem nenhuma chamada de rede extra de logo em
 *    lugar nenhum (nem no adapter, nem aqui). Isso também elimina de vez
 *    essa fonte de disputa por rede com o carregamento dos pôsteres.
 *
 * 5. Ao final da sincronização, a cópia em memória do ContentRepository
 *    é recarregada com os dados frescos do banco.
 */
object TmdbSyncHelper {

    private val TMDB_KEY = TmdbConfig.API_KEY
    private const val NOVIDADE_ANO_MIN = 2025
    private const val LIMITE_SERIES_TEMPORADA_EPISODIO = 40
    private const val LIMITE_SERIES_SEM_TMDB_ID = 30
    private const val ANTECEDENCIA_EM_BREVE_MS = 30L * 24 * 60 * 60 * 1000L

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
        try {
            sincronizarTemporadasEpisodios(db)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val vodsAtualizados = db.streamDao().getAllVods()
            val seriesAtualizadas = db.streamDao().getAllSeries()
            ContentRepository.atualizarVods(vodsAtualizados)
            ContentRepository.atualizarSeries(seriesAtualizadas)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        ultimoDiagnostico = diag.toString()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOP 10
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun sincronizarTop10(db: AppDatabase): String {
        val ranking = buscarTop10NetflixBrasil()

        var filmesAchados = 0
        var seriesAchadas = 0

        db.withTransaction {
            db.streamDao().clearVodTop10Flags()
            db.streamDao().clearSeriesTop10Flags()

            val idsVodUsados = mutableSetOf<Int>()
            val idsSeriesUsados = mutableSetOf<Int>()

            for (item in ranking.filmes) {
                val id = encontrarVod(db, item.item, idsVodUsados)
                if (id != null) {
                    idsVodUsados.add(id)
                    db.streamDao().updateVodTop10(id, item.rank)
                    filmesAchados++
                }
            }

            for (item in ranking.series) {
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
    private suspend fun sincronizarTemporadasEpisodios(db: AppDatabase) {
        try { vincularTmdbIdsFaltantes(db) } catch (e: Exception) { e.printStackTrace() }

        val candidatas = db.streamDao()
            .getSeriesComTmdbIdParaChecarEpisodios(LIMITE_SERIES_TEMPORADA_EPISODIO)
            .filter { it.tmdb_id != null }
        if (candidatas.isEmpty()) return

        val resultados = mutableListOf<Pair<SeriesTmdbProgresso, DetalhesSerieTmdb>>()
        for (c in candidatas) {
            val detalhes = buscarDetalhesSerieTmdb(c.tmdb_id!!) ?: continue
            resultados.add(c to detalhes)
        }
        if (resultados.isEmpty()) return

        val agora = System.currentTimeMillis()

        db.withTransaction {
            for ((progresso, detalhes) in resultados) {
                when {
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

                var novaTemporadaEmBreveData: String? = null
                var novoEpisodioEmBreveData: String? = null

                if (detalhes.proximaData != null) {
                    val proximaMillis = parseDataParaMillis(detalhes.proximaData)
                    val dentroDaJanela = proximaMillis != null &&
                        proximaMillis > agora &&
                        (proximaMillis - agora) <= ANTECEDENCIA_EM_BREVE_MS

                    if (dentroDaJanela) {
                        val temporadaDoProximo = detalhes.proximaTemporadaNumero ?: detalhes.temporadaAtual
                        if (temporadaDoProximo > detalhes.temporadaAtual) {
                            novaTemporadaEmBreveData = detalhes.proximaData
                        } else {
                            novoEpisodioEmBreveData = detalhes.proximaData
                        }
                    }
                }

                db.streamDao().atualizarProximaTemporada(progresso.series_id, novaTemporadaEmBreveData)
                db.streamDao().atualizarProximoEpisodio(progresso.series_id, novoEpisodioEmBreveData)
            }
        }
    }

    private data class DetalhesSerieTmdb(
        val temporadaAtual: Int,
        val episodioAtual: Int,
        val proximaData: String?,
        val proximaTemporadaNumero: Int?
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
            val proximaTemporadaNumero = proximo?.optInt("season_number", -1)?.takeIf { it >= 0 }

            DetalhesSerieTmdb(temporadaAtual, episodioAtual, proximaData, proximaTemporadaNumero)
        } catch (e: Exception) { null }
    }

    private fun parseDataParaMillis(data: String): Long? {
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(data)?.time
        } catch (e: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VINCULAÇÃO RETROATIVA DE tmdb_id (séries antigas tipo Reacher)
    // ─────────────────────────────────────────────────────────────────────────
    private val REGEX_TARJAS_CATALOGO = Regex(
        "(?i)\\b(4K|8K|FULL[\\s.-]?HD|HD|SD|720P|1080P|2160P|DUBLADO|LEGENDADO|LEG|DUB|DUAL|AUDIO|LATINO|" +
        "NACIONAL|PT[-.]?BR|PTBR|WEB[-.]?DL|WEBRIP|BLU-?RAY|REMUX|MKV|MP4|AVI|REPACK|H\\.?264|H\\.?265|" +
        "HEVC|X264|X265|WEB|HDR|UHD|FHD|CAM|HDCAM|TS|TC|R5|SCREENER|CINEMA|LAN[ÇC]AMENTO|EXCLUSIVO|" +
        "COMPLETO|COMPLETE|S\\d{1,2}|E\\d{1,3}|EP\\d{1,3}|TEMPORADA|SEASON)\\b"
    )

    private suspend fun vincularTmdbIdsFaltantes(db: AppDatabase) {
        val candidatas = db.streamDao().getSeriesSemTmdbId(LIMITE_SERIES_SEM_TMDB_ID)
        if (candidatas.isEmpty()) return

        val encontrados = mutableListOf<Pair<Int, Int>>()
        for (c in candidatas) {
            val tmdbId = buscarTmdbIdPorNomeCatalogo(c.name) ?: continue
            encontrados.add(c.series_id to tmdbId)
        }
        if (encontrados.isEmpty()) return

        db.withTransaction {
            for ((seriesId, tmdbId) in encontrados) {
                db.streamDao().atualizarTmdbIdSerie(seriesId, tmdbId)
            }
        }
    }

    private fun buscarTmdbIdPorNomeCatalogo(nomeCatalogo: String): Int? {
        val limpo = nomeCatalogo
            .replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "")
            .replace(REGEX_TARJAS_CATALOGO, "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        if (limpo.isBlank()) return null

        return try {
            val query = URLEncoder.encode(limpo, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/tv" +
                "?api_key=$TMDB_KEY&query=$query&language=pt-BR&region=BR&page=1"
            val json = JSONObject(URL(url).readText())
            val results = json.optJSONArray("results") ?: return null
            if (results.length() == 0) return null

            val alvo = normalizarTitulo(limpo)
            var melhorId: Int? = null
            var melhorPontuacao = 0

            for (i in 0 until minOf(results.length(), 10)) {
                val obj = results.getJSONObject(i)
                val nPt = normalizarTitulo(obj.optString("name", ""))
                val nOrig = normalizarTitulo(obj.optString("original_name", ""))
                val score = when {
                    alvo == nPt && alvo.isNotBlank() -> 100
                    alvo == nOrig && alvo.isNotBlank() -> 95
                    nPt.startsWith(alvo) || alvo.startsWith(nPt) -> 80
                    nOrig.startsWith(alvo) || alvo.startsWith(nOrig) -> 75
                    else -> 0
                }
                if (score > melhorPontuacao) {
                    melhorPontuacao = score
                    melhorId = obj.optInt("id", 0).takeIf { it > 0 }
                }
            }
            if (melhorPontuacao < 75) return null
            melhorId
        } catch (e: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MATCHING — 4 estratégias em cascata para VOD
    // ─────────────────────────────────────────────────────────────────────────
    private fun encontrarVod(db: AppDatabase, item: TmdbItem, excluir: Set<Int>): Int? {
        item.tmdbId?.let { tmdbId ->
            queryVodByTmdbId(db, tmdbId, excluir)?.let { return it }
        }
        if (item.tituloOrig.length >= 3) {
            val id = queryVodMultiPattern(db, wordBoundaryPatterns(item.tituloOrig), excluir)
            if (id != null) return id
        }
        if (item.tituloPt.length >= 4) {
            val id = queryVodMultiPattern(db, wordBoundaryPatterns(item.tituloPt, acentoCuringa = true), excluir)
            if (id != null) return id
        }
        palavraMaisLonga(item.tituloOrig)?.let { p ->
            val id = queryVodMultiPattern(db, wordBoundaryPatterns(p), excluir)
            if (id != null) return id
        }
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
        item.tmdbId?.let { tmdbId ->
            querySerieByTmdbId(db, tmdbId, excluir)?.let { return it }
        }
        if (item.tituloOrig.length >= 3) {
            val id = querySerieMultiPattern(db, wordBoundaryPatterns(item.tituloOrig), excluir)
            if (id != null) return id
        }
        if (item.tituloPt.length >= 4) {
            val id = querySerieMultiPattern(db, wordBoundaryPatterns(item.tituloPt, acentoCuringa = true), excluir)
            if (id != null) return id
        }
        palavraMaisLonga(item.tituloOrig)?.let { p ->
            val id = querySerieMultiPattern(db, wordBoundaryPatterns(p), excluir)
            if (id != null) return id
        }
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

    // ─────────────────────────────────────────────────────────────────────────
    // TOP 10 OFICIAL — NETFLIX BRASIL
    // ─────────────────────────────────────────────────────────────────────────
    private data class NetflixRankedItem(val rank: Int, val item: TmdbItem)

    private data class NetflixTop10Brasil(
        val filmes: List<NetflixRankedItem>,
        val series: List<NetflixRankedItem>
    )

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

    private fun wordBoundaryPatterns(titulo: String, acentoCuringa: Boolean = false): List<String> {
        val limpo = titulo
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(
                Regex("(?i)\\b(4K|FULL HD|HD|SD|DUBLADO|LEGENDADO|DUAL|BLURAY|BLU-RAY|WEB-DL|HEVC|H264|H265|UHD|FHD|HDR|REMUX)\\b"),
                ""
            )
            .trim()

        if (limpo.isBlank()) return emptyList()

        val token = if (acentoCuringa) aplicarCuringaAcento(limpo) else limpo

        return listOf(
            "% $token %",
            "$token %",
            "% $token",
            token,
            "%: $token %",
            ": $token %",
            "%: $token",
            "% - $token %",
            "- $token %",
            "% - $token"
        )
    }

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
