package com.vltv.play.retro

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.vltv.play.R
import com.vltv.play.isTelevisionDevice

class RetroGameAdapter(
    private val games: List<RetroGame>,
    private val onClick: (RetroGame) -> Unit
) : RecyclerView.Adapter<RetroGameAdapter.RetroGameViewHolder>() {

    inner class RetroGameViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val cover: ImageView = itemView.findViewById(R.id.imageRetroCover)
        val name: TextView = itemView.findViewById(R.id.textRetroName)
        val console: TextView = itemView.findViewById(R.id.textRetroConsole)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RetroGameViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_retro_game, parent, false)
        return RetroGameViewHolder(view)
    }

    override fun onBindViewHolder(holder: RetroGameViewHolder, position: Int) {
        val game = games[position]
        holder.name.text = game.name
        holder.console.text = game.console

        Glide.with(holder.itemView.context)
            .load(game.coverUrl)
            .placeholder(R.drawable.ic_retro_placeholder)
            .error(R.drawable.ic_retro_placeholder)
            .into(holder.cover)

        holder.itemView.setOnClickListener { onClick(game) }

        // ✅ Mesmo padrão de foco/D-pad usado no resto do app (Home, Vod,
        // Series, LiveTv): só fica focável e com destaque visual quando é
        // TV — no celular o toque continua funcionando normalmente.
        val isTv = holder.itemView.context.isTelevisionDevice()
        holder.itemView.isFocusable = isTv
        holder.itemView.isFocusableInTouchMode = isTv
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.08f).scaleY(1.08f).translationZ(12f).setDuration(150).start()
            } else {
                v.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(150).start()
            }
        }
    }

    override fun getItemCount(): Int = games.size
}
