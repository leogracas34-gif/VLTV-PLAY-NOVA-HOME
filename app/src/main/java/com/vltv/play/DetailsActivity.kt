package com.vltv.play

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.progressindicator.CircularProgressIndicator
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import com.vltv.play.data.AppDatabase
import kotlinx.coroutines.*

data class EpisodeData(
    val streamId: Int,
    val season: Int,
    val episode: Int,
    val title: String,
    val thumb: String,
    val videoKey: String? = null
)

class DetailsActivity : AppCompatActivity() {

    private var streamId: Int = 0
    private var name: String = ""
    private var icon: String? = null
    private var rating: String = "0.0"
    private var isSeries: Boolean = false
    private var episodes: List<EpisodeData> = emptyList()
    private var serverYoutubeTrailer: String? = null
    private var currentProfile: String = "Padrao"
    private var currentProfileIcon: String? = null
    private var streamExt: String? = null

    private val database by lazy { AppDatabase.getDatabase(this) }

    // ── Views ────────────────────────────────────────────────────
    private lateinit var imgPoster: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var imgTitleLogo: ImageView
    private lateinit var tvRating: TextView
    private lateinit var tvGenre: TextView
    private lateinit var tvCast: TextView
    private lateinit var tvPlot: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnResume: Button
    private lateinit var btnFavorite: ImageButton
    private var btnDownloadArea: LinearLayout? = null
    private lateinit var imgDownloadState: ImageView
    private lateinit var tvDownloadState: TextView
    private lateinit var imgBackground: ImageView
    private lateinit var tvEpisodesTitle: TextView
    private lateinit var recyclerEpisodes: RecyclerView
    private var tvYear: TextView? = null
    private var btnSettings: Button? = null
    private lateinit var episodesAdapter: EpisodesAdapter
    private var btnDownloadAction: LinearLayout? = null
    private var btnFavoriteLayout: LinearLayout? = null
    private var btnTrailerAction: LinearLayout? = null
    private var btnRestartAction: LinearLayout? = null
    private var layoutProgress: LinearLayout? = null
    private var progressBarMovie: ProgressBar? = null
    private var tvTimeRemaining: TextView? = null
    private lateinit var tabLayoutDetails: TabLayout
    private lateinit var recyclerSuggestions: RecyclerView
    private var bottomNavigation: BottomNavigationView? = null
    private var layoutTabDetails: LinearLayout? = null
    private var tvDetailFullTitle: TextView? = null
    private var tvDetailFullPlot: TextView? = null
    private var tvDetailDuration: TextView? = null
    private var tvDetailReleaseDate: TextView? = null
    private var tvDetailGenre: TextView? = null
    private var tvDetailDirector: TextView? = null
    private var pbDownloadCircular: CircularProgressIndicator? = null

    // ── WebView trailer ──────────────────────────────────────────
    private lateinit var webViewTrailer: WebView
    private lateinit var layoutHero: FrameLayout
    private lateinit var btnToggleMute: ImageView
    private lateinit var trailerLoadingSpinner: ProgressBar
    private lateinit var nestedScrollDetails: NestedScrollView

    private var isMuted = true
    private var trailerReady = false
    private var trailerCarregado = false
    private var heroVisivel = true

    private val trailerHandler = Handler(Looper.getMainLooper())
    private val TRAILER_DELAY_MS = 2000L

    // ── Download ─────────────────────────────────────────────────
    private enum class DownloadState { BAIXAR, NA_FILA, BAIXANDO, PAUSADO, BAIXADO }
    private var downloadState: DownloadState = DownloadState.BAIXAR
    private var downloadAtual: com.vltv.play.data.DownloadEntity? = null
    private var uiMonitorJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "Mozilla/5.0").build())
        }
        .build()

    // ─────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_details)
            configurarTelaTV()

            val vltvPrefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            currentProfile = intent.getStringExtra("PROFILE_NAME")
                ?: vltvPrefs.getString("last_profile_name", null)
                ?: "Padrao"
            currentProfileIcon = intent.getStringExtra("PROFILE_ICON")
                ?.takeIf { it.isNotEmpty() }
                ?: vltvPrefs.getString("last_profile_icon", null)?.takeIf { it.isNotEmpty() }
            streamId             = intent.getIntExtra("stream_id", 0)
            name                 = intent.getStringExtra("name") ?: ""
            icon                 = intent.getStringExtra("icon")
            rating               = intent.getStringExtra("rating") ?: "0.0"
            isSeries             = intent.getBooleanExtra("is_series", false)
            streamExt            = intent.getStringExtra("stream_ext")
            serverYoutubeTrailer = intent.getStringExtra("youtube_trailer")
                ?.takeIf { it.isNotEmpty() && it != "null" }

            inicializarViews()
            setupWebViewTrailer()
            setupBottomNavigation()
            carregarConteudo()
            setupEventos()
            setupEpisodesRecycler()
            tentarCarregarTextoCache()
            tentarCarregarLogoCache()
            sincronizarDadosTMDB()

            trailerHandler.postDelayed({
                if (!isFinishing && !isDestroyed) buscarETocarTrailer()
            }, TRAILER_DELAY_MS)

        } catch (e: Exception) {
            Log.e("VLTV_DEBUG", "Erro no onCreate: ${e.message}")
            Toast.makeText(this, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val vltvPrefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        currentProfile = vltvPrefs.getString("last_profile_name", currentProfile) ?: currentProfile
        currentProfileIcon = vltvPrefs.getString("last_profile_icon", currentProfileIcon)
            ?.takeIf { it.isNotEmpty() } ?: currentProfileIcon
        BottomNavProfileHelper.aplicarPerfilNoRodape(this, bottomNavigation, currentProfile, currentProfileIcon)
        restaurarEstadoDownload()
        verificarResume()
        if (trailerReady) {
            try { webViewTrailer.onResume() } catch (e: Exception) { }
        }
    }

    override fun onPause() {
        super.onPause()
        if (trailerReady) {
            try {
                webViewTrailer.evaluateJavascript("pauseTrailer();", null)
                webViewTrailer.onPause()
            } catch (e: Exception) { }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        trailerHandler.removeCallbacksAndMessages(null)
        client.dispatcher.cancelAll()
        uiMonitorJob?.cancel()
        try {
            webViewTrailer.evaluateJavascript("pauseTrailer();", null)
            webViewTrailer.stopLoading()
            webViewTrailer.destroy()
        } catch (e: Exception) { }
    }

    // ─────────────────────────────────────────────────────────────
    // WEBVIEW — IFrame API do YouTube
    // ─────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewTrailer() {
        webViewTrailer.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode             = true
            useWideViewPort                  = true
            cacheMode                        = WebSettings.LOAD_NO_CACHE
        }
        webViewTrailer.setBackgroundColor(Color.BLACK)
        webViewTrailer.webChromeClient = WebChromeClient()
        webViewTrailer.webViewClient   = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true
        }

        nestedScrollDetails.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val heroHeight = layoutHero.height.takeIf { it > 0 }
                ?: (420 * resources.displayMetrics.density).toInt()
            val alpha = 1f - (scrollY.toFloat() / heroHeight).coerceIn(0f, 1f)
            layoutHero.alpha    = alpha
            btnToggleMute.alpha = alpha

            if (trailerReady) {
                if (alpha <= 0f && heroVisivel) {
                    heroVisivel = false
                    try { webViewTrailer.evaluateJavascript("pauseTrailer();", null) } catch (e: Exception) { }
                } else if (alpha > 0f && !heroVisivel) {
                    heroVisivel = true
                    try { webViewTrailer.evaluateJavascript("playTrailer();", null) } catch (e: Exception) { }
                }
            }
        }
    }

    private fun carregarTrailerNoWebView(youtubeKey: String) {
        if (trailerCarregado) return
        trailerCarregado = true

        val html = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  * { margin:0; padding:0; box-sizing:border-box; background:#000; }
  html, body { width:100%; height:100%; overflow:hidden; }
  #player {
    position: absolute;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    min-width: 100%;
    min-height: 100%;
    width: 177.78vh;
    height: 56.25vw;
  }
</style>
</head>
<body>
<div id="player"></div>
<script>
  var tag = document.createElement('script');
  tag.src = "https://www.youtube.com/iframe_api";
  document.head.appendChild(tag);

  var player;
  var muted = true;

  function onYouTubeIframeAPIReady() {
    player = new YT.Player('player', {
      videoId: '$youtubeKey',
      playerVars: {
        autoplay: 1,
        mute: 1,
        loop: 1,
        playlist: '$youtubeKey',
        controls: 0,
        showinfo: 0,
        rel: 0,
        iv_load_policy: 3,
        cc_load_policy: 0,
        disablekb: 1,
        fs: 0,
        modestbranding: 1,
        playsinline: 1
      },
      events: {
        onReady: function(e) { e.target.playVideo(); },
        onStateChange: function(e) {
          if (e.data === YT.PlayerState.ENDED) {
            player.seekTo(0);
            player.playVideo();
          }
        }
      }
    });
  }

  function pauseTrailer()  { if(player && player.pauseVideo) player.pauseVideo(); }
  function playTrailer()   { if(player && player.playVideo)  player.playVideo();  }
  function muteTrailer()   { if(player && player.mute)       player.mute();       muted=true;  }
  function unmuteTrailer() { if(player && player.unMute)     player.unMute();     muted=false; }
</script>
</body>
</html>
        """.trimIndent()

        webViewTrailer.loadDataWithBaseURL(
            "https://vltv.app",
            html,
            "text/html",
            "utf-8",
            null
        )

        trailerHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                trailerReady = true
                imgBackground.animate().alpha(0f).setDuration(800).start()
                webViewTrailer.animate().alpha(1f).setDuration(800)
                    .withStartAction { webViewTrailer.visibility = View.VISIBLE }
                    .start()
                btnToggleMute.visibility         = View.VISIBLE
                trailerLoadingSpinner.visibility = View.GONE
            }
        }, 2000L)
    }

    // ─────────────────────────────────────────────────────────────
    // BUSCA TRAILER NO TMDB
    // ─────────────────────────────────────────────────────────────

    private fun buscarETocarTrailer() {
        if (!serverYoutubeTrailer.isNullOrEmpty()) {
            carregarTrailerNoWebView(serverYoutubeTrailer!!)
            return
        }

        val tipo    = if (isSeries) "tv" else "movie"
        val apiKey  = TmdbConfig.API_KEY
        val encoded = URLEncoder.encode(limparNomeParaTMDB(name), "UTF-8")
        val url     = "https://api.themoviedb.org/3/search/$tipo?api_key=$apiKey&query=$encoded&language=pt-BR&region=BR"

        trailerLoadingSpinner.visibility = View.VISIBLE

        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { trailerLoadingSpinner.visibility = View.GONE }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: run {
                    runOnUiThread { trailerLoadingSpinner.visibility = View.GONE }; return
                }
                try {
                    val results = JSONObject(body).optJSONArray("results")
                    if (results == null || results.length() == 0) {
                        runOnUiThread { trailerLoadingSpinner.visibility = View.GONE }; return
                    }
                    val tmdbId = results.getJSONObject(0).optInt("id", 0)
                    if (tmdbId == 0) {
                        runOnUiThread { trailerLoadingSpinner.visibility = View.GONE }; return
                    }
                    buscarYouTubeKey(tmdbId, tipo, apiKey)
                } catch (e: Exception) {
                    runOnUiThread { trailerLoadingSpinner.visibility = View.GONE }
                }
            }
        })
    }

    private fun buscarYouTubeKey(tmdbId: Int, tipo: String, apiKey: String) {
        val idiomas     = listOf("pt-BR", "en-US")
        val prioridades = listOf("Trailer", "Teaser", "Clip", "Featurette")
        val filtroNome = listOf("legendado", "dublado", "subtitled", "dubbed", "leg.", "dub.")

        fun tentarIdioma(idx: Int) {
            if (idx >= idiomas.size) {
                runOnUiThread { trailerLoadingSpinner.visibility = View.GONE }; return
            }
            val url = "https://api.themoviedb.org/3/$tipo/$tmdbId/videos?api_key=$apiKey&language=${idiomas[idx]}"
            client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { tentarIdioma(idx + 1) }
                override fun onResponse(call: Call, response: Response) {
                    val body   = response.body?.string() ?: run { tentarIdioma(idx + 1); return }
                    val videos = try { JSONObject(body).optJSONArray("results") } catch (e: Exception) { null }
                    if (videos == null || videos.length() == 0) { tentarIdioma(idx + 1); return }

                    var key: String? = null

                    outer@ for (tp in prioridades) {
                        for (i in 0 until videos.length()) {
                            val v         = videos.getJSONObject(i)
                            val nomeVideo = v.optString("name", "")
                            val ehLegDub  = filtroNome.any { nomeVideo.contains(it, ignoreCase = true) }
                            if (v.optString("site") == "YouTube" &&
                                v.optString("type").equals(tp, ignoreCase = true) &&
                                v.optBoolean("official", false) &&
                                !ehLegDub) {
                                key = v.optString("key").takeIf { it.isNotEmpty() }
                                if (key != null) break@outer
                            }
                        }
                    }

                    if (key == null && idx == idiomas.lastIndex) {
                        for (i in 0 until videos.length()) {
                            val v = videos.getJSONObject(i)
                            if (v.optString("site") == "YouTube") {
                                key = v.optString("key").takeIf { it.isNotEmpty() }
                                if (key != null) break
                            }
                        }
                    }

                    if (key != null) {
                        runOnUiThread {
                            trailerLoadingSpinner.visibility = View.GONE
                            if (!isFinishing && !isDestroyed) carregarTrailerNoWebView(key!!)
                        }
                    } else {
                        tentarIdioma(idx + 1)
                    }
                }
            })
        }
        tentarIdioma(0)
    }

    // ─────────────────────────────────────────────────────────────
    // SETUP
    // ─────────────────────────────────────────────────────────────

    private fun configurarTelaTV() {
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        ctrl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isTelevisionDevice()) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ctrl?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctrl?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun inicializarViews() {
        imgPoster           = findViewById(R.id.imgPoster)
        tvTitle             = findViewById(R.id.tvTitle)
        imgTitleLogo        = findViewById(R.id.imgTitleLogo)
        tvRating            = findViewById(R.id.tvRating)
        tvGenre             = findViewById(R.id.tvGenre)
        tvCast              = findViewById(R.id.tvCast)
        tvPlot              = findViewById(R.id.tvPlot)
        btnPlay             = findViewById(R.id.btnPlay)
        btnResume           = findViewById(R.id.btnResume)
        btnFavorite         = findViewById(R.id.btnFavorite)
        btnDownloadArea     = findViewById(R.id.btnDownloadArea)
        imgDownloadState    = findViewById(R.id.imgDownloadState)
        tvDownloadState     = findViewById(R.id.tvDownloadState)
        imgBackground       = findViewById(R.id.imgBackground)
        tvEpisodesTitle     = findViewById(R.id.tvEpisodesTitle)
        recyclerEpisodes    = findViewById(R.id.recyclerEpisodes)
        tvYear              = findViewById(R.id.tvYear)
        btnSettings         = findViewById(R.id.btnSettings)
        btnDownloadAction   = findViewById(R.id.btnDownloadAction)
        btnFavoriteLayout   = findViewById(R.id.btnFavoriteLayout)
        btnTrailerAction    = findViewById(R.id.btnTrailerAction)
        btnRestartAction    = findViewById(R.id.btnRestartAction)
        layoutProgress      = findViewById(R.id.layoutProgress)
        progressBarMovie    = findViewById(R.id.progressBarMovie)
        tvTimeRemaining     = findViewById(R.id.tvTimeRemaining)
        tabLayoutDetails    = findViewById(R.id.tabLayoutDetails)
        recyclerSuggestions = findViewById(R.id.recyclerSuggestions)
        bottomNavigation    = findViewById(R.id.bottomNavigation)
        layoutTabDetails    = findViewById(R.id.layoutTabDetails)
        tvDetailFullTitle   = findViewById(R.id.tvDetailFullTitle)
        tvDetailFullPlot    = findViewById(R.id.tvDetailFullPlot)
        tvDetailDuration    = findViewById(R.id.tvDetailDuration)
        tvDetailReleaseDate = findViewById(R.id.tvDetailReleaseDate)
        tvDetailGenre       = findViewById(R.id.tvDetailGenre)
        tvDetailDirector    = findViewById(R.id.tvDetailDirector)

        webViewTrailer        = findViewById(R.id.webViewTrailer)
        layoutHero            = findViewById(R.id.layoutHero)
        btnToggleMute         = findViewById(R.id.btnToggleMute)
        trailerLoadingSpinner = findViewById(R.id.trailerLoadingSpinner)
        nestedScrollDetails   = findViewById(R.id.nestedScrollDetails)

        webViewTrailer.visibility = View.INVISIBLE
        webViewTrailer.alpha      = 0f

        btnToggleMute.bringToFront()
        btnToggleMute.setOnClickListener {
            isMuted = !isMuted
            try {
                if (isMuted) {
                    webViewTrailer.evaluateJavascript("muteTrailer();", null)
                    btnToggleMute.setImageResource(R.drawable.ic_volume_off)
                    btnToggleMute.setColorFilter(Color.RED)
                } else {
                    webViewTrailer.evaluateJavascript("unmuteTrailer();", null)
                    btnToggleMute.setImageResource(R.drawable.ic_volume_on)
                    btnToggleMute.clearColorFilter()
                }
            } catch (e: Exception) { }
        }

        if (btnDownloadAction != null) {
            pbDownloadCircular = CircularProgressIndicator(this).apply {
                layoutParams = LinearLayout.LayoutParams(24.toPx(), 24.toPx())
                    .apply { setMargins(0, 0, 0, 5) }
                isIndeterminate = false
                max = 100
                trackThickness = 3.toPx()
                setIndicatorColor(Color.parseColor("#D9A24B"))
                trackColor = Color.parseColor("#33FFFFFF")
                visibility = View.GONE
            }
            btnDownloadAction?.addView(pbDownloadCircular, 0)
        }

        val mostrarDownload = !isTelevisionDevice()
        btnDownloadArea?.visibility   = View.GONE
        btnDownloadAction?.visibility = if (mostrarDownload) View.VISIBLE else View.GONE

        if (isTelevisionDevice()) bottomNavigation?.visibility = View.GONE

        btnPlay.isFocusable = true
        btnPlay.requestFocus()
    }

    private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun setupBottomNavigation() {
        BottomNavProfileHelper.aplicarPerfilNoRodape(this, bottomNavigation, currentProfile, currentProfileIcon)
        bottomNavigation?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home      -> { finish(); true }
                R.id.nav_search    -> { startActivity(Intent(this, SearchActivity::class.java).apply { putExtra("PROFILE_NAME", currentProfile) }); true }
                R.id.nav_novidades -> { startActivity(Intent(this, NovidadesActivity::class.java).apply { putExtra("PROFILE_NAME", currentProfile) }); true }
                R.id.nav_profile   -> { startActivity(Intent(this, SettingsActivity::class.java).apply { putExtra("PROFILE_NAME", currentProfile) }); true }
                else -> false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // CONTEÚDO
    // ─────────────────────────────────────────────────────────────

    private fun carregarConteudo() {
        tvRating.text = "⭐ $rating"
        tvPlot.text   = "Buscando detalhes..."
        tvGenre.text  = "Gênero: ..."
        tvCast.text   = "Elenco:"
        Glide.with(this).load(icon).diskCacheStrategy(DiskCacheStrategy.ALL).into(imgPoster)
        Glide.with(this).load(icon).centerCrop().diskCacheStrategy(DiskCacheStrategy.ALL).into(imgBackground)
        atualizarIconeFavorito(getFavMovies(this).contains(streamId))
        if (isSeries) {
            carregarEpisodios()
        } else {
            tvEpisodesTitle.visibility = View.GONE
            if (serverYoutubeTrailer != null) {
                val extras = listOf(EpisodeData(0, 0, 1, "Trailer Oficial",
                    "https://img.youtube.com/vi/$serverYoutubeTrailer/0.jpg", serverYoutubeTrailer))
                episodesAdapter.submitList(extras)
                recyclerEpisodes.visibility = if (tabLayoutDetails.selectedTabPosition == 1) View.VISIBLE else View.GONE
            }
        }
        verificarResume()
        restaurarEstadoDownload()
    }

    private fun tentarCarregarTextoCache() {
        val p = getSharedPreferences("vltv_text_cache", Context.MODE_PRIVATE)
        p.getString("title_$streamId", null)?.let { tvTitle.text = it }
        p.getString("plot_$streamId", null)?.let  { tvPlot.text = it }
        p.getString("cast_$streamId", null)?.let  { tvCast.text = it }
        p.getString("genre_$streamId", null)?.let { tvGenre.text = it }
        p.getString("year_$streamId", null)?.let  { tvYear?.text = it }
    }

    private fun tentarCarregarLogoCache() {
        val url = getSharedPreferences("vltv_logos_cache", Context.MODE_PRIVATE)
            .getString("movie_logo_$streamId", null)
        if (url != null) {
            tvTitle.visibility      = View.GONE
            imgTitleLogo.visibility = View.VISIBLE
            Glide.with(this).load(url).diskCacheStrategy(DiskCacheStrategy.ALL).into(imgTitleLogo)
        } else {
            tvTitle.visibility = View.VISIBLE
        }
    }

    private fun sincronizarDadosTMDB() {
        val apiKey  = TmdbConfig.API_KEY
        val type    = if (isSeries) "tv" else "movie"
        val encoded = URLEncoder.encode(limparNomeParaTMDB(name), "UTF-8")
        val url     = "https://api.themoviedb.org/3/search/$type?api_key=$apiKey&query=$encoded&language=pt-BR&region=BR"

        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { runOnUiThread { tvTitle.visibility = View.VISIBLE } }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val results = JSONObject(body).optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val sel    = results.getJSONObject(0)
                        val idTmdb = sel.getInt("id")
                        buscarLogoTMDB(idTmdb, type, apiKey)
                        buscarDetalhesCompletos(idTmdb, type, apiKey)
                        runOnUiThread {
                            val tOficial = if (type == "movie") sel.optString("title") else sel.optString("name")
                            val sinopse  = sel.optString("overview")
                            val date     = if (isSeries) sel.optString("first_air_date") else sel.optString("release_date")
                            tvTitle.text = tOficial
                            if (sinopse.isNotEmpty()) tvPlot.text = sinopse
                            if (date.length >= 4) tvYear?.text = date.substring(0, 4)
                            tvDetailFullTitle?.text   = tOficial
                            tvDetailFullPlot?.text    = sinopse
                            tvDetailReleaseDate?.text = date
                            getSharedPreferences("vltv_text_cache", Context.MODE_PRIVATE).edit()
                                .putString("title_$streamId", tOficial)
                                .putString("plot_$streamId", sinopse)
                                .putString("year_$streamId", if (date.length >= 4) date.substring(0, 4) else "")
                                .apply()
                        }
                    } else { runOnUiThread { tvTitle.visibility = View.VISIBLE } }
                } catch (e: Exception) { runOnUiThread { tvTitle.visibility = View.VISIBLE } }
            }
        })
    }

    private fun buscarLogoTMDB(id: Int, type: String, key: String) {
        val url = "https://api.themoviedb.org/3/$type/$id/images?api_key=$key&include_image_language=pt-BR,pt,null"
        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { tvTitle.visibility = View.VISIBLE; imgTitleLogo.visibility = View.GONE }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val logos = JSONObject(body).optJSONArray("logos")
                    if (logos != null && logos.length() > 0) {
                        var path: String? = null
                        for (i in 0 until logos.length()) {
                            val lg = logos.getJSONObject(i)
                            if (lg.optString("iso_639_1").lowercase() == "pt" &&
                                lg.optString("iso_3166_1").uppercase() == "BR") {
                                path = lg.optString("file_path").takeIf { it.isNotEmpty() }
                                if (path != null) break
                            }
                        }
                        if (path == null) for (i in 0 until logos.length()) {
                            val lg = logos.getJSONObject(i)
                            if (lg.optString("iso_639_1").lowercase() == "pt") {
                                path = lg.optString("file_path").takeIf { it.isNotEmpty() }
                                if (path != null) break
                            }
                        }
                        if (path == null) {
                            runOnUiThread { tvTitle.visibility = View.VISIBLE; imgTitleLogo.visibility = View.GONE }
                            return
                        }
                        // ✅ Imagem servida via VPS (VpsConfig) em vez de
                        // bater direto em image.tmdb.org.
                        val finalUrl = VpsConfig.tmdbImage(path, "w500")
                        getSharedPreferences("vltv_logos_cache", Context.MODE_PRIVATE).edit()
                            .putString("movie_logo_$streamId", finalUrl).apply()
                        runOnUiThread {
                            tvTitle.visibility      = View.GONE
                            imgTitleLogo.visibility = View.VISIBLE
                            Glide.with(this@DetailsActivity).load(finalUrl).into(imgTitleLogo)
                        }
                    } else {
                        runOnUiThread { tvTitle.visibility = View.VISIBLE; imgTitleLogo.visibility = View.GONE }
                    }
                } catch (e: Exception) {
                    runOnUiThread { tvTitle.visibility = View.VISIBLE; imgTitleLogo.visibility = View.GONE }
                }
            }
        })
    }

    private fun buscarDetalhesCompletos(id: Int, type: String, key: String) {
        val url = "https://api.themoviedb.org/3/$type/$id?api_key=$key&append_to_response=credits,recommendations,similar,videos&language=pt-BR"
        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val d = JSONObject(body)
                    val gs = d.optJSONArray("genres"); val genresList = mutableListOf<String>()
                    if (gs != null) for (i in 0 until gs.length()) genresList.add(gs.getJSONObject(i).getString("name"))
                    val castArr = d.optJSONObject("credits")?.optJSONArray("cast"); val castNames = mutableListOf<String>()
                    if (castArr != null) for (i in 0 until minOf(castArr.length(), 10)) castNames.add(castArr.getJSONObject(i).getString("name"))
                    val runtime = d.optInt("runtime", 0)
                    val durText = if (runtime > 0) "${runtime / 60}h ${runtime % 60}min" else "N/A"
                    val crew = d.optJSONObject("credits")?.optJSONArray("crew"); var director = "Desconhecido"
                    if (crew != null) for (i in 0 until crew.length()) {
                        if (crew.getJSONObject(i).optString("job") == "Director") {
                            director = crew.getJSONObject(i).getString("name"); break
                        }
                    }
                    var simArr = d.optJSONObject("recommendations")?.optJSONArray("results")
                    if (simArr == null || simArr.length() == 0) simArr = d.optJSONObject("similar")?.optJSONArray("results")
                    val suggestions = mutableListOf<JSONObject>()
                    if (simArr != null) for (i in 0 until simArr.length()) suggestions.add(simArr.getJSONObject(i))
                    val extras = mutableListOf<EpisodeData>()
                    if (serverYoutubeTrailer == null) {
                        val vids = d.optJSONObject("videos")?.optJSONArray("results")
                        if (vids != null) for (i in 0 until vids.length()) {
                            val v = vids.getJSONObject(i)
                            if (v.optString("site") == "YouTube")
                                extras.add(EpisodeData(i, 0, i + 1, v.getString("name"),
                                    "https://img.youtube.com/vi/${v.getString("key")}/0.jpg", v.getString("key")))
                        }
                    }
                    runOnUiThread {
                        val g = genresList.joinToString(", "); val e = castNames.joinToString("\n")
                        tvGenre.text = "Gênero: $g"
                        tvCast.text  = "Elenco: ${castNames.take(5).joinToString(", ")}"
                        tvDetailGenre?.text    = g
                        tvDetailDuration?.text = durText
                        tvDetailDirector?.text = director
                        findViewById<TextView>(R.id.tvCast)?.text = e
                        if (suggestions.isNotEmpty()) recyclerSuggestions.adapter = SuggestionsAdapter(suggestions)
                        if (!isSeries && serverYoutubeTrailer == null && extras.isNotEmpty()) {
                            episodes = extras; episodesAdapter.submitList(extras)
                            recyclerEpisodes.visibility = if (tabLayoutDetails.selectedTabPosition == 1) View.VISIBLE else View.GONE
                        }
                        getSharedPreferences("vltv_text_cache", Context.MODE_PRIVATE).edit()
                            .putString("genre_$streamId", g).putString("cast_$streamId", e).apply()
                    }
                } catch (e: Exception) {}
            }
        })
    }

    private fun setupEpisodesRecycler() {
        episodesAdapter = EpisodesAdapter { episode ->
            if (episode.videoKey != null) {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:${episode.videoKey}")))
            } else {
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra("stream_id", episode.streamId); putExtra("stream_type", "series")
                    putExtra("channel_name", "${name} - S${episode.season}:E${episode.episode}")
                    putExtra("icon", episode.thumb); putExtra("PROFILE_NAME", currentProfile)
                })
            }
        }
        recyclerEpisodes.apply {
            layoutManager = if (isTelevisionDevice())
                GridLayoutManager(this@DetailsActivity, 6)
            else
                LinearLayoutManager(this@DetailsActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = episodesAdapter; setHasFixedSize(true)
        }
        recyclerSuggestions.layoutManager = GridLayoutManager(this, 3)
    }

    private fun carregarEpisodios() {
        episodes = listOf(EpisodeData(101, 1, 1, "Episódio 1", icon ?: ""))
        episodesAdapter.submitList(episodes); tvEpisodesTitle.visibility = View.VISIBLE
        recyclerEpisodes.visibility = if (tabLayoutDetails.selectedTabPosition == 1) View.VISIBLE else View.GONE
    }

    private fun setupEventos() {
        val focusListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundResource(R.drawable.bg_focus_neon)
                if (v is Button) v.setTextColor(Color.YELLOW)
                v.animate().scaleX(1.10f).scaleY(1.10f).setDuration(150).start()
            } else {
                if (v is Button) { v.setBackgroundResource(R.drawable.bg_button_default); v.setTextColor(Color.WHITE) }
                else v.setBackgroundResource(0)
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            }
        }
        btnPlay.onFocusChangeListener     = focusListener
        btnFavorite.onFocusChangeListener = focusListener

        btnFavorite.setOnClickListener        { toggleFavorite() }
        btnFavoriteLayout?.setOnClickListener { toggleFavorite() }
        btnPlay.setOnClickListener            { abrirPlayer(true) }

        btnRestartAction?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reiniciar Filme")
                .setMessage("Deseja assistir desde o início?")
                .setPositiveButton("Sim") { _, _ -> abrirPlayer(false) }
                .setNegativeButton("Não", null).show()
        }

        btnDownloadAction?.setOnClickListener { handleDownloadClick() }

        btnTrailerAction?.setOnClickListener {
            if (serverYoutubeTrailer != null) {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$serverYoutubeTrailer")))
            } else {
                if (tabLayoutDetails.selectedTabPosition != 1) tabLayoutDetails.getTabAt(1)?.select()
                Toast.makeText(this, "Trailer disponível na aba Extras", Toast.LENGTH_SHORT).show()
            }
        }

        tabLayoutDetails.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> { recyclerSuggestions.visibility = View.VISIBLE; recyclerEpisodes.visibility = View.GONE; layoutTabDetails?.visibility = View.GONE }
                    1 -> { recyclerSuggestions.visibility = View.GONE; recyclerEpisodes.visibility = View.VISIBLE; layoutTabDetails?.visibility = View.GONE }
                    2 -> { recyclerSuggestions.visibility = View.GONE; recyclerEpisodes.visibility = View.GONE; layoutTabDetails?.visibility = View.VISIBLE }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        btnSettings?.setOnClickListener { mostrarConfiguracoes() }
    }

    // ─────────────────────────────────────────────────────────────
    // FAVORITOS / RESUME / PLAYER
    // ─────────────────────────────────────────────────────────────

    private fun getFavMovies(context: Context): MutableList<Int> =
        context.getSharedPreferences("vltv_favoritos", Context.MODE_PRIVATE)
            .getStringSet("${currentProfile}_favoritos", emptySet())
            ?.mapNotNull { it.toIntOrNull() }?.toMutableList() ?: mutableListOf()

    private fun saveFavMovies(context: Context, favs: List<Int>) =
        context.getSharedPreferences("vltv_favoritos", Context.MODE_PRIVATE).edit()
            .putStringSet("${currentProfile}_favoritos", favs.map { it.toString() }.toSet()).apply()

    private fun atualizarIconeFavorito(isFavorite: Boolean) {
        if (isFavorite) { btnFavorite.setImageResource(android.R.drawable.btn_star_big_on); btnFavorite.setColorFilter(Color.parseColor("#FFD700")) }
        else            { btnFavorite.setImageResource(android.R.drawable.btn_star_big_off); btnFavorite.clearColorFilter() }
    }

    private fun toggleFavorite() {
        val favs = getFavMovies(this); val isFav = favs.contains(streamId)
        atualizarIconeFavorito(!isFav)
        if (isFav) favs.remove(streamId) else favs.add(streamId)
        saveFavMovies(this, favs)
    }

    private fun verificarResume() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val pos   = prefs.getLong("${currentProfile}_movie_resume_${streamId}_pos", 0L)
        val total = prefs.getLong("${currentProfile}_movie_resume_${streamId}_dur", 0L)
        if (pos > 30000L && total > 0) {
            btnPlay.text = "▶  CONTINUAR"
            btnRestartAction?.visibility = View.VISIBLE; layoutProgress?.visibility = View.VISIBLE
            progressBarMovie?.progress   = ((pos.toFloat() / total.toFloat()) * 100).toInt()
            val rest = total - pos
            tvTimeRemaining?.text = "Restam ${TimeUnit.MILLISECONDS.toHours(rest)}h${TimeUnit.MILLISECONDS.toMinutes(rest) % 60}min"
        } else {
            btnPlay.text = "▶  ASSISTIR"
            btnRestartAction?.visibility = View.GONE; layoutProgress?.visibility = View.GONE
        }
    }

    private fun abrirPlayer(usarResume: Boolean) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("stream_id", streamId); putExtra("stream_type", if (isSeries) "series" else "movie")
            putExtra("channel_name", name); putExtra("icon", icon); putExtra("PROFILE_NAME", currentProfile)
            val pos = if (usarResume) getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
                .getLong("${currentProfile}_movie_resume_${streamId}_pos", 0L) else 0L
            putExtra("start_position_ms", pos)
        })
    }

    // ─────────────────────────────────────────────────────────────
    // DOWNLOAD
    // ─────────────────────────────────────────────────────────────

    private fun restaurarEstadoDownload() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dl = database.streamDao().getDownloadByStreamId(streamId, if (isSeries) "series" else "movie")
            withContext(Dispatchers.Main) {
                downloadAtual = dl
                downloadState = mapStatusParaEstado(dl?.status, existe = dl != null)
                if (downloadState == DownloadState.NA_FILA || downloadState == DownloadState.BAIXANDO || downloadState == DownloadState.PAUSADO) {
                    iniciarMonitoramentoUI()
                }
                atualizarUI_download()
            }
        }
    }

    private fun mapStatusParaEstado(status: String?, existe: Boolean): DownloadState {
        if (!existe || status == null) return DownloadState.BAIXAR
        return when (status) {
            "BAIXADO", "COMPLETED" -> DownloadState.BAIXADO
            "ERRO" -> DownloadState.BAIXAR
            "NA_FILA" -> DownloadState.NA_FILA
            "PAUSADO" -> DownloadState.PAUSADO
            else -> DownloadState.BAIXANDO
        }
    }

    // ✅ CORRIGIDO (bug da "seta que gira e volta sozinha"): antes, esse
    // loop desistia na PRIMEIRA leitura em que o download não era
    // encontrado no banco ("dl == null"), voltando o estado pra BAIXAR e
    // cancelando o monitoramento de vez — mesmo que o download estivesse
    // rodando normalmente por trás. Agora ele tolera algumas leituras
    // nulas seguidas (o Room pode levar uma fração de segundo pra
    // "assentar" o insert) antes de considerar que realmente não existe
    // download em andamento.
    private fun iniciarMonitoramentoUI() {
        if (uiMonitorJob?.isActive == true) return
        uiMonitorJob = lifecycleScope.launch(Dispatchers.Main) {
            val db   = database.streamDao()
            val tipo = if (isSeries) "series" else "movie"
            var tentativasNulas = 0
            while (isActive) {
                val dl = withContext(Dispatchers.IO) { db.getDownloadByStreamId(streamId, tipo) }
                if (dl != null) {
                    tentativasNulas = 0
                    downloadAtual = dl
                    val novoEstado = mapStatusParaEstado(dl.status, true)
                    downloadState = novoEstado
                    atualizarUI_download()
                    if (novoEstado == DownloadState.BAIXADO || novoEstado == DownloadState.BAIXAR) {
                        cancel()
                    }
                } else {
                    tentativasNulas++
                    if (tentativasNulas >= 5) {
                        downloadAtual = null
                        downloadState = DownloadState.BAIXAR
                        atualizarUI_download()
                        cancel()
                    }
                }
                delay(1000)
            }
        }
    }

    // ✅ CORRIGIDO: antes chamava iniciarMonitoramentoUI() via
    // Handler().postDelayed(500ms), "adivinhando" que o insert no Room já
    // tinha terminado. Agora usa o callback "aoIniciar" do DownloadHelper,
    // que só dispara quando a linha já está garantida no banco — sem
    // corrida, sem chute de tempo.
    private fun iniciarDownload() {
        downloadState = DownloadState.NA_FILA
        atualizarUI_download()
        DownloadHelper.iniciarDownload(
            context = this,
            streamId = streamId,
            nomePrincipal = name,
            nomeEpisodio = null,
            imagemUrl = icon,
            isSeries = isSeries,
            season = 0,
            extensaoContainer = streamExt,
            // ✅ NOVO: grava qual perfil (adulto ou Kids) iniciou esse
            // download — é isso que DownloadsActivity/KidsDownloadsActivity
            // usam pra filtrar cada um mostrar só o que é seu.
            profileName = currentProfile,
            aoIniciar = {
                iniciarMonitoramentoUI()
            }
        )
    }

    private fun atualizarUI_download() {
        when (downloadState) {
            DownloadState.BAIXAR -> {
                imgDownloadState.setImageResource(android.R.drawable.stat_sys_download)
                imgDownloadState.visibility = View.VISIBLE
                pbDownloadCircular?.visibility = View.GONE
                tvDownloadState.text = "BAIXAR"
            }
            DownloadState.NA_FILA -> {
                imgDownloadState.visibility = View.GONE
                pbDownloadCircular?.visibility = View.VISIBLE
                pbDownloadCircular?.isIndeterminate = true
                tvDownloadState.text = "NA FILA"
            }
            DownloadState.BAIXANDO -> {
                imgDownloadState.visibility = View.GONE
                pbDownloadCircular?.isIndeterminate = false
                pbDownloadCircular?.visibility = View.VISIBLE
                pbDownloadCircular?.setProgressCompat(downloadAtual?.progress ?: 0, true)
                tvDownloadState.text = "${downloadAtual?.progress ?: 0}%"
            }
            DownloadState.PAUSADO -> {
                imgDownloadState.setImageResource(android.R.drawable.ic_media_pause)
                imgDownloadState.visibility = View.VISIBLE
                pbDownloadCircular?.visibility = View.GONE
                tvDownloadState.text = "PAUSADO"
            }
            DownloadState.BAIXADO -> {
                imgDownloadState.setImageResource(R.drawable.ic_phone_outline)
                imgDownloadState.visibility = View.VISIBLE
                pbDownloadCircular?.visibility = View.GONE
                tvDownloadState.text = "BAIXADO"
            }
        }
    }

    private fun handleDownloadClick() {
        when (downloadState) {
            DownloadState.BAIXAR                          -> iniciarDownload()
            DownloadState.NA_FILA, DownloadState.BAIXANDO  -> confirmarPausarOuCancelar()
            DownloadState.PAUSADO                          -> confirmarContinuarOuCancelar()
            DownloadState.BAIXADO                          -> confirmarExcluirDownload()
        }
    }

    private fun confirmarPausarOuCancelar() {
        val dl = downloadAtual ?: return
        DownloadDialogHelper.confirmarAcaoDupla(
            context = this,
            titulo = "Download em Andamento",
            mensagem = "O que deseja fazer com o download de \"$name\"?",
            btnPrincipal = "Pausar Download",
            corPrincipal = "#FFFFFF",
            onPrincipal = {
                DownloadHelper.pausarDownload(this, dl)
                downloadState = DownloadState.PAUSADO
                atualizarUI_download()
            },
            btnSecundario = "Cancelar Download",
            corSecundario = "#FF5252",
            onSecundario = {
                DownloadHelper.cancelarDownload(this, dl)
                uiMonitorJob?.cancel()
                downloadState = DownloadState.BAIXAR
                atualizarUI_download()
            }
        )
    }

    private fun confirmarContinuarOuCancelar() {
        val dl = downloadAtual ?: return
        DownloadDialogHelper.confirmarAcaoDupla(
            context = this,
            titulo = "Download Pausado",
            mensagem = "\"$name\" está pausado. O que deseja fazer?",
            btnPrincipal = "Continuar Download",
            corPrincipal = "#FFFFFF",
            onPrincipal = {
                DownloadHelper.continuarDownload(this, dl)
                downloadState = DownloadState.NA_FILA
                atualizarUI_download()
                iniciarMonitoramentoUI()
            },
            btnSecundario = "Cancelar Download",
            corSecundario = "#FF5252",
            onSecundario = {
                DownloadHelper.cancelarDownload(this, dl)
                uiMonitorJob?.cancel()
                downloadState = DownloadState.BAIXAR
                atualizarUI_download()
            }
        )
    }

    private fun confirmarExcluirDownload() {
        mostrarDialogConfirmacao(
            titulo = "Excluir Download",
            mensagem = "Deseja apagar \"$name\" do seu dispositivo? Você pode baixar de novo quando quiser.",
            btnPositivo = "Excluir",
            corPositivo = "#FF5252"
        ) {
            downloadAtual?.let {
                DownloadHelper.excluirDownload(this, it) {
                    downloadState = DownloadState.BAIXAR
                    downloadAtual = null
                    atualizarUI_download()
                }
            }
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun mostrarDialogConfirmacao(titulo: String, mensagem: String, btnPositivo: String, corPositivo: String = "#FFFFFF", onConfirmar: () -> Unit) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(24.dp, 24.dp, 24.dp, 20.dp)
        }
        root.addView(TextView(this).apply {
            text = titulo; textSize = 17f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10.dp }
        })
        root.addView(TextView(this).apply {
            text = mensagem; textSize = 13f; setTextColor(Color.parseColor("#AAAAAA")); setLineSpacing(0f, 1.4f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 20.dp }
        })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val corBtnPos = try { Color.parseColor(corPositivo) } catch (e: Exception) { Color.WHITE }
        val isDestructive = corPositivo == "#FF5252"
        btnRow.addView(TextView(this).apply {
            text = "Voltar"; textSize = 14f; setTextColor(Color.parseColor("#888888")); gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 6.dp }
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat(); setStroke(1.dp, Color.parseColor("#2A2A2A")) }
            isClickable = true; isFocusable = true; setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(TextView(this).apply {
            text = btnPositivo; textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (isDestructive) Color.WHITE else Color.BLACK); gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginStart = 6.dp }
            background = android.graphics.drawable.GradientDrawable().apply { setColor(corBtnPos); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true; setOnClickListener { dialog.dismiss(); onConfirmar() }
        })
        root.addView(btnRow)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#141414")); cornerRadius = 16.dp.toFloat() })
            val p = attributes; p.width = (resources.displayMetrics.widthPixels * 0.85).toInt(); attributes = p
        }
        dialog.show()
    }

    private fun mostrarConfiguracoes() {
        val p = arrayOf("ExoPlayer", "VLC", "MX Player")
        AlertDialog.Builder(this).setTitle("Player")
            .setItems(p) { _, i -> getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
                .edit().putString("player_preferido", p[i]).apply() }.show()
    }

    private fun limparNomeParaTMDB(nome: String): String =
        nome.replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "")
            .replace(Regex("\\b\\d{4}\\b"), "")
            .replace(Regex("(?i)\\b(FHD|HD|4K|H265|LEG|DUBLADO|BR:|SP:|UHD|HDR)\\b"), "")
            .replace(Regex("\\s+"), " ").trim()

    // Detecção de TV centralizada em DeviceUtils.kt (isTelevisionDevice()),
    // usada em todo o app — não reimplementar localmente aqui.

    // ─────────────────────────────────────────────────────────────
    // RESOLUÇÃO DE ID REAL NO CATÁLOGO (Sugestões do TMDB)
    // ─────────────────────────────────────────────────────────────

    private fun normalizarTituloParaMatch(titulo: String): String {
        return titulo
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("(?i)\\b(4K|FULL HD|HD|SD|DUBLADO|LEGENDADO|DUAL|BLURAY|WEB-DL|HEVC|H264|H265|UHD|FHD|HDR)\\b"), "")
            .trim()
    }

    private suspend fun resolverStreamIdReal(tituloTmdb: String): Pair<Int, String>? =
        withContext(Dispatchers.IO) {
            val tituloLimpo = normalizarTituloParaMatch(tituloTmdb)
            if (tituloLimpo.isBlank()) return@withContext null

            var cursor = database.openHelper.readableDatabase.query(
                "SELECT stream_id, stream_icon FROM vod_streams WHERE name = ? COLLATE NOCASE LIMIT 1",
                arrayOf(tituloLimpo)
            )
            if (cursor.moveToFirst()) {
                val id   = cursor.getInt(0)
                val icon = cursor.getString(1) ?: ""
                cursor.close()
                return@withContext id to icon
            }
            cursor.close()

            cursor = database.openHelper.readableDatabase.query(
                "SELECT stream_id, stream_icon FROM vod_streams WHERE name LIKE ? ORDER BY LENGTH(name) ASC LIMIT 1",
                arrayOf("%$tituloLimpo%")
            )
            if (cursor.moveToFirst()) {
                val id   = cursor.getInt(0)
                val icon = cursor.getString(1) ?: ""
                cursor.close()
                return@withContext id to icon
            }
            cursor.close()
            null
        }

    // ─────────────────────────────────────────────────────────────
    // ADAPTERS
    // ─────────────────────────────────────────────────────────────

    inner class EpisodesAdapter(private val onEpisodeClick: (EpisodeData) -> Unit) :
        ListAdapter<EpisodeData, EpisodesAdapter.ViewHolder>(DiffCallback) {
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_episode, p, false))
        override fun onBindViewHolder(h: ViewHolder, p: Int) = h.bind(getItem(p))
        inner class ViewHolder(val v: View) : RecyclerView.ViewHolder(v) {
            fun bind(e: EpisodeData) {
                v.isFocusable = true
                v.findViewById<TextView>(R.id.tvEpisodeTitle).text =
                    if (e.videoKey != null) "Extra: ${e.title}" else "S${e.season}E${e.episode}: ${e.title}"
                Glide.with(v.context).load(e.thumb).centerCrop().into(v.findViewById(R.id.imgEpisodeThumb))
                v.findViewById<View>(R.id.btnDownloadEpisode)?.visibility = View.GONE
                v.setOnClickListener { onEpisodeClick(e) }
            }
        }
    }

    inner class SuggestionsAdapter(val items: List<JSONObject>) :
        RecyclerView.Adapter<SuggestionsAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(android.R.id.icon)
            val tv: TextView   = v.findViewById(android.R.id.text1)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val c = LinearLayout(parent.context).apply {
                orientation  = LinearLayout.VERTICAL
                layoutParams = ViewGroup.MarginLayoutParams(130.toPx(), ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(10, 10, 10, 10) }
                gravity = android.view.Gravity.CENTER_HORIZONTAL; isFocusable = true; isClickable = true
            }
            val card = androidx.cardview.widget.CardView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(130.toPx(), 190.toPx()); radius = 8f
            }
            val img = ImageView(parent.context).apply { id = android.R.id.icon; scaleType = ImageView.ScaleType.CENTER_CROP }
            card.addView(img)
            val tv = TextView(parent.context).apply {
                id = android.R.id.text1; setTextColor(Color.WHITE)
                textSize = 10f; gravity = android.view.Gravity.CENTER; maxLines = 2
            }
            c.addView(card); c.addView(tv)
            return ViewHolder(c)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]; val path = item.optString("poster_path")
            val tituloTmdb = item.optString("title")
            // ✅ Imagem servida via VPS (VpsConfig)
            val posterUrl = VpsConfig.tmdbImage(path, "w342")
            Glide.with(holder.itemView.context).load(posterUrl).into(holder.img)
            holder.tv.text = tituloTmdb
            holder.itemView.setOnClickListener {
                lifecycleScope.launch {
                    val resolvido = resolverStreamIdReal(tituloTmdb)
                    if (resolvido == null) {
                        Toast.makeText(holder.itemView.context, "Esse título não está disponível no seu catálogo.", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val (idReal, iconReal) = resolvido
                    startActivity(Intent(holder.itemView.context, DetailsActivity::class.java).apply {
                        putExtra("stream_id", idReal)
                        putExtra("name", tituloTmdb)
                        putExtra("icon", iconReal.ifEmpty { posterUrl })
                        putExtra("PROFILE_NAME", currentProfile)
                        putExtra("PROFILE_ICON", currentProfileIcon)
                    })
                }
            }
        }
        override fun getItemCount() = items.size
        private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()
    }

    companion object {
        private object DiffCallback : DiffUtil.ItemCallback<EpisodeData>() {
            override fun areItemsTheSame(o: EpisodeData, n: EpisodeData) = o.streamId == n.streamId
            override fun areContentsTheSame(o: EpisodeData, n: EpisodeData) = o == n
        }
    }
}
