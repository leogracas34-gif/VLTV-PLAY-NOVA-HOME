package com.vltv.play

import android.content.Context
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.DownloadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ────────────────────────────────────────────────────────────────
// Tela aberta ao tocar num "card de série" em Meus Downloads. Mostra só
// os episódios baixados/baixando/na fila/pausados daquela série.
//
// ✅ Mesmo tratamento de estados NA_FILA/PAUSADO que o filme — e o "X"
// abre um diálogo com 2 opções em vez de cancelar direto.
//
// ✅ Botão "Ver Detalhes da Série" (resolve o series_id real pelo nome
// no catálogo, mesma lógica usada em SeriesDetailsActivity pras
// sugestões) e barra de progresso "Assistido" em cada episódio já
// baixado, igual existe na tela de Detalhes da Série.
//
// ✅ CORRIGIDO: o player era aberto com stream_type = "vod_offline"
// (o mesmo tipo usado pelo filme offline), o que fazia o PlayerActivity
// salvar a posição assistida na chave de FILME em vez da chave de SÉRIE
// — a barra "Assistido" abaixo nunca era preenchida. Agora usa
// "series_offline", um tipo próprio que o PlayerActivity reconhece como
// série. Também corrigido o PROFILE_NAME, que ia fixo como "Padrao".
// ────────────────────────────────────────────────────────────────
class SeriesEpisodesActivity : AppCompatActivity() {

    private lateinit var rvEpisodios: RecyclerView
    private lateinit var imgHeaderPoster: ImageView
    private lateinit var tvHeaderName: TextView
    private lateinit var tvHeaderCount: TextView
    private lateinit var adapter: EpisodiosAdapter

    private var seriesName: String = ""
    private var seriesImage: String? = null
    private var currentProfile: String = "Padrao"
    private var currentProfileIcon: String? = null

    // ✅ NOVO: guarda a lista atual de episódios baixados/baixando dessa
    // série (já ordenada por temporada/id), pra poder montar a "mochila"
    // de episódios (só os já BAIXADOS) quando o usuário aperta Assistir.
    private var episodiosAtuais: List<DownloadEntity> = emptyList()

    private val database by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())

        setContentView(R.layout.activity_series_episodes)

        val vltvPrefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        currentProfile = intent.getStringExtra("PROFILE_NAME")
            ?: vltvPrefs.getString("last_profile_name", null)
            ?: "Padrao"
        currentProfileIcon = intent.getStringExtra("PROFILE_ICON")
            ?.takeIf { it.isNotEmpty() }
            ?: vltvPrefs.getString("last_profile_icon", null)?.takeIf { it.isNotEmpty() }

        seriesName  = intent.getStringExtra("series_name") ?: ""
        seriesImage = intent.getStringExtra("series_image")

        rvEpisodios      = findViewById(R.id.rvEpisodios)
        imgHeaderPoster  = findViewById(R.id.imgSeriesHeaderPoster)
        tvHeaderName     = findViewById(R.id.tvSeriesHeaderName)
        tvHeaderCount    = findViewById(R.id.tvSeriesHeaderCount)

        findViewById<TextView>(R.id.btnBackSeries).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btnExcluirSerieHeader).setOnClickListener { confirmarExclusaoSerie() }
        findViewById<TextView>(R.id.btnVerDetalhesSerie).setOnClickListener { abrirDetalhesDaSerie() }

        tvHeaderName.text = seriesName
        Glide.with(this)
            .load(seriesImage)
            .placeholder(R.drawable.bg_logo_placeholder)
            .centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(imgHeaderPoster)

        rvEpisodios.layoutManager = LinearLayoutManager(this)

        adapter = EpisodiosAdapter(
            emptyList(),
            profile = currentProfile,
            onClickPlay = { item -> abrirPlayerOffline(item) },
            onClickPrimaryAction = { item -> handlePrimaryAction(item) },
            onClickExcluir = { item -> confirmarExclusaoEpisodio(item) }
        )
        rvEpisodios.adapter = adapter

        observarBancoDeDados()
    }

    override fun onResume() {
        super.onResume()
        // Progresso de exibição pode ter mudado (usuário voltou de assistir
        // um episódio offline), então força reload das barras "Assistido".
        adapter.notifyDataSetChanged()
    }

    private fun observarBancoDeDados() {
        val dao = AppDatabase.getDatabase(this).streamDao()
        dao.getAllDownloads().observe(this) { lista ->
            val episodios = (lista ?: emptyList())
                .filter { it.type == "series" && it.name == seriesName }
                .sortedWith(compareBy({ it.season }, { it.id }))

            if (episodios.isEmpty()) {
                Toast.makeText(this, "Nenhum episódio baixado dessa série.", Toast.LENGTH_SHORT).show()
                finish()
                return@observe
            }

            tvHeaderCount.text = "${episodios.size} episódio(s)"
            episodiosAtuais = episodios
            adapter.atualizarLista(episodios)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // VER DETALHES DA SÉRIE
    // ─────────────────────────────────────────────────────────────

    private fun normalizarTituloParaMatch(titulo: String): String {
        return titulo
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("(?i)\\b(4K|FULL HD|HD|SD|DUBLADO|LEGENDADO|DUAL|BLURAY|WEB-DL|HEVC|H264|H265|UHD|FHD|HDR)\\b"), "")
            .trim()
    }

    private suspend fun resolverSeriesIdReal(tituloOriginal: String): Pair<Int, String>? =
        withContext(Dispatchers.IO) {
            val tituloLimpo = normalizarTituloParaMatch(tituloOriginal)
            if (tituloLimpo.isBlank()) return@withContext null

            var cursor = database.openHelper.readableDatabase.query(
                "SELECT series_id, cover FROM series_streams WHERE name = ? COLLATE NOCASE LIMIT 1",
                arrayOf(tituloLimpo)
            )
            if (cursor.moveToFirst()) {
                val id    = cursor.getInt(0)
                val cover = cursor.getString(1) ?: ""
                cursor.close()
                return@withContext id to cover
            }
            cursor.close()

            cursor = database.openHelper.readableDatabase.query(
                "SELECT series_id, cover FROM series_streams WHERE name LIKE ? ORDER BY LENGTH(name) ASC LIMIT 1",
                arrayOf("%$tituloLimpo%")
            )
            if (cursor.moveToFirst()) {
                val id    = cursor.getInt(0)
                val cover = cursor.getString(1) ?: ""
                cursor.close()
                return@withContext id to cover
            }
            cursor.close()
            null
        }

    private fun abrirDetalhesDaSerie() {
        lifecycleScope.launch {
            val resolvido = resolverSeriesIdReal(seriesName)
            if (resolvido == null) {
                Toast.makeText(this@SeriesEpisodesActivity, "Essa série não está disponível no seu catálogo atual.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val (idReal, coverReal) = resolvido
            startActivity(Intent(this@SeriesEpisodesActivity, SeriesDetailsActivity::class.java).apply {
                putExtra("series_id", idReal)
                putExtra("name", seriesName)
                putExtra("icon", coverReal.ifEmpty { seriesImage ?: "" })
                putExtra("PROFILE_NAME", currentProfile)
                putExtra("PROFILE_ICON", currentProfileIcon)
            })
        }
    }

    // Status que significam que o episódio AINDA NÃO está pronto pra
    // assistir (só o download completo entra na mochila de "próximo").
    private val statusNaoBaixado = setOf("NA_FILA", "BAIXANDO", "DOWNLOADING", "PAUSADO", "ERRO")

    private fun abrirPlayerOffline(item: DownloadEntity) {
        if (item.file_path.isBlank() || item.download_url.isBlank()) {
            DownloadDialogHelper.mostrarInfo(this, "Arquivo não encontrado", "Esse download parece estar corrompido. Remova-o da lista e baixe novamente.")
            return
        }

        // ✅ NOVO: lê a posição salva (mesma chave que o PlayerActivity
        // grava em onPause/onStop/onDestroy pra série) e passa como ponto
        // de partida, pra "Assistir" retomar de onde parou.
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val savedPos = prefs.getLong("${currentProfile}_series_resume_${item.stream_id}_pos", 0L)

        // ✅ NOVO: mochila de episódios pro botão "Próximo Episódio"
        // funcionar offline. Só entram episódios já BAIXADOS (pula
        // NA_FILA/BAIXANDO/PAUSADO/ERRO) — não faz sentido oferecer
        // "próximo" pra um episódio que ainda não existe no dispositivo.
        // A lista já vem ordenada por temporada/id de observarBancoDeDados.
        val baixados = episodiosAtuais.filter { it.status !in statusNaoBaixado }
        val episodeIds     = ArrayList(baixados.map { it.stream_id })
        val episodeSeasons = ArrayList(baixados.map { it.season })
        val episodeTitles  = ArrayList(baixados.map { "${it.name} - ${it.episode_name}" })
        val episodeExts    = ArrayList(baixados.map { "mp4" }) // não usado na reprodução offline em si, só mantido por paridade com o fluxo online

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("stream_id", item.stream_id)
            // ✅ CORRIGIDO: antes era "vod_offline" (tipo de FILME), o que
            // fazia o PlayerActivity salvar a posição assistida na chave
            // errada e a barra "Assistido" nunca aparecer aqui. Agora usa
            // um tipo próprio de episódio de série offline.
            putExtra("stream_type", "series_offline")
            putExtra("offline_uri", item.file_path)
            putExtra("offline_url", item.download_url)
            putExtra("channel_name", "${item.name} - ${item.episode_name}")
            putExtra("icon", item.image_url)
            // ✅ CORRIGIDO: antes ia sempre "Padrao" fixo, quebrando a
            // leitura/gravação de posição quando o perfil ativo era outro.
            putExtra("PROFILE_NAME", currentProfile)
            if (savedPos > 0L) {
                putExtra("start_position_ms", savedPos)
            }
            if (episodeIds.size > 1) {
                putIntegerArrayListExtra("episode_list", episodeIds)
                putIntegerArrayListExtra("episode_seasons", episodeSeasons)
                putStringArrayListExtra("episode_titles", episodeTitles)
                putStringArrayListExtra("episode_exts", episodeExts)
            }
        }
        startActivity(intent)
    }

    // ✅ mesmo comportamento do filme — Pausar/Cancelar se estiver
    // baixando ou na fila; Continuar/Cancelar se já estiver pausado.
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
    // ADAPTER
    // ────────────────────────────────────────────────────────────────

    class EpisodiosAdapter(
        private var items: List<DownloadEntity>,
        private val profile: String,
        private val onClickPlay: (DownloadEntity) -> Unit,
        private val onClickPrimaryAction: (DownloadEntity) -> Unit,
        private val onClickExcluir: (DownloadEntity) -> Unit
    ) : RecyclerView.Adapter<EpisodiosAdapter.VH>() {

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
            // Views da barra "Assistido"
            val layoutWatched: LinearLayout     = v.findViewById(R.id.layoutWatchedProgressItem)
            val pbWatched: ProgressBar          = v.findViewById(R.id.pbWatchedItem)
            val tvWatchedPercent: TextView      = v.findViewById(R.id.tvWatchedPercentItem)
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

                    holder.layoutWatched.visibility = View.GONE
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

                    holder.layoutWatched.visibility = View.GONE
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

                    holder.layoutWatched.visibility = View.GONE
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

                    // Barra "Assistido" — só faz sentido pra episódio já
                    // baixado. Usa a mesma chave de resume que a tela de
                    // Detalhes da Série já usa.
                    aplicarProgressoAssistido(holder, item)
                }
            }
        }

        private fun aplicarProgressoAssistido(holder: VH, item: DownloadEntity) {
            val prefs = holder.itemView.context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            val pos = prefs.getLong("${profile}_series_resume_${item.stream_id}_pos", 0L)
            val dur = prefs.getLong("${profile}_series_resume_${item.stream_id}_dur", 0L)
            if (pos > 10000L && dur > 0) {
                val percent = ((pos.toFloat() / dur.toFloat()) * 100).toInt().coerceIn(0, 100)
                holder.layoutWatched.visibility = View.VISIBLE
                holder.pbWatched.progress = percent
                holder.tvWatchedPercent.text = if (percent >= 95) "Concluído" else "Assistido $percent%"
            } else {
                holder.layoutWatched.visibility = View.GONE
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
