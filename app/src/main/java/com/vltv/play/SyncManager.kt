package com.vltv.play

import android.content.Context
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.LiveStreamEntity
import com.vltv.play.data.SeriesEntity
import com.vltv.play.data.VodEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL

/**
 * SyncManager — controla a sincronização de conteúdo com o servidor Xtream.
 *
 * ✅ CORREÇÃO (tela abre com nome antigo, só atualiza depois de fechar e
 * reabrir): a sincronização inicial (sincronizarSeNecessario) atualizava o
 * banco e o ContentRepository com os nomes oficiais vindos do TMDB
 * (TmdbSyncHelper.sincronizar), mas NUNCA avisava a Home que isso tinha
 * acontecido. O único aviso existente (notificarOuvintes) só disparava na
 * sincronização PERIÓDICA (a cada 10 min) e só quando a CONTAGEM de itens
 * mudava — só nome/logo mudando não contava. Por isso a tela só aparecia
 * atualizada depois de fechar e abrir o app de novo (quando
 * ContentRepository.preCarregar() rodava de novo já com o banco
 * atualizado). Agora, ao final da sincronização inicial, notificarOuvintes()
 * é chamado incondicionalmente — a Home se atualiza sozinha assim que os
 * nomes/logos oficiais chegarem, sem precisar fechar/reabrir o app.
 *
 * PROBLEMA QUE RESOLVE (arquitetura original):
 * Antes, HomeActivity.onResume() chamava sincronizarConteudoSilenciosamente()
 * toda vez que o usuário voltava para a Home. Isso disparava downloads de
 * listas inteiras e travamentos de 15-30s. A solução usa Mutex real (não
 * Boolean solto), escopo próprio (sobrevive à troca de Activity, mas morre
 * com o processo), e uma flag de sessão que bloqueia novas sincronizações
 * completas até o app ser reaberto de verdade.
 */
object SyncManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var jaSincronizouNestaSessao = false

    @Volatile
    private var jobAtual: Job? = null

    // ── Sync periódica leve ───────────────────────────────────────────────────
    private val PERIODIC_INTERVAL_MS = 10 * 60 * 1000L // 10 minutos

    @Volatile
    private var periodicJob: Job? = null

    @Volatile
    private var periodicoIniciado = false

    private val ouvintesNovidade = mutableListOf<() -> Unit>()

    /**
     * Registra um callback chamado (na Main thread) sempre que:
     *   a) a sincronização INICIAL terminar (nomes/logos oficiais do TMDB
     *      já aplicados), ou
     *   b) a sincronização periódica detectar itens novos no servidor.
     * Retorna uma função para remover o listener — chame no onDestroy().
     */
    fun registrarOuvinteNovidade(callback: () -> Unit): () -> Unit {
        ouvintesNovidade.add(callback)
        return { ouvintesNovidade.remove(callback) }
    }

    private fun notificarOuvintes() {
        scope.launch(Dispatchers.Main) {
            ouvintesNovidade.toList().forEach { it.invoke() }
        }
    }

    fun iniciarSyncPeriodica(context: Context) {
        if (periodicoIniciado) return
        periodicoIniciado = true

        periodicJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(PERIODIC_INTERVAL_MS)
                executarSyncPeriodicaLeve(context)
            }
        }
    }

    fun pararSyncPeriodica() {
        periodicJob?.cancel()
        periodicJob = null
        periodicoIniciado = false
    }

    private suspend fun executarSyncPeriodicaLeve(context: Context) {
        mutex.withLock {
            val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            val dns = prefs.getString("dns", "") ?: ""
            val user = prefs.getString("username", "") ?: ""
            val pass = prefs.getString("password", "") ?: ""
            if (dns.isEmpty() || user.isEmpty()) return@withLock

            val db = AppDatabase.getDatabase(context)
            val contagemVodAntes = try { db.streamDao().getVodCount() } catch (e: Exception) { -1 }

            try {
                executarSincronizacao(context, dns, user, pass)
            } catch (e: Exception) {
                e.printStackTrace()
                return@withLock
            }

            val contagemVodDepois = try { db.streamDao().getVodCount() } catch (e: Exception) { -1 }

            if (contagemVodAntes != -1 && contagemVodDepois != -1 && contagemVodAntes != contagemVodDepois) {
                notificarOuvintes()
            }
        }
    }

    /**
     * Ponto de entrada único. Chame isso de onCreate() ou onResume() da Home
     * sem medo — é idempotente. Só a primeira chamada por sessão do processo
     * realmente dispara a sincronização; as demais retornam imediatamente.
     */
    fun sincronizarSeNecessario(context: Context) {
        if (jaSincronizouNestaSessao) return

        scope.launch {
            mutex.withLock {
                if (jaSincronizouNestaSessao) return@withLock

                val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
                val dns = prefs.getString("dns", "") ?: ""
                val user = prefs.getString("username", "") ?: ""
                val pass = prefs.getString("password", "") ?: ""
                if (dns.isEmpty() || user.isEmpty()) return@withLock

                try {
                    executarSincronizacao(context, dns, user, pass)
                } finally {
                    jaSincronizouNestaSessao = true
                    // ✅ NOVO: avisa quem estiver ouvindo (a Home) que a
                    // sincronização inicial — já com nomes/logos oficiais do
                    // TMDB aplicados — terminou. Sem isso, a tela só refletia
                    // essa atualização na próxima vez que o app fosse aberto
                    // do zero (quando ContentRepository.preCarregar() rodava
                    // de novo já com o banco atualizado).
                    notificarOuvintes()
                }
            }
        }
    }

    /**
     * Força uma nova sincronização mesmo que já tenha rodado nesta sessão.
     * Use apenas em ações explícitas do usuário (ex: botão "Atualizar" nas
     * configurações), nunca em ciclo de vida automático de Activity.
     */
    fun forcarResincronizacao(context: Context, onConcluido: (() -> Unit)? = null) {
        jobAtual?.cancel()
        jobAtual = scope.launch {
            mutex.withLock {
                val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
                val dns = prefs.getString("dns", "") ?: ""
                val user = prefs.getString("username", "") ?: ""
                val pass = prefs.getString("password", "") ?: ""
                if (dns.isEmpty() || user.isEmpty()) return@withLock

                try {
                    executarSincronizacao(context, dns, user, pass)
                } finally {
                    jaSincronizouNestaSessao = true
                    notificarOuvintes()
                    withContext(Dispatchers.Main) { onConcluido?.invoke() }
                }
            }
        }
    }

    /** Reseta o estado — chamar apenas no logout, para a próxima sessão sincronizar de novo. */
    fun resetarSessao() {
        jobAtual?.cancel()
        jaSincronizouNestaSessao = false
        pararSyncPeriodica()
        ouvintesNovidade.clear()
    }

    private suspend fun executarSincronizacao(context: Context, dnsRaw: String, user: String, pass: String) {
        val dns = dnsRaw
        val db = AppDatabase.getDatabase(context)
        val palavrasProibidas = listOf("XXX", "PORN", "ADULTO", "SEXO", "EROTICO", "🔞", "PORNÔ")

        try {
            // ── VOD ────────────────────────────────────────────────────────
            val vodUrl = "$dns/player_api.php?username=$user&password=$pass&action=get_vod_streams"
            val vodArray = JSONArray(URL(vodUrl).readText())
            val vodBatch = mutableListOf<VodEntity>()
            for (i in 0 until vodArray.length()) {
                val obj = vodArray.getJSONObject(i)
                val nome = obj.optString("name")
                if (!palavrasProibidas.any { nome.uppercase().contains(it) }) {
                    vodBatch.add(VodEntity(
                        stream_id = obj.optInt("stream_id"),
                        name = nome,
                        title = obj.optString("name"),
                        stream_icon = obj.optString("stream_icon"),
                        container_extension = obj.optString("container_extension"),
                        rating = obj.optString("rating"),
                        category_id = obj.optString("category_id"),
                        added = obj.optLong("added")
                    ))
                }
                if (vodBatch.size >= 200) {
                    db.streamDao().insertVodStreams(vodBatch)
                    vodBatch.clear()
                }
            }
            if (vodBatch.isNotEmpty()) db.streamDao().insertVodStreams(vodBatch)

            val vodsAtualizados = db.streamDao().getRecentVods(200)
            ContentRepository.atualizarVods(vodsAtualizados)

            // ── SÉRIES ─────────────────────────────────────────────────────
            val seriesUrl = "$dns/player_api.php?username=$user&password=$pass&action=get_series"
            val seriesArray = JSONArray(URL(seriesUrl).readText())
            val seriesBatch = mutableListOf<SeriesEntity>()
            for (i in 0 until seriesArray.length()) {
                val obj = seriesArray.getJSONObject(i)
                val nome = obj.optString("name")
                if (!palavrasProibidas.any { nome.uppercase().contains(it) }) {
                    seriesBatch.add(SeriesEntity(
                        series_id = obj.optInt("series_id"),
                        name = nome,
                        cover = obj.optString("cover"),
                        rating = obj.optString("rating"),
                        category_id = obj.optString("category_id"),
                        last_modified = obj.optLong("last_modified")
                    ))
                }
                if (seriesBatch.size >= 200) {
                    db.streamDao().insertSeriesStreams(seriesBatch)
                    seriesBatch.clear()
                }
            }
            if (seriesBatch.isNotEmpty()) db.streamDao().insertSeriesStreams(seriesBatch)

            val seriesAtualizadas = db.streamDao().getRecentSeries(200)
            ContentRepository.atualizarSeries(seriesAtualizadas)

            // ── LIVE ───────────────────────────────────────────────────────
            val liveUrl = "$dns/player_api.php?username=$user&password=$pass&action=get_live_streams"
            val liveArray = JSONArray(URL(liveUrl).readText())
            val liveBatch = mutableListOf<LiveStreamEntity>()
            for (i in 0 until liveArray.length()) {
                val obj = liveArray.getJSONObject(i)
                liveBatch.add(LiveStreamEntity(
                    stream_id = obj.optInt("stream_id"),
                    name = obj.optString("name"),
                    stream_icon = obj.optString("stream_icon"),
                    epg_channel_id = obj.optString("epg_channel_id"),
                    category_id = obj.optString("category_id")
                ))
                if (liveBatch.size >= 200) {
                    db.streamDao().insertLiveStreams(liveBatch)
                    liveBatch.clear()
                }
            }
            if (liveBatch.isNotEmpty()) db.streamDao().insertLiveStreams(liveBatch)

            // ── TMDB (nomes oficiais + top10/novidades) ─────────────────────
            // É AQUI que os nomes crus do provedor ("007", "Rambo") são
            // resolvidos para os nomes oficiais e logos são associados.
            try {
                TmdbSyncHelper.sincronizar(db)
                val vodsFinal = db.streamDao().getRecentVods(200)
                val seriesFinal = db.streamDao().getRecentSeries(200)
                ContentRepository.atualizarVods(vodsFinal)
                ContentRepository.atualizarSeries(seriesFinal)
            } catch (e: Exception) {
                e.printStackTrace()
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
