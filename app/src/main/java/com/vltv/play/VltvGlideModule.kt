package com.vltv.play

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import java.io.InputStream

/**
 * Modulo central de rede do Glide, valido para o app inteiro (Home,
 * Filmes, Series, Detalhes, Novidades, Kids, Busca).
 *
 * Problema que este arquivo resolve: em aparelhos ou internet mais
 * fracos, as capas as vezes nao terminavam de carregar e o app caia
 * direto no icone reserva do aplicativo. A causa e que o carregador de
 * rede padrao do Glide usa um timeout curto (cerca de 2,5 segundos) e
 * desiste na primeira falha.
 *
 * A partir de agora o Glide usa o mesmo OkHttpClient compartilhado
 * (SharedHttpClient) que outras telas de imagem/TMDB do app, com
 * timeout maior (15s) e nova tentativa automatica quando a primeira
 * falha — em vez de manter um pool de conexoes so pra si.
 */
@GlideModule
class VltvGlideModule : AppGlideModule() {

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(SharedHttpClient.client)
        )
    }
}
