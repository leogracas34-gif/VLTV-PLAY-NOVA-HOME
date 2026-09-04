package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DecodeFormat
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import okhttp3.ResponseBody
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.CategoryEntity
import com.vltv.play.data.SeriesEntity
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class SeriesActivity : AppCompatActivity() {

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvSeries: RecyclerView
    private lateinit var progressBar: View
    private lateinit var tvCategoryTitle: TextView
    private var bottomNavigation: BottomNavigationView? = null

    private var username = ""
    private var password = ""
    private lateinit var seriesCachePrefs: SharedPreferences

    // ✅ NOVO: controle de "última sincronização" por categoria, salvo em
    // SharedPreferences (sobrevive entre aberturas da Activity/app, ao
    // contrário do seriesCache que é só em memória). Usado para não bater
    // no servidor de novo toda vez que a tela de Séries é reaberta — ver
    // categoriaEstaFresca() / marcarCategoriaSincronizada() mais abaixo.
    // Mesma correção aplicada no VodActivity.
    private lateinit var syncPrefs: SharedPreferences
    private val SYNC_STALE_MS = 6 * 60 * 60 * 1000L // 6 horas

    private val seriesCache = mutableMapOf<String, List<SeriesStream>>()
    private val logoMemoryCache = mutableMapOf<String, String>()

    private var categoryAdapter: SeriesCategoryAdapter? = null

    // Adapter único — nunca recriado, atualizado via DiffUtil
    private var seriesAdapter: SeriesAdapter? = null

    private var currentProfile: String = "Padrao"
    private var currentProfileIcon: String? = null
    private var ultimaCategoriaId: String? = null
    private var ultimaCategoriaNome: String? = null

    // Guard de race condition
    private var categoriaAtualId: String? = null

    private val database by lazy { AppDatabase.getDatabase(this) }

    // Detecção de TV centralizada em DeviceUtils.kt (context.isTelevisionDevice()),
    // usada em todo o app — não reimplementar localmente aqui.

    // ✅ Filtro central de conteúdo adulto para SÉRIES — chamado em TODO ponto
    // onde uma lista vai pro adapter, não importa de onde os dados vieram
    // (cache em memória, ContentRepository, banco Room, rede ou favoritos).
    private fun filtrarSeriesAdultas(lista: List<SeriesStream>): List<SeriesStream> {
        return if (ParentalControlManager.isEnabled(this))
            lista.filterNot { ParentalControlManager.isAdultName(it.name) }
        else lista
    }

    // ✅ Filtro central de conteúdo adulto para CATEGORIAS
    private fun filtrarCategoriasAdultas(lista: List<LiveCategory>): List<LiveCategory> {
        return if (ParentalControlManager.isEnabled(this))
            lista.filterNot { ParentalControlManager.isAdultName(it.name) }
        else lista
    }

    // ✅ NOVO: extrai o ano (19xx ou 20xx) embutido no nome da série, ex:
    // "Nome da Série (2026)" → 2026. Usado para ordenar sempre da mais
    // recente pra mais antiga. Séries sem ano detectável vão pro final.
    private fun extrairAnoSerie(nome: String): Int {
        return Regex("\\b(19|20)\\d{2}\\b").find(nome)?.value?.toIntOrNull() ?: 0
    }

    // ✅ NOVO: verdadeiro se essa categoria já foi sincronizada com o
    // servidor há menos de SYNC_STALE_MS. Enquanto estiver "fresca", o app
    // confia 100% no que já está salvo no Room/ContentRepository e NÃO
    // busca de novo na rede — é isso que elimina o "re-sync" toda vez que
    // a tela de Séries é reaberta.
    private fun categoriaEstaFresca(categoriaId: String): Boolean {
        val ultimaSync = syncPrefs.getLong("sync_$categoriaId", 0L)
        return (System.currentTimeMillis() - ultimaSync) < SYNC_STALE_MS
    }

    private fun marcarCategoriaSincronizada(categoriaId: String) {
        syncPrefs.edit().putLong("sync_$categoriaId", System.currentTimeMillis()).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vod)

        val vltvPrefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        currentProfile = intent.getStringExtra("PROFILE_NAME")
            ?: vltvPrefs.getString("last_profile_name", null)
            ?: "Padrao"
        currentProfileIcon = intent.getStringExtra("PROFILE_ICON")
            ?.takeIf { it.isNotEmpty() }
            ?: vltvPrefs.getString("last_profile_icon", null)?.takeIf { it.isNotEmpty() }

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (this.isTelevisionDevice()) {
            windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
        }

        rvCategories    = findViewById(R.id.rvCategories)
        rvSeries        = findViewById(R.id.rvChannels)
        progressBar     = findViewById(R.id.progressBar)
        tvCategoryTitle = findViewById(R.id.tvCategoryTitle)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        seriesCachePrefs = getSharedPreferences("vltv_series_cache", Context.MODE_PRIVATE)
        syncPrefs         = getSharedPreferences("vltv_series_sync", Context.MODE_PRIVATE)

        setupBottomNavigation()
        BottomNavProfileHelper.aplicarPerfilNoRodape(this, bottomNavigation, currentProfile, currentProfileIcon)

        findViewById<View>(R.id.etSearchContent)?.apply {
            isFocusableInTouchMode = false
            setOnClickListener {
                startActivity(Intent(this@SeriesActivity, SearchActivity::class.java).apply {
                    putExtra("initial_query", "")
                    putExtra("PROFILE_NAME", currentProfile)
                    putExtra("tipo_pesquisa", "series")
                })
            }
        }

        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        username = prefs.getString("username", "") ?: ""
        password = prefs.getString("password", "") ?: ""

        if (this.isTelevisionDevice()) {
            rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
            rvSeries.layoutManager = GridLayoutManager(this, 5)
            bottomNavigation?.visibility = View.GONE
        } else {
            rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            rvSeries.layoutManager = GridLayoutManager(this, 3)
            bottomNavigation?.visibility = View.VISIBLE
        }

        rvCategories.setHasFixedSize(true)
        rvCategories.setItemViewCacheSize(60)
        rvCategories.overScrollMode = View.OVER_SCROLL_NEVER

        if (this.isTelevisionDevice()) {
            rvCategories.isFocusable = true
            rvCategories.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            rvSeries.isFocusable = true
            rvSeries.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        } else {
            rvCategories.isFocusable = false
            rvCategories.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            rvSeries.isFocusable = false
            rvSeries.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        rvSeries.setHasFixedSize(true)
        rvSeries.setItemViewCacheSize(100)

        // Adapter criado UMA vez — nunca recriado
        seriesAdapter = SeriesAdapter { abrirDetalhesSerie(it) }
        rvSeries.adapter = seriesAdapter

        // Última categoria salva
        val catPrefs = getSharedPreferences("vltv_series_prefs", Context.MODE_PRIVATE)
        ultimaCategoriaId   = catPrefs.getString("ultima_cat_id", null)
        ultimaCategoriaNome = catPrefs.getString("ultima_cat_nome", null)

        // ── CARREGAMENTO INSTANTÂNEO DE SÉRIES ───────────────────────────────
        val catId = ultimaCategoriaId
        if (catId != null) {
            val seriesEmMemoria = ContentRepository.getSeriesByCategory(catId)
            if (seriesEmMemoria.isNotEmpty()) {
                categoriaAtualId = catId
                if (ultimaCategoriaNome != null) tvCategoryTitle.text = ultimaCategoriaNome
                seriesEmMemoria.take(30).forEach { s ->
                    val cached = seriesCachePrefs.getString("logo_${s.name}", null)
                    if (cached != null) logoMemoryCache[s.name] = cached
                }
                val items = seriesEmMemoria.map { SeriesStream(it.series_id, it.name, it.cover, it.rating) }
                // ✅ Filtro aplicado também no carregamento instantâneo
                val itemsFiltrados = filtrarSeriesAdultas(items)
                seriesAdapter?.submitList(itemsFiltrados)
                preLoadImages(itemsFiltrados)
            }
        }

        // ── CARREGAMENTO INSTANTÂNEO DE CATEGORIAS ───────────────────────────
        // Lê do banco Room (thread IO, ~2ms) → mostra imediatamente.
        // A rede atualiza em background e só reaplica se ainda não havia categorias.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val categoriasSalvas = database.streamDao().getCategoriesByType("series")
                if (categoriasSalvas.isNotEmpty()) {
                    val cats = mutableListOf<LiveCategory>()
                    cats.add(LiveCategory(category_id = "FAV_SERIES", category_name = "FAVORITOS"))
                    cats.addAll(categoriasSalvas.map {
                        LiveCategory(category_id = it.category_id, category_name = it.category_name)
                    })
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) aplicarCategorias(cats)
                    }
                }
                // Sempre busca da rede em background
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) carregarCategoriasRede()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) carregarCategoriasRede()
                }
            }
        }
    }

    private fun setupBottomNavigation() {
        bottomNavigation?.setOnItemSelectedListener { item ->
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
    }

    override fun onResume() {
        super.onResume()
        val vltvPrefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        currentProfile = vltvPrefs.getString("last_profile_name", currentProfile) ?: currentProfile
        currentProfileIcon = vltvPrefs.getString("last_profile_icon", currentProfileIcon)
            ?.takeIf { it.isNotEmpty() } ?: currentProfileIcon
        BottomNavProfileHelper.aplicarPerfilNoRodape(this, bottomNavigation, currentProfile, currentProfileIcon)
    }

    // ✅ Corrigido: usa lifecycleScope em vez de CoroutineScope(Dispatchers.IO)
    // solta. Assim a coroutine é cancelada automaticamente quando a Activity é
    // destruída, evitando "You cannot start a load for a destroyed activity".
    private fun preLoadImages(series: List<SeriesStream>) {
        lifecycleScope.launch(Dispatchers.IO) {
            series.take(30).forEach { s ->
                val url = s.icon ?: return@forEach
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    Glide.with(this@SeriesActivity)
                        .asBitmap().load(url)
                        .format(DecodeFormat.PREFER_ARGB_8888)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .priority(Priority.HIGH)
                        .preload(240, 360)
                }
            }
        }
    }

    private suspend fun searchTmdbLogoSeries(rawName: String): String? {
        val apiKey = TmdbConfig.API_KEY
        var cleanName = rawName
            .replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "")
            .replace(Regex("\\b\\d{4}\\b"), "").trim()
            .replace(Regex("\\s+"), " ")
        val sujeiras = listOf("FHD","HD","SD","4K","8K","H265","LEG","DUB","MKV","MP4","COMPLETE","S01","S02","E01")
        sujeiras.forEach { cleanName = cleanName.replace(it, "", ignoreCase = true) }
        cleanName = cleanName.trim().replace(Regex("\\s+"), " ")
        return try {
            val query = URLEncoder.encode(cleanName, "UTF-8")
            val searchJson = URL(
                "https://api.themoviedb.org/3/search/tv?api_key=$apiKey&query=$query&language=pt-BR&region=BR"
            ).readText()
            val results = JSONObject(searchJson).getJSONArray("results")
            if (results.length() == 0) return null
            var best = results.getJSONObject(0)
            for (j in 0 until results.length()) {
                val obj = results.getJSONObject(j)
                if (obj.optString("name","").equals(cleanName, ignoreCase = true)) { best = obj; break }
            }
            val id = best.getString("id")
            val imagesJson = URL(
                "https://api.themoviedb.org/3/tv/$id/images?api_key=$apiKey&include_image_language=pt,en,null"
            ).readText()
            val logos = JSONObject(imagesJson).getJSONArray("logos")
            if (logos.length() == 0) return null
            var path = ""
            for (i in 0 until logos.length()) {
                val lg = logos.getJSONObject(i)
                if (lg.optString("iso_639_1") == "pt") { path = lg.getString("file_path"); break }
            }
            if (path.isEmpty()) path = logos.getJSONObject(0).getString("file_path")
            "https://cdn.vltvplay.tech/t/p/w500$path"
        } catch (e: Exception) { null }
    }

    private fun salvarUltimaCategoria(categoria: LiveCategory) {
        ultimaCategoriaId   = categoria.id
        ultimaCategoriaNome = categoria.name
        getSharedPreferences("vltv_series_prefs", Context.MODE_PRIVATE).edit()
            .putString("ultima_cat_id", categoria.id)
            .putString("ultima_cat_nome", categoria.name)
            .apply()
    }

    /**
     * Busca categorias da REDE em background.
     * Salva no banco para a próxima abertura ser instantânea.
     * Só reaplica na tela se o adapter ainda não foi criado (banco estava vazio).
     */
    private fun carregarCategoriasRede() {
        XtreamApi.service.getSeriesCategories(username, password)
            .enqueue(object : Callback<ResponseBody> {
                override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                    if (!response.isSuccessful || response.body() == null) return
                    try {
                        val rawJson = response.body()!!.string()
                        val lista = mutableListOf<LiveCategory>()
                        val gson = Gson()
                        if (rawJson.trim().startsWith("[")) {
                            val type = object : TypeToken<List<LiveCategory>>() {}.type
                            lista.addAll(gson.fromJson(rawJson, type))
                        } else if (rawJson.trim().startsWith("{")) {
                            val obj = JSONObject(rawJson); val keys = obj.keys()
                            while (keys.hasNext()) {
                                lista.add(gson.fromJson(obj.getJSONObject(keys.next()).toString(), LiveCategory::class.java))
                            }
                        }

                        // Salva no banco em background
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val entities = lista.map {
                                    CategoryEntity(it.category_id, it.category_name, "series")
                                }
                                database.streamDao().deleteCategoriesByType("series")
                                database.streamDao().insertCategories(entities)
                            } catch (e: Exception) { e.printStackTrace() }
                        }

                        // ✅ Lista crua aqui — o filtro é aplicado dentro de
                        // aplicarCategorias(), centralizando a regra num único lugar.
                        val cats = mutableListOf<LiveCategory>()
                        cats.add(LiveCategory(category_id = "FAV_SERIES", category_name = "FAVORITOS"))
                        cats.addAll(lista)

                        // Só reaplica se o banco estava vazio (adapter ainda não criado)
                        if (categoryAdapter == null) {
                            aplicarCategorias(cats)
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {}
            })
    }

    private fun aplicarCategorias(categoriasOriginais: List<LiveCategory>) {
        if (isFinishing || isDestroyed) return

        // ✅ Filtro central de conteúdo adulto. Roda sempre, não importa se a
        // lista veio do banco Room (carregamento instantâneo) ou da rede.
        val categorias = filtrarCategoriasAdultas(categoriasOriginais)
        if (categorias.isEmpty()) return

        val catSalvaId = ultimaCategoriaId
        val indexInicial = if (catSalvaId != null) {
            val idx = categorias.indexOfFirst { it.id == catSalvaId }
            if (idx >= 0) idx else if (categorias.size > 1) 1 else 0
        } else {
            if (categorias.size > 1) 1 else 0
        }

        categoryAdapter = SeriesCategoryAdapter(categorias, indexInicial) { categoria ->
            salvarUltimaCategoria(categoria)
            if (categoria.id == "FAV_SERIES") carregarSeriesFavoritas()
            else carregarSeries(categoria)
        }
        rvCategories.adapter = categoryAdapter

        val categoriaAlvo = categorias.getOrNull(indexInicial)
            ?.takeIf { it.id != "FAV_SERIES" }
            ?: categorias.firstOrNull { it.id != "FAV_SERIES" }

        if (categoriaAlvo != null) {
            tvCategoryTitle.text = categoriaAlvo.name
            if (categoriaAlvo.id == categoriaAtualId) {
                atualizarEmBackground(categoriaAlvo)
            } else {
                carregarSeries(categoriaAlvo)
            }
        }
    }

    // ✅ CORRIGIDO (bug do "re-sync" toda vez que reabre a tela de Séries):
    // antes, esta função só evitava rebuscar na rede se seriesCache (memória
    // da Activity) já tivesse a categoria — e como uma Activity NOVA é
    // criada toda vez que você sai da tela de Séries e volta, esse cache
    // sempre estava vazio, então o app batia no servidor de novo em TODA
    // abertura, mesmo com os dados já salvos e corretos no Room/
    // ContentRepository. Mesma causa do bug corrigido no VodActivity.
    //
    // Agora, além do cache de memória, checamos categoriaEstaFresca(): se
    // essa categoria já foi sincronizada com o servidor há menos de
    // SYNC_STALE_MS (6h), a função nem chega a fazer a chamada de rede —
    // confia 100% no que já está salvo localmente.
    private fun atualizarEmBackground(categoria: LiveCategory) {
        if (seriesCache.containsKey(categoria.id)) return
        if (categoriaEstaFresca(categoria.id)) return
        XtreamApi.service.getSeries(username, password, categoryId = categoria.id)
            .enqueue(object : Callback<List<SeriesStream>> {
                override fun onResponse(call: Call<List<SeriesStream>>, response: Response<List<SeriesStream>>) {
                    if (!response.isSuccessful || response.body() == null) return
                    val series = response.body()!!
                    // ✅ Cache guarda a lista crua — filtro aplicado só no submit
                    seriesCache[categoria.id] = series
                    if (categoriaAtualId == categoria.id) {
                        seriesAdapter?.submitList(filtrarSeriesAdultas(series))
                    }
                    salvarNoBancoERepositorio(categoria.id, series)
                    marcarCategoriaSincronizada(categoria.id)
                }
                override fun onFailure(call: Call<List<SeriesStream>>, t: Throwable) {}
            })
    }

    private fun carregarSeries(categoria: LiveCategory) {
        tvCategoryTitle.text = categoria.name
        categoriaAtualId = categoria.id
        salvarUltimaCategoria(categoria)

        // 1. Cache de memória da API — instantâneo
        seriesCache[categoria.id]?.let {
            val filtrados = filtrarSeriesAdultas(it)
            seriesAdapter?.submitList(filtrados); preLoadImages(filtrados); return
        }

        // 2. ContentRepository — O(1), instantâneo (quando já está pronto)
        //
        // ✅ CORREÇÃO (tela aparecia vazia por alguns segundos TODA vez que
        // abria, mesma causa do bug corrigido no VodActivity): se o usuário
        // chegasse nesta tela antes do ContentRepository terminar de carregar
        // em background, getSeriesByCategory() retornava lista vazia mesmo
        // com séries salvas localmente, e caía direto no item 3 (rede), bem
        // mais lento. Agora espera o repositório terminar (leitura local do
        // Room, geralmente bem menos de 1 segundo, sem rede) antes de decidir
        // se precisa mesmo buscar da rede.
        if (!ContentRepository.pronto) {
            ContentRepository.aoFicarPronto {
                if (isFinishing || isDestroyed) return@aoFicarPronto
                if (categoriaAtualId == categoria.id) carregarSeries(categoria)
            }
            return
        }
        val emRepositorio = ContentRepository.getSeriesByCategory(categoria.id)
        if (emRepositorio.isNotEmpty()) {
            emRepositorio.take(30).forEach { s ->
                val cached = seriesCachePrefs.getString("logo_${s.name}", null)
                if (cached != null) logoMemoryCache[s.name] = cached
            }
            val items = emRepositorio.map { SeriesStream(it.series_id, it.name, it.cover, it.rating) }
            val itemsFiltrados = filtrarSeriesAdultas(items)
            seriesAdapter?.submitList(itemsFiltrados)
            preLoadImages(itemsFiltrados)
            // ✅ Só tenta atualizar em segundo plano se a categoria não
            // estiver "fresca" (ver categoriaEstaFresca) — evita o re-sync
            // repetido toda vez que essa categoria é reaberta.
            atualizarEmBackground(categoria)
            return
        }

        // 3. Sem dados locais — primeira instalação
        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getSeries(username, password, categoryId = categoria.id)
            .enqueue(object : Callback<List<SeriesStream>> {
                override fun onResponse(call: Call<List<SeriesStream>>, response: Response<List<SeriesStream>>) {
                    progressBar.visibility = View.GONE
                    if (!response.isSuccessful || response.body() == null) return
                    val series = response.body()!!
                    seriesCache[categoria.id] = series
                    if (categoriaAtualId == categoria.id) {
                        val filtrados = filtrarSeriesAdultas(series)
                        seriesAdapter?.submitList(filtrados)
                        preLoadImages(filtrados)
                    }
                    salvarNoBancoERepositorio(categoria.id, series)
                    marcarCategoriaSincronizada(categoria.id)
                }
                override fun onFailure(call: Call<List<SeriesStream>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                }
            })
    }

    private fun salvarNoBancoERepositorio(categoryId: String, series: List<SeriesStream>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val entities = series.map {
                    SeriesEntity(it.series_id, it.name, it.cover, it.rating,
                        categoryId, System.currentTimeMillis())
                }
                database.streamDao().insertSeriesStreams(entities)
                ContentRepository.atualizarCategoriaSeries(categoryId, entities)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun carregarSeriesFavoritas() {
        categoriaAtualId = "FAV_SERIES"
        tvCategoryTitle.text = "FAVORITOS"
        val favIds = getFavSeries(this)
        if (favIds.isEmpty()) { seriesAdapter?.submitList(emptyList()); return }
        val listaNoCache = seriesCache.values.flatten().distinctBy { it.id }.filter { favIds.contains(it.id) }
        if (listaNoCache.size >= favIds.size) {
            seriesAdapter?.submitList(filtrarSeriesAdultas(listaNoCache)); return
        }
        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getSeries(username, password, categoryId = "0")
            .enqueue(object : Callback<List<SeriesStream>> {
                override fun onResponse(call: Call<List<SeriesStream>>, response: Response<List<SeriesStream>>) {
                    progressBar.visibility = View.GONE
                    if (!response.isSuccessful || response.body() == null) return
                    val todas = response.body()!!
                    seriesCache["ALL_FOR_FAV"] = todas
                    val favs = todas.filter { favIds.contains(it.id) }
                    if (categoriaAtualId == "FAV_SERIES") {
                        val favsFiltradas = filtrarSeriesAdultas(favs)
                        seriesAdapter?.submitList(favsFiltradas)
                        preLoadImages(favsFiltradas)
                    }
                }
                override fun onFailure(call: Call<List<SeriesStream>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    if (categoriaAtualId == "FAV_SERIES") seriesAdapter?.submitList(filtrarSeriesAdultas(listaNoCache))
                }
            })
    }

    private fun abrirDetalhesSerie(serie: SeriesStream) {
        startActivity(Intent(this, SeriesDetailsActivity::class.java).apply {
            putExtra("series_id", serie.id)
            putExtra("name", serie.name)
            putExtra("icon", serie.icon)
            putExtra("rating", serie.rating ?: "0.0")
            putExtra("PROFILE_NAME", currentProfile)
            putExtra("PROFILE_ICON", currentProfileIcon)
        })
    }

    private fun getFavSeries(context: Context): MutableSet<Int> {
        val p = context.getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        return (p.getStringSet("${currentProfile}_fav_series", emptySet()) ?: emptySet())
            .mapNotNull { it.toIntOrNull() }.toMutableSet()
    }

    // =========================================================================
    // ADAPTER DE CATEGORIAS — chips estilo pill, com degradê vermelho quando
    // selecionado e contorno sutil quando não selecionado. Foco de TV usa um
    // contorno neon próprio (bg_chip_focused) e sempre restaura o estilo base
    // correto (selecionado ou não) ao perder o foco.
    // =========================================================================
    inner class SeriesCategoryAdapter(
        private val list: List<LiveCategory>,
        private var selectedPos: Int = 0,
        private val onClick: (LiveCategory) -> Unit
    ) : RecyclerView.Adapter<SeriesCategoryAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            val chip = holder.tvName
            chip.text = item.name
            val isSel = selectedPos == position

            fun aplicarEstiloBase() {
                if (isSel) {
                    chip.setBackgroundResource(R.drawable.bg_chip_selected)
                    chip.setTextColor(Color.WHITE)
                } else {
                    chip.setBackgroundResource(R.drawable.bg_chip_unselected)
                    chip.setTextColor(chip.context.getColor(R.color.gray_text))
                }
            }
            aplicarEstiloBase()

            val isTV = this@SeriesActivity.isTelevisionDevice()
            holder.itemView.isFocusable = isTV
            holder.itemView.isClickable = true
            if (isTV) {
                holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        chip.setTextColor(Color.WHITE)
                        chip.setBackgroundResource(R.drawable.bg_chip_focused)
                        view.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start()
                    } else {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                        aplicarEstiloBase()
                    }
                }
            }
            holder.itemView.setOnClickListener {
                val oldPos = selectedPos
                selectedPos = holder.adapterPosition
                notifyItemChanged(oldPos)
                notifyItemChanged(selectedPos)
                onClick(item)
            }
        }

        override fun getItemCount() = list.size
    }

    // =========================================================================
    // ADAPTER DE SÉRIES — DiffUtil, sem placeholder, sem círculo
    // =========================================================================
    inner class SeriesAdapter(
        private val onClick: (SeriesStream) -> Unit
    ) : RecyclerView.Adapter<SeriesAdapter.VH>() {

        private val items = mutableListOf<SeriesStream>()

        // ✅ NOVO: toda lista enviada pro adapter é ordenada da série mais
        // recente (ano maior) pra mais antiga, e o RecyclerView é reposicionado
        // no topo — corrige tanto a ordem por ano quanto o bug de abrir a tela
        // no meio/final da lista.
        fun submitList(newList: List<SeriesStream>) {
            val listaOrdenada = newList.sortedByDescending { extrairAnoSerie(it.name) }
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = items.size
                override fun getNewListSize() = listaOrdenada.size
                override fun areItemsTheSame(o: Int, n: Int) = items[o].id == listaOrdenada[n].id
                override fun areContentsTheSame(o: Int, n: Int) =
                    items[o].name == listaOrdenada[n].name && items[o].icon == listaOrdenada[n].icon
            })
            items.clear()
            items.addAll(listaOrdenada)
            diff.dispatchUpdatesTo(this)
            rvSeries.scrollToPosition(0)
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView     = v.findViewById(R.id.tvName)
            val imgPoster: ImageView = v.findViewById(R.id.imgPoster)
            val imgLogo: ImageView   = v.findViewById(R.id.imgLogo)
            var job: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_vod, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.job?.cancel()
            val item = items[position]

            holder.tvName.text = item.name
            holder.tvName.visibility = View.VISIBLE
            holder.imgLogo.setImageDrawable(null)
            holder.imgLogo.visibility = View.INVISIBLE
            holder.itemView.findViewById<View?>(R.id.imgDownload)?.visibility = View.GONE

            Glide.with(holder.itemView.context)
                .load(item.icon)
                .format(DecodeFormat.PREFER_ARGB_8888)
                .override(240, 360)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .priority(Priority.HIGH)
                .centerCrop()
                .into(holder.imgPoster)

            val memCached = logoMemoryCache[item.name]
            if (memCached != null) {
                holder.tvName.visibility = View.GONE
                holder.imgLogo.visibility = View.VISIBLE
                Glide.with(holder.itemView.context).load(memCached)
                    .diskCacheStrategy(DiskCacheStrategy.ALL).dontAnimate().into(holder.imgLogo)
            } else {
                val diskCached = seriesCachePrefs.getString("logo_${item.name}", null)
                if (diskCached != null) {
                    logoMemoryCache[item.name] = diskCached
                    holder.tvName.visibility = View.GONE
                    holder.imgLogo.visibility = View.VISIBLE
                    Glide.with(holder.itemView.context).load(diskCached)
                        .diskCacheStrategy(DiskCacheStrategy.ALL).dontAnimate().into(holder.imgLogo)
                } else {
                    // ✅ Corrigido: lifecycleScope em vez de CoroutineScope(Dispatchers.IO)
                    // solta. Isso cancela automaticamente a busca de logo se a Activity
                    // for destruída, evitando o crash "destroyed activity" no Glide.with().
                    holder.job = lifecycleScope.launch(Dispatchers.IO) {
                        val url = searchTmdbLogoSeries(item.name)
                        if (url != null) {
                            logoMemoryCache[item.name] = url
                            seriesCachePrefs.edit().putString("logo_${item.name}", url).apply()
                            withContext(Dispatchers.Main) {
                                // ✅ Guard extra: nunca chama Glide se a Activity já
                                // estiver finalizando/destruída (ex: usuário saiu da tela
                                // enquanto a busca TMDB ainda estava em andamento).
                                if (isFinishing || isDestroyed) return@withContext
                                if (holder.adapterPosition == position) {
                                    holder.tvName.visibility = View.GONE
                                    holder.imgLogo.visibility = View.VISIBLE
                                    Glide.with(holder.itemView.context).load(url)
                                        .override(200, 110)
                                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                                        .dontAnimate().into(holder.imgLogo)
                                }
                            }
                        }
                    }
                }
            }

            val isTV = holder.itemView.context.isTelevisionDevice()
            holder.itemView.isFocusable = isTV
            holder.itemView.isClickable = true
            if (isTV) {
                holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        holder.tvName.setTextColor(Color.YELLOW)
                        view.animate().scaleX(1.10f).scaleY(1.10f).setDuration(160).start()
                        view.elevation = 20f
                        view.setBackgroundResource(R.drawable.bg_focus_neon)
                    } else {
                        holder.tvName.setTextColor(Color.WHITE)
                        view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                        view.elevation = 4f
                        view.setBackgroundResource(0)
                    }
                }
            }
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
