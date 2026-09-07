package com.vltv.play

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

        // ✅ NOVO: se existe logo do título, mostra ela por baixo do
        // pôster (estilo Netflix) e esconde o nome em texto. Sem logo,
        // mantém o comportamento antigo (nome em texto).
        if (holder.ivLogo != null) {
            if (!item.logoUrl.isNullOrEmpty()) {
                holder.tvTitle.visibility = View.INVISIBLE
                holder.ivLogo.visibility = View.VISIBLE
                Glide.with(context)
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
