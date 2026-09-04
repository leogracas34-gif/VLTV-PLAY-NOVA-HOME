package com.vltv.play

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cliente OkHttp único, compartilhado por tudo que busca imagens/dados
 * auxiliares do TMDB e afins (Glide, capas de perfil, logos traduzidas,
 * escudos de time).
 *
 * Problema que este arquivo resolve: antes, cada tela que precisava de
 * rede pra esse tipo de chamada (VltvGlideModule, EscudoHelper,
 * ProfilesActivity, SeriesDetailsActivity) criava seu próprio
 * OkHttpClient do zero — cada um com seu próprio pool de conexões e seu
 * próprio pool de threads em segundo plano, gastando memória e conexões
 * à toa no aparelho. Agora todos usam esta única instância, com o mesmo
 * timeout generoso (15s) e a mesma nova tentativa automática que já
 * existia no Glide.
 *
 * IMPORTANTE: isso não inclui o cliente do XtreamApi.kt, que continua
 * isolado de propósito — ele tem configuração própria de DNS-over-HTTPS
 * e failover, feita especificamente pra falar com o painel IPTV, e não
 * deve ser misturada com chamadas de imagem/TMDB.
 */
object SharedHttpClient {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor(maxTentativas = 2))
            .build()
    }

    /**
     * Tenta novamente automaticamente se a primeira tentativa falhar por
     * problema de rede (timeout, conexão instável etc). Sem isso, uma
     * única falha já derruba a imagem/dado direto pro fallback.
     */
    private class RetryInterceptor(private val maxTentativas: Int) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var tentativa = 0
            var ultimaExcecao: IOException? = null
            while (tentativa < maxTentativas) {
                try {
                    return chain.proceed(request)
                } catch (e: IOException) {
                    ultimaExcecao = e
                    tentativa++
                }
            }
            throw ultimaExcecao ?: IOException("Falha apos $maxTentativas tentativas")
        }
    }
}
