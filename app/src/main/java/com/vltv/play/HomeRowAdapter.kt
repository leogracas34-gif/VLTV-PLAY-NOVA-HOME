package com.vltv.play

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy

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
class HomeRowAdapter(
    private var list: List<VodItem>,
    private val useWideLayout: Boolean = false,
    private val onItemClick: (VodItem) -> Unit
) : RecyclerView.Adapter<HomeRowAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivPoster: ImageView = view.findViewById(R.id.ivPoster)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val ivLogo: ImageView? = view.findViewById(R.id.ivLogo)
        val tvBadgeStatus: TextView = view.findViewById(R.id.tvBadgeNew)
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
        holder.tvTitle.text = item.name

        // ✅ NOVO: se existe logo do título, mostra ela por baixo do
        // pôster (estilo Netflix) e esconde o nome em texto. Sem logo,
        // mantém o comportamento antigo (nome em texto).
        if (holder.ivLogo != null) {
            if (!item.logoUrl.isNullOrEmpty()) {
                holder.tvTitle.visibility = View.INVISIBLE
                holder.ivLogo.visibility = View.VISIBLE
                Glide.with(holder.itemView.context)
                    .load(item.logoUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .dontAnimate()
                    .into(holder.ivLogo)
            } else {
                holder.ivLogo.visibility = View.GONE
                holder.tvTitle.visibility = View.VISIBLE
            }
        }

        // Selo de status — só um por vez, ordem de prioridade:
        // Nova Temporada > Novo Episódio > Em Breve > Novidade.
        val textoStatus = when {
            item.isNovaTemporada -> "NOVA TEMPORADA"
            item.isNovoEpisodio -> "NOVO EPISÓDIO"
            item.isNovaTemporadaEmBreve -> "EM BREVE"
            item.isNovidade -> "NOVIDADE"
            else -> null
        }
        if (textoStatus != null) {
            holder.tvBadgeStatus.text = textoStatus
            holder.tvBadgeStatus.visibility = View.VISIBLE
        } else {
            holder.tvBadgeStatus.visibility = View.GONE
        }

        // Selo TOP 10 — independente do status acima.
        holder.tvBadgeTop10.visibility = if (item.isTop10) View.VISIBLE else View.GONE

        // ✅ NOVO: barra de progresso (só existe no card largo).
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

        Glide.with(holder.itemView.context)
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

    override fun getItemCount() = list.size
}
