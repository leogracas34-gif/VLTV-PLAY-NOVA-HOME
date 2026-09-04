package com.vltv.play

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Ponto ÚNICO para mostrar o nome + avatar do perfil ativo no item
 * "Perfil" do BottomNavigationView. Antes essa lógica ficava duplicada
 * (e desatualizada) em cada Activity — agora qualquer tela com um
 * BottomNavigationView e um item de menu R.id.nav_profile só precisa
 * chamar:
 *
 *     BottomNavProfileHelper.aplicarPerfilNoRodape(this, bottomNavigation, currentProfile)
 *
 * em onCreate() e onResume().
 *
 * Fonte da verdade: SharedPreferences "vltv_prefs" → "last_profile_name"
 * e "last_profile_icon", que é onde ProfilesActivity/HomeActivity sempre
 * gravam o perfil selecionado. Os parâmetros profileNameFallback e
 * profileIconFallback são opcionais — só usados se a Activity já tiver
 * esses valores em mãos (ex: vindos de um Intent extra) e o SharedPreferences
 * ainda não tiver sido atualizado por algum motivo.
 */
object BottomNavProfileHelper {

    /**
     * Drawable "à prova de tint": o BottomNavigationView aplica
     * automaticamente uma cor sólida (itemIconTintList) em cima de
     * QUALQUER ícone do menu, inclusive os definidos programaticamente.
     * Isso faz o avatar (uma foto) virar uma silhueta cinza sem detalhe
     * nenhum. Bloqueando setColorFilter (é assim que esse tint é
     * aplicado por baixo dos panos), o avatar mantém suas cores reais,
     * sem afetar o tint normal dos outros ícones do menu.
     */
    private class UntintableDrawable(private val base: Drawable) : Drawable() {
        override fun draw(canvas: Canvas) = base.draw(canvas)
        override fun setAlpha(alpha: Int) { base.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) {
            // Ignorado de propósito — bloqueia o tint do BottomNavigationView.
        }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = base.intrinsicWidth
        override fun getIntrinsicHeight(): Int = base.intrinsicHeight
        override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
            super.setBounds(left, top, right, bottom)
            base.setBounds(left, top, right, bottom)
        }
    }

    fun aplicarPerfilNoRodape(
        activity: Activity,
        bottomNavigation: BottomNavigationView?,
        profileNameFallback: String? = null,
        profileIconFallback: String? = null
    ) {
        val nav = bottomNavigation ?: return
        val profileItem = nav.menu.findItem(R.id.nav_profile) ?: return

        val prefs = activity.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)

        val nome = profileNameFallback?.takeIf { it.isNotEmpty() }
            ?: prefs.getString("last_profile_name", null)?.takeIf { it.isNotEmpty() }
            ?: "Perfil"

        val icone = profileIconFallback?.takeIf { it.isNotEmpty() }
            ?: prefs.getString("last_profile_icon", null)?.takeIf { it.isNotEmpty() }

        profileItem.title = nome

        if (icone.isNullOrEmpty()) return

        // Avatares escolhidos na tela de Perfis são nomes de drawables
        // locais (ex: "av_iron_man"), não URLs — resolve pro resource ID
        // certo antes de mandar pro Glide. Se algum dia vier uma URL de
        // verdade (http/https), o fallback abaixo continua funcionando.
        val ehUrlRemota = icone.startsWith("http://") || icone.startsWith("https://")
        val resId = if (!ehUrlRemota) {
            activity.resources.getIdentifier(icone, "drawable", activity.packageName)
        } else {
            0
        }

        val requestBuilder = if (resId != 0) {
            Glide.with(activity).asBitmap().load(resId)
        } else {
            Glide.with(activity).asBitmap().load(icone)
        }

        requestBuilder
            .circleCrop()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(object : CustomTarget<Bitmap>(96, 96) {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (activity.isFinishing || activity.isDestroyed) return
                    profileItem.icon = UntintableDrawable(BitmapDrawable(activity.resources, resource))
                }
                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }
}
