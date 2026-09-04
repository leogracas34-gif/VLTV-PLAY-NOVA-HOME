package com.vltv.play

import androidx.room.withTransaction
import com.vltv.play.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

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

    private const val TMDB_KEY = "9b73f5dd15b8165b1b57419be2f29128"
    private const val NOVIDADE_ANO_MIN = 2025

    suspend fun sincronizar(db: AppDatabase) = withContext(Dispatchers.IO) {
        try { sincronizarTop10(db) } catch (e: Exception) { e.printStackTrace() }
        try { sincronizarNovidades(db) } catch (e: Exception) { e.printStackTrace() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOP 10
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun sincronizarTop10(db: AppDatabase) {
        // Busca na rede ANTES de abrir a transação — transação deve conter
        // só operações de banco, nunca I/O de rede (evita segurar o lock
        // de escrita esperando resposta HTTP).
        val trendingFilmes = buscarTrendingTmdb("movie")
        val trendingSeries = buscarTrendingTmdb("tv")

        db.withTransaction {
            db.streamDao().clearVodTop10Flags()
            db.streamDao().clearSeriesTop10Flags()

            val idsVodUsados    = mutableSetOf<Int>()
            val idsSeriesUsados = mutableSetOf<Int>()

            for ((rank, item) in trendingFilmes.withIndex()) {
                val id = encontrarVod(db, item, idsVodUsados)
                if (id != null) {
                    idsVodUsados.add(id)
                    db.streamDao().updateVodTop10(id, rank + 1)
                }
            }

            for ((rank, item) in trendingSeries.withIndex()) {
                val id = encontrarSerie(db, item, idsSeriesUsados)
                if (id != null) {
                    idsSeriesUsados.add(id)
                    db.streamDao().updateSeriesTop10(id, rank + 1)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NOVIDADES
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun sincronizarNovidades(db: AppDatabase) {
        // Idem: rede primeiro, fora da transação.
        val filmesNovos = buscarLancamentosTmdb("movie", paginas = 3)
        val seriesNovas = buscarLancamentosTmdb("tv",    paginas = 3)

        db.withTransaction {
            db.streamDao().clearVodNovidadeFlags()
            db.streamDao().clearSeriesNovidadeFlags()

            for (item in filmesNovos) {
                val ano = item.releaseDate.take(4).toIntOrNull() ?: 0
                if (ano < NOVIDADE_ANO_MIN) continue
                val id = encontrarVod(db, item, emptySet())
                if (id != null) db.streamDao().updateVodNovidade(id, item.releaseDate)
            }

            for (item in seriesNovas) {
                val ano = item.releaseDate.take(4).toIntOrNull() ?: 0
                if (ano < NOVIDADE_ANO_MIN) continue
                val id = encontrarSerie(db, item, emptySet())
                if (id != null) db.streamDao().updateSeriesNovidade(id, item.releaseDate)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MATCHING — 4 estratégias em cascata para VOD
    // ─────────────────────────────────────────────────────────────────────────
    private fun encontrarVod(db: AppDatabase, item: TmdbItem, excluir: Set<Int>): Int? {
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

    // ─────────────────────────────────────────────────────────────────────────
    // Chamadas TMDB
    // ─────────────────────────────────────────────────────────────────────────
    private fun buscarTrendingTmdb(tipo: String): List<TmdbItem> {
        return try {
            val url = "https://api.themoviedb.org/3/trending/$tipo/week?api_key=$TMDB_KEY&language=pt-BR&region=BR"
            parseTmdbResults(JSONObject(URL(url).readText()), tipo)
        } catch (e: Exception) { emptyList() }
    }

    private fun buscarLancamentosTmdb(tipo: String, paginas: Int): List<TmdbItem> {
        val resultado = mutableListOf<TmdbItem>()
        val dataMin   = "$NOVIDADE_ANO_MIN-01-01"
        val campData  = if (tipo == "movie") "primary_release_date.gte" else "first_air_date.gte"
        for (page in 1..paginas) {
            val url = "https://api.themoviedb.org/3/discover/$tipo" +
                    "?api_key=$TMDB_KEY&language=pt-BR&region=BR" +
                    "&sort_by=popularity.desc&$campData=$dataMin&page=$page"
            try { resultado.addAll(parseTmdbResults(JSONObject(URL(url).readText()), tipo)) }
            catch (e: Exception) { break }
        }
        return resultado
    }

    private fun parseTmdbResults(json: JSONObject, tipo: String): List<TmdbItem> {
        val lista   = mutableListOf<TmdbItem>()
        val results = json.optJSONArray("results") ?: return lista
        for (i in 0 until results.length()) {
            val obj = results.getJSONObject(i)
            val tituloPt: String
            val tituloOrig: String
            val releaseDate: String
            if (tipo == "movie") {
                tituloPt    = obj.optString("title", "")
                tituloOrig  = obj.optString("original_title", "")
                releaseDate = obj.optString("release_date", "")
            } else {
                tituloPt    = obj.optString("name", "")
                tituloOrig  = obj.optString("original_name", "")
                releaseDate = obj.optString("first_air_date", "")
            }
            if (tituloPt.isNotEmpty() || tituloOrig.isNotEmpty()) {
                lista.add(TmdbItem(tituloPt, tituloOrig, releaseDate))
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
        val tituloPt:    String,
        val tituloOrig:  String,
        val releaseDate: String
    )
}
