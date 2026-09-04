package com.vltv.play

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.DownloadEntity
import android.content.Context

// ────────────────────────────────────────────────────────────────
// ✅ NOVO: Tela DEDICADA da Área Kids, aberta ao tocar num "card de
// série" na KidsDownloadsActivity. Mesma lógica da SeriesEpisodesActivity
// do perfil adulto, mas layout e classe próprios — nunca compartilha tela
// com o adulto.
// ────────────────────────────────────────────────────────────────
class KidsSeriesEpisodesActivity : AppCompatActivity() {

    private lateinit var rvEpisodiosKids: RecyclerView
    private lateinit var imgHeaderPosterKids: ImageView
    private lateinit var tvHeaderNameKids: TextView
    private lateinit var tvHeaderCountKids: TextView
    private lateinit var adapter: KidsEpisodiosAdapter

    private var seriesName: String = ""

    // ✅ NOVO: mesmo padrão do KidsDownloadsActivity — garante que só
    // aparecem episódios baixados pelo perfil Kids ativo, mesmo que o
    // adulto tenha baixado uma série com o mesmo nome.
    private val perfilAtivo: String
        get() = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            .getString("last_profile_name", "") ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())

        setContentView(R.layout.activity_kids_series_episodes)

        seriesName = intent.getStringExtra("series_name") ?: ""
        val seriesImage = intent.getStringExtra("series_image")

        rvEpisodiosKids     = findViewById(R.id.rvEpisodiosKids)
        imgHeaderPosterKids = findViewById(R.id.imgSeriesHeaderPosterKids)
        tvHeaderNameKids    = findViewById(R.id.tvSeriesHeaderNameKids)
        tvHeaderCountKids   = findViewById(R.id.tvSeriesHeaderCountKids)

        findViewById<TextView>(R.id.btnBackSeriesKids).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btnExcluirSerieHeaderKids).setOnClickListener { confirmarExclusaoSerie() }

        tvHeaderNameKids.text = seriesName
        Glide.with(this)
            .load(seriesImage)
            .placeholder(R.drawable.bg_logo_placeholder)
            .centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(imgHeaderPosterKids)

        rvEpisodiosKids.layoutManager = LinearLayoutManager(this)

        adapter = KidsEpisodiosAdapter(
            emptyList(),
            onClickPlay = { item -> abrirPlayerOffline(item) },
            onClickPrimaryAction = { item -> handlePrimaryAction(item) },
            onClickExcluir = { item -> confirmarExclusaoEpisodio(item) }
        )
        rvEpisodiosKids.adapter = adapter

        observarBancoDeDados()
    }

    private fun observarBancoDeDados() {
        val dao = AppDatabase.getDatabase(this).streamDao()
        dao.getDownloadsByProfile(perfilAtivo).observe(this) { lista ->
            val episodios = (lista ?: emptyList())
                .filter { it.type == "series" && it.name == seriesName }
                .sortedWith(compareBy({ it.season }, { it.id }))

            if (episodios.isEmpty()) {
                Toast.makeText(this, "Nenhum episódio baixado dessa série.", Toast.LENGTH_SHORT).show()
                finish()
                return@observe
            }

            tvHeaderCountKids.text = "${episodios.size} episódio(s)"
            adapter.atualizarLista(episodios)
        }
    }

    private fun abrirPlayerOffline(item: DownloadEntity) {
        if (item.file_path.isBlank() || item.download_url.isBlank()) {
            DownloadDialogHelper.mostrarInfo(this, "Arquivo não encontrado", "Esse download parece estar corrompido. Remova-o da lista e baixe novamente.")
            return
        }
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("stream_id", item.stream_id)
            putExtra("stream_type", "vod_offline")
            putExtra("offline_uri", item.file_path)
            putExtra("offline_url", item.download_url)
            putExtra("channel_name", "${item.name} - ${item.episode_name}")
            putExtra("icon", item.image_url)
            putExtra("PROFILE_NAME", perfilAtivo)
        }
        startActivity(intent)
    }

    private fun handlePrimaryAction(item: DownloadEntity) {
        when (item.status) {
            "PAUSADO" -> DownloadDialogHelper.confirmarAcaoDupla(
                context = this,
                titulo = "Episódio Pausado",
                mensagem = "\"${item.episode_name}\" está pausado. O que deseja fazer?",
                btnPrincipal = "Continuar Download",
                corPrincipal = "#FFFFFF",
                onPrincipal = { DownloadHelper.continuarDownload(this, item) },
                btnSecundario = "Cancelar Download",
                corSecundario = "#FF5252",
                onSecundario = { DownloadHelper.cancelarDownload(this, item) }
            )
            else -> DownloadDialogHelper.confirmarAcaoDupla(
                context = this,
                titulo = "Download em Andamento",
                mensagem = "O que deseja fazer com o download de \"${item.episode_name}\"?",
                btnPrincipal = "Pausar Download",
                corPrincipal = "#FFFFFF",
                onPrincipal = { DownloadHelper.pausarDownload(this, item) },
                btnSecundario = "Cancelar Download",
                corSecundario = "#FF5252",
                onSecundario = { DownloadHelper.cancelarDownload(this, item) }
            )
        }
    }

    private fun confirmarExclusaoEpisodio(item: DownloadEntity) {
        DownloadDialogHelper.confirmarAcao(
            context     = this,
            titulo      = "Excluir Episódio",
            mensagem    = "Deseja apagar \"${item.episode_name}\" do seu dispositivo?",
            btnPositivo = "Excluir",
            corPositivo = "#FF5252"
        ) {
            DownloadHelper.excluirDownload(this, item) {
                Toast.makeText(this, "Episódio excluído", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmarExclusaoSerie() {
        DownloadDialogHelper.confirmarAcao(
            context     = this,
            titulo      = "Excluir Série",
            mensagem    = "Deseja apagar TODOS os episódios baixados de \"$seriesName\" do seu dispositivo?",
            btnPositivo = "Excluir Tudo",
            corPositivo = "#FF5252"
        ) {
            DownloadHelper.excluirSerieCompleta(this, seriesName) {
                Toast.makeText(this, "Série removida dos downloads", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    // ADAPTER — próprio da Área Kids, não compartilhado com o adulto
    // ────────────────────────────────────────────────────────────────

    class KidsEpisodiosAdapter(
        private var items: List<DownloadEntity>,
        private val onClickPlay: (DownloadEntity) -> Unit,
        private val onClickPrimaryAction: (DownloadEntity) -> Unit,
        private val onClickExcluir: (DownloadEntity) -> Unit
    ) : RecyclerView.Adapter<KidsEpisodiosAdapter.VH>() {

        fun atualizarLista(novaLista: List<DownloadEntity>) {
            items = novaLista
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val imgPoster: ImageView            = v.findViewById(R.id.imgPoster)
            val tvName: TextView                = v.findViewById(R.id.tvDownloadName)
            val tvEpisodeInfo: TextView          = v.findViewById(R.id.tvDownloadEpisodeInfo)
            val tvPath: TextView                = v.findViewById(R.id.tvDownloadPath)
            val imgStatusPhone: ImageView       = v.findViewById(R.id.imgStatusPhone)
            val layoutProgress: LinearLayout    = v.findViewById(R.id.layoutProgressDownload)
            val pbLinear: ProgressBar           = v.findViewById(R.id.pbDownloadLinear)
            val tvPercent: TextView             = v.findViewById(R.id.tvDownloadPercent)
            val btnPrimaryAction: FrameLayout   = v.findViewById(R.id.btnDownloadPrimaryAction)
            val imgPrimaryAction: ImageView     = v.findViewById(R.id.imgPrimaryAction)
            val btnDelete: ImageView            = v.findViewById(R.id.btnDownloadDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvName.text = item.episode_name ?: item.name
            holder.tvEpisodeInfo.visibility = View.GONE

            Glide.with(holder.itemView.context)
                .load(item.image_url)
                .placeholder(R.drawable.bg_logo_placeholder)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(holder.imgPoster)

            when (item.status) {
                "BAIXANDO", "DOWNLOADING", "NA_FILA" -> {
                    holder.imgStatusPhone.visibility  = View.GONE
                    val naFila = item.status == "NA_FILA"
                    holder.tvPath.text = if (naFila) "Na fila de espera..." else "Baixando..."
                    holder.tvPath.setTextColor(Color.parseColor("#A6FFFFFF"))
                    holder.layoutProgress.visibility  = View.VISIBLE
                    holder.pbLinear.isIndeterminate   = naFila
                    holder.pbLinear.progress          = item.progress
                    holder.tvPercent.text             = if (naFila) "Aguardando" else "${item.progress}%"

                    holder.btnPrimaryAction.visibility = View.VISIBLE
                    holder.imgPrimaryAction.setImageResource(R.drawable.ic_close_premium)
                    (holder.btnPrimaryAction.background?.mutate() as? GradientDrawable)?.setColor(Color.parseColor("#2E2E38"))
                    holder.btnPrimaryAction.setOnClickListener { onClickPrimaryAction(item) }

                    holder.btnDelete.visibility = View.GONE
                    holder.itemView.setOnClickListener { onClickPrimaryAction(item) }
                }
                "PAUSADO" -> {
                    holder.imgStatusPhone.visibility = View.GONE
                    holder.tvPath.text = "Pausado — ${item.progress}%"
                    holder.tvPath.setTextColor(Color.parseColor("#FFC107"))
                    holder.layoutProgress.visibility = View.VISIBLE
                    holder.pbLinear.isIndeterminate  = false
                    holder.pbLinear.progress         = item.progress
                    holder.tvPercent.text            = "${item.progress}%"

                    holder.btnPrimaryAction.visibility = View.VISIBLE
                    holder.imgPrimaryAction.setImageResource(R.drawable.ic_play_circle)
                    (holder.btnPrimaryAction.background?.mutate() as? GradientDrawable)?.setColor(Color.parseColor("#232336"))
                    holder.btnPrimaryAction.setOnClickListener { onClickPrimaryAction(item) }

                    holder.btnDelete.visibility = View.GONE
                    holder.itemView.setOnClickListener { onClickPrimaryAction(item) }
                }
                "ERRO" -> {
                    holder.imgStatusPhone.visibility = View.GONE
                    holder.tvPath.text = "Falha no download"
                    holder.tvPath.setTextColor(Color.parseColor("#FF5252"))
                    holder.layoutProgress.visibility = View.GONE

                    holder.btnPrimaryAction.visibility = View.GONE
                    holder.btnDelete.visibility = View.VISIBLE
                    holder.btnDelete.setOnClickListener { onClickExcluir(item) }
                    holder.itemView.setOnClickListener { onClickExcluir(item) }
                }
                else -> {
                    holder.imgStatusPhone.visibility = View.VISIBLE
                    holder.tvPath.text = "No dispositivo"
                    holder.tvPath.setTextColor(Color.parseColor("#A6FFFFFF"))
                    holder.layoutProgress.visibility = View.GONE

                    holder.btnPrimaryAction.visibility = View.VISIBLE
                    holder.imgPrimaryAction.setImageResource(R.drawable.ic_play_circle)
                    (holder.btnPrimaryAction.background?.mutate() as? GradientDrawable)?.setColor(Color.parseColor("#232336"))
                    holder.btnPrimaryAction.setOnClickListener { onClickPlay(item) }

                    holder.btnDelete.visibility = View.VISIBLE
                    holder.btnDelete.setOnClickListener { onClickExcluir(item) }
                    holder.itemView.setOnClickListener { onClickPlay(item) }
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
