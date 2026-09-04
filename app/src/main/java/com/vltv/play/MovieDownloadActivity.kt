package com.vltv.play

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.DownloadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// ────────────────────────────────────────────────────────────────
// Tela dedicada de um filme baixado/baixando. Equivalente à
// SeriesEpisodesActivity, mas pra um único filme — assim o usuário tem
// uma tela real com informação e ações (pausar/continuar/cancelar/
// assistir/excluir/ver detalhes) em vez de um Toast "ainda baixando"
// sem nenhuma ação possível.
// ────────────────────────────────────────────────────────────────
class MovieDownloadActivity : AppCompatActivity() {

    private var streamId: Int = 0
    private var movieName: String = ""
    private var movieIcon: String? = null

    private val database by lazy { AppDatabase.getDatabase(this) }
    private var monitorJob: Job? = null
    private var downloadAtual: DownloadEntity? = null

    private lateinit var imgPoster: ImageView
    private lateinit var tvName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var pbProgress: CircularProgressIndicator
    private lateinit var tvPercent: TextView
    private lateinit var btnPrimary: Button
    private lateinit var btnSecondary: Button
    private lateinit var btnVerDetalhes: Button

    // Views do progresso de EXIBIÇÃO (quanto do filme já foi assistido).
    private lateinit var layoutWatchedProgress: LinearLayout
    private lateinit var pbMovieWatched: ProgressBar
    private lateinit var tvMovieWatchedInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())

        setContentView(R.layout.activity_movie_download)

        streamId  = intent.getIntExtra("stream_id", 0)
        movieName = intent.getStringExtra("movie_name") ?: ""
        movieIcon = intent.getStringExtra("movie_icon")

        imgPoster      = findViewById(R.id.imgMoviePoster)
        tvName         = findViewById(R.id.tvMovieName)
        tvStatus       = findViewById(R.id.tvMovieStatus)
        pbProgress     = findViewById(R.id.pbMovieProgress)
        tvPercent      = findViewById(R.id.tvMoviePercent)
        btnPrimary     = findViewById(R.id.btnMoviePrimary)
        btnSecondary   = findViewById(R.id.btnMovieSecondary)
        btnVerDetalhes = findViewById(R.id.btnVerDetalhesFilme)

        layoutWatchedProgress = findViewById(R.id.layoutWatchedProgress)
        pbMovieWatched        = findViewById(R.id.pbMovieWatched)
        tvMovieWatchedInfo    = findViewById(R.id.tvMovieWatchedInfo)

        findViewById<TextView>(R.id.btnBackMovieDownload).setOnClickListener { finish() }

        tvName.text = movieName
        Glide.with(this)
            .load(movieIcon)
            .placeholder(R.drawable.bg_logo_placeholder)
            .centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(imgPoster)

        btnVerDetalhes.setOnClickListener {
            startActivity(Intent(this, DetailsActivity::class.java).apply {
                putExtra("stream_id", streamId)
                putExtra("name", movieName)
                putExtra("icon", movieIcon)
                putExtra("is_series", false)
                putExtra("PROFILE_NAME", perfilAtual())
            })
        }
    }

    override fun onResume() {
        super.onResume()
        iniciarMonitoramento()
        verificarProgressoAssistido()
    }

    override fun onPause() {
        super.onPause()
        monitorJob?.cancel()
    }

    // ✅ NOVO: nome do perfil realmente ativo, lido do mesmo SharedPreferences
    // usado pelo resto do app (SessionManager/ProfilesActivity). Antes essa
    // tela usava "Padrao" fixo em alguns pontos, o que fazia a barra de
    // "Assistido" não bater com a posição salva pelo PlayerActivity quando
    // o usuário estava logado em outro perfil.
    private fun perfilAtual(): String {
        return getSharedPreferences("vltv_prefs", MODE_PRIVATE)
            .getString("last_profile_name", "Padrao") ?: "Padrao"
    }

    // Verifica se existe posição de exibição salva (o quanto do filme o
    // usuário já assistiu OFFLINE) e exibe a barra, igual à tela de
    // Detalhes do Filme — só que lendo a chave gravada pelo PlayerActivity
    // quando o filme é reproduzido em modo "vod_offline".
    private fun verificarProgressoAssistido() {
        val prefs   = getSharedPreferences("vltv_prefs", MODE_PRIVATE)
        val profile = perfilAtual()
        val pos     = prefs.getLong("${profile}_movie_resume_${streamId}_pos", 0L)
        val total   = prefs.getLong("${profile}_movie_resume_${streamId}_dur", 0L)

        if (pos > 30000L && total > 0) {
            layoutWatchedProgress.visibility = View.VISIBLE
            pbMovieWatched.progress = ((pos.toFloat() / total.toFloat()) * 100).toInt()
            val rest    = total - pos
            val hours   = TimeUnit.MILLISECONDS.toHours(rest)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(rest) % 60
            tvMovieWatchedInfo.text = if (hours > 0) {
                "Restam ${hours}h${minutes}min para terminar"
            } else {
                "Restam ${minutes}min para terminar"
            }
        } else {
            layoutWatchedProgress.visibility = View.GONE
        }
    }

    private fun iniciarMonitoramento() {
        monitorJob?.cancel()
        monitorJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                val dl = withContext(Dispatchers.IO) {
                    database.streamDao().getDownloadByStreamId(streamId, "movie")
                }
                downloadAtual = dl

                if (dl == null) {
                    // Foi excluído/cancelado em outra tela — não tem mais o
                    // que gerenciar aqui.
                    Toast.makeText(this@MovieDownloadActivity, "Este download não existe mais.", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }

                aplicarEstado(dl)
                delay(1000)
            }
        }
    }

    private fun aplicarEstado(dl: DownloadEntity) {
        when (dl.status) {
            "NA_FILA" -> {
                tvStatus.text = "Na fila de espera..."
                tvStatus.setTextColor(Color.parseColor("#A6FFFFFF"))
                pbProgress.visibility = View.VISIBLE
                pbProgress.isIndeterminate = true
                tvPercent.text = "Aguardando vaga para começar"
                btnPrimary.text = "Pausar Download"
                btnPrimary.visibility = View.VISIBLE
                btnPrimary.setOnClickListener { DownloadHelper.pausarDownload(this, dl) }
                btnSecondary.text = "Cancelar Download"
                btnSecondary.setOnClickListener { confirmarCancelar(dl) }
            }
            "BAIXANDO", "DOWNLOADING" -> {
                tvStatus.text = "Baixando..."
                tvStatus.setTextColor(Color.parseColor("#A6FFFFFF"))
                pbProgress.visibility = View.VISIBLE
                pbProgress.isIndeterminate = false
                pbProgress.setProgressCompat(dl.progress, true)
                tvPercent.text = "${dl.progress}%"
                btnPrimary.text = "Pausar Download"
                btnPrimary.visibility = View.VISIBLE
                btnPrimary.setOnClickListener { DownloadHelper.pausarDownload(this, dl) }
                btnSecondary.text = "Cancelar Download"
                btnSecondary.setOnClickListener { confirmarCancelar(dl) }
            }
            "PAUSADO" -> {
                tvStatus.text = "Pausado"
                tvStatus.setTextColor(Color.parseColor("#FFC107"))
                pbProgress.visibility = View.VISIBLE
                pbProgress.isIndeterminate = false
                pbProgress.setProgressCompat(dl.progress, true)
                tvPercent.text = "${dl.progress}%"
                btnPrimary.text = "Continuar Download"
                btnPrimary.visibility = View.VISIBLE
                btnPrimary.setOnClickListener { DownloadHelper.continuarDownload(this, dl) }
                btnSecondary.text = "Cancelar Download"
                btnSecondary.setOnClickListener { confirmarCancelar(dl) }
            }
            "ERRO" -> {
                tvStatus.text = "Falha no download"
                tvStatus.setTextColor(Color.parseColor("#FF5252"))
                pbProgress.visibility = View.GONE
                tvPercent.text = ""
                btnPrimary.text = "Tentar Novamente"
                btnPrimary.visibility = View.VISIBLE
                btnPrimary.setOnClickListener {
                    DownloadHelper.excluirDownload(this, dl) {
                        Toast.makeText(this, "Baixe novamente pela tela de detalhes do filme.", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
                btnSecondary.text = "Excluir"
                btnSecondary.setOnClickListener {
                    DownloadHelper.excluirDownload(this, dl) { finish() }
                }
            }
            else -> { // BAIXADO / COMPLETED
                tvStatus.text = "Disponível no dispositivo"
                tvStatus.setTextColor(Color.parseColor("#4CAF50"))
                pbProgress.visibility = View.GONE
                tvPercent.text = ""
                btnPrimary.text = "▶  Assistir"
                btnPrimary.visibility = View.VISIBLE
                btnPrimary.setOnClickListener { abrirPlayerOffline(dl) }
                btnSecondary.text = "Excluir Download"
                btnSecondary.setOnClickListener { confirmarExcluir(dl) }
            }
        }
    }

    private fun abrirPlayerOffline(item: DownloadEntity) {
        if (item.file_path.isBlank() || item.download_url.isBlank()) {
            DownloadDialogHelper.mostrarInfo(this, "Arquivo não encontrado", "Esse download parece estar corrompido. Remova-o e baixe novamente.")
            return
        }

        val profile = perfilAtual()
        // ✅ NOVO: lê a posição salva (mesma chave que o PlayerActivity
        // grava em onPause/onStop/onDestroy) e passa como ponto de partida,
        // pra "Assistir" retomar de onde o usuário parou em vez de sempre
        // começar do zero.
        val prefs = getSharedPreferences("vltv_prefs", MODE_PRIVATE)
        val savedPos = prefs.getLong("${profile}_movie_resume_${item.stream_id}_pos", 0L)

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("stream_id", item.stream_id)
            putExtra("stream_type", "vod_offline")
            putExtra("offline_uri", item.file_path)
            putExtra("offline_url", item.download_url)
            putExtra("channel_name", item.name)
            putExtra("icon", item.image_url)
            // ✅ CORRIGIDO: antes ia sempre "Padrao" fixo aqui, quebrando a
            // leitura/gravação da posição assistida quando o perfil ativo
            // era outro. Agora usa o perfil real da sessão.
            putExtra("PROFILE_NAME", profile)
            if (savedPos > 0L) {
                putExtra("start_position_ms", savedPos)
            }
        }
        startActivity(intent)
    }

    private fun confirmarCancelar(item: DownloadEntity) {
        DownloadDialogHelper.confirmarAcao(
            context     = this,
            titulo      = "Cancelar Download",
            mensagem    = "Isso interrompe o download de \"${item.name}\" e apaga o que já foi baixado.",
            btnPositivo = "Cancelar Download",
            corPositivo = "#FF5252"
        ) {
            DownloadHelper.cancelarDownload(this, item)
            finish()
        }
    }

    private fun confirmarExcluir(item: DownloadEntity) {
        DownloadDialogHelper.confirmarAcao(
            context     = this,
            titulo      = "Excluir Download",
            mensagem    = "Deseja apagar \"${item.name}\" do seu dispositivo? Você pode baixar de novo quando quiser.",
            btnPositivo = "Excluir",
            corPositivo = "#FF5252"
        ) {
            DownloadHelper.excluirDownload(this, item) {
                Toast.makeText(this, "Download excluído", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
