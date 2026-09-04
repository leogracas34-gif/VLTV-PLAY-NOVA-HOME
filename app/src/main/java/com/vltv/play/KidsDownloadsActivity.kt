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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.content.Context

// ────────────────────────────────────────────────────────────────
// ✅ NOVO: Tela de Downloads DEDICADA da Área Kids.
//
// É uma cópia intencional (mesma lógica, mesmo agrupamento por série,
// mesmo mecanismo de download por trás) da DownloadsActivity do perfil
// adulto — mas 100% separada: layout próprio (activity_kids_downloads),
// classe própria, adapter próprio, e navega apenas para as telas
// dedicadas da Área Kids (KidsSeriesEpisodesActivity /
// KidsMovieDownloadActivity), nunca para as telas do perfil adulto.
//
// O motor de download em si (DownloadHelper, DownloadEntity, o
// VltvDownloadService/VltvDownloadObserver do Media3) é o MESMO usado
// pelo perfil adulto — é o mesmo dispositivo, o mesmo armazenamento;
// só a tela (navegação e visual) é isolada.
// ────────────────────────────────────────────────────────────────
class KidsDownloadsActivity : AppCompatActivity() {

    private lateinit var rvDownloadsKids: RecyclerView
    private lateinit var layoutEmptyDownloadsKids: LinearLayout
    private lateinit var btnExcluirTodosKids: TextView
    private lateinit var adapter: KidsDownloadsAdapter

    // ✅ NOVO: mesma lógica usada em DownloadsActivity (perfil adulto) —
    // lê o perfil ativo agora (que aqui dentro da Área Kids é sempre o
    // perfil Infantil que está sendo usado) e filtra a lista só por ele.
    // Isso garante que o Kids nunca vê download nenhum feito no perfil
    // adulto — só o que ele mesmo baixou.
    private val perfilAtivo: String
        get() = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            .getString("last_profile_name", "") ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())

        setContentView(R.layout.activity_kids_downloads)

        rvDownloadsKids          = findViewById(R.id.rvDownloadsKids)
        layoutEmptyDownloadsKids = findViewById(R.id.layoutEmptyDownloadsKids)
        btnExcluirTodosKids      = findViewById(R.id.btnExcluirTodosKids)

        findViewById<TextView>(R.id.btnBackDownloadsKids).setOnClickListener { finish() }

        rvDownloadsKids.layoutManager = LinearLayoutManager(this)

        adapter = KidsDownloadsAdapter(
            emptyList(),
            onClickPlayMovie = { item -> abrirPlayerOffline(item) },
            onClickPrimaryActionMovie = { item -> handlePrimaryActionMovie(item) },
            onClickExcluirMovie = { item -> confirmarExclusao(item) },
            onClickAbrirMovieScreen = { item -> abrirTelaDeFilme(item) },
            onClickSeriesGroup = { grupo -> abrirEpisodiosDaSerie(grupo) },
            onClickExcluirSerie = { grupo -> confirmarExclusaoSerie(grupo) }
        )
        rvDownloadsKids.adapter = adapter

        btnExcluirTodosKids.setOnClickListener { confirmarExclusaoTotal() }

        observarBancoDeDados()
    }

    private fun observarBancoDeDados() {
        val dao = AppDatabase.getDatabase(this).streamDao()
        // ✅ Filtra só pelos downloads do perfil Kids ativo — nunca mostra
        // downloads feitos no perfil adulto.
        dao.getDownloadsByProfile(perfilAtivo).observe(this) { lista ->
            val listaSegura = lista ?: emptyList()
            if (listaSegura.isEmpty()) {
                layoutEmptyDownloadsKids.visibility = View.VISIBLE
                rvDownloadsKids.visibility          = View.GONE
                btnExcluirTodosKids.visibility       = View.GONE
            } else {
                layoutEmptyDownloadsKids.visibility = View.GONE
                rvDownloadsKids.visibility           = View.VISIBLE
                btnExcluirTodosKids.visibility        = View.VISIBLE
                adapter.atualizarLista(montarLinhasAgrupadas(listaSegura))
            }
        }
    }

    // Reaproveita o mesmo tipo DownloadRow (sealed class) já usado pela
    // tela de downloads do perfil adulto — é só uma estrutura de dados,
    // não tem nenhuma tela/navegação embutida, então reaproveitar aqui
    // não quebra o isolamento das TELAS que o Léo pediu.
    private fun montarLinhasAgrupadas(lista: List<DownloadEntity>): List<DownloadRow> {
        val linhas = mutableListOf<DownloadRow>()
        val indexPorSerie = HashMap<String, Int>()
        val episodiosPorSerie = HashMap<String, MutableList<DownloadEntity>>()

        for (item in lista) {
            if (item.type == "series") {
                val epsList = episodiosPorSerie.getOrPut(item.name) { mutableListOf() }
                epsList.add(item)
                if (!indexPorSerie.containsKey(item.name)) {
                    indexPorSerie[item.name] = linhas.size
                    linhas.add(DownloadRow.SeriesGroup(item.name, null, 0, 0, 0, 0))
                }
            } else {
                linhas.add(DownloadRow.Movie(item))
            }
        }

        val statusEmProgresso = setOf("BAIXANDO", "DOWNLOADING", "NA_FILA", "PAUSADO")

        for ((nome, idx) in indexPorSerie) {
            val eps = episodiosPorSerie[nome] ?: continue
            val imagem = eps.firstOrNull { it.image_url != null }?.image_url
            val emProgresso = eps.count { it.status in statusEmProgresso }
            val comErro = eps.count { it.status == "ERRO" }
            val progressoMedio = if (emProgresso > 0) {
                eps.filter { it.status in statusEmProgresso }.map { it.progress }.average().toInt()
            } else 0
            linhas[idx] = DownloadRow.SeriesGroup(nome, imagem, eps.size, emProgresso, comErro, progressoMedio)
        }

        return linhas
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
            putExtra("channel_name", if (item.episode_name != null) "${item.name} - ${item.episode_name}" else item.name)
            putExtra("icon", item.image_url)
            putExtra("PROFILE_NAME", perfilAtivo)
        }
        startActivity(intent)
    }

    // ✅ Toque no card do filme (fora dos ícones de ação) sempre abre a
    // tela dedicada da Área Kids de gerenciamento daquele download.
    private fun abrirTelaDeFilme(item: DownloadEntity) {
        val intent = Intent(this, KidsMovieDownloadActivity::class.java).apply {
            putExtra("stream_id", item.stream_id)
            putExtra("movie_name", item.name)
            putExtra("movie_icon", item.image_url)
        }
        startActivity(intent)
    }

    private fun abrirEpisodiosDaSerie(grupo: DownloadRow.SeriesGroup) {
        val intent = Intent(this, KidsSeriesEpisodesActivity::class.java).apply {
            putExtra("series_name", grupo.seriesName)
            putExtra("series_image", grupo.imageUrl)
        }
        startActivity(intent)
    }

    private fun handlePrimaryActionMovie(item: DownloadEntity) {
        when (item.status) {
            "PAUSADO" -> DownloadDialogHelper.confirmarAcaoDupla(
                context = this,
                titulo = "Download Pausado",
                mensagem = "\"${item.name}\" está pausado. O que deseja fazer?",
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
                mensagem = "O que deseja fazer com o download de \"${item.name}\"?",
                btnPrincipal = "Pausar Download",
                corPrincipal = "#FFFFFF",
                onPrincipal = { DownloadHelper.pausarDownload(this, item) },
                btnSecundario = "Cancelar Download",
                corSecundario = "#FF5252",
                onSecundario = { DownloadHelper.cancelarDownload(this, item) }
            )
        }
    }

    private fun confirmarExclusao(item: DownloadEntity) {
        val label = if (item.episode_name != null) "${item.name} - ${item.episode_name}" else item.name
        DownloadDialogHelper.confirmarAcao(
            context     = this,
            titulo      = "Excluir Download",
            mensagem    = "Deseja apagar \"$label\" do seu dispositivo?",
            btnPositivo = "Excluir",
            corPositivo = "#FF5252"
        ) {
            DownloadHelper.excluirDownload(this, item) {
                Toast.makeText(this, "Download excluído", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmarExclusaoSerie(grupo: DownloadRow.SeriesGroup) {
        DownloadDialogHelper.confirmarAcao(
            context     = this,
            titulo      = "Excluir Série",
            mensagem    = "Deseja apagar TODOS os ${grupo.totalEpisodes} episódio(s) baixado(s) de \"${grupo.seriesName}\" do seu dispositivo?",
            btnPositivo = "Excluir Tudo",
            corPositivo = "#FF5252"
        ) {
            DownloadHelper.excluirSerieCompleta(this, grupo.seriesName) {
                Toast.makeText(this, "Série removida dos downloads", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmarExclusaoTotal() {
        DownloadDialogHelper.confirmarAcao(
            context     = this,
            titulo      = "Limpar Todos os Downloads",
            mensagem    = "Isso apaga TODOS os filmes e episódios baixados do seu dispositivo. Esta ação não pode ser desfeita.",
            btnPositivo = "Apagar Tudo",
            corPositivo = "#FF5252"
        ) {
            // ✅ Apaga só os downloads DESTE perfil Kids, nunca os do
            // perfil adulto que divide o mesmo aparelho.
            val perfil = perfilAtivo
            CoroutineScope(Dispatchers.IO).launch {
                val dao = AppDatabase.getDatabase(this@KidsDownloadsActivity).streamDao()
                dao.deleteAllDownloadsByProfile(perfil)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    // ADAPTER — próprio da Área Kids, não compartilhado com o adulto
    // ────────────────────────────────────────────────────────────────

    class KidsDownloadsAdapter(
        private var rows: List<DownloadRow>,
        private val onClickPlayMovie: (DownloadEntity) -> Unit,
        private val onClickPrimaryActionMovie: (DownloadEntity) -> Unit,
        private val onClickExcluirMovie: (DownloadEntity) -> Unit,
        private val onClickAbrirMovieScreen: (DownloadEntity) -> Unit,
        private val onClickSeriesGroup: (DownloadRow.SeriesGroup) -> Unit,
        private val onClickExcluirSerie: (DownloadRow.SeriesGroup) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TIPO_FILME = 0
            private const val TIPO_SERIE = 1
        }

        fun atualizarLista(novaLista: List<DownloadRow>) {
            rows = novaLista
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is DownloadRow.Movie -> TIPO_FILME
            is DownloadRow.SeriesGroup -> TIPO_SERIE
        }

        class VHFilme(v: View) : RecyclerView.ViewHolder(v) {
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

        class VHSerie(v: View) : RecyclerView.ViewHolder(v) {
            val imgPoster: ImageView         = v.findViewById(R.id.imgSeriesPoster)
            val tvName: TextView             = v.findViewById(R.id.tvSeriesName)
            val tvCount: TextView            = v.findViewById(R.id.tvSeriesEpisodeCount)
            val layoutProgress: LinearLayout = v.findViewById(R.id.layoutProgressSeries)
            val pbLinear: ProgressBar        = v.findViewById(R.id.pbSeriesLinear)
            val tvPercent: TextView          = v.findViewById(R.id.tvSeriesPercent)
            val btnAbrir: FrameLayout        = v.findViewById(R.id.btnAbrirEpisodios)
            val btnExcluir: ImageView        = v.findViewById(R.id.btnExcluirSerie)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TIPO_SERIE) {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_download_series, parent, false)
                VHSerie(v)
            } else {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
                VHFilme(v)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is DownloadRow.Movie -> bindFilme(holder as VHFilme, row.entity)
                is DownloadRow.SeriesGroup -> bindSerie(holder as VHSerie, row)
            }
        }

        private fun bindFilme(holder: VHFilme, item: DownloadEntity) {
            holder.tvName.text = item.name
            holder.tvEpisodeInfo.visibility = View.GONE

            Glide.with(holder.itemView.context)
                .load(item.image_url)
                .placeholder(R.drawable.bg_logo_placeholder)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(holder.imgPoster)

            holder.itemView.setOnClickListener { onClickAbrirMovieScreen(item) }

            when (item.status) {
                "BAIXANDO", "DOWNLOADING", "NA_FILA" -> {
                    holder.imgStatusPhone.visibility  = View.GONE
                    val naFila = item.status == "NA_FILA"
                    holder.tvPath.text = if (naFila) "Na fila de espera..." else "Baixando..."
                    holder.tvPath.setTextColor(Color.parseColor("#A6FFFFFF"))
                    holder.layoutProgress.visibility  = View.VISIBLE
                    holder.pbLinear.progress          = item.progress
                    holder.pbLinear.isIndeterminate   = naFila
                    holder.tvPercent.text             = if (naFila) "Aguardando" else "${item.progress}%"

                    holder.btnPrimaryAction.visibility = View.VISIBLE
                    holder.imgPrimaryAction.setImageResource(R.drawable.ic_close_premium)
                    (holder.btnPrimaryAction.background?.mutate() as? GradientDrawable)?.setColor(Color.parseColor("#2E2E38"))
                    holder.btnPrimaryAction.setOnClickListener { onClickPrimaryActionMovie(item) }

                    holder.btnDelete.visibility = View.GONE
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
                    holder.btnPrimaryAction.setOnClickListener { onClickPrimaryActionMovie(item) }

                    holder.btnDelete.visibility = View.GONE
                }
                "ERRO" -> {
                    holder.imgStatusPhone.visibility = View.GONE
                    holder.tvPath.text = "Falha no download"
                    holder.tvPath.setTextColor(Color.parseColor("#FF5252"))
                    holder.layoutProgress.visibility = View.GONE

                    holder.btnPrimaryAction.visibility = View.GONE
                    holder.btnDelete.visibility = View.VISIBLE
                    holder.btnDelete.setOnClickListener { onClickExcluirMovie(item) }
                }
                else -> { // BAIXADO / COMPLETED
                    holder.imgStatusPhone.visibility = View.VISIBLE
                    holder.tvPath.text = "No dispositivo"
                    holder.tvPath.setTextColor(Color.parseColor("#A6FFFFFF"))
                    holder.layoutProgress.visibility = View.GONE

                    holder.btnPrimaryAction.visibility = View.VISIBLE
                    holder.imgPrimaryAction.setImageResource(R.drawable.ic_play_circle)
                    (holder.btnPrimaryAction.background?.mutate() as? GradientDrawable)?.setColor(Color.parseColor("#232336"))
                    holder.btnPrimaryAction.setOnClickListener { onClickPlayMovie(item) }

                    holder.btnDelete.visibility = View.VISIBLE
                    holder.btnDelete.setOnClickListener { onClickExcluirMovie(item) }
                }
            }
        }

        private fun bindSerie(holder: VHSerie, grupo: DownloadRow.SeriesGroup) {
            holder.tvName.text = grupo.seriesName

            Glide.with(holder.itemView.context)
                .load(grupo.imageUrl)
                .placeholder(R.drawable.bg_logo_placeholder)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(holder.imgPoster)

            if (grupo.downloadingCount > 0) {
                holder.tvCount.text = "Baixando ${grupo.downloadingCount} de ${grupo.totalEpisodes} episódio(s)"
                holder.layoutProgress.visibility = View.VISIBLE
                holder.pbLinear.progress = grupo.avgProgress
                holder.tvPercent.text = "${grupo.avgProgress}%"
            } else {
                holder.layoutProgress.visibility = View.GONE
                holder.tvCount.text = if (grupo.erroCount > 0 && grupo.erroCount == grupo.totalEpisodes) {
                    "Falha no download"
                } else {
                    "${grupo.totalEpisodes} episódio(s) no dispositivo"
                }
            }

            holder.btnAbrir.setOnClickListener { onClickSeriesGroup(grupo) }
            holder.btnExcluir.setOnClickListener { onClickExcluirSerie(grupo) }
            holder.itemView.setOnClickListener { onClickSeriesGroup(grupo) }
        }

        override fun getItemCount(): Int = rows.size
    }
}
