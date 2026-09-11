package com.vltv.play

import com.vltv.play.data.SeriesEntity
import com.vltv.play.data.VodEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fala com o vltv-backend (o serviço Node.js que agora roda na VPS
 * pré-processando Top10/Novidades/selos com o TMDB).
 *
 * ⚠️ POR QUE ISSO EXISTE: os painéis Xtream bloqueiam requisições vindas
 * de IP de VPS/datacenter (defesa comum contra scraping/revenda). Por
 * isso o backend NÃO consegue baixar o catálogo cru do Xtream sozinho —
 * quem continua fazendo isso é o app, na rede residencial/móvel do
 * cliente, exatamente como já fazia antes. O app só passou a AVISAR o
 * backend do que encontrou, pra ele cruzar com o TMDB e guardar pronto
 * (Top10/Novidades/selos) — assim qualquer cliente do mesmo painel lê
 * instantâneo, sem cada celular reprocessar o próprio TMDB do zero.
 *
 * Fluxo:
 *  1) SyncManager baixa o catálogo cru do Xtream (como sempre fez) e
 *     chama enviarCatalogo(...) — silencioso: se o backend estiver fora
 *     do ar, essa chamada só falha e loga; o app continua funcionando
 *     100% como antes, com o TmdbSyncHelper local calculando os selos.
 *  2) SyncManager então chama buscarHome(dns): se o backend já tiver
 *     processado esse domínio, o resultado é aplicado nas mesmas
 *     colunas que o TmdbSyncHelper local usa (via HomeBackendSync) e o
 *     cálculo local é pulado. Se vier null, o TmdbSyncHelper local roda
 *     normalmente — nada muda pra quem ainda não tem backend.
 */
object HomeApiClient {

    // ⚠️ Preencha com a URL real do seu backend — ex.:
    // "https://api.vltvplay.tech" (com Nginx+certbot) ou
    // "http://SEU_IP:3344" (direto na porta, sem domínio) — e a MESMA
    // APP_SHARED_KEY que você colocou no .env da VPS.
    private const val BASE_URL = "http://51.222.26.119:3344"
    private const val APP_SHARED_KEY = "L468983c@"

    // O upload demora mais porque o backend PROCESSA o TMDB inteiro
    // (Top10/Novidades/Temporadas) antes de responder — dê uma folga.
    private const val UPLOAD_TIMEOUT_MS = 40_000
    private const val HOME_TIMEOUT_MS = 15_000

    data class HomeCatalogo(
        val top10FilmesRank: Map<Int, Int>,       // stream_id -> rank
        val top10SeriesRank: Map<Int, Int>,       // series_id -> rank
        val novidadeFilmesData: Map<Int, String>, // stream_id -> release_date
        val novidadeSeriesData: Map<Int, String>, // series_id -> release_date
        val badgesSeries: List<JSONObject>        // series_id, is_nova_temporada, is_novo_episodio, datas "em breve"
    )

    /**
     * Envia o catálogo cru (já no formato que o SyncManager monta a
     * partir do Xtream) pro backend processar. NUNCA lança exceção —
     * qualquer erro é só logado (printStackTrace), pra nunca atrasar ou
     * travar a sincronização normal do app se o backend estiver
     * indisponível ou ainda não tiver sido configurado.
     */
    suspend fun enviarCatalogo(dns: String, vods: List<VodEntity>, series: List<SeriesEntity>) {
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val vodArray = JSONArray()
                for (v in vods) {
                    vodArray.put(JSONObject().apply {
                        put("stream_id", v.stream_id)
                        put("name", v.name)
                        put("stream_icon", v.stream_icon ?: "")
                        put("container_extension", v.container_extension ?: "")
                        put("rating", v.rating ?: "")
                        put("category_id", v.category_id ?: "")
                        put("added", v.added)
                    })
                }
                val seriesArray = JSONArray()
                for (s in series) {
                    seriesArray.put(JSONObject().apply {
                        put("series_id", s.series_id)
                        put("name", s.name)
                        put("cover", s.cover ?: "")
                        put("rating", s.rating ?: "")
                        put("category_id", s.category_id ?: "")
                        put("last_modified", s.last_modified)
                    })
                }
                val body = JSONObject().apply {
                    put("domain", dns)
                    put("vod_streams", vodArray)
                    put("series_streams", seriesArray)
                }

                conn = (URL("$BASE_URL/catalog/upload").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = UPLOAD_TIMEOUT_MS
                    readTimeout = UPLOAD_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-app-key", APP_SHARED_KEY)
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                // força a requisição a completar e lê a resposta (mesmo sem usá-la)
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                conn?.disconnect()
            }
        }
    }

    /**
     * Busca o Top10/Novidades/selos já processados pelo backend pra um
     * domínio. Retorna null se o backend não tiver nada pronto ainda
     * (ex.: primeiro upload ainda não terminou de processar) ou estiver
     * indisponível — quem chamar deve manter o cálculo local
     * (TmdbSyncHelper) como fallback nesse caso — ver SyncManager.
     */
    suspend fun buscarHome(dns: String): HomeCatalogo? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val urlDomain = URLEncoder.encode(dns, "UTF-8")
            conn = (URL("$BASE_URL/home?domain=$urlDomain").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HOME_TIMEOUT_MS
                readTimeout = HOME_TIMEOUT_MS
            }
            if (conn.responseCode != 200) return@withContext null

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })

            val top10Filmes = json.getJSONObject("top10").getJSONArray("filmes")
            val top10FilmesRank = (0 until top10Filmes.length()).associate {
                val o = top10Filmes.getJSONObject(it)
                o.getInt("stream_id") to o.getInt("rank")
            }
            val top10Series = json.getJSONObject("top10").getJSONArray("series")
            val top10SeriesRank = (0 until top10Series.length()).associate {
                val o = top10Series.getJSONObject(it)
                o.getInt("series_id") to o.getInt("rank")
            }
            val novFilmes = json.getJSONObject("novidades").getJSONArray("filmes")
            val novidadeFilmesData = (0 until novFilmes.length()).associate {
                val o = novFilmes.getJSONObject(it)
                o.getInt("stream_id") to o.getString("release_date")
            }
            val novSeries = json.getJSONObject("novidades").getJSONArray("series")
            val novidadeSeriesData = (0 until novSeries.length()).associate {
                val o = novSeries.getJSONObject(it)
                o.getInt("series_id") to o.getString("release_date")
            }
            val badgesArr = json.getJSONArray("badges_series")
            val badges = (0 until badgesArr.length()).map { badgesArr.getJSONObject(it) }

            HomeCatalogo(top10FilmesRank, top10SeriesRank, novidadeFilmesData, novidadeSeriesData, badges)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            conn?.disconnect()
        }
    }
}
