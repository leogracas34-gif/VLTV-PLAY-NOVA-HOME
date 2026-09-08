package com.vltv.play

import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.request.RequestOptions
import com.vltv.play.download.VltvDownloadObserver
import java.io.InputStream

/**
 * Application customizado — ponto de entrada do app.
 *
 * O que faz:
 *  1. Pré-carrega VodEntity e SeriesEntity do Room para memória (ContentRepository)
 *     → HomeActivity lê direto da memória, sem esperar query
 *  2. Configura o Glide com cache maior e formato otimizado
 *     → Imagens carregam do disco sem piscar
 *  3. ✅ NOVO: liga o observador de progresso de downloads do Media3
 *     → é isso que mantém a tabela "downloads" do Room sincronizada com
 *     o status real do DownloadManager do Media3 (baixando/erro/concluído)
 */
@UnstableApi
class VLTVApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // ── ✅ NOVO: liga o observador de downloads do Media3 ──────────────────
        // Precisa ser chamado uma única vez, aqui, pra já estar ativo antes
        // de qualquer tela disparar um download.
        try {
            VltvDownloadObserver.attach(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // ── 1. Pré-carrega dados do banco para memória ────────────────────────
        // Isso roda em background (IO thread) assim que o app inicia.
        // Quando a HomeActivity abrir (alguns milissegundos depois),
        // ContentRepository.vods e .series já estarão prontos.
        ContentRepository.preCarregar(this)

        // ── 2. Configura Glide globalmente ────────────────────────────────────
        // Cache de memória: 64 MB (padrão é ~32 MB)
        // Cache de disco:   300 MB (padrão é 250 MB)
        // Formato:          PREFER_ARGB_8888 para qualidade
        // Estratégia:       ALL — salva original + transformada no disco
        //
        // ✅ NOVO: Glide.init() manual substitui de vez o @GlideModule
        // processado via kapt (removido do projeto — era a causa do erro
        // de build "duplicate class: com.vltv.play.SeriesStream", um bug
        // do kapt gerando o stub de forma inconsistente nessa combinação
        // de versões). Chamar Glide.init() na mão sempre pulou a descoberta
        // via annotation processing — ou seja, o AppGlideModule gerado
        // pelo kapt já não fazia efeito nenhum na prática. O que antes
        // vivia em VltvGlideModule.registerComponents() (arquivo removido)
        // agora é aplicado direto aqui, registrando o Registry logo após
        // o Glide.init(), com o mesmo OkHttpClient compartilhado
        // (SharedHttpClient) — timeout maior (15s) e retry automático,
        // corrigindo capas que não carregavam em internet mais fraca.
        try {
            Glide.init(this, GlideBuilder()
                .setMemoryCache(LruResourceCache(64L * 1024 * 1024))
                .setDiskCache(InternalCacheDiskCacheFactory(this, 300L * 1024 * 1024))
                .setDefaultRequestOptions(
                    RequestOptions()
                        .format(DecodeFormat.PREFER_ARGB_8888)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                )
            )
            Glide.get(this).registry.replace(
                GlideUrl::class.java,
                InputStream::class.java,
                OkHttpUrlLoader.Factory(SharedHttpClient.client)
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
