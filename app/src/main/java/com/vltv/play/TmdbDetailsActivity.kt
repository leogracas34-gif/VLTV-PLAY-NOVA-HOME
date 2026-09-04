package com.vltv.play

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class TmdbDetailsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tmdb_details)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())

        val tmdbId    = intent.getIntExtra("tmdb_id", 0)
        val titulo    = intent.getStringExtra("titulo") ?: ""
        val sinopse   = intent.getStringExtra("sinopse") ?: ""
        val imagemUrl = intent.getStringExtra("imagem_url") ?: ""
        val isSerie   = intent.getBooleanExtra("is_serie", false)
        val isEmBreve = intent.getBooleanExtra("is_em_breve", false)
        val tagline   = intent.getStringExtra("tagline") ?: ""

        val imgBackground = findViewById<ImageView>(R.id.imgTmdbBackground)
        val tvTitulo      = findViewById<TextView>(R.id.tvTmdbTitulo)
        val tvTagline     = findViewById<TextView>(R.id.tvTmdbTagline)
        val tvSinopse     = findViewById<TextView>(R.id.tvTmdbSinopse)
        val tvGenero      = findViewById<TextView>(R.id.tvTmdbGenero)
        val tvElenco      = findViewById<TextView>(R.id.tvTmdbElenco)
        val tvLancamento  = findViewById<TextView>(R.id.tvTmdbLancamento)
        val tvAviso       = findViewById<TextView>(R.id.tvTmdbAviso)
        val btnVoltar     = findViewById<View>(R.id.btnTmdbVoltar)

        btnVoltar.setOnClickListener { finish() }

        // ── Preenche com dados básicos que já chegaram pelo intent ────────
        tvTitulo.text  = titulo
        tvSinopse.text = sinopse
        tvTagline.text = tagline

        // ✅ HD: imagem de fundo em qualidade máxima
        Glide.with(this)
            .load(imagemUrl)
            .format(DecodeFormat.PREFER_ARGB_8888)
            .override(780, 440)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .transition(DrawableTransitionOptions.withCrossFade(300))
            .centerCrop()
            .into(imgBackground)

        // Mensagem de disponibilidade
        tvAviso.text = if (isEmBreve)
            "🗓 Disponível no aplicativo após o lançamento"
        else
            "Em breve disponível no aplicativo"

        // ── Busca detalhes completos no TMDB ──────────────────────────────
        if (tmdbId > 0) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val tipo = if (isSerie) "tv" else "movie"
                    val apiKey = "9b73f5dd15b8165b1b57419be2f29128"
                    val url = "https://api.themoviedb.org/3/$tipo/$tmdbId" +
                              "?api_key=$apiKey&language=pt-BR&append_to_response=credits"
                    val resp = URL(url).readText()
                    val obj  = JSONObject(resp)

                    // Gêneros
                    val genresArr = obj.optJSONArray("genres")
                    val generos = mutableListOf<String>()
                    if (genresArr != null) {
                        for (i in 0 until genresArr.length())
                            generos.add(genresArr.getJSONObject(i).optString("name"))
                    }

                    // Elenco
                    val castArr = obj.optJSONObject("credits")?.optJSONArray("cast")
                    val elenco = mutableListOf<String>()
                    if (castArr != null) {
                        val limite = minOf(castArr.length(), 8)
                        for (i in 0 until limite)
                            elenco.add(castArr.getJSONObject(i).optString("name"))
                    }

                    // Data de lançamento
                    val dataLancamento = obj.optString("release_date",
                        obj.optString("first_air_date", ""))

                    // Sinopse completa (pode ser mais longa que a que veio pelo intent)
                    val sinopseCompleta = obj.optString("overview", sinopse)

                    // Backdrop melhor se disponível
                    val backdropPath = obj.optString("backdrop_path", "")

                    withContext(Dispatchers.Main) {
                        if (!sinopseCompleta.isNullOrEmpty()) tvSinopse.text = sinopseCompleta
                        if (generos.isNotEmpty()) {
                            tvGenero.text = "Gênero: ${generos.joinToString(", ")}"
                            tvGenero.visibility = View.VISIBLE
                        }
                        if (elenco.isNotEmpty()) {
                            tvElenco.text = "Elenco: ${elenco.joinToString(", ")}"
                            tvElenco.visibility = View.VISIBLE
                        }
                        if (dataLancamento.isNotEmpty()) {
                            val ano = dataLancamento.take(4)
                            tvLancamento.text = "Lançamento: $ano"
                            tvLancamento.visibility = View.VISIBLE
                        }
                        // Atualiza background com imagem de maior qualidade se disponível
                        if (backdropPath.isNotEmpty()) {
                            Glide.with(this@TmdbDetailsActivity)
                                .load("https://cdn.vltvplay.tech/t/p/original$backdropPath")
                                .format(DecodeFormat.PREFER_ARGB_8888)
                                .override(1280, 720)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .transition(DrawableTransitionOptions.withCrossFade(400))
                                .centerCrop()
                                .into(imgBackground)
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }
}
