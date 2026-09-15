package com.vltv.play

import android.app.Activity
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * TemaVisualManager
 *
 * Aplica o TEMA VISUAL SAZONAL (Halloween, Dia das Crianças, Natal, Ano
 * Novo, Carnaval) nas telas principais do app — Home, Filmes/Séries
 * (activity_vod, usada por VodActivity e SeriesActivity), Canais
 * (LiveTvActivity), Detalhes de Filme (DetailsActivity), Detalhes de
 * Série (SeriesDetailsActivity), Novidades e Busca.
 *
 * ⚠️ Este helper só desenha uma CAMADA DECORATIVA por cima da tela (dois
 * cantos com arte temática — teia de aranha, guirlanda, confete etc.).
 * Ele NÃO muda cores de fundo, de botões ou de texto das telas — isso
 * exigiria um refactor bem maior. Ver overlay_tema_decorativo.xml.
 *
 * COMO FUNCIONA (100% remoto, sem gerar APK novo — diferente do ícone
 * do launcher, que precisa estar compilado):
 *
 *   1) Firebase Remote Config guarda a chave:
 *        tema_app_ativo (String) = "" | "halloween" | "criancas" | "natal"
 *                                  | "ano_novo" | "carnaval"
 *      Vazio = nenhum tema ativo, telas ficam normais.
 *
 *   2) A partir desse id, o app busca um JSON fixo na VPS:
 *        https://cdn.vltvplay.tech/temas/<id>/config.json
 *      Exemplo de conteúdo (subido por você na VPS, um arquivo por tema):
 *        {
 *          "canto_esquerdo_url": "https://cdn.vltvplay.tech/temas/halloween/canto_esquerdo.png",
 *          "canto_direito_url":  "https://cdn.vltvplay.tech/temas/halloween/canto_direito.png"
 *        }
 *
 *   3) PRA TROCAR DE TEMA: suba as duas imagens (PNG com fundo
 *      transparente) e o config.json numa pasta nova na VPS
 *      (ex: /temas/natal/) e mude o valor de "tema_app_ativo" no Firebase
 *      pra "natal". Publique — pronto, sem GitHub, sem recompilar.
 *
 *   4) PRA DESLIGAR: deixe "tema_app_ativo" vazia. Os cantos decorativos
 *      somem em todas as telas.
 *
 * USO em cada Activity (chamar depois do setContentView, uma vez):
 *      TemaVisualManager.aplicarEm(this, findViewById(R.id.overlayTemaSazonal))
 *
 * (Na HomeActivity, que já usa ViewBinding, chame com
 *  binding.overlayTemaSazonal.root — ver HomeActivity.kt.)
 */
object TemaVisualManager {

    private const val TAG = "VLTV_TemaVisual"
    private const val KEY_TEMA = "tema_app_ativo"
    private const val BASE_URL = "https://cdn.vltvplay.tech/temas"

    data class TemaConfig(
        val cantoEsquerdoUrl: String,
        val cantoDireitoUrl: String
    )

    // Cache em memória — vale pra vida do processo. Evita baixar o JSON
    // de novo toda vez que o usuário troca de tela. Reinicia o app,
    // busca de novo (o app já reabre com bastante frequência, então o
    // tema nunca fica "preso" desatualizado por muito tempo).
    @Volatile private var temaIdCacheado: String? = null
    @Volatile private var configCacheada: TemaConfig? = null
    @Volatile private var buscaEmAndamento = false

    /**
     * Chama em qualquer Activity, depois do setContentView, passando o
     * FrameLayout do <include layout="@layout/overlay_tema_decorativo">.
     */
    fun aplicarEm(activity: Activity, overlay: FrameLayout?) {
        if (overlay == null) return

        val temaId = try {
            Firebase.remoteConfig.getString(KEY_TEMA).trim().lowercase()
        } catch (e: Exception) {
            "" // Remote Config pode não estar pronto ainda nesta tela — sem problema, sem tema.
        }

        if (temaId.isBlank()) {
            overlay.visibility = View.GONE
            return
        }

        // Já buscamos esse tema nesta sessão do app? Aplica direto, sem rede.
        val configJaCacheada = configCacheada
        if (temaId == temaIdCacheado && configJaCacheada != null) {
            aplicarConfig(activity, overlay, configJaCacheada)
            return
        }

        overlay.visibility = View.GONE // some enquanto busca, evita "piscar" tema velho

        if (buscaEmAndamento) return
        buscaEmAndamento = true

        CoroutineScope(Dispatchers.IO).launch {
            val config = buscarConfig(temaId)
            buscaEmAndamento = false
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                if (config != null) {
                    temaIdCacheado = temaId
                    configCacheada = config
                    aplicarConfig(activity, overlay, config)
                } else {
                    overlay.visibility = View.GONE
                }
            }
        }
    }

    private fun buscarConfig(temaId: String): TemaConfig? {
        return try {
            val request = Request.Builder()
                .url("$BASE_URL/$temaId/config.json")
                .build()
            SharedHttpClient.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "config.json do tema '$temaId' respondeu ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                TemaConfig(
                    cantoEsquerdoUrl = json.optString("canto_esquerdo_url", ""),
                    cantoDireitoUrl  = json.optString("canto_direito_url", "")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao buscar tema '$temaId': ${e.message}")
            null
        }
    }

    private fun aplicarConfig(activity: Activity, overlay: FrameLayout, config: TemaConfig) {
        val imgEsquerdo = overlay.findViewById<ImageView>(R.id.imgTemaCantoEsquerdo)
        val imgDireito  = overlay.findViewById<ImageView>(R.id.imgTemaCantoDireito)
        if (imgEsquerdo == null || imgDireito == null) return

        var algumaImagem = false

        if (config.cantoEsquerdoUrl.isNotBlank()) {
            try {
                Glide.with(activity)
                    .load(config.cantoEsquerdoUrl)
                    .format(DecodeFormat.PREFER_ARGB_8888) // preserva transparência do PNG
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(imgEsquerdo)
                imgEsquerdo.visibility = View.VISIBLE
                algumaImagem = true
            } catch (e: Exception) {
                imgEsquerdo.visibility = View.GONE
            }
        } else {
            imgEsquerdo.visibility = View.GONE
        }

        if (config.cantoDireitoUrl.isNotBlank()) {
            try {
                Glide.with(activity)
                    .load(config.cantoDireitoUrl)
                    .format(DecodeFormat.PREFER_ARGB_8888)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(imgDireito)
                imgDireito.visibility = View.VISIBLE
                algumaImagem = true
            } catch (e: Exception) {
                imgDireito.visibility = View.GONE
            }
        } else {
            imgDireito.visibility = View.GONE
        }

        overlay.visibility = if (algumaImagem) View.VISIBLE else View.GONE
    }
}
