package com.vltv.play

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy

class HomeRowAdapter(
    private var list: List<VodItem>,
    private val onItemClick: (VodItem) -> Unit
) : RecyclerView.Adapter<HomeRowAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivPoster: ImageView = view.findViewById(R.id.ivPoster)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val tvBadgeStatus: TextView = view.findViewById(R.id.tvBadgeNew)
        val tvBadgeTop10: TextView = view.findViewById(R.id.tvBadgeTop10)
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
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_vod_card_horizontal, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]
        holder.tvTitle.text = item.name

        // ✅ NOVO: selo de status — só um por vez, ordem de prioridade:
        // Nova Temporada > Novo Episódio > Em Breve > Novidade. Um item
        // pode ter mais de uma flag verdadeira ao mesmo tempo (ex: acabou
        // de ganhar episódio novo E ainda está marcado como "novidade" de
        // catálogo) — mostra só a informação mais específica.
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

        // ✅ NOVO: selo TOP 10 — independente do status acima. Mostra
        // sempre que o item está no Top 10, mesmo aparecendo numa fileira
        // que não é a de Top 10 (ex: dentro de "Novidades").
        holder.tvBadgeTop10.visibility = if (item.isTop10) View.VISIBLE else View.GONE

        Glide.with(holder.itemView.context)
            .asBitmap()
            .load(item.streamIcon)
            .format(DecodeFormat.PREFER_RGB_565)
            .override(180, 270)
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
