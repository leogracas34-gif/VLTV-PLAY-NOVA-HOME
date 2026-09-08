package com.vltv.play

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

// ✅ NOVO: adapter agora suporta dois formatos de card, igual Netflix:
// - useWideLayout = false (padrão): card vertical normal (item_vod_card_horizontal)
//   usado em Filmes, Séries, Novidades etc.
// - useWideLayout = true: card horizontal largo com barra de progresso
//   (item_vod_card_wide), usado só na fileira "Continuar Assistindo".
//
// ✅ NOVO: por baixo do pôster, o card agora prioriza a LOGO do título
// (clearlogo vinda do TMDB, igual já aparece nas telas de detalhes) em
// vez do nome em texto. Se o item não tiver logo salva, cai de volta pro
// nome em texto — nunca fica sem identificação nenhuma.
//
// ✅ NOVO: o selo de status (Novidade / Nova Temporada / Novo Episódio /
// Em Breve) deixou de ser um selinho no canto e virou uma barra sólida
// de largura total embaixo do pôster, igual ao formato da Netflix —
// inclusive com duas linhas empilhadas pro caso "Nova Temporada Em
// Breve" (linha 1 "NOVA TEMPORADA" + linha 2 "EM BREVE"). Como essa
// barra ocupa a base do card, a logo/nome do título é empurrada pra
// cima na mesma proporção pra nunca ficar por baixo do selo.
//
// ✅ NOVO: antes, a logo só aparecia nas fileiras da Home se o campo
// logo_url já estivesse salvo no banco — o que só acontecia pro item que
// já tinha passado pelo banner "Destaque da Semana". Agora, quando um
// item da fileira não tem logo_url, o adapter busca a logo no TMDB em
// segundo plano (com a mesma "vassoura" de limpeza de nome usada em
// Filmes/Séries), mostra assim que encontra e SALVA no banco — assim da
// próxima vez que esse item aparecer (aqui ou em qualquer outra tela) a
// logo já vem pronta, sem precisar buscar de novo.
class HomeRowAdapter(
    private var list: List<VodItem>,
    private val useWideLayout: Boolean = false,
    private val onItemClick: (VodItem) -> Unit
) : RecyclerView.Adapter<HomeRowAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivPoster: ImageView = view.findViewById(R.id.ivPoster)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val ivLogo: ImageView? = view.findViewById(R.id.ivLogo)
        val llBadgeStatus: LinearLayout = view.findViewById(R.id.llBadgeStatus)
        val tvBadgeStatus: TextView = view.findViewById(R.id.tvBadgeNew)
        val tvBadgeStatusLine2: TextView = view.findViewById(R.id.tvBadgeNewLine2)
        val tvBadgeTop10: TextView = view.findViewById(R.id.tvBadgeTop10)
        val pbProgress: ProgressBar? = view.findViewById(R.id.pbProgress)
        var logoJob: Job? = null
    }

    companion object {
        // Cache em memória (sobrevive enquanto o processo do app está
        // vivo) pra nunca buscar a mesma logo duas vezes na mesma sessão.
        // Chave: "v_<id>" pra filme, "s_<id>" pra série.
        private val logoMemoryCache = mutableMapOf<String, String>()
        private const val PREFS_LOGO_HOME = "home_row_logo_cache"

        // ✅ "Vassoura" — mesma lista de tarjas de qualidade/idioma/formato
        // usada em VodActivity/SeriesActivity (LEGENDADO, DUBLADO, 4K,
        // CINEMA, CAM etc.), agora reaproveitada aqui pra que a busca de
        // logo das fileiras da Home também ache o título mesmo com nome
        // "sujo" vindo do servidor Xtream.
        private val REGEX_TARJAS_LOGO_HOME = Regex(
            "(?i)\\b(4K|8K|FULL[\\s.-]?HD|HD|SD|720P|1080P|2160P|DUBLADO|LEGENDADO|LEG|DUB|DUAL|AUDIO|LATINO|" +
            "NACIONAL|PT[-.]?BR|PTBR|WEB[-.]?DL|WEBRIP|BLU-?RAY|REMUX|MKV|MP4|AVI|REPACK|H\\.?264|H\\.?265|" +
            "HEVC|X264|X265|WEB|HDR|UHD|FHD|CAM|HDCAM|TS|TC|R5|SCREENER|CINEMA|LAN[ÇC]AMENTO|EXCLUSIVO|" +
            "COMPLETO|COMPLETE|S\\d{1,2}|E\\d{1,3}|EP\\d{1,3}|TEMPORADA|SEASON)\\b"
        )
    }

    fun updateList(newList: List<VodItem>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = list.size
            override fun getNewListSize() = newList.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                list[oldPos].id == newList[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                list[oldPos] == newList[newPos]
        })
        list = newList
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutRes = if (useWideLayout) R.layout.item_vod_card_wide else R.layout.item_vod_card_horizontal
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]
        val context = holder.itemView.context
        holder.tvTitle.text = item.name

        // Cancela qualquer busca de logo pendente de um item anterior
        // que esse ViewHolder reciclado estivesse carregando.
        holder.logoJob?.cancel()
        holder.logoJob = null

        // ✅ NOVO: se existe logo do título, mostra ela por baixo do
        // pôster (estilo Netflix) e esconde o nome em texto. Sem logo
        // salva, tenta o cache local e, por último, busca no TMDB.
        if (holder.ivLogo != null) {
            if (!item.logoUrl.isNullOrEmpty()) {
                mostrarLogo(context, holder, item.logoUrl)
            } else {
                val cacheKey = "${if (item.isSerie) "s" else "v"}_${item.id}"
                val memCached = logoMemoryCache[cacheKey]
                if (memCached != null) {
                    mostrarLogo(context, holder, memCached)
                } else {
                    val prefs = context.getSharedPreferences(PREFS_LOGO_HOME, Context.MODE_PRIVATE)
                    val diskCached = prefs.getString(cacheKey, null)
                    if (diskCached != null) {
                        logoMemoryCache[cacheKey] = diskCached
                        mostrarLogo(context, holder, diskCached)
                    } else {
                        // Enquanto busca, cai no nome em texto — nunca
                        // fica sem identificação nenhuma.
                        holder.ivLogo.visibility = View.GONE
                        holder.tvTitle.visibility = View.VISIBLE

                        val lifecycleOwner = context as? LifecycleOwner
                        if (lifecycleOwner != null) {
                            holder.logoJob = lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                val url = buscarLogoTmdbHome(item.name, item.isSerie)
                                if (url != null) {
                                    logoMemoryCache[cacheKey] = url
                                    prefs.edit().putString(cacheKey, url).apply()

                                    // Salva no banco pra esse item já vir
                                    // com logo pronta nas próximas vezes,
                                    // em qualquer tela que leia essa tabela.
                                    item.id.toIntOrNull()?.let { idInt ->
                                        try {
                                            val db = AppDatabase.getDatabase(context)
                                            if (item.isSerie) db.streamDao().updateSeriesLogo(idInt, url)
                                            else db.streamDao().updateVodLogo(idInt, url)
                                        } catch (e: Exception) {}
                                    }

                                    withContext(Dispatchers.Main) {
                                        // Guard: só aplica se esse ViewHolder
                                        // ainda estiver mostrando o mesmo item
                                        // (não foi reciclado pra outro).
                                        if (holder.adapterPosition == position) {
                                            mostrarLogo(context, holder, url)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Selo de status — só um por vez, ordem de prioridade:
        // Nova Temporada > Novo Episódio > Em Breve > Novidade. O caso
        // "Em Breve" usa as duas linhas (NOVA TEMPORADA + EM BREVE); os
        // outros usam só a linha 1.
        val linhasBadge: Int
        when {
            item.isNovaTemporada -> {
                holder.tvBadgeStatus.text = "NOVA TEMPORADA"
                holder.tvBadgeStatusLine2.visibility = View.GONE
                holder.llBadgeStatus.visibility = View.VISIBLE
                linhasBadge = 1
            }
            item.isNovoEpisodio -> {
                holder.tvBadgeStatus.text = "NOVO EPISÓDIO"
                holder.tvBadgeStatusLine2.visibility = View.GONE
                holder.llBadgeStatus.visibility = View.VISIBLE
                linhasBadge = 1
            }
            item.isNovaTemporadaEmBreve -> {
                holder.tvBadgeStatus.text = "NOVA TEMPORADA"
                holder.tvBadgeStatusLine2.visibility = View.VISIBLE
                holder.llBadgeStatus.visibility = View.VISIBLE
                linhasBadge = 2
            }
            item.isNovidade -> {
                holder.tvBadgeStatus.text = "NOVIDADE"
                holder.tvBadgeStatusLine2.visibility = View.GONE
                holder.llBadgeStatus.visibility = View.VISIBLE
                linhasBadge = 1
            }
            else -> {
                holder.llBadgeStatus.visibility = View.GONE
                linhasBadge = 0
            }
        }

        // ✅ NOVO: já que o selo agora é uma barra na base do card, a
        // logo/nome do título precisa subir pra não ficar embaixo dela.
        ajustarMargemInferior(context, holder.ivLogo, linhasBadge)
        ajustarMargemInferior(context, holder.tvTitle, linhasBadge)

        // Selo TOP 10 — independente do status acima.
        holder.tvBadgeTop10.visibility = if (item.isTop10) View.VISIBLE else View.GONE

        // Barra de progresso (só existe no card largo).
        if (holder.pbProgress != null) {
            if (item.progressoAssistido in 0..100) {
                holder.pbProgress.progress = item.progressoAssistido
                holder.pbProgress.visibility = View.VISIBLE
            } else {
                holder.pbProgress.visibility = View.GONE
            }
        }

        val larguraPoster = if (useWideLayout) 320 else 180
        val alturaPoster = if (useWideLayout) 180 else 270

        Glide.with(context)
            .asBitmap()
            .load(item.streamIcon)
            .format(DecodeFormat.PREFER_RGB_565)
            .override(larguraPoster, alturaPoster)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .dontAnimate()
            .placeholder(R.drawable.ic_launcher)
            .into(holder.ivPoster)

        holder.itemView.setOnClickListener { onItemClick(item) }

        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.scaleX = if (hasFocus) 1.1f else 1.0f
            v.scaleY = if (hasFocus) 1.1f else 1.0f
            v.elevation = if (hasFocus) 10f else 0f
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.logoJob?.cancel()
        holder.logoJob = null
    }

    private fun mostrarLogo(context: Context, holder: ViewHolder, url: String) {
        val ivLogo = holder.ivLogo ?: return
        holder.tvTitle.visibility = View.INVISIBLE
        ivLogo.visibility = View.VISIBLE
        Glide.with(context)
            .load(url)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .dontAnimate()
            .into(ivLogo)
    }

    // ✅ Busca a logo (clearlogo) no TMDB pra um item que ainda não tem
    // logo_url salva. Limpa o nome com a mesma vassoura de Filmes/Séries
    // antes de perguntar pro TMDB, senão nomes "sujos" (com LEGENDADO,
    // 4K, CINEMA, CAM etc.) nunca batem com o título real.
    private suspend fun buscarLogoTmdbHome(rawName: String, isSerie: Boolean): String? {
        val apiKey = TmdbConfig.API_KEY
        val yearRegex = Regex("\\b(19|20)\\d{2}\\b")
        val year = yearRegex.find(rawName)?.value
        val cleanName = rawName
            .replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "")
            .replace(yearRegex, "")
            .replace(REGEX_TARJAS_LOGO_HOME, "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        if (cleanName.isEmpty()) return null

        val tipo = if (isSerie) "tv" else "movie"
        return try {
            var url = "https://api.themoviedb.org/3/search/$tipo?api_key=$apiKey" +
                    "&query=${URLEncoder.encode(cleanName, "UTF-8")}&language=pt-BR&region=BR&include_adult=false"
            if (!isSerie && year != null) url += "&year=$year"
            val results = JSONObject(URL(url).readText()).getJSONArray("results")
            if (results.length() == 0) return null
            val id = results.getJSONObject(0).getString("id")
            val logos = JSONObject(
                URL("https://api.themoviedb.org/3/$tipo/$id/images?api_key=$apiKey&include_image_language=pt,en,null")
                    .readText()
            ).getJSONArray("logos")
            if (logos.length() == 0) return null
            var path: String? = null
            for (i in 0 until logos.length()) {
                if (logos.getJSONObject(i).optString("iso_639_1") == "pt") {
                    path = logos.getJSONObject(i).getString("file_path"); break
                }
            }
            if (path == null) path = logos.getJSONObject(0).getString("file_path")
            path?.let { VpsConfig.tmdbImage(it, "w500") }
        } catch (e: Exception) { null }
    }

    // ✅ NOVO: eleva a logo/nome do título pra cima do selo de status,
    // já que ambos moram no mesmo FrameLayout ancorados embaixo. Sem
    // selo, usa a margem original de cada layout; com 1 ou 2 linhas de
    // selo, soma a altura aproximada da(s) barra(s).
    private fun ajustarMargemInferior(context: android.content.Context, view: View?, linhasBadge: Int) {
        if (view == null) return
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val density = context.resources.displayMetrics.density
        val margemBaseDp = if (useWideLayout) 10f else 8f
        val alturaLinhaDp = if (useWideLayout) 18f else 16f
        // ✅ O selo de status agora fica centralizado e flutuando (com
        // margem própria embaixo) em vez de colado na borda inferior —
        // então quando ele está visível, soma essa folga extra pra logo/
        // nome não ficar colado em cima dele.
        val folgaBadgeFlutuanteDp = if (linhasBadge > 0) (if (useWideLayout) 10f else 8f) else 0f
        val novaMargemDp = margemBaseDp + folgaBadgeFlutuanteDp + (linhasBadge * alturaLinhaDp)
        params.bottomMargin = (novaMargemDp * density).toInt()
        view.layoutParams = params
    }

    override fun getItemCount() = list.size
}
