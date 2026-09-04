package com.vltv.play.download

import android.content.Context
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import com.vltv.play.DownloadHelper
import com.vltv.play.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

// ────────────────────────────────────────────────────────────────
// Escuta os eventos do DownloadManager do Media3 e replica o status na
// tabela "downloads" do Room.
//
// ✅ CORREÇÃO (porcentagem travada / só atualiza no pause-continue):
// O listener onDownloadChanged() do Media3 só dispara em MUDANÇAS DE ESTADO
// (fila -> baixando -> pausado -> completo), e NÃO a cada byte baixado.
// Por isso a % ficava parada até o usuário pausar/continuar (o que força
// uma transição de estado) ou até o download quase terminar (mais
// transições internas acontecendo perto do fim).
//
// A solução é não depender só do listener: agora existe também um
// POLLING ATIVO (iniciarPollingDeProgresso) que, a cada 1 segundo, lê
// diretamente downloadManager.currentDownloads — que sempre tem o
// percentDownloaded real, atualizado em tempo real pelo próprio Media3 —
// e grava esse valor no banco. Isso garante que baixando 1%, 2%, 3%...
// até 100% apareça de forma contínua, tanto na tela de Detalhes quanto
// na tela de Downloads (ambas leem o banco).
//
// O listener continua existindo para capturar rapidamente as transições
// de estado (BAIXADO, ERRO, PAUSADO, NA_FILA), que não precisam esperar
// o próximo ciclo do polling.
// ────────────────────────────────────────────────────────────────
@UnstableApi
object VltvDownloadObserver {

    private var anexado = false
    private var progressJob: Job? = null
    private val observerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun attach(context: Context) {
        if (anexado) return
        anexado = true

        val appContext = context.applicationContext
        val dm = VltvDownloadTracker.getDownloadManager(appContext)

        dm.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?
            ) {
                aplicarEstado(appContext, download, finalException)
            }
        })

        // ✅ NOVO: polling ativo de progresso real, independente do listener.
        iniciarPollingDeProgresso(appContext, dm)
    }

    private fun aplicarEstado(appContext: Context, download: Download, finalException: Exception?) {
        val contentId = download.request.id
        val progresso = download.percentDownloaded.toInt().coerceIn(0, 100)

        if (download.state == Download.STATE_REMOVING) {
            return
        }

        val status = when {
            download.state == Download.STATE_COMPLETED -> DownloadHelper.STATE_BAIXADO
            download.state == Download.STATE_FAILED -> DownloadHelper.STATE_ERRO
            download.stopReason != Download.STOP_REASON_NONE -> DownloadHelper.STATE_PAUSADO
            download.state == Download.STATE_QUEUED -> DownloadHelper.STATE_NA_FILA
            download.state == Download.STATE_DOWNLOADING ||
                download.state == Download.STATE_RESTARTING -> DownloadHelper.STATE_BAIXANDO
            else -> DownloadHelper.STATE_NA_FILA
        }

        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.getDatabase(appContext).streamDao()
                .updateDownloadProgressByContentId(contentId, status, progresso)
        }

        if (download.state == Download.STATE_FAILED) {
            val motivo = finalException?.message ?: "Erro desconhecido do Media3"
            MainScope().launch {
                Toast.makeText(appContext, "Falha ao baixar: $motivo", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ✅ NOVO: a cada 1s, varre os downloads ativos direto do DownloadManager
    // (fonte da verdade do Media3) e força a gravação do % real no banco.
    // Isso resolve o problema de "porcentagem travada" enquanto o download
    // está rodando normalmente, sem depender de o Media3 avisar sozinho.
    private fun iniciarPollingDeProgresso(appContext: Context, dm: DownloadManager) {
        if (progressJob?.isActive == true) return
        progressJob = observerScope.launch {
            while (isActive) {
                try {
                    val downloadsAtivos = dm.currentDownloads
                    for (d in downloadsAtivos) {
                        if (d.state == Download.STATE_DOWNLOADING || d.state == Download.STATE_RESTARTING) {
                            val progresso = d.percentDownloaded.toInt().coerceIn(0, 100)
                            val contentId = d.request.id
                            AppDatabase.getDatabase(appContext).streamDao()
                                .updateDownloadProgressByContentId(
                                    contentId,
                                    DownloadHelper.STATE_BAIXANDO,
                                    progresso
                                )
                        }
                    }
                } catch (e: Exception) {
                    // DownloadManager pode ainda não estar pronto (carregando índice) — ignora e tenta de novo no próximo ciclo
                }
                delay(1000)
            }
        }
    }
}
