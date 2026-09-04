package com.vltv.play

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.bottomsheet.BottomSheetDialog
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.net.URLEncoder
import okhttp3.Request
import org.json.JSONObject
import java.util.ArrayList
import java.util.concurrent.TimeUnit
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.DownloadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout
import com.google.android.material.bottomnavigation.BottomNavigationView

// ✅ NOVO: estrutura simples pra carregar a "mochila" de episódios
// cobrindo TODAS as temporadas da série (não só a que está na tela),
// junto com a temporada/título/extensão de cada item — na MESMA posição
// do id em "ids". É isso que o PlayerActivity usa pra saber o próximo
// episódio corretamente mesmo virando de temporada.
private data class MochilaEpisodios(
    val ids: ArrayList<Int>,
    val seasons: ArrayList<Int>,
    val titles: ArrayList<String>,
    val exts: ArrayList<String>
)

class SeriesDetailsActivity : AppCompatActivity() {

    private var seriesId: Int = 0
    private var seriesName: String = ""
    private var seriesIcon: String? = null
    private var seriesRating: String = "0.0"

    private var currentProfile: String = "Padrao"
    private var currentProfileIcon: String? = null

    val database by lazy { AppDatabase.getDatabase(this) }

    // Views
    private lateinit var imgPoster: ImageView
    private lateinit var imgBackground: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var imgTitleLogo: ImageView
    private lateinit var tvRating: TextView
    private lateinit var tvGenre: TextView
    private lateinit var tvCast: TextView
    private lateinit var recyclerCast: RecyclerView
    private lateinit var tvPlot: TextView
    private lateinit var btnSeasonSelector: TextView
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var btnFavoriteSeries: ImageButton
    private lateinit var btnPlaySeries: Button
    private lateinit var btnDownloadEpisodeArea: LinearLayout
    private lateinit var imgDownloadEpisodeState: ImageView
    private lateinit var tvDownloadEpisodeState: TextView
    private lateinit var btnDownloadSeason: Button
    private lateinit var btnResume: Button
    private var appBarLayout: AppBarLayout? = null
    private var tabLayout: TabLayout? = null

    private var layoutProgress: LinearLayout? = null
    private var progressBarSeries: ProgressBar? = null
    private var tvTimeRemaining: TextView? = null
    private var btnRestartAction: LinearLayout? = null

    private lateinit var bottomNavigation: BottomNavigationView

    private lateinit var recyclerSuggestions: RecyclerView
    private lateinit var llTechBadges: LinearLayout
    private lateinit var tvBadge4k: TextView
    private lateinit var tvBadgeHdr: TextView
    private lateinit var tvBadgeDolby: TextView
    private lateinit var tvBadge51: TextView
    private lateinit var tvReleaseDate: TextView
    private lateinit var tvCreatedBy: TextView

    // ── WebView trailer ──────────────────────────────────────────
    private lateinit var webViewTrailer: WebView
    private lateinit var btnToggleMute: ImageView
    private lateinit var trailerLoadingSpinner: ProgressBar

    private var isMuted = true
    private var trailerReady = false
    private var trailerCarregado = false
    private var heroVisivel = true

    private val trailerHandler = Handler(Looper.getMainLooper())
    private val TRAILER_DELAY_MS = 2000L

    // ────────────────────────────────────────────────────────────
    private var episodesBySeason: Map<String, List<EpisodeStream>> = emptyMap()
    private var sortedSeasons: List<String> = emptyList()
    private var currentSeason: String = ""
    private var currentEpisode: EpisodeStream? = null

    private val client = SharedHttpClient.client

    // ─────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series_details)

        val vltvPrefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        currentProfile = intent.getStringExtra("PROFILE_NAME")
            ?: vltvPrefs.getString("last_profile_name", null)
            ?: "Padrao"
        currentProfileIcon = intent.getStringExtra("PROFILE_ICON")
            ?.takeIf { it.isNotEmpty() }
            ?: vltvPrefs.getString("last_profile_icon", null)?.takeIf { it.isNotEmpty() }

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())

        seriesId     = intent.getIntExtra("series_id", 0)
        seriesName   = intent.getStringExtra("name") ?: ""
        seriesIcon   = intent.getStringExtra("icon")
        seriesRating = intent.getStringExtra("rating") ?: "0.0"

        inicializarViews()
        BottomNavProfileHelper.aplicarPerfilNoRodape(this, bottomNavigation, currentProfile, currentProfileIcon)
        setupWebViewTrailer()
        verificarTecnologias(seriesName)

        appBarLayout?.addOnOffsetChangedListener { appBar, verticalOffset ->
            val percentage = Math.abs(verticalOffset).toFloat() / appBar.totalScrollRange
            val alphaValue = if (percentage > 0.6f) 0f else 1f - (percentage * 1.5f).coerceAtMost(1f)
            tvTitle.alpha           = alphaValue
            imgTitleLogo.alpha      = alphaValue
            btnPlaySeries.alpha     = alphaValue
            btnResume.alpha         = alphaValue
            layoutProgress?.alpha   = alphaValue
            btnFavoriteSeries.alpha = alphaValue
            tvRating.alpha          = alphaValue
            tvGenre.alpha           = alphaValue

            if (trailerReady) {
                val heroColuiu = percentage > 0.7f
                if (heroColuiu && heroVisivel) {
                    heroVisivel = false
                    try { webViewTrailer.evaluateJavascript("pauseTrailer();", null) } catch (e: Exception) { }
                } else if (!heroColuiu && !heroVisivel) {
                    heroVisivel = true
                    try { webViewTrailer.evaluateJavascript("playTrailer();", null) } catch (e: Exception) { }
                }
            }
        }

        if (isTelevisionDevice()) {
            btnDownloadEpisodeArea.visibility = View.GONE
            btnDownloadSeason.visibility      = View.GONE
        } else {
            btnDownloadEpisodeArea.visibility = View.GONE
            btnDownloadSeason.visibility      = View.VISIBLE
        }

        tvTitle.text  = seriesName
        tvRating.text = "Nota: $seriesRating"
        tvGenre.text  = "Gênero: Buscando..."
        tvCast.text   = "Elenco:"
        tvPlot.text   = "Carregando sinopse..."

        btnSeasonSelector.setBackgroundColor(Color.parseColor("#333333"))
        btnSeasonSelector.setTextColor(Color.WHITE)

        Glide.with(this)
            .load(seriesIcon)
            .placeholder(R.mipmap.ic_launcher)
            .centerCrop()
            .into(imgPoster)

        rvEpisodes.isFocusable           = true
        rvEpisodes.isFocusableInTouchMode = true
        rvEpisodes.setHasFixedSize(true)
        rvEpisodes.layoutManager = LinearLayoutManager(this)

        rvEpisodes.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val holder = rvEpisodes.findContainingViewHolder(view) as? EpisodeAdapter.VH
                holder?.let {
                    val position = holder.adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        currentEpisode = (rvEpisodes.adapter as? EpisodeAdapter)?.list?.getOrNull(position)
                        verificarResume()
                    }
                }
            }
            override fun onChildViewDetachedFromWindow(view: View) {}
        })

        recyclerSuggestions.layoutManager = GridLayoutManager(this, 3)
        recyclerSuggestions.setHasFixedSize(true)

        val isFavInicial = getFavSeries(this).contains(seriesId)
        atualizarIconeFavoritoSerie(isFavInicial)

        btnFavoriteSeries.setOnClickListener {
            val favs = getFavSeries(this)
            if (favs.contains(seriesId)) favs.remove(seriesId) else favs.add(seriesId)
            saveFavSeries(this, favs)
            atualizarIconeFavoritoSerie(favs.contains(seriesId))
        }

        btnSeasonSelector.setOnClickListener { mostrarSeletorDeTemporada() }

        btnDownloadSeason.setOnClickListener { baixarTemporadaAtual() }

        btnPlaySeries.setOnClickListener {
            val ep = encontrarEpisodioParaAssistir()
            if (ep != null) abrirPlayer(ep, false)
            else Toast.makeText(this, "Nenhum episódio encontrado.", Toast.LENGTH_SHORT).show()
        }

        btnResume.setOnClickListener {
            val ep = encontrarEpisodioParaContinuar()
            if (ep != null) abrirPlayer(ep, true)
        }

        btnRestartAction?.setOnClickListener {
            val ep = encontrarEpisodioParaContinuar() ?: encontrarEpisodioParaAssistir()
            if (ep != null) {
                mostrarDialogConfirmacao(
                    titulo = "Reiniciar Episódio",
                    mensagem = "Deseja assistir desde o início?",
                    btnPositivo = "Sim",
                    corPositivo = "#FFFFFF"
                ) { abrirPlayer(ep, false) }
            }
        }

        tentarCarregarLogoCache()
        carregarSeriesInfo()
        sincronizarDadosTMDB()

        trailerHandler.postDelayed({
            if (!isFinishing && !isDestroyed) buscarETocarTrailer()
        }, TRAILER_DELAY_MS)

        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { finish(); true }
                R.id.nav_search -> {
                    startActivity(Intent(this, SearchActivity::class.java).apply {
                        putExtra("PROFILE_NAME", currentProfile)
                    }); true
                }
                R.id.nav_novidades -> {
                    startActivity(Intent(this, NovidadesActivity::class.java).apply {
                        putExtra("PROFILE_NAME", currentProfile)
                    }); true
                }
                R.id.nav_profile -> {
                    startActivity(Intent(this, SettingsActivity::class.java).apply {
                        putExtra("PROFILE_NAME", currentProfile)
                    }); true
                }
                else -> false
            }
        }

        val commonFocus = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundResource(R.drawable.bg_focus_neon)
                if (v is Button) v.setTextColor(Color.YELLOW)
                v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).start()
            } else {
                if (v is Button) {
                    v.setBackgroundResource(android.R.drawable.btn_default)
                    v.setTextColor(Color.WHITE)
                } else v.setBackgroundResource(0)
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            }
        }
        btnPlaySeries.onFocusChangeListener = commonFocus
        btnResume.onFocusChangeListener     = commonFocus

        btnSeasonSelector.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundResource(R.drawable.bg_focus_neon)
                (v as TextView).setTextColor(Color.YELLOW)
                v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).start()
            } else {
                v.setBackgroundColor(Color.parseColor("#333333"))
                (v as TextView).setTextColor(Color.WHITE)
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            }
        }

        tabLayout?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        rvEpisodes.visibility          = View.VISIBLE
                        tvPlot.visibility              = View.GONE
                        tvCast.visibility              = View.GONE
                        recyclerCast.visibility        = View.GONE
                        tvReleaseDate.visibility       = View.GONE
                        tvCreatedBy.visibility         = View.GONE
                        recyclerSuggestions.visibility = View.GONE
                    }
                    1 -> {
                        rvEpisodes.visibility          = View.GONE
                        tvPlot.visibility              = View.GONE
                        tvCast.visibility              = View.GONE
                        recyclerCast.visibility        = View.GONE
                        tvReleaseDate.visibility       = View.GONE
                        tvCreatedBy.visibility         = View.GONE
                        recyclerSuggestions.visibility = View.VISIBLE
                    }
                    2 -> {
                        rvEpisodes.visibility          = View.GONE
                        recyclerSuggestions.visibility = View.GONE
                        tvPlot.visibility              = View.VISIBLE
                        tvCast.visibility              = View.VISIBLE
                        recyclerCast.visibility        = View.VISIBLE
                        tvReleaseDate.visibility       = View.VISIBLE
                        tvCreatedBy.visibility         = View.VISIBLE
                        tvPlot.setTextColor(Color.WHITE)
                        tvCast.setTextColor(Color.WHITE)
                        tvReleaseDate.setTextColor(Color.WHITE)
                        tvCreatedBy.setTextColor(Color.WHITE)
                        tabLayout?.requestFocus()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        val vltvPrefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        currentProfile = vltvPrefs.getString("last_profile_name", currentProfile) ?: currentProfile
        currentProfileIcon = vltvPrefs.getString("last_profile_icon", currentProfileIcon)
            ?.takeIf { it.isNotEmpty() } ?: currentProfileIcon
        BottomNavProfileHelper.aplicarPerfilNoRodape(this, bottomNavigation, currentProfile, currentProfileIcon)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
        verificarResume()
        rvEpisodes.adapter?.notifyDataSetChanged()
        if (trailerReady && heroVisivel) {
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
        trailerHandler.removeCallbacksAndMessages(null)
        client.dispatcher.cancelAll()
        try {
            webViewTrailer.evaluateJavascript("pauseTrailer();", null)
            webViewTrailer.stopLoading()
            webViewTrailer.destroy()
        } catch (e: Exception) { }
        super.onDestroy()
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
        val apiKey = TmdbConfig.API_KEY
        var cleanName = seriesName
        cleanName = cleanName.replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "")
        cleanName = cleanName.replace(Regex("\\b\\d{4}\\b"), "")
        listOf("FHD", "HD", "SD", "4K", "8K", "H265", "LEG", "DUBLADO", "DUB", "|", "-", "_", ".")
            .forEach { cleanName = cleanName.replace(it, "", ignoreCase = true) }
        cleanName = cleanName.trim().replace(Regex("\\s+"), " ")

        val encoded = try { URLEncoder.encode(cleanName, "UTF-8") } catch (e: Exception) { cleanName }
        val url = "https://api.themoviedb.org/3/search/tv?api_key=$apiKey&query=$encoded&language=pt-BR&region=BR"

        trailerLoadingSpinner.visibility = View.VISIBLE

        client.newCall(Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread { trailerLoadingSpinner.visibility = View.GONE }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
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
                    buscarYouTubeKey(tmdbId, apiKey)
                } catch (e: Exception) {
                    runOnUiThread { trailerLoadingSpinner.visibility = View.GONE }
                }
            }
        })
    }

    private fun buscarYouTubeKey(tmdbId: Int, apiKey: String) {
        val idiomas     = listOf("pt-BR", "en-US")
        val prioridades = listOf("Trailer", "Teaser", "Clip", "Featurette")
        val filtroNome = listOf("legendado", "dublado", "subtitled", "dubbed", "leg.", "dub.")

        fun tentarIdioma(idx: Int) {
            if (idx >= idiomas.size) {
                runOnUiThread { trailerLoadingSpinner.visibility = View.GONE }; return
            }
            val url = "https://api.themoviedb.org/3/tv/$tmdbId/videos?api_key=$apiKey&language=${idiomas[idx]}"
            client.newCall(Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) { tentarIdioma(idx + 1) }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
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
    // SETUP VIEWS
    // ─────────────────────────────────────────────────────────────

    private fun inicializarViews() {
        appBarLayout = findViewById(R.id.appBar)
        tabLayout    = findViewById(R.id.tabLayout)
        if (tabLayout?.tabCount == 0) {
            tabLayout?.addTab(tabLayout!!.newTab().setText("EPISÓDIOS"))
            tabLayout?.addTab(tabLayout!!.newTab().setText("SUGESTÕES"))
            tabLayout?.addTab(tabLayout!!.newTab().setText("DETALHES"))
        }

        bottomNavigation = findViewById(R.id.bottomNavigation)

        imgPoster      = findViewById(R.id.imgPoster)
        imgBackground  = try { findViewById(R.id.imgBackground) } catch (e: Exception) { imgPoster }
        tvTitle        = findViewById(R.id.tvTitle)
        tvTitle.visibility = View.INVISIBLE
        imgTitleLogo   = findViewById(R.id.imgTitleLogo)
        tvRating       = findViewById(R.id.tvRating)
        tvGenre        = findViewById(R.id.tvGenre)
        llTechBadges   = findViewById(R.id.llTechBadges)
        tvBadge4k      = findViewById(R.id.tvBadge4k)
        tvBadgeHdr     = findViewById(R.id.tvBadgeHdr)
        tvBadgeDolby   = findViewById(R.id.tvBadgeDolby)
        tvBadge51      = findViewById(R.id.tvBadge51)
        tvPlot         = findViewById(R.id.tvPlot)
        tvReleaseDate  = findViewById(R.id.tvReleaseDate)
        tvCreatedBy    = findViewById(R.id.tvCreatedBy)
        tvCast         = findViewById(R.id.tvCast)
        recyclerCast   = findViewById(R.id.recyclerCast)
        recyclerCast.visibility     = View.GONE
        recyclerSuggestions         = findViewById(R.id.recyclerSuggestions)
        btnSeasonSelector           = findViewById(R.id.btnSeasonSelector)
        rvEpisodes                  = findViewById(R.id.recyclerEpisodes)
        btnPlaySeries               = findViewById(R.id.btnPlay)
        btnFavoriteSeries           = findViewById(R.id.btnFavorite)
        btnResume                   = findViewById(R.id.btnResume)
        btnDownloadEpisodeArea      = findViewById(R.id.btnDownloadArea)
        imgDownloadEpisodeState     = findViewById(R.id.imgDownloadState)
        tvDownloadEpisodeState      = findViewById(R.id.tvDownloadState)
        btnDownloadSeason           = findViewById(R.id.btnDownloadSeason)

        layoutProgress    = findViewById(R.id.layoutProgress)
        progressBarSeries = findViewById(R.id.progressBarMovie)
        tvTimeRemaining   = findViewById(R.id.tvTimeRemaining)
        btnRestartAction  = findViewById(R.id.btnRestartAction)

        webViewTrailer        = findViewById(R.id.webViewTrailer)
        btnToggleMute         = findViewById(R.id.btnToggleMute)
        trailerLoadingSpinner = findViewById(R.id.trailerLoadingSpinner)

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
    }

    // ─────────────────────────────────────────────────────────────
    // TMDB
    // ─────────────────────────────────────────────────────────────

    private fun verificarTecnologias(nome: String) {
        val nomeUpper = nome.uppercase()
        var temBadge  = false
        if (nomeUpper.contains("4K") || nomeUpper.contains("UHD"))       { tvBadge4k.visibility   = View.VISIBLE; temBadge = true }
        if (nomeUpper.contains("HDR"))                                     { tvBadgeHdr.visibility  = View.VISIBLE; temBadge = true }
        if (nomeUpper.contains("DOLBY") || nomeUpper.contains("VISION"))  { tvBadgeDolby.visibility = View.VISIBLE; temBadge = true }
        if (nomeUpper.contains("5.1"))                                     { tvBadge51.visibility   = View.VISIBLE; temBadge = true }
        llTechBadges.visibility = if (temBadge) View.VISIBLE else View.GONE
    }

    private fun sincronizarDadosTMDB() {
        val apiKey = TmdbConfig.API_KEY
        var cleanName = seriesName
        cleanName = cleanName.replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "")
        cleanName = cleanName.replace(Regex("\\b\\d{4}\\b"), "")
        listOf("FHD", "HD", "SD", "4K", "8K", "H265", "LEG", "DUBLADO", "DUB", "|", "-", "_", ".")
            .forEach { cleanName = cleanName.replace(it, "", ignoreCase = true) }
        cleanName = cleanName.trim().replace(Regex("\\s+"), " ")
        val encodedName = try { URLEncoder.encode(cleanName, "UTF-8") } catch (e: Exception) { cleanName }
        val url = "https://api.themoviedb.org/3/search/tv?api_key=$apiKey&query=$encodedName&language=pt-BR&region=BR"

        client.newCall(Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = cleanName }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string()
                if (body != null) {
                    try {
                        val results = JSONObject(body).optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            val show   = results.getJSONObject(0)
                            val tmdbId = show.getInt("id")
                            buscarLogoSerieTraduzida(tmdbId, apiKey, cleanName)
                            buscarDetalhesTMDB(tmdbId, apiKey)
                            runOnUiThread {
                                val sinopse = show.optString("overview")
                                tvPlot.text = if (sinopse.isNotEmpty()) sinopse else "Sinopse indisponível."
                                val vote = show.optDouble("vote_average", 0.0)
                                if (vote > 0) tvRating.text = "Nota: ${String.format("%.1f", vote)}"
                                val backdropPath = show.optString("backdrop_path")
                                if (backdropPath.isNotEmpty() && imgBackground != imgPoster) {
                                    // ✅ Imagem servida via VPS (VpsConfig)
                                    Glide.with(this@SeriesDetailsActivity)
                                        .load(VpsConfig.tmdbImage(backdropPath, "w1280"))
                                        .centerCrop().into(imgBackground)
                                }
                                Glide.with(this@SeriesDetailsActivity)
                                    .load(seriesIcon).placeholder(R.mipmap.ic_launcher).centerCrop().into(imgPoster)
                            }
                        } else {
                            runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = cleanName }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { tvTitle.visibility = View.VISIBLE; tvTitle.text = cleanName }
                    }
                }
            }
        })
    }

    private fun buscarLogoSerieTraduzida(id: Int, key: String, nomeLimpo: String) {
        val imagesUrl = "https://api.themoviedb.org/3/tv/$id/images?api_key=$key&include_image_language=pt,null"
        client.newCall(Request.Builder().url(imagesUrl).build()).enqueue(object : okhttp3.Callback {
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string()
                if (body != null) {
                    try {
                        val logos = JSONObject(body).optJSONArray("logos")
                        if (logos != null && logos.length() > 0) {
                            var logoPath: String? = null
                            for (i in 0 until logos.length()) {
                                val logo = logos.getJSONObject(i)
                                if (logo.optString("iso_639_1", "").equals("pt", ignoreCase = true)) {
                                    val fp = logo.optString("file_path", "")
                                    if (fp.isNotEmpty()) { logoPath = fp; break }
                                }
                            }
                            if (!logoPath.isNullOrEmpty()) {
                                // ✅ Imagem servida via VPS (VpsConfig)
                                val finalUrl = VpsConfig.tmdbImage(logoPath, "w500")
                                getSharedPreferences("vltv_logos_cache", Context.MODE_PRIVATE).edit()
                                    .putString("series_logo_$seriesId", finalUrl).apply()
                                runOnUiThread {
                                    tvTitle.visibility      = View.GONE
                                    imgTitleLogo.visibility = View.VISIBLE
                                    Glide.with(this@SeriesDetailsActivity).load(finalUrl)
                                        .diskCacheStrategy(DiskCacheStrategy.ALL).into(imgTitleLogo)
                                }
                            } else {
                                runOnUiThread { imgTitleLogo.visibility = View.GONE; tvTitle.visibility = View.VISIBLE; tvTitle.text = nomeLimpo }
                            }
                        } else {
                            runOnUiThread { imgTitleLogo.visibility = View.GONE; tvTitle.visibility = View.VISIBLE; tvTitle.text = nomeLimpo }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { imgTitleLogo.visibility = View.GONE; tvTitle.visibility = View.VISIBLE; tvTitle.text = nomeLimpo }
                    }
                } else {
                    runOnUiThread { imgTitleLogo.visibility = View.GONE; tvTitle.visibility = View.VISIBLE; tvTitle.text = nomeLimpo }
                }
            }
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread { imgTitleLogo.visibility = View.GONE; tvTitle.visibility = View.VISIBLE; tvTitle.text = nomeLimpo }
            }
        })
    }

    private fun buscarDetalhesTMDB(id: Int, key: String) {
        val url = "https://api.themoviedb.org/3/tv/$id?api_key=$key&append_to_response=credits,recommendations&language=pt-BR"
        client.newCall(Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string() ?: return
                try {
                    val d = JSONObject(body)
                    val gs = d.optJSONArray("genres"); val genresList = mutableListOf<String>()
                    if (gs != null) for (i in 0 until gs.length()) genresList.add(gs.getJSONObject(i).getString("name"))
                    val castArray = d.optJSONObject("credits")?.optJSONArray("cast"); val castNames = mutableListOf<String>()
                    if (castArray != null) for (i in 0 until minOf(castArray.length(), 10)) castNames.add(castArray.getJSONObject(i).getString("name"))
                    val firstAirDate   = d.optString("first_air_date", "")
                    val createdByArray = d.optJSONArray("created_by"); val creatorsList = mutableListOf<String>()
                    if (createdByArray != null) for (i in 0 until createdByArray.length()) creatorsList.add(createdByArray.getJSONObject(i).getString("name"))
                    val similarResults = d.optJSONObject("recommendations")?.optJSONArray("results")
                    val sugestoesList  = ArrayList<JSONObject>()
                    if (similarResults != null) for (i in 0 until similarResults.length()) sugestoesList.add(similarResults.getJSONObject(i))
                    runOnUiThread {
                        tvGenre.text = "Gênero: ${if (genresList.isEmpty()) "Variados" else genresList.joinToString(", ")}"
                        tvCast.text  = "Elenco: ${castNames.joinToString(", ")}"
                        if (firstAirDate.isNotEmpty()) { tvReleaseDate.text = "Lançamento: ${firstAirDate.split("-")[0]}"; tvReleaseDate.visibility = View.VISIBLE }
                        if (creatorsList.isNotEmpty()) { tvCreatedBy.text = "Criado por: ${creatorsList.joinToString(", ")}"; tvCreatedBy.visibility = View.VISIBLE }
                        if (sugestoesList.isNotEmpty()) recyclerSuggestions.adapter = SuggestionsAdapter(sugestoesList)
                    }
                } catch (e: Exception) { }
            }
        })
    }

    private fun tentarCarregarLogoCache() {
        val cachedUrl = getSharedPreferences("vltv_logos_cache", Context.MODE_PRIVATE)
            .getString("series_logo_$seriesId", null)
        if (cachedUrl != null) {
            tvTitle.visibility      = View.GONE
            imgTitleLogo.visibility = View.VISIBLE
            Glide.with(this).load(cachedUrl).diskCacheStrategy(DiskCacheStrategy.ALL).into(imgTitleLogo)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // EPISÓDIOS / TEMPORADAS
    // ─────────────────────────────────────────────────────────────

    private fun carregarSeriesInfo() {
        val prefs    = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""
        XtreamApi.service.getSeriesInfoV2(username, password, seriesId = seriesId)
            .enqueue(object : Callback<SeriesInfoResponse> {
                override fun onResponse(call: Call<SeriesInfoResponse>, response: Response<SeriesInfoResponse>) {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        episodesBySeason = body.episodes ?: emptyMap()
                        sortedSeasons    = episodesBySeason.keys.sortedBy { it.toIntOrNull() ?: 0 }
                        if (sortedSeasons.isNotEmpty()) {
                            mudarTemporada(sortedSeasons.first())
                            verificarResume()
                        } else {
                            btnSeasonSelector.text = "Indisponível"
                            btnSeasonSelector.setTextColor(Color.WHITE)
                        }
                    }
                }
                override fun onFailure(call: Call<SeriesInfoResponse>, t: Throwable) {
                    Toast.makeText(this@SeriesDetailsActivity, "Erro de conexão", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun mostrarSeletorDeTemporada() {
        if (sortedSeasons.isEmpty()) return
        val dialog = BottomSheetDialog(this, R.style.DialogTemporadaTransparente)
        val root   = RelativeLayout(this)
        root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 500.toPx())
        root.setBackgroundColor(Color.TRANSPARENT)

        val btnClose = ImageButton(this)
        btnClose.id  = View.generateViewId()
        val closeParams = RelativeLayout.LayoutParams(65.toPx(), 65.toPx())
        closeParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        closeParams.addRule(RelativeLayout.CENTER_HORIZONTAL)
        closeParams.setMargins(0, 0, 0, 30.toPx())
        btnClose.layoutParams = closeParams
        btnClose.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        btnClose.setColorFilter(Color.WHITE)
        btnClose.background = null; btnClose.scaleType = ImageView.ScaleType.FIT_CENTER
        btnClose.setPadding(10.toPx(), 10.toPx(), 10.toPx(), 10.toPx())
        btnClose.isFocusable = true; btnClose.isClickable = true
        btnClose.setOnClickListener { dialog.dismiss() }
        btnClose.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) { v.setBackgroundResource(R.drawable.bg_focus_neon); v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).start() }
            else          { v.setBackgroundResource(0); v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start() }
        }

        val rvSeasons = RecyclerView(this)
        val rvParams  = RelativeLayout.LayoutParams(250.toPx(), ViewGroup.LayoutParams.WRAP_CONTENT)
        rvParams.addRule(RelativeLayout.ALIGN_PARENT_TOP)
        rvParams.addRule(RelativeLayout.ABOVE, btnClose.id)
        rvParams.addRule(RelativeLayout.CENTER_HORIZONTAL)
        rvParams.setMargins(0, 10.toPx(), 0, 10.toPx())
        rvSeasons.layoutParams = rvParams
        rvSeasons.layoutManager = LinearLayoutManager(this)
        rvSeasons.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(parent.context)
                tv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                tv.setPadding(20, 35, 20, 35); tv.gravity = Gravity.CENTER; tv.textSize = 22f
                tv.setTextColor(Color.WHITE); tv.isFocusable = true; tv.isClickable = true
                return object : RecyclerView.ViewHolder(tv) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val season = sortedSeasons[position]
                val tv = holder.itemView as TextView
                tv.text = "Temporada $season"
                tv.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) { v.setBackgroundResource(R.drawable.bg_focus_neon); (v as TextView).setTextColor(Color.YELLOW); v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start() }
                    else          { v.setBackgroundColor(Color.TRANSPARENT); (v as TextView).setTextColor(Color.WHITE); v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start() }
                }
                tv.setOnClickListener { mudarTemporada(season); dialog.dismiss() }
            }
            override fun getItemCount() = sortedSeasons.size
        }

        root.addView(btnClose); root.addView(rvSeasons)
        dialog.setContentView(root)
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
            behavior.peekHeight = 500.toPx(); it.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.show()
        rvSeasons.postDelayed({ rvSeasons.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }, 150)
    }

    private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun mudarTemporada(seasonKey: String) {
        currentSeason = seasonKey
        btnSeasonSelector.text = "Temporada $seasonKey ▼"
        btnSeasonSelector.setTextColor(Color.WHITE)
        btnDownloadSeason.text = "⬇  Baixar Temporada $seasonKey"
        val lista = episodesBySeason[seasonKey] ?: emptyList()
        if (lista.isNotEmpty()) {
            currentEpisode = lista.first()
            verificarResume()
        }
        rvEpisodes.adapter = EpisodeAdapter(this, lista, currentProfile, seasonKey) { ep, _ ->
            currentEpisode = ep
            verificarResume()
            abrirPlayer(ep, true)
        }
    }

    private fun baixarTemporadaAtual() {
        val lista = episodesBySeason[currentSeason] ?: emptyList()
        if (lista.isEmpty()) {
            Toast.makeText(this, "Nenhum episódio pra baixar nessa temporada.", Toast.LENGTH_SHORT).show()
            return
        }
        val seasonInt = currentSeason.toIntOrNull() ?: 0
        val paraBaixar = lista.map { ep ->
            DownloadHelper.EpisodioParaBaixar(
                streamId = ep.id.toIntOrNull() ?: 0,
                extensao = ep.container_extension,
                nomeExibicao = "T${currentSeason}E${ep.episode_num} - ${ep.title}"
            )
        }
        mostrarDialogConfirmacao(
            titulo = "Baixar Temporada $currentSeason",
            mensagem = "Isso vai baixar todos os ${lista.size} episódios dessa temporada que ainda não foram baixados.",
            btnPositivo = "Baixar Temporada",
            corPositivo = "#FFFFFF"
        ) {
            // ✅ NOVO: passa o perfil atual (adulto ou Kids) pra cada
            // episódio da temporada ficar marcado com o dono certo — é o
            // que faz DownloadsActivity/KidsDownloadsActivity filtrarem
            // corretamente.
            DownloadHelper.baixarTemporadaCompleta(this, seriesName, seasonInt, paraBaixar, seriesIcon, profileName = currentProfile)
            Handler(Looper.getMainLooper()).postDelayed({ rvEpisodes.adapter?.notifyDataSetChanged() }, 800)
        }
    }

    // ✅ NOVO: monta a "mochila" completa da série — TODOS os episódios de
    // TODAS as temporadas, em ordem, com temporada/título/extensão de
    // cada um alinhados por índice. É essa lista que o PlayerActivity usa
    // pra calcular o próximo episódio corretamente, mesmo virando de
    // temporada.
    private fun construirMochilaCompleta(): MochilaEpisodios {
        val ids = ArrayList<Int>()
        val seasons = ArrayList<Int>()
        val titles = ArrayList<String>()
        val exts = ArrayList<String>()
        for (season in sortedSeasons) {
            val eps = episodesBySeason[season] ?: continue
            val seasonInt = season.toIntOrNull() ?: 0
            for (ep in eps) {
                val sid = ep.id.toIntOrNull() ?: 0
                if (sid == 0) continue
                ids.add(sid)
                seasons.add(seasonInt)
                titles.add("T${season}E${ep.episode_num} - ${ep.title}")
                exts.add(ep.container_extension ?: "mp4")
            }
        }
        return MochilaEpisodios(ids, seasons, titles, exts)
    }

    // ✅ REESCRITO: antes montava a mochila só com episodesBySeason[currentSeason]
    // e calculava o "próximo episódio" manualmente (o que travava no
    // último episódio da temporada). Agora usa construirMochilaCompleta()
    // — a mochila cobre a série inteira e o PlayerActivity é quem decide
    // sozinho, com precisão, qual é o próximo episódio (mesmo que seja o
    // primeiro da temporada seguinte) e se deve avisar sobre o fim da
    // temporada.
    private fun abrirPlayer(ep: EpisodeStream, usarResume: Boolean) {
        val streamId  = ep.id.toIntOrNull() ?: 0
        val ext       = ep.container_extension ?: "mp4"

        val mochila = construirMochilaCompleta()

        val prefs     = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val keyResume = "${currentProfile}_series_resume_${streamId}_pos"
        val pos       = prefs.getLong(keyResume, 0L)
        val existe    = usarResume && pos > 30000L

        val intent    = Intent(this, PlayerActivity::class.java)
        intent.putExtra("stream_id", streamId); intent.putExtra("stream_ext", ext)
        intent.putExtra("stream_type", "series")
        intent.putExtra("channel_name", "T${currentSeason}E${ep.episode_num} - $seriesName")
        intent.putExtra("PROFILE_NAME", currentProfile)

        if (mochila.ids.isNotEmpty()) {
            intent.putIntegerArrayListExtra("episode_list", mochila.ids)
            intent.putIntegerArrayListExtra("episode_seasons", mochila.seasons)
            intent.putStringArrayListExtra("episode_titles", mochila.titles)
            intent.putStringArrayListExtra("episode_exts", mochila.exts)
        }
        if (existe) intent.putExtra("start_position_ms", pos)
        startActivity(intent)
    }

    private fun encontrarEpisodioParaAssistir(): EpisodeStream? {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        for (season in sortedSeasons) {
            val eps = episodesBySeason[season] ?: continue
            for (ep in eps) {
                val sid = ep.id.toIntOrNull() ?: 0
                val pos = prefs.getLong("${currentProfile}_series_resume_${sid}_pos", 0L)
                if (pos > 10000L) { currentSeason = season; currentEpisode = ep; return ep }
            }
        }
        if (sortedSeasons.isNotEmpty()) {
            val s1 = sortedSeasons.first(); val eps = episodesBySeason[s1]
            if (!eps.isNullOrEmpty()) { currentSeason = s1; currentEpisode = eps.first(); return eps.first() }
        }
        return null
    }

    private fun encontrarEpisodioParaContinuar(): EpisodeStream? {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        for (season in sortedSeasons) {
            val eps = episodesBySeason[season] ?: continue
            for (ep in eps) {
                val sid = ep.id.toIntOrNull() ?: 0
                val pos = prefs.getLong("${currentProfile}_series_resume_${sid}_pos", 0L)
                if (pos > 10000L) return ep
            }
        }
        return null
    }

    private fun verificarResume() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        var epRecente: EpisodeStream? = null
        var maxPos = 0L; var maxDur = 0L
        for (season in sortedSeasons) {
            val eps = episodesBySeason[season] ?: continue
            for (ep in eps) {
                val sid = ep.id.toIntOrNull() ?: 0
                val pos = prefs.getLong("${currentProfile}_series_resume_${sid}_pos", 0L)
                val dur = prefs.getLong("${currentProfile}_series_resume_${sid}_dur", 0L)
                if (pos > 1000L) { epRecente = ep; maxPos = pos; maxDur = dur; break }
            }
            if (epRecente != null) break
        }
        runOnUiThread {
            if (epRecente != null && maxDur > 0) {
                btnPlaySeries.text            = "CONTINUAR T${currentSeason}:E${epRecente.episode_num}"
                btnResume.visibility          = View.VISIBLE
                btnRestartAction?.visibility  = View.VISIBLE
                layoutProgress?.visibility    = View.VISIBLE
                progressBarSeries?.progress   = ((maxPos.toFloat() / maxDur.toFloat()) * 100).toInt()
                val restMs  = maxDur - maxPos
                val hours   = TimeUnit.MILLISECONDS.toHours(restMs)
                val minutes = TimeUnit.MILLISECONDS.toMinutes(restMs) % 60
                tvTimeRemaining?.text = "Restam ${hours}h${minutes}min"
            } else {
                btnPlaySeries.text           = "ASSISTIR"
                btnResume.visibility         = View.GONE
                btnRestartAction?.visibility = View.GONE
                layoutProgress?.visibility   = View.GONE
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    // DIÁLOGO PREMIUM (mesmo padrão do resto do app)
    // ────────────────────────────────────────────────────────────────

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
            text = "Cancelar"; textSize = 14f; setTextColor(Color.parseColor("#888888")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 6.dp }
            background = GradientDrawable().apply { setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat(); setStroke(1.dp, Color.parseColor("#2A2A2A")) }
            isClickable = true; isFocusable = true; setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(TextView(this).apply {
            text = btnPositivo; textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (isDestructive) Color.WHITE else Color.BLACK); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginStart = 6.dp }
            background = GradientDrawable().apply { setColor(corBtnPos); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true; setOnClickListener { dialog.dismiss(); onConfirmar() }
        })
        root.addView(btnRow)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.parseColor("#141414")); cornerRadius = 16.dp.toFloat() })
            val p = attributes; p.width = (resources.displayMetrics.widthPixels * 0.85).toInt(); attributes = p
        }
        dialog.show()
    }

    // ─────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────

    // Detecção de TV centralizada em DeviceUtils.kt (isTelevisionDevice()),
    // usada em todo o app — não reimplementar localmente aqui.

    private fun getFavSeries(context: Context): MutableSet<Int> {
        val prefs = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val set   = prefs.getStringSet("${currentProfile}_fav_series", emptySet()) ?: emptySet()
        return set.mapNotNull { it.toIntOrNull() }.toMutableSet()
    }

    private fun saveFavSeries(context: Context, ids: Set<Int>) {
        context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit()
            .putStringSet("${currentProfile}_fav_series", ids.map { it.toString() }.toSet()).apply()
    }

    private fun atualizarIconeFavoritoSerie(isFav: Boolean) {
        if (isFav) { btnFavoriteSeries.setImageResource(android.R.drawable.btn_star_big_on); btnFavoriteSeries.setColorFilter(Color.parseColor("#FFD700")) }
        else        { btnFavoriteSeries.setImageResource(android.R.drawable.btn_star_big_off); btnFavoriteSeries.clearColorFilter() }
    }

    // ─────────────────────────────────────────────────────────────
    // RESOLUÇÃO DE ID REAL NO CATÁLOGO (Sugestões do TMDB)
    // ─────────────────────────────────────────────────────────────

    // ✅ NOVO: normalização de comparação (mesma técnica usada na Home
    // pra corrigir o bug do banner de destaque — ver
    // HomeActivity.normalizarParaComparacaoTitulo). Remove acento,
    // pontuação e diferenças de maiúscula/minúscula, mas preserva o
    // texto inteiro — não é uma busca "parcial".
    private fun normalizarParaComparacao(s: String): String {
        return s.lowercase()
            .replace(Regex("[àáâãäå]"), "a")
            .replace(Regex("[èéêë]"), "e")
            .replace(Regex("[ìíîï]"), "i")
            .replace(Regex("[òóôõö]"), "o")
            .replace(Regex("[ùúûü]"), "u")
            .replace(Regex("[ç]"), "c")
            .replace(Regex("[ñ]"), "n")
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ✅ CORRIGIDO (bug "Reacher abre Preacher", agora também nas
    // Sugestões dos Detalhes de série): a versão antiga fazia
    // "name = tituloLimpo" (exata, muito sensível a formatação) e, se
    // falhasse, caía num "name LIKE '%titulo%'" que aceitava QUALQUER
    // série cujo nome contivesse o título como substring — foi
    // exatamente isso que causou o "Reacher" abrindo episódios de
    // "Preacher".
    //
    // Agora a busca é em duas etapas: (1) traz uma lista ampla de
    // candidatos via LIKE só pra reduzir o universo, (2) só aceita um
    // candidato se o título INTEIRO normalizado for igual ao nome dele
    // normalizado — não apenas contido nele. Mais tolerante a diferenças
    // de acento/pontuação/espaço, mas sem o risco de pegar outra série.
    private suspend fun resolverSeriesIdReal(tituloTmdb: String): Pair<Int, String>? =
        withContext(Dispatchers.IO) {
            val alvoNormalizado = normalizarParaComparacao(tituloTmdb)
            if (alvoNormalizado.isBlank()) return@withContext null
            val termoBusca = tituloTmdb.split(" ").filter { it.length >= 5 }.maxByOrNull { it.length }
                ?: tituloTmdb.trim().take(6)
            if (termoBusca.isBlank()) return@withContext null

            val cursor = database.openHelper.readableDatabase.query(
                "SELECT series_id, cover, name FROM series_streams WHERE name LIKE ? LIMIT 100",
                arrayOf("%$termoBusca%")
            )
            var resultado: Pair<Int, String>? = null
            while (cursor.moveToNext()) {
                val nomeCandidato = cursor.getString(2) ?: ""
                if (normalizarParaComparacao(nomeCandidato) == alvoNormalizado) {
                    resultado = cursor.getInt(0) to (cursor.getString(1) ?: "")
                    break
                }
            }
            cursor.close()
            resultado
        }

    // ─────────────────────────────────────────────────────────────
    // ADAPTERS
    // ─────────────────────────────────────────────────────────────

    class EpisodeAdapter(
        private val activity: SeriesDetailsActivity,
        val list: List<EpisodeStream>,
        private val profile: String,
        private val season: String,
        private val onClick: (EpisodeStream, Int) -> Unit
    ) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

        private val jobsAtivos = mutableMapOf<Int, Job>()

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView             = v.findViewById(R.id.tvEpisodeTitle)
            val imgThumb: ImageView           = v.findViewById(R.id.imgEpisodeThumb)
            val tvPlotEp: TextView            = v.findViewById(R.id.tvEpisodePlot)
            val btnDownloadItem: FrameLayout  = v.findViewById(R.id.btnDownloadEpisode)
            val imgDownloadIcon: ImageView    = v.findViewById(R.id.imgDownloadIcon)
            val pbDownload: com.google.android.material.progressindicator.CircularProgressIndicator =
                v.findViewById(R.id.pbEpisodeDownload)
            val pbEpisodeProgress: ProgressBar? = v.findViewById(R.id.pbEpisodeProgress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ep = list[position]
            holder.tvTitle.text  = "E${ep.episode_num.toString().padStart(2, '0')} - ${ep.title}"
            holder.tvPlotEp.text = ep.info?.plot ?: "Sem descrição disponível."
            val capaUrl = ep.info?.movie_image ?: ""
            Glide.with(holder.itemView.context)
                .load(capaUrl)
                .error(android.R.color.black)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .into(holder.imgThumb)

            val epId  = ep.id.toIntOrNull() ?: 0
            val prefs = holder.itemView.context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            val pos   = prefs.getLong("${profile}_series_resume_${epId}_pos", 0)
            val dur   = prefs.getLong("${profile}_series_resume_${epId}_dur", 0)
            if (pos > 0 && dur > 0) {
                holder.pbEpisodeProgress?.visibility = View.VISIBLE
                holder.pbEpisodeProgress?.progress   = ((pos.toFloat() / dur.toFloat()) * 100).toInt()
            } else {
                holder.pbEpisodeProgress?.visibility = View.GONE
            }

            holder.itemView.setOnClickListener { onClick(ep, position) }
            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                holder.tvTitle.setTextColor(if (hasFocus) Color.YELLOW else Color.WHITE)
                if (hasFocus) { view.setBackgroundResource(R.drawable.bg_focus_neon); view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200).start() }
                else          { view.setBackgroundResource(0); view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start() }
            }

            if (activity.isTelevisionDevice()) {
                holder.btnDownloadItem.visibility = View.GONE
            } else {
                holder.btnDownloadItem.visibility = View.VISIBLE
                jobsAtivos[position]?.cancel()
                // ✅ CORRIGIDO (mesmo bug da "seta que volta sozinha", só
                // que no download por episódio dentro da série): antes,
                // se a primeira leitura do banco desse "null" (porque o
                // insert do DownloadHelper ainda não tinha assentado), o
                // "while" nem entrava e o ícone ficava preso no estado
                // "BAIXAR" pra sempre — mesmo com o download rodando por
                // trás. Agora o loop tolera algumas leituras nulas antes
                // de desistir de fato.
                jobsAtivos[position] = activity.lifecycleScope.launch(Dispatchers.IO) {
                    var dl = activity.database.streamDao().getDownloadByStreamId(epId, "series")
                    withContext(Dispatchers.Main) { aplicarEstadoDownload(holder, dl) }
                    var tentativasNulas = 0
                    while (isActive) {
                        val emProgresso = dl != null &&
                            (dl.status == "BAIXANDO" || dl.status == "RUNNING" ||
                             dl.status == "NA_FILA" || dl.status == "PAUSADO")
                        if (emProgresso) {
                            tentativasNulas = 0
                            kotlinx.coroutines.delay(1200)
                            dl = activity.database.streamDao().getDownloadByStreamId(epId, "series")
                            withContext(Dispatchers.Main) { aplicarEstadoDownload(holder, dl) }
                        } else if (dl == null && tentativasNulas < 4) {
                            tentativasNulas++
                            kotlinx.coroutines.delay(400)
                            dl = activity.database.streamDao().getDownloadByStreamId(epId, "series")
                            withContext(Dispatchers.Main) { aplicarEstadoDownload(holder, dl) }
                        } else {
                            break
                        }
                    }
                }

                holder.btnDownloadItem.setOnClickListener {
                    activity.lifecycleScope.launch(Dispatchers.IO) {
                        val existente = activity.database.streamDao().getDownloadByStreamId(epId, "series")
                        withContext(Dispatchers.Main) {
                            when {
                                existente == null || existente.status == "ERRO" -> {
                                    // ✅ Mostra "na fila" imediatamente na UI,
                                    // e só confirma de vez (notifyItemChanged)
                                    // quando o DownloadHelper garantir que a
                                    // linha já está no Room — elimina a
                                    // mesma corrida da tela de Detalhes.
                                    holder.imgDownloadIcon.visibility = View.GONE
                                    holder.pbDownload.visibility = View.VISIBLE
                                    holder.pbDownload.isIndeterminate = true
                                    DownloadHelper.iniciarDownload(
                                        context = activity,
                                        streamId = epId,
                                        nomePrincipal = activity.seriesNameParaDownload(),
                                        nomeEpisodio = "T${season}E${ep.episode_num} - ${ep.title}",
                                        imagemUrl = activity.seriesIconParaDownload(),
                                        isSeries = true,
                                        season = season.toIntOrNull() ?: 0,
                                        extensaoContainer = ep.container_extension,
                                        // ✅ NOVO: "profile" já é o perfil atual,
                                        // recebido no construtor do adapter
                                        // (mesmo currentProfile da Activity).
                                        profileName = profile,
                                        aoIniciar = {
                                            notifyItemChanged(position)
                                        }
                                    )
                                }
                                existente.status == "PAUSADO" -> {
                                    activity.confirmarContinuarOuCancelarEpisodio(existente) { notifyItemChanged(position) }
                                }
                                else -> {
                                    activity.confirmarPausarOuCancelarEpisodio(existente) { notifyItemChanged(position) }
                                }
                            }
                        }
                    }
                }
            }
        }

        private fun aplicarEstadoDownload(holder: VH, dl: DownloadEntity?) {
            when {
                dl == null || dl.status == "ERRO" -> {
                    holder.pbDownload.visibility = View.GONE
                    holder.imgDownloadIcon.visibility = View.VISIBLE
                    holder.imgDownloadIcon.setImageResource(android.R.drawable.stat_sys_download)
                    holder.imgDownloadIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#AAFFFFFF"))
                }
                dl.status == "NA_FILA" -> {
                    holder.imgDownloadIcon.visibility = View.GONE
                    holder.pbDownload.visibility = View.VISIBLE
                    holder.pbDownload.isIndeterminate = true
                }
                dl.status == "BAIXANDO" || dl.status == "RUNNING" -> {
                    holder.imgDownloadIcon.visibility = View.GONE
                    holder.pbDownload.visibility = View.VISIBLE
                    holder.pbDownload.isIndeterminate = false
                    holder.pbDownload.setProgressCompat(dl.progress, true)
                }
                dl.status == "PAUSADO" -> {
                    holder.pbDownload.visibility = View.GONE
                    holder.imgDownloadIcon.visibility = View.VISIBLE
                    holder.imgDownloadIcon.setImageResource(android.R.drawable.ic_media_pause)
                    holder.imgDownloadIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFC107"))
                }
                else -> { // BAIXADO / COMPLETED
                    holder.pbDownload.visibility = View.GONE
                    holder.imgDownloadIcon.visibility = View.VISIBLE
                    holder.imgDownloadIcon.setImageResource(R.drawable.ic_phone_outline)
                    holder.imgDownloadIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                }
            }
        }

        override fun getItemCount() = list.size
    }

    fun seriesNameParaDownload() = seriesName
    fun seriesIconParaDownload() = seriesIcon

    fun confirmarPausarOuCancelarEpisodio(download: DownloadEntity, aoConcluir: () -> Unit) {
        DownloadDialogHelper.confirmarAcaoDupla(
            context = this,
            titulo = "Download em Andamento",
            mensagem = "O que deseja fazer com o download desse episódio?",
            btnPrincipal = "Pausar Download",
            corPrincipal = "#FFFFFF",
            onPrincipal = { DownloadHelper.pausarDownload(this, download); aoConcluir() },
            btnSecundario = "Cancelar Download",
            corSecundario = "#FF5252",
            onSecundario = { DownloadHelper.cancelarDownload(this, download); aoConcluir() }
        )
    }

    fun confirmarContinuarOuCancelarEpisodio(download: DownloadEntity, aoConcluir: () -> Unit) {
        DownloadDialogHelper.confirmarAcaoDupla(
            context = this,
            titulo = "Episódio Pausado",
            mensagem = "Esse episódio está pausado. O que deseja fazer?",
            btnPrincipal = "Continuar Download",
            corPrincipal = "#FFFFFF",
            onPrincipal = { DownloadHelper.continuarDownload(this, download); aoConcluir() },
            btnSecundario = "Cancelar Download",
            corSecundario = "#FF5252",
            onSecundario = { DownloadHelper.cancelarDownload(this, download); aoConcluir() }
        )
    }

    fun confirmarExcluirEpisodio(download: DownloadEntity, aoConcluir: () -> Unit) {
        mostrarDialogConfirmacao(
            titulo = "Excluir Episódio Baixado",
            mensagem = "Deseja apagar esse episódio do seu dispositivo?",
            btnPositivo = "Excluir",
            corPositivo = "#FF5252"
        ) {
            DownloadHelper.excluirDownload(this, download) { aoConcluir() }
        }
    }

    inner class SuggestionsAdapter(val items: List<JSONObject>) : RecyclerView.Adapter<SuggestionsAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView   = v.findViewById(android.R.id.icon)
            val tvName: TextView = v.findViewById(android.R.id.text1)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val container = LinearLayout(parent.context)
            container.orientation = LinearLayout.VERTICAL
            val params = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(12, 12, 12, 12); container.layoutParams = params
            container.gravity = Gravity.CENTER_HORIZONTAL; container.isFocusable = true; container.isClickable = true
            val card = androidx.cardview.widget.CardView(parent.context)
            val cardParams = LinearLayout.LayoutParams(130.toPx(), 200.toPx())
            card.layoutParams = cardParams; card.radius = 12f; card.cardElevation = 4f
            val img = ImageView(parent.context); img.id = android.R.id.icon; img.scaleType = ImageView.ScaleType.CENTER_CROP
            card.addView(img)
            val tv = TextView(parent.context)
            tv.id = android.R.id.text1
            val tvParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            tvParams.topMargin = 8; tv.layoutParams = tvParams
            tv.setTextColor(Color.WHITE); tv.textSize = 12f; tv.maxLines = 2
            tv.ellipsize = android.text.TextUtils.TruncateAt.END; tv.gravity = Gravity.CENTER
            container.addView(card); container.addView(tv)
            return ViewHolder(container)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item       = items[position]
            val posterPath = item.optString("poster_path")
            val name       = item.optString("name")
            val rating     = item.optDouble("vote_average", 0.0)
            // ✅ Imagem servida via VPS (VpsConfig)
            val posterUrl  = VpsConfig.tmdbImage(posterPath, "w342")
            Glide.with(holder.itemView.context).load(posterUrl).into(holder.img)
            holder.tvName.text = name
            holder.itemView.setOnClickListener {
                lifecycleScope.launch {
                    val resolvido = resolverSeriesIdReal(name)
                    if (resolvido == null) {
                        Toast.makeText(holder.itemView.context, "Essa série não está disponível no seu catálogo.", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val (idReal, coverReal) = resolvido
                    val intent = Intent(holder.itemView.context, SeriesDetailsActivity::class.java)
                    intent.putExtra("series_id", idReal)
                    intent.putExtra("name", name)
                    intent.putExtra("icon", coverReal.ifEmpty { posterUrl })
                    intent.putExtra("rating", rating.toString())
                    intent.putExtra("PROFILE_NAME", currentProfile)
                    intent.putExtra("PROFILE_ICON", currentProfileIcon)
                    holder.itemView.context.startActivity(intent)
                }
            }
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) { v.animate().scaleX(1.05f).scaleY(1.05f).start(); v.setBackgroundResource(R.drawable.bg_focus_neon) }
                else          { v.animate().scaleX(1.0f).scaleY(1.0f).start(); v.setBackgroundResource(0) }
            }
        }
        override fun getItemCount() = items.size
        private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()
    }
}
