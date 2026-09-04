package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.material.button.MaterialButton
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.SeriesEntity
import com.vltv.play.data.VodEntity
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NovidadesAdapter(
    private var lista: List<NovidadeItem>,
    private val currentProfile: String,
    private val database: AppDatabase,
    private var vodsMap: Map<String, VodEntity>,
    private var seriesMap: Map<String, SeriesEntity>
) : RecyclerView.Adapter<NovidadesAdapter.VH>() {

    // ── OkHttpClient dedicado para logos — conexões persistentes, timeout curto ──
    private val logoClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(8, 30, TimeUnit.SECONDS))
        .build()

    private val logoSemaphore = kotlinx.coroutines.sync.Semaphore(6)

    // ── Scope vinculado ao adapter — cancela tudo quando adapter é descartado ──
    private val adapterScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // CORREÇÃO (trava/carregamento lento da aba Novidades): valor sentinela
    // gravado no cache quando o TMDB não tem logo para o item. Antes, um
    // "não encontrei logo" não era salvo em lugar nenhum, então TODO holder
    // sem logo refazia a chamada de rede /images toda vez que era religado
    // (ex: ao rolar a lista pra cima e voltar), multiplicando chamadas
    // simultâneas e travando o preenchimento da tela. Agora o "não tem logo"
    // também é cacheado, então cada item só bate na rede UMA vez na vida
    // do app (ou até o cache ser limpo).
    private val LOGO_AUSENTE = "__SEM_LOGO__"

    // ── CORREÇÃO (travamento ao rolar): resultado da checagem "esse item
    // já existe no catálogo local?" (série/filme) fica em cache aqui, por
    // idTMDB. Antes, quando não havia match exato pelo nome, o código varria
    // TODO o catálogo local (mapa.values.filter{...}) de novo a cada bind —
    // inclusive nos re-binds causados pela reciclagem de views durante o
    // scroll — e ainda por cima re-normalizava o nome de cada item do mapa
    // (mesmo as chaves do mapa já sendo nomes normalizados). Isso travava a
    // rolagem. Agora: match exato continua síncrono (O(1), instantâneo);
    // quando não bate exato, a varredura "parecida" roda em background UMA
    // única vez por item e o resultado (achou ou não achou) fica salvo aqui
    // — nunca mais refaz o trabalho pesado pra esse item.
    private data class DispResultado(val serie: SeriesEntity?, val filme: VodEntity?)
    private val dispCache = mutableMapOf<Int, DispResultado>()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val imgFundo: ImageView           = view.findViewById(R.id.imgFundoNovidade)
        val imgLogo: ImageView            = view.findViewById(R.id.imgLogoNovidade)
        val tvTitulo: TextView            = view.findViewById(R.id.tvTituloNovidade)
        val tvTagline: TextView           = view.findViewById(R.id.tvTagline)
        val tvSinopse: TextView           = view.findViewById(R.id.tvSinopseNovidade)
        val tvMensagem: TextView?         = try { view.findViewById(R.id.tvMensagemDisponibilidade) } catch (e: Exception) { null }
        val containerBotoes: LinearLayout = view.findViewById(R.id.containerBotoesAtivos)
        // ── Botões agora são MaterialButton (visual profissional: cantos
        // arredondados, ripple, ícone integrado) em vez de LinearLayout manual.
        // A lógica de clique/visibilidade continua idêntica, pois MaterialButton
        // também é uma View/TextView normal.
        val btnAssistir: MaterialButton    = view.findViewById(R.id.btnAssistirNovidade)
        val btnDetalhes: MaterialButton    = view.findViewById(R.id.btnMinhaListaNovidade)
        var job: Job? = null
        // CORREÇÃO: job separado pra busca de disponibilidade em background,
        // cancelado independentemente do job da logo
        var jobDisponibilidade: Job? = null
        // Guarda o id atual para evitar atualizações em holders reciclados
        var tmdbIdAtual: Int = -1
    }

    fun atualizarMapas(vods: Map<String, VodEntity>, series: Map<String, SeriesEntity>) {
        vodsMap   = vods
        seriesMap = series
        // CORREÇÃO: os mapas mudaram (ex: banco terminou de carregar depois
        // do TMDB já ter chegado), então qualquer resultado de disponibilidade
        // salvo em cache antes disso pode estar desatualizado (calculado em
        // cima de mapas vazios/antigos). Limpa pra recalcular do zero.
        dispCache.clear()
        notifyItemRangeChanged(0, lista.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_novidade, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        // Cancela IMEDIATAMENTE qualquer trabalho do holder anterior
        holder.job?.cancel()
        holder.job = null
        holder.jobDisponibilidade?.cancel()
        holder.jobDisponibilidade = null

        val item    = lista[position]
        val context = holder.itemView.context
        val logoPrefs = context.getSharedPreferences("vltv_logos_cache", Context.MODE_PRIVATE)

        // Marca qual id este holder está exibindo agora
        holder.tmdbIdAtual = item.idTMDB

        // ── Textos (síncrono, instantâneo) ──────────────────────────────────
        holder.tvTitulo.text  = item.titulo
        holder.tvSinopse.text = item.sinopse
        holder.tvTagline.text = if (item.isTop10) "🏆 TOP ${item.posicaoTop10}" else item.tagline.uppercase()

        // ── Imagem de fundo — cantos arredondados aplicados pelo próprio
        // MaterialCardView no layout (clipa o conteúdo automaticamente) ──────
        Glide.with(context)
            .load(item.imagemFundoUrl)
            .format(DecodeFormat.PREFER_RGB_565)
            .override(780, 440)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .dontAnimate()
            .centerCrop()
            .placeholder(android.R.color.black)
            .error(android.R.color.black)
            .into(holder.imgFundo)

        // ── Logo: cache SharedPreferences → zero latência ────────────────────
        val cachedLogo = logoPrefs.getString("novidade_logo_${item.idTMDB}", null)

        // CORREÇÃO: trata o valor sentinela "sem logo" — não mostra imagem
        // nem dispara rede, só usa o título como já era feito no caso "sem cache"
        if (cachedLogo == LOGO_AUSENTE) {
            holder.tvTitulo.visibility = View.VISIBLE
            holder.imgLogo.visibility  = View.GONE
        } else if (cachedLogo != null) {
            holder.tvTitulo.visibility = View.GONE
            holder.imgLogo.visibility  = View.VISIBLE
            Glide.with(context)
                .load(cachedLogo)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .dontAnimate()
                .into(holder.imgLogo)
        } else {
            // Sem cache (nunca buscado): título texto visível imediatamente, logo vem em background
            holder.tvTitulo.visibility = View.VISIBLE
            holder.imgLogo.visibility  = View.GONE
        }

        // ── Reset botões ─────────────────────────────────────────────────────
        holder.btnAssistir.visibility     = View.GONE
        holder.tvMensagem?.visibility     = View.GONE
        holder.containerBotoes.visibility = View.VISIBLE

        // ── Disponibilidade ────────────────────────────────────────────────
        resolverDisponibilidade(holder, item, context)

        // ── Busca logo em background somente se NUNCA foi buscado antes ──────
        // CORREÇÃO: antes a condição era `cachedLogo == null`, o que incluía
        // o caso "já busquei e não tinha logo" (porque esse caso nunca era
        // salvo). Agora esse caso vira LOGO_AUSENTE no cache e cai fora daqui,
        // então a rede só é chamada de fato na primeira vez que o item aparece.
        if (cachedLogo == null) {
            val idCapturado = item.idTMDB
            holder.job = adapterScope.launch {
                logoSemaphore.acquire()
                try {
                    if (!isActive) return@launch
                    val logoUrl = buscarLogoTMDB(idCapturado, item.isSerie, logoPrefs)
                    if (logoUrl != null && isActive) {
                        withContext(Dispatchers.Main) {
                            // verifica tmdbIdAtual em vez de adapterPosition
                            // adapterPosition pode ser -1 quando holder está em transição
                            if (holder.tmdbIdAtual == idCapturado) {
                                holder.tvTitulo.visibility = View.GONE
                                holder.imgLogo.visibility  = View.VISIBLE
                                Glide.with(context)
                                    .load(logoUrl)
                                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                                    .transition(DrawableTransitionOptions.withCrossFade(200))
                                    .into(holder.imgLogo)
                            }
                        }
                    }
                    // Se logoUrl == null, buscarLogoTMDB já cuidou de gravar
                    // LOGO_AUSENTE no cache — não precisa fazer nada aqui.
                } finally {
                    logoSemaphore.release()
                }
            }
        }
    }

    // CORREÇÃO (travamento ao rolar): ponto de entrada da checagem de
    // disponibilidade. Resolve na hora se: (a) já está em cache, ou (b) bate
    // por nome exato no mapa (O(1)). Só cai pra busca em background quando
    // nenhum dos dois resolve — e mesmo assim só uma vez por item, porque o
    // resultado fica salvo em dispCache.
    private fun resolverDisponibilidade(holder: VH, item: NovidadeItem, context: Context) {
        val cacheKey = item.idTMDB
        val cacheado = dispCache[cacheKey]
        if (cacheado != null) {
            aplicarDisponibilidade(holder, item, context, cacheado.serie, cacheado.filme)
            return
        }

        val nomeNorm = normalizarNome(item.titulo)
        val serieExata = if (item.isSerie) seriesMap[nomeNorm] else null
        val filmeExata = if (!item.isSerie) vodsMap[nomeNorm] else null

        if (serieExata != null || filmeExata != null) {
            dispCache[cacheKey] = DispResultado(serieExata, filmeExata)
            aplicarDisponibilidade(holder, item, context, serieExata, filmeExata)
            return
        }

        // Não bateu exato: mostra estado provisório (não é "indisponível"
        // definitivo, é "ainda verificando") enquanto a busca parecida roda
        // em background, fora da thread principal.
        aplicarDisponibilidade(holder, item, context, null, null, provisorio = true)

        val idCapturado    = item.idTMDB
        val isSerieCapturada = item.isSerie
        holder.jobDisponibilidade = adapterScope.launch {
            val resultado = buscarDisponibilidadeParecida(nomeNorm, isSerieCapturada)
            dispCache[idCapturado] = resultado
            if (isActive && holder.tmdbIdAtual == idCapturado) {
                withContext(Dispatchers.Main) {
                    aplicarDisponibilidade(holder, item, context, resultado.serie, resultado.filme)
                }
            }
        }
    }

    // CORREÇÃO: roda em Dispatchers.Default (CPU) e compara nomeNorm direto
    // contra as CHAVES do mapa — que já são nomes normalizados (a Activity
    // monta os mapas com associateBy { normalizarNomeBanco(it.name) }).
    // Antes, o código chamava normalizarNome(getNome(item)) de novo pra cada
    // item do mapa, refazendo lowercase + vários replace + regex à toa,
    // já que esse valor já existia pronto como chave do mapa.
    private suspend fun buscarDisponibilidadeParecida(nomeNorm: String, isSerie: Boolean): DispResultado =
        withContext(Dispatchers.Default) {
            if (isSerie) {
                val chave = encontrarChaveParecida(nomeNorm, seriesMap.keys)
                DispResultado(serie = chave?.let { seriesMap[it] }, filme = null)
            } else {
                val chave = encontrarChaveParecida(nomeNorm, vodsMap.keys)
                DispResultado(serie = null, filme = chave?.let { vodsMap[it] })
            }
        }

    // Mesma técnica de sempre: entre os candidatos que batem por substring,
    // escolhe o de nome mais curto (mais próximo do termo buscado).
    private fun encontrarChaveParecida(nomeNorm: String, chaves: Set<String>): String? {
        return chaves
            .filter { chave -> chave.contains(nomeNorm) || nomeNorm.contains(chave) }
            .minByOrNull { it.length }
    }

    private fun aplicarDisponibilidade(
        holder: VH,
        item: NovidadeItem,
        context: Context,
        serieLocal: SeriesEntity?,
        filmeLocal: VodEntity?,
        provisorio: Boolean = false
    ) {
        if (item.isEmBreve) {
            holder.btnAssistir.visibility = View.GONE
            holder.tvMensagem?.text       = "🗓  Disponível no aplicativo após o lançamento"
            holder.tvMensagem?.visibility = View.VISIBLE
            configurarBotaoDetalhes(holder, item, context, null, null)
            return
        }

        if (serieLocal != null || filmeLocal != null) {
            holder.btnAssistir.visibility = View.VISIBLE
            holder.tvMensagem?.visibility = View.GONE
            holder.btnAssistir.setOnClickListener {
                val intent = if (item.isSerie && serieLocal != null) {
                    Intent(context, SeriesDetailsActivity::class.java).apply {
                        putExtra("series_id", serieLocal.series_id)
                        putExtra("name", serieLocal.name)
                        putExtra("icon", serieLocal.cover)
                        putExtra("rating", serieLocal.rating ?: "0.0")
                        putExtra("PROFILE_NAME", currentProfile)
                    }
                } else if (filmeLocal != null) {
                    Intent(context, DetailsActivity::class.java).apply {
                        putExtra("stream_id", filmeLocal.stream_id)
                        putExtra("name", filmeLocal.name)
                        putExtra("icon", filmeLocal.stream_icon)
                        putExtra("poster", filmeLocal.stream_icon)
                        putExtra("rating", filmeLocal.rating ?: "0.0")
                        putExtra("container_extension", filmeLocal.container_extension)
                        putExtra("PROFILE_NAME", currentProfile)
                    }
                } else null
                intent?.let { context.startActivity(it) }
            }
            configurarBotaoDetalhes(holder, item, context, serieLocal, filmeLocal)
        } else if (provisorio) {
            // CORREÇÃO: enquanto a busca parecida ainda não terminou em
            // background, não afirma "em breve disponível" (que é uma
            // mensagem definitiva) — mostra um texto neutro de verificação
            // pra não piscar informação errada antes do resultado real chegar.
            holder.btnAssistir.visibility = View.GONE
            holder.tvMensagem?.text       = "Verificando disponibilidade..."
            holder.tvMensagem?.visibility = View.VISIBLE
            configurarBotaoDetalhes(holder, item, context, null, null)
        } else {
            holder.btnAssistir.visibility = View.GONE
            holder.tvMensagem?.text       = "Em breve disponível no aplicativo"
            holder.tvMensagem?.visibility = View.VISIBLE
            configurarBotaoDetalhes(holder, item, context, null, null)
        }
    }

    private fun configurarBotaoDetalhes(
        holder: VH,
        item: NovidadeItem,
        context: Context,
        serieLocal: SeriesEntity?,
        filmeLocal: VodEntity?
    ) {
        holder.btnDetalhes.setOnClickListener {
            when {
                item.isSerie && serieLocal != null -> {
                    context.startActivity(Intent(context, SeriesDetailsActivity::class.java).apply {
                        putExtra("series_id", serieLocal.series_id)
                        putExtra("name", serieLocal.name)
                        putExtra("icon", serieLocal.cover)
                        putExtra("rating", serieLocal.rating ?: "0.0")
                        putExtra("PROFILE_NAME", currentProfile)
                    })
                }
                !item.isSerie && filmeLocal != null -> {
                    context.startActivity(Intent(context, DetailsActivity::class.java).apply {
                        putExtra("stream_id", filmeLocal.stream_id)
                        putExtra("name", filmeLocal.name)
                        putExtra("icon", filmeLocal.stream_icon)
                        putExtra("rating", filmeLocal.rating ?: "0.0")
                        putExtra("container_extension", filmeLocal.container_extension)
                        putExtra("PROFILE_NAME", currentProfile)
                    })
                }
                else -> {
                    context.startActivity(Intent(context, TmdbDetailsActivity::class.java).apply {
                        putExtra("tmdb_id", item.idTMDB)
                        putExtra("titulo", item.titulo)
                        putExtra("sinopse", item.sinopse)
                        putExtra("imagem_url", item.imagemFundoUrl)
                        putExtra("is_serie", item.isSerie)
                        putExtra("is_em_breve", item.isEmBreve)
                        putExtra("tagline", item.tagline)
                        putExtra("PROFILE_NAME", currentProfile)
                    })
                }
            }
        }
    }

    private fun normalizarNome(nome: String): String {
        var n = nome.lowercase()
        listOf("fhd", "hd", "sd", "4k", "8k", "h265", "leg", "dublado", "dub",
               "nacional", "legendado", "|", "-", "_", ".", "(", ")")
            .forEach { n = n.replace(it, " ") }
        return n.trim().replace(Regex("\\s+"), " ")
    }

    // CORREÇÃO: agora grava LOGO_AUSENTE no cache quando não encontra logo,
    // em vez de simplesmente retornar null sem persistir nada. Isso é o que
    // impede o re-fetch infinito descrito acima.
    private suspend fun buscarLogoTMDB(
        tmdbId: Int,
        isSerie: Boolean,
        prefs: SharedPreferences
    ): String? {
        val tipo = if (isSerie) "tv" else "movie"
        return try {
            val url = "https://api.themoviedb.org/3/$tipo/$tmdbId/images" +
                      "?api_key=${TmdbConfig.API_KEY}&include_image_language=pt,en,null"

            val request  = Request.Builder().url(url).build()
            val response = logoClient.newCall(request).execute()
            val body     = response.body?.string() ?: return null
            response.close()

            val logos = JSONObject(body).optJSONArray("logos") ?: return null
            if (logos.length() == 0) {
                // CORREÇÃO: sem logo disponível — cacheia o sentinela pra
                // nunca mais bater nessa API pra esse id.
                prefs.edit().putString("novidade_logo_$tmdbId", LOGO_AUSENTE).apply()
                return null
            }

            // Prioridade: pt → en → qualquer um
            var path: String? = null
            for (i in 0 until logos.length()) {
                val logo = logos.getJSONObject(i)
                if (logo.optString("iso_639_1") == "pt") { path = logo.optString("file_path"); break }
            }
            if (path == null) {
                for (i in 0 until logos.length()) {
                    val logo = logos.getJSONObject(i)
                    if (logo.optString("iso_639_1") == "en") { path = logo.optString("file_path"); break }
                }
            }
            if (path == null) path = logos.getJSONObject(0).optString("file_path")

            val finalUrl = VpsConfig.tmdbImage(path ?: "", "w500")
            prefs.edit().putString("novidade_logo_$tmdbId", finalUrl).apply()
            finalUrl
        } catch (e: Exception) {
            // CORREÇÃO: erro de rede/parse — NÃO cacheia LOGO_AUSENTE aqui de
            // propósito, pois pode ser falha temporária (timeout, sem
            // internet); nesse caso deve tentar de novo na próxima vez que
            // o item for religado, diferente de "TMDB confirmou que não tem logo".
            null
        }
    }

    override fun getItemCount() = lista.size

    // CORREÇÃO: DiffUtil em vez de notifyDataSetChanged
    // Calcula exatamente quais itens mudaram — evita redesenho completo da lista
    fun atualizarLista(novaLista: List<NovidadeItem>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = lista.size
            override fun getNewListSize() = novaLista.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                lista[oldPos].idTMDB == novaLista[newPos].idTMDB
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                lista[oldPos] == novaLista[newPos]
        })
        lista = novaLista.toList()
        diff.dispatchUpdatesTo(this)
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.job?.cancel()
        holder.job    = null
        holder.jobDisponibilidade?.cancel()
        holder.jobDisponibilidade = null
        holder.tmdbIdAtual = -1
    }

    // Cancela todas as coroutines quando o adapter é descartado
    fun onDestroy() {
        adapterScope.cancel()
    }
}
