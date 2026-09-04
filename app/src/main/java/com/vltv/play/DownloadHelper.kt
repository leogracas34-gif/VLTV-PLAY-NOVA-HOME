package com.vltv.play

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.DownloadEntity
import com.vltv.play.download.VltvDownloadService
import com.vltv.play.download.VltvDownloadTracker
import kotlinx.coroutines.*

// ────────────────────────────────────────────────────────────────
// DownloadHelper — usa o DownloadManager do Media3 via DownloadService
// (garante que o download continue rodando em segundo plano de verdade).
//
// ✅ NOVO (correção do bug de downloads cruzando entre perfis): tanto
// iniciarDownload() quanto baixarTemporadaCompleta() agora recebem
// "profileName" e gravam esse valor no campo DownloadEntity.profile_name.
// É esse campo que DownloadsActivity (perfil adulto) e KidsDownloadsActivity
// usam pra filtrar — cada perfil só vê os próprios downloads.
//
// ✅ NOVO: pausarDownload()/continuarDownload() — usa o recurso nativo do
// Media3 de "stop reason" pra pausar um download SEM apagar o que já foi
// baixado (diferente de cancelar, que remove tudo do cache). Um stopReason
// diferente de STOP_REASON_NONE marca o download como parado; setar de
// volta pra STOP_REASON_NONE faz ele retomar de onde parou.
//
// ✅ NOVO: dois estados a mais no fluxo, além de BAIXAR/BAIXANDO/BAIXADO/ERRO:
//   - STATE_NA_FILA: o Media3 só baixa 3 itens ao mesmo tempo
//     (maxParallelDownloads = 3, configurado no VltvDownloadTracker). Itens
//     que não couberem nas 3 vagas ficam "na fila", aguardando uma vaga
//     abrir — isso ANTES parecia "baixando 0%" parado, o que dava a
//     impressão de trava. Agora fica claro que é só fila de espera.
//   - STATE_PAUSADO: o usuário pausou manualmente esse item específico.
//
// ✅ CORREÇÃO (demora pra aparecer na tela de Downloads): em
// iniciarDownload(), o insert no Room agora acontece ANTES de
// DownloadService.sendAddDownload(). Antes, o insert só rodava depois do
// sendAddDownload terminar — e como esse comando aciona o DownloadService
// (que pode gastar um tempo inicializando o DownloadManager do Media3 na
// primeira chamada), a linha só existia no banco depois disso, deixando a
// tela de Downloads (que observa LiveData do Room) "vazia" até então.
// Agora a UI atualiza na hora, mostrando "Na fila de espera..." assim que
// o usuário manda baixar.
//
// ✅ NOVO (correção da "seta que gira e volta sozinha"): iniciarDownload()
// agora aceita um parâmetro opcional "aoIniciar" — um callback chamado na
// Main thread, mas SÓ depois que a linha já foi gravada no Room com
// garantia (insertDownload já terminou). Antes, as telas de Detalhes
// (DetailsActivity/SeriesDetailsActivity) tentavam começar a monitorar o
// progresso usando um Handler().postDelayed(500ms) "chutando" um tempo
// que achavam suficiente pro insert terminar. Só que o insert roda numa
// coroutine solta, competindo com outras operações de IO do app (Room,
// pré-carregamento do catálogo, etc) — então, às vezes, o insert ainda
// não tinha terminado quando a tela verificava o banco, encontrava
// "null", desistia e voltava a seta pro estado "BAIXAR". O download
// continuava rodando por trás (por isso ele aparecia certinho, alguns
// segundos depois, na tela de Downloads — que reage via LiveData a
// qualquer momento). Agora as telas só começam a monitorar quando esse
// callback confirma que a linha já existe — sem adivinhação de tempo.
//
// ✅ CORRIGIDO (Toast de "Download iniciado..." aparecendo por cima de
// outros apps, ex: WhatsApp): Toast é uma janela do próprio SISTEMA — ela
// não fica presa à Activity, então se o usuário sai do app logo depois de
// mandar baixar (ex: pra responder uma mensagem), o Toast "flutua" por
// cima de qualquer app que estiver em primeiro plano naquele instante.
// Removidos os Toasts de SUCESSO ("Download iniciado...", "Baixando N
// episódio(s)...") — o feedback visual já existe na própria tela (seta
// vira "NA FILA", progresso circular aparece), então o Toast era
// redundante e é ele quem "vazava" pra fora do app. Os Toasts de ERRO
// (sessão inválida, falha ao preparar) foram mantidos de propósito: se o
// download falhar e o usuário já tiver saído da tela, ele ainda precisa
// ser avisado que algo deu errado.
// ────────────────────────────────────────────────────────────────
@UnstableApi
object DownloadHelper {

    const val STATE_BAIXAR   = "BAIXAR"
    const val STATE_NA_FILA  = "NA_FILA"
    const val STATE_BAIXANDO = "BAIXANDO"
    const val STATE_PAUSADO  = "PAUSADO"
    const val STATE_BAIXADO  = "BAIXADO"
    const val STATE_ERRO     = "ERRO"

    // Qualquer valor != Download.STOP_REASON_NONE (0) serve como "motivo de
    // parada". Usamos 1 como convenção pra "pausado manualmente pelo usuário".
    private const val STOP_REASON_USER_PAUSED = 1

    fun iniciarDownload(
        context: Context,
        streamId: Int,
        nomePrincipal: String,
        nomeEpisodio: String? = null,
        imagemUrl: String? = null,
        isSeries: Boolean,
        season: Int = 0,
        extensaoContainer: String? = null,
        // ✅ NOVO: nome do perfil que está iniciando esse download (ex:
        // "Perfil 1" ou "Infantil"). Fica gravado na linha do banco pra
        // cada tela de downloads (adulto/Kids) filtrar só o que é dela.
        profileName: String = "",
        // ✅ NOVO: chamado na Main thread assim que a linha já está
        // garantida no Room — é o sinal certo pra tela começar a
        // monitorar o progresso, em vez de usar postDelayed "no chute".
        aoIniciar: (() -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
                val dns  = (prefs.getString("dns", "") ?: "").trimEnd('/')
                val user = prefs.getString("username", "") ?: ""
                val pass = prefs.getString("password", "") ?: ""

                if (dns.isBlank() || user.isBlank() || pass.isBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Não foi possível iniciar o download (sessão inválida).", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val ext = extensaoContainer?.takeIf { it.isNotBlank() } ?: "mp4"
                val tipoPasta = if (isSeries) "series" else "movie"
                val url = "$dns/$tipoPasta/$user/$pass/$streamId.$ext"
                val tipo = if (isSeries) "series" else "movie"

                val contentId = "${tipo}_${streamId}_${System.currentTimeMillis()}"

                // ✅ Grava no banco ANTES de acionar o DownloadService — a
                // tela de Downloads atualiza na hora (LiveData), sem esperar
                // o Media3 inicializar o DownloadManager internamente.
                val entity = DownloadEntity(
                    stream_id = streamId,
                    name = nomePrincipal,
                    episode_name = nomeEpisodio,
                    image_url = imagemUrl,
                    file_path = contentId,
                    download_url = url,
                    type = tipo,
                    status = STATE_NA_FILA,
                    progress = 0,
                    season = season,
                    profile_name = profileName
                )
                AppDatabase.getDatabase(context).streamDao().insertDownload(entity)

                // ✅ NOVO: só agora, com o insert GARANTIDO, avisa quem
                // chamou que já pode começar a monitorar o progresso.
                withContext(Dispatchers.Main) {
                    aoIniciar?.invoke()
                }

                val request = DownloadRequest.Builder(contentId, Uri.parse(url))
                    .setCustomCacheKey(contentId)
                    .build()

                withContext(Dispatchers.Main) {
                    DownloadService.sendAddDownload(
                        context,
                        VltvDownloadService::class.java,
                        request,
                        /* foreground = */ false
                    )
                }

                // ✅ REMOVIDO: Toast.makeText(context, "Download iniciado...", ...).show()
                // Era um Toast de SUCESSO — o feedback já acontece na própria
                // tela (seta vira "NA FILA"/progresso circular via aoIniciar()
                // + monitoramento). Esse Toast, por ser uma janela do sistema,
                // aparecia flutuando por cima de outros apps (ex: WhatsApp) se
                // o usuário saísse do VLTV Play logo em seguida.
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Erro ao preparar download: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    data class EpisodioParaBaixar(
        val streamId: Int,
        val extensao: String?,
        val nomeExibicao: String
    )

    fun baixarTemporadaCompleta(
        context: Context,
        seriesName: String,
        season: Int,
        episodios: List<EpisodioParaBaixar>,
        imagemUrl: String?,
        // ✅ NOVO: mesmo propósito do parâmetro em iniciarDownload() —
        // repassado pra cada episódio da temporada.
        profileName: String = ""
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.getDatabase(context).streamDao()
            episodios.forEach { ep ->
                val existente = dao.getDownloadByStreamId(ep.streamId, "series")
                if (existente == null || existente.status == STATE_ERRO) {
                    iniciarDownload(
                        context = context,
                        streamId = ep.streamId,
                        nomePrincipal = seriesName,
                        nomeEpisodio = ep.nomeExibicao,
                        imagemUrl = imagemUrl,
                        isSeries = true,
                        season = season,
                        extensaoContainer = ep.extensao,
                        profileName = profileName
                    )
                    delay(300)
                }
            }
            // ✅ REMOVIDO: Toast.makeText(context, "Baixando N episódio(s)...", ...).show()
            // Mesmo motivo do Toast de iniciarDownload(): é feedback de
            // sucesso e a tela (RecyclerView de episódios) já reflete o
            // estado de cada item individualmente.
        }
    }

    // ✅ NOVO: pausa um download em andamento (ou na fila) SEM apagar o que
    // já foi baixado. Diferente de cancelar — dá pra continuar depois de
    // onde parou.
    fun pausarDownload(context: Context, download: DownloadEntity) {
        try {
            DownloadService.sendSetStopReason(
                context,
                VltvDownloadService::class.java,
                download.file_path,
                STOP_REASON_USER_PAUSED,
                /* foreground = */ false
            )
        } catch (e: Exception) { }
        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.getDatabase(context).streamDao()
                .updateDownloadProgressByContentId(download.file_path, STATE_PAUSADO, download.progress)
        }
    }

    // ✅ NOVO: retoma um download pausado, de onde parou (o Media3 mantém
    // os bytes já baixados no cache e continua a partir dali).
    fun continuarDownload(context: Context, download: DownloadEntity) {
        try {
            DownloadService.sendSetStopReason(
                context,
                VltvDownloadService::class.java,
                download.file_path,
                Download.STOP_REASON_NONE,
                /* foreground = */ false
            )
        } catch (e: Exception) { }
        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.getDatabase(context).streamDao()
                .updateDownloadProgressByContentId(download.file_path, STATE_NA_FILA, download.progress)
        }
    }

    // Cancelar: interrompe de vez e apaga os bytes já baixados do cache.
    fun cancelarDownload(context: Context, download: DownloadEntity) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                DownloadService.sendRemoveDownload(
                    context,
                    VltvDownloadService::class.java,
                    download.file_path,
                    /* foreground = */ false
                )
            } catch (e: Exception) { }
            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(context).streamDao().deleteDownload(download.id)
            }
            Toast.makeText(context, "Download cancelado.", Toast.LENGTH_SHORT).show()
        }
    }

    fun excluirDownload(context: Context, download: DownloadEntity, aoConcluir: (() -> Unit)? = null) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                DownloadService.sendRemoveDownload(
                    context,
                    VltvDownloadService::class.java,
                    download.file_path,
                    /* foreground = */ false
                )
            } catch (e: Exception) { }
            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(context).streamDao().deleteDownload(download.id)
            }
            aoConcluir?.invoke()
        }
    }

    fun excluirTemporada(context: Context, seriesName: String, season: Int, aoConcluir: (() -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.getDatabase(context).streamDao()
            val episodios = dao.getDownloadsBySeason(seriesName, season)
            withContext(Dispatchers.Main) {
                episodios.forEach { ep ->
                    try {
                        DownloadService.sendRemoveDownload(
                            context,
                            VltvDownloadService::class.java,
                            ep.file_path,
                            /* foreground = */ false
                        )
                    } catch (e: Exception) { }
                }
            }
            dao.deleteDownloadsBySeason(seriesName, season)
            withContext(Dispatchers.Main) { aoConcluir?.invoke() }
        }
    }

    fun excluirSerieCompleta(context: Context, seriesName: String, aoConcluir: (() -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.getDatabase(context).streamDao()
            val episodios = dao.getDownloadsBySeriesName(seriesName)
            withContext(Dispatchers.Main) {
                episodios.forEach { ep ->
                    try {
                        DownloadService.sendRemoveDownload(
                            context,
                            VltvDownloadService::class.java,
                            ep.file_path,
                            /* foreground = */ false
                        )
                    } catch (e: Exception) { }
                }
            }
            dao.deleteDownloadsBySeries(seriesName)
            withContext(Dispatchers.Main) { aoConcluir?.invoke() }
        }
    }
}
