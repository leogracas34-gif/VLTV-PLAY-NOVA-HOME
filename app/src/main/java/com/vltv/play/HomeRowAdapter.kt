package com.vltv.play

import android.graphics.Color
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
    // Quando essa fileira É a própria fileira "Top 10" (filmes ou séries),
    // o selo TOP 10 no card fica redundante ali (a posição já aparece por
    // conta da própria fileira) — então só é mostrado quando o mesmo item
    // aparece em OUTRA fileira (ex: "Filmes Para Você"), exatamente como
    // pedido: um item do Top 10 que também aparece em outra aba ganha a
    // bandeirinha lá.
    private val mostrarTop10: Boolean = true,
    private val onItemClick: (VodItem) -> Unit
) : RecyclerView.Adapter<HomeRowAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivPoster: ImageView = view.findViewById(R.id.ivPoster)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val ivLogoTitle: ImageView = view.findViewById(R.id.ivLogoTitle)
        val tvBadgeNew: TextView = view.findViewById(R.id.tvBadgeNew)
        val tvBadgeTop10: View = view.findViewById(R.id.tvBadgeTop10)
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

        // Selo de faixa (Novidade / Novo Episódio / Nova Temporada).
        // badgeLabel manda; se estiver vazio, cai no isNovidade antigo, só
        // pra manter compatibilidade com qualquer chamada que ainda não
        // tenha sido atualizada pra passar badgeLabel.
        val textoSelo = item.badgeLabel ?: if (item.isNovidade) "NOVIDADE" else null
        if (textoSelo != null) {
            holder.tvBadgeNew.visibility = View.VISIBLE
            holder.tvBadgeNew.text = textoSelo
            holder.tvBadgeNew.setBackgroundColor(corDoSelo(textoSelo))
        } else {
            holder.tvBadgeNew.visibility = View.GONE
        }

        holder.tvBadgeTop10.visibility =
            if (mostrarTop10 && item.isTop10) View.VISIBLE else View.GONE

        // Se o título/série tem uma logo (a mesma já usada no banner
        // principal), mostra ela no lugar do texto — visual mais parecido
        // com o pôster de verdade. Se não tiver logo, mantém o texto
        // simples (reserva), pra sempre ter algo legível ali.
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

    // Cada tipo de selo tem sua própria cor — reforça a diferença entre
    // "Novidade" (filme/série nova), "Novo Episódio" e "Nova Temporada" só
    // olhando pro card, sem precisar ler o texto todo.
    private fun corDoSelo(texto: String): Int = when (texto) {
        "NOVIDADE" -> Color.parseColor("#D9A24B")        // dourado
        "NOVO EPISÓDIO" -> Color.parseColor("#3E8ED0")   // azul
        "NOVA TEMPORADA" -> Color.parseColor("#A64BD9")  // roxo
        else -> Color.parseColor("#D9A24B")
    }
}
