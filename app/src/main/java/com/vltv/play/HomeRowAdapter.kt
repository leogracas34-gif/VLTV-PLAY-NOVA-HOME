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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy

// ✅ CORRIGIDO (demora de 2-4 min pra carregar a Home numa instalação
// nova): este adapter buscava a logo (clearlogo do TMDB) AO VIVO, com 2
// chamadas de rede por item visível sem logo_url salva — numa instalação
// nova isso é TODO item de TODA fileira ao mesmo tempo, o que saturava a
// rede e competia com o carregamento dos próprios pôsteres. Essa busca
// foi movida pro TmdbSyncHelper (roda em segundo plano, limitada, sem
// travar nada — ver preencherLogosFaltantes() lá). Este adapter agora só
// LÊ o que já está salvo no banco: tem logo → mostra logo; não tem →
// mostra o nome em texto. Zero chamada de rede na hora de desenhar a tela.
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

        // ✅ Sem busca de rede aqui — só lê o que já está salvo.
        if (holder.ivLogo != null) {
            if (!item.logoUrl.isNullOrEmpty()) {
                mostrarLogo(context, holder, item.logoUrl)
            } else {
                holder.ivLogo.visibility = View.GONE
                holder.tvTitle.visibility = View.VISIBLE
            }
        }

        // Selo de status — só um por vez, ordem de prioridade:
        // Nova Temporada > Novo Episódio > Nova Temporada Em Breve >
        // Novo Episódio Em Breve > Novidade.
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
            item.isNovoEpisodioEmBreve -> {
                holder.tvBadgeStatus.text = "NOVO EPISÓDIO"
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

        ajustarMargemInferior(context, holder.ivLogo, linhasBadge)
        ajustarMargemInferior(context, holder.tvTitle, linhasBadge)

        holder.tvBadgeTop10.visibility = if (item.isTop10) View.VISIBLE else View.GONE

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

    private fun ajustarMargemInferior(context: android.content.Context, view: View?, linhasBadge: Int) {
        if (view == null) return
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val density = context.resources.displayMetrics.density
        val margemBaseDp = if (useWideLayout) 10f else 8f
        val alturaLinhaDp = if (useWideLayout) 18f else 16f
        val folgaBadgeFlutuanteDp = if (linhasBadge > 0) (if (useWideLayout) 10f else 8f) else 0f
        val novaMargemDp = margemBaseDp + folgaBadgeFlutuanteDp + (linhasBadge * alturaLinhaDp)
        params.bottomMargin = (novaMargemDp * density).toInt()
        view.layoutParams = params
    }

    override fun getItemCount() = list.size
}
