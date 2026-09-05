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
        val ivLogoTitle: ImageView = view.findViewById(R.id.ivLogoTitle)
        val tvBadgeNew: TextView = view.findViewById(R.id.tvBadgeNew)
        val tvBadgeTop10: View = view.findViewById(R.id.tvBadgeTop10)
        val tvBadgeNovaTemporada: TextView = view.findViewById(R.id.tvBadgeNovaTemporada)
        val tvBadgeNovoEpisodio: TextView = view.findViewById(R.id.tvBadgeNovoEpisodio)
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
        holder.tvBadgeTop10.visibility = if (item.isTop10) View.VISIBLE else View.GONE

        // ✅ Só um selo de "novidade" por vez, na ordem de prioridade:
        // nova temporada > novo episódio > novidade (título recém-chegado
        // ao catálogo). Evita dois selos empilhados ao mesmo tempo.
        holder.tvBadgeNovaTemporada.visibility = View.GONE
        holder.tvBadgeNovoEpisodio.visibility = View.GONE
        holder.tvBadgeNew.visibility = View.GONE
        when {
            item.isNovaTemporada -> holder.tvBadgeNovaTemporada.visibility = View.VISIBLE
            item.isNovoEpisodio -> holder.tvBadgeNovoEpisodio.visibility = View.VISIBLE
            item.isNovidade -> holder.tvBadgeNew.visibility = View.VISIBLE
        }

        // ✅ NOVO: se o título/série tem uma logo (a mesma já usada no
        // banner principal), mostra ela no lugar do texto — visual mais
        // parecido com o pôster de verdade. Se não tiver logo, mantém o
        // texto simples (reserva), pra sempre ter algo legível ali.
        if (!item.logoUrl.isNullOrEmpty()) {
            holder.tvTitle.visibility = View.INVISIBLE
            holder.ivLogoTitle.visibility = View.VISIBLE
            Glide.with(holder.itemView.context)
                .load(item.logoUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(holder.ivLogoTitle)
        } else {
            holder.tvTitle.visibility = View.VISIBLE
            holder.ivLogoTitle.visibility = View.GONE
        }

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
