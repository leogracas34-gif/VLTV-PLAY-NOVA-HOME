package com.vltv.play

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
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
import android.content.Context

// ────────────────────────────────────────────────────────────────
// ✅ NOVO: Tela DEDICADA de um filme baixado/baixando na Área Kids.
// Equivalente à MovieDownloadActivity do perfil adulto, mas layout e
// classe próprios — nunca compartilha tela com o adulto.
//
// "Ver detalhes" continua abrindo a DetailsActivity — a mesma tela que a
// própria KidsActivity já usa pra mostrar detalhes de filmes do catálogo
// infantil (não é uma tela "adulta" propriamente, é a tela de detalhes
// compartilhada por todo o app, já usada normalmente dentro da Área Kids).
// ────────────────────────────────────────────────────────────────
class KidsMovieDownloadActivity : AppCompatActivity() {

    private var streamId: Int = 0
    private var movieName: String = ""
    private var movieIcon: String? = null

    // ✅ NOVO: mesmo padrão das outras telas Kids — usado pra filtrar o
    // download certo caso o mesmo filme tenha sido baixado tanto pelo
    // adulto quanto pelo Kids (evita mostrar o progresso/estado errado).
    private val perfilAtivo: String
        get() = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            .getString("last_profile_name", "") ?: ""

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())

        setContentView(R.layout.activity_kids_movie_download)

        streamId  = intent.getIntExtra("stream_id", 0)
        movieName = intent.getStringExtra("movie_name") ?: ""
        movieIcon = intent.getStringExtra("movie_icon")

        imgPoster      = findViewById(R.id.imgMoviePosterKids)
        tvName         = findViewById(R.id.tvMovieNameKids)
        tvStatus       = findViewById(R.id.tvMovieStatusKids)
        pbProgress     = findViewById(R.id.pbMovieProgressKids)
        tvPercent      = findViewById(R.id.tvMoviePercentKids)
        btnPrimary     = findViewById(R.id.btnMoviePrimaryKids)
        btnSecondary   = findViewById(R.id.btnMovieSecondaryKids)
        btnVerDetalhes = findViewById(R.id.btnVerDetalhesFilmeKids)

        findViewById<TextView>(R.id.btnBackMovieDownloadKids).setOnClickListener { finish() }

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
                putExtra("PROFILE_NAME", perfilAtivo)
            })
        }
    }

    override fun onResume() {
        super.onResume()
        iniciarMonitoramento()
    }

    override fun onPause() {
        super.onPause()
        monitorJob?.cancel()
    }

    private fun iniciarMonitoramento() {
        monitorJob?.cancel()
        monitorJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                val dl = withContext(Dispatchers.IO) {
                    database.streamDao().getDownloadByStreamIdAndProfile(streamId, "movie", perfilAtivo)
                }
                downloadAtual = dl

                if (dl == null) {
                    Toast.makeText(this@KidsMovieDownloadActivity, "Este download não existe mais.", Toast.LENGTH_SHORT).show()
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
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("stream_id", item.stream_id)
            putExtra("stream_type", "vod_offline")
            putExtra("offline_uri", item.file_path)
            putExtra("offline_url", item.download_url)
            putExtra("channel_name", item.name)
            putExtra("icon", item.image_url)
            putExtra("PROFILE_NAME", perfilAtivo)
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
