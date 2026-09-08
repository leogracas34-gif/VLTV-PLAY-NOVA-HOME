package com.vltv.play

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
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
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.CategoryEntity
import com.vltv.play.data.VodEntity
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import okhttp3.ResponseBody
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class VodActivity : AppCompatActivity() {

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvMovies: RecyclerView
    private lateinit var progressBar: View
    private lateinit var tvCategoryTitle: TextView
    private var username = ""
    private var password = ""
    private lateinit var prefs: SharedPreferences
    private lateinit var gridCachePrefs: SharedPreferences

    private lateinit var syncPrefs: SharedPreferences
    private val SYNC_STALE_MS = 6 * 60 * 60 * 1000L // 6 horas

    private val moviesCache = mutableMapOf<String, List<VodStream>>()
    private var categoryAdapter: VodCategoryAdapter? = null

    private var moviesAdapter: VodAdapter? = null

    private val logoMemoryCache = mutableMapOf<String, String>()
    private var ultimaCategoriaId: String? = null
    private var ultimaCategoriaNome: String? = null

    private var categoriaAtualId: String? = null

    private var currentProfile: String = "Padrao"
    private var currentProfileIcon: String? = null
    private var bottomNavigation: BottomNavigationView? = null

    private val database by lazy { AppDatabase.getDatabase(this) }

    private fun filtrarFilmesAdultos(lista: List<VodStream>): List<VodStream> {
        return if (ParentalControlManager.isEnabled(this))
            lista.filterNot { ParentalControlManager.isAdultName(it.name) || ParentalControlManager.isAdultName(it.title) }
        else lista
    }

    private fun filtrarCategoriasAdultas(lista: List<LiveCategory>): List<LiveCategory> {
        return if (ParentalControlManager.isEnabled(this))
            lista.filterNot { ParentalControlManager.isAdultName(it.name) }
        else lista
    }

    private fun extrairAnoFilme(nome: String?): Int {
        if (nome.isNullOrEmpty()) return 0
        return Regex("\\b(19|20)\\d{2}\\b").find(nome)?.value?.toIntOrNull() ?: 0
    }

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
        rvMovies        = findViewById(R.id.rvChannels)
        progressBar     = findViewById(R.id.progressBar)
        tvCategoryTitle = findViewById(R.id.tvCategoryTitle)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        gridCachePrefs  = getSharedPreferences("vltv_grid_cache", Context.MODE_PRIVATE)
        syncPrefs       = getSharedPreferences("vltv_vod_sync", Context.MODE_PRIVATE)

        setupBottomNavigation()
        BottomNavProfileHelper.aplicarPerfilNoRodape(this, bottomNavigation, currentProfile, currentProfileIcon)

        findViewById<View>(R.id.etSearchContent)?.apply {
            isFocusableInTouchMode = false
            setOnClickListener {
                startActivity(Intent(this@VodActivity, SearchActivity::class.java).apply {
                    putExtra("initial_query", "")
                    putExtra("PROFILE_NAME", currentProfile)
                    putExtra("tipo_pesquisa", "filmes")
                })
            }
        }

        prefs    = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        username = prefs.getString("username", "") ?: ""
        password = prefs.getString("password", "") ?: ""

        if (this.isTelevisionDevice()) {
            rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
            rvMovies.layoutManager     = GridLayoutManager(this, 5)
            bottomNavigation?.visibility = View.GONE
        } else {
            rvCategories.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            rvMovies.layoutManager     = GridLayoutManager(this, 3)
        }

        rvCategories.setHasFixedSize(true)
        rvCategories.setItemViewCacheSize(50)
        rvCategories.overScrollMode = View.OVER_SCROLL_NEVER

        if (this.isTelevisionDevice()) {
            rvCategories.isFocusable = true
            rvCategories.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            rvMovies.isFocusable = true
            rvMovies.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        } else {
            rvCategories.isFocusable = false
            rvCategories.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            rvMovies.isFocusable = false
            rvMovies.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        rvMovies.setHasFixedSize(true)
        rvMovies.setItemViewCacheSize(100)

        moviesAdapter = VodAdapter(
            onItemClick     = { abrirDetalhes(it) },
            onDownloadClick = { mostrarMenuDownload(it) }
        )
        rvMovies.adapter = moviesAdapter

        val catPrefs = getSharedPreferences("vltv_vod_prefs", Context.MODE_PRIVATE)
        ultimaCategoriaId   = catPrefs.getString("ultima_cat_id", null)
        ultimaCategoriaNome = catPrefs.getString("ultima_cat_nome", null)

        val catId = ultimaCategoriaId
        if (catId != null) {
            val filmesEmMemoria = ContentRepository.getVodsByCategory(catId)
            if (filmesEmMemoria.isNotEmpty()) {
                categoriaAtualId = catId
                if (ultimaCategoriaNome != null) tvCategoryTitle.text = ultimaCategoriaNome
                filmesEmMemoria.take(30).forEach { vod ->
                    val nomeChave = vod.name.ifEmpty { vod.title ?: "" }
                    val cached = gridCachePrefs.getString("logo_$nomeChave", null)
                    if (cached != null) logoMemoryCache[nomeChave] = cached
                }
                val items = filmesEmMemoria.map {
                    VodStream(it.stream_id, it.name, it.title, it.stream_icon, it.container_extension, it.rating)
                }
                val itemsFiltrados = filtrarFilmesAdultos(items)
                moviesAdapter?.submitList(itemsFiltrados)
                preLoadImages(itemsFiltrados)
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val categoriasSalvas = database.streamDao().getCategoriesByType("vod")
                if (categoriasSalvas.isNotEmpty()) {
                    val cats = mutableListOf<LiveCategory>()
                    cats.add(LiveCategory(category_id = "FAV", category_name = "FAVORITOS"))
                    cats.addAll(categoriasSalvas.map {
                        LiveCategory(category_id = it.category_id, category_name = it.category_name)
                    })
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) aplicarCategorias(cats)
                    }
                }
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
                R.id.nav_home      -> { finish(); true }
                R.id.nav_search    -> {
                    startActivity(Intent(this, SearchActivity::class.java).apply {
                        putExtra("PROFILE_NAME", currentProfile)
                    }); true
                }
                R.id.nav_novidades -> {
                    startActivity(Intent(this, NovidadesActivity::class.java).apply {
                        putExtra("PROFILE_NAME", currentProfile)
                    }); true
                }
                R.id.nav_profile   -> {
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

    private fun preLoadImages(filmes: List<VodStream>) {
        lifecycleScope.launch(Dispatchers.IO) {
            filmes.take(30).forEach { vod ->
                val url = vod.icon ?: return@forEach
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    Glide.with(this@VodActivity)
                        .asBitmap().load(url)
                        .format(DecodeFormat.PREFER_ARGB_8888)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .priority(Priority.HIGH)
                        .preload(240, 360)
                }
            }
        }
    }

    private val REGEX_TARJAS_LOGO = Regex(
        "(?i)\\b(4K|8K|FULL[\\s.-]?HD|HD|SD|720P|1080P|2160P|DUBLADO|LEGENDADO|LEG|DUB|DUAL|AUDIO|LATINO|" +
        "NACIONAL|PT[-.]?BR|PTBR|WEB[-.]?DL|WEBRIP|BLU-?RAY|REMUX|MKV|MP4|AVI|REPACK|H\\.?264|H\\.?265|" +
        "HEVC|X264|X265|WEB|HDR|UHD|FHD|CAM|HDCAM|TS|TC|R5|SCREENER|CINEMA|LAN[ÇC]AMENTO|EXCLUSIVO|" +
        "COMPLETO|COMPLETE)\\b"
    )

    private fun limparNomeParaBuscaLogo(rawName: String, yearRegex: Regex): String {
        return rawName
            .replace(Regex("[\\(\\[\\{].*?[\\)\\]\\}]"), "")
            .replace(yearRegex, "")
            .replace(REGEX_TARJAS_LOGO, "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private suspend fun searchTmdbLogoVod(rawName: String): String? {
        val apiKey = TmdbConfig.API_KEY
        val yearRegex = Regex("\\b(19|20)\\d{2}\\b")
        val year = yearRegex.find(rawName)?.value
        val cleanName = limparNomeParaBuscaLogo(rawName, yearRegex)
        return try {
            var url = "https://api.themoviedb.org/3/search/movie?api_key=$apiKey" +
                    "&query=${URLEncoder.encode(cleanName, "UTF-8")}&language=pt-BR&region=BR&include_adult=false"
            if (year != null) url += "&year=$year"
            val results = JSONObject(URL(url).readText()).getJSONArray("results")
            if (results.length() == 0) return null
            val id = results.getJSONObject(0).getString("id")
            val logos = JSONObject(
                URL("https://api.themoviedb.org/3/movie/$id/images?api_key=$apiKey&include_image_language=pt,en,null")
                    .readText()
            ).getJSONArray("logos")
            if (logos.length() == 0) return null
            var path: String? = null
            for (i in 0 until logos.length()) {
                if (logos.getJSONObject(i).optString("iso_639_1") == "pt") {
                    path = logos.getJSONObject(i).getString("file_path"); break
                }
            }
            if (path == null) path = logos.getJSONObject(0).getString("file_path")
            "https://cdn.vltvplay.tech/t/p/w500$path"
        } catch (e: Exception) { null }
    }

    private fun carregarCategoriasRede() {
        XtreamApi.service.getVodCategories(username, password)
            .enqueue(object : retrofit2.Callback<ResponseBody> {
                override fun onResponse(
                    call: retrofit2.Call<ResponseBody>,
                    response: retrofit2.Response<ResponseBody>
                ) {
                    if (!response.isSuccessful || response.body() == null) return
                    try {
                        val rawJson = response.body()!!.string()
                        val lista = mutableListOf<LiveCategory>()
                        val gson = Gson()
                        if (rawJson.trim().startsWith("[")) {
                            val type = object : TypeToken<List<LiveCategory>>() {}.type
                            lista.addAll(gson.fromJson(rawJson, type))
                        } else if (rawJson.trim().startsWith("{")) {
                            val obj = JSONObject(rawJson)
                            val keys = obj.keys()
                            while (keys.hasNext()) {
                                lista.add(gson.fromJson(obj.getJSONObject(keys.next()).toString(), LiveCategory::class.java))
                            }
                        }

                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val entities = lista.map {
                                    CategoryEntity(it.category_id, it.category_name, "vod")
                                }
                                database.streamDao().deleteCategoriesByType("vod")
                                database.streamDao().insertCategories(entities)
                            } catch (e: Exception) { e.printStackTrace() }
                        }

                        val cats = mutableListOf<LiveCategory>()
                        cats.add(LiveCategory(category_id = "FAV", category_name = "FAVORITOS"))
                        cats.addAll(lista)

                        if (categoryAdapter == null) {
                            aplicarCategorias(cats)
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
                override fun onFailure(call: retrofit2.Call<ResponseBody>, t: Throwable) {}
            })
    }

    private fun aplicarCategorias(categoriasOriginais: List<LiveCategory>) {
        if (isFinishing || isDestroyed) return

        val categorias = filtrarCategoriasAdultas(categoriasOriginais)

        val catSalvaId = ultimaCategoriaId
        val indexInicial = if (catSalvaId != null) {
            val idx = categorias.indexOfFirst { it.id == catSalvaId }
            if (idx >= 0) idx else if (categorias.size > 1) 1 else 0
        } else {
            if (categorias.size > 1) 1 else 0
        }

        categoryAdapter = VodCategoryAdapter(categorias, indexInicial) { categoria ->
            salvarUltimaCategoria(categoria)
            if (categoria.id == "FAV") carregarFilmesFavoritos()
            else carregarFilmes(categoria)
        }
        rvCategories.adapter = categoryAdapter

        val categoriaAlvo = categorias.getOrNull(indexInicial)
            ?.takeIf { it.id != "FAV" }
            ?: categorias.firstOrNull { it.id != "FAV" }

        if (categoriaAlvo != null) {
            tvCategoryTitle.text = categoriaAlvo.name
            if (categoriaAlvo.id == categoriaAtualId) {
                atualizarEmBackground(categoriaAlvo)
            } else {
                carregarFilmes(categoriaAlvo)
            }
        }
    }

    private fun salvarUltimaCategoria(categoria: LiveCategory) {
        ultimaCategoriaId   = categoria.id
        ultimaCategoriaNome = categoria.name
        getSharedPreferences("vltv_vod_prefs", Context.MODE_PRIVATE).edit()
            .putString("ultima_cat_id", categoria.id)
            .putString("ultima_cat_nome", categoria.name)
            .apply()
    }

    private fun atualizarEmBackground(categoria: LiveCategory) {
        if (moviesCache.containsKey(categoria.id)) return
        if (categoriaEstaFresca(categoria.id)) return
        XtreamApi.service.getVodStreams(username, password, categoryId = categoria.id)
            .enqueue(object : retrofit2.Callback<List<VodStream>> {
                override fun onResponse(
                    call: retrofit2.Call<List<VodStream>>,
                    response: retrofit2.Response<List<VodStream>>
                ) {
                    if (!response.isSuccessful || response.body() == null) return
                    val filmes = response.body()!!
                    moviesCache[categoria.id] = filmes
                    if (categoriaAtualId == categoria.id) {
                        moviesAdapter?.submitList(filtrarFilmesAdultos(filmes))
                    }
                    salvarNoBancoERepositorio(categoria.id, filmes)
                    marcarCategoriaSincronizada(categoria.id)
                }
                override fun onFailure(call: retrofit2.Call<List<VodStream>>, t: Throwable) {}
            })
    }

    private fun carregarFilmes(categoria: LiveCategory) {
        tvCategoryTitle.text = categoria.name
        categoriaAtualId = categoria.id
        salvarUltimaCategoria(categoria)

        moviesCache[categoria.id]?.let {
            val filtrados = filtrarFilmesAdultos(it)
            moviesAdapter?.submitList(filtrados); preLoadImages(filtrados); return
        }

        if (!ContentRepository.pronto) {
            ContentRepository.aoFicarPronto {
                if (isFinishing || isDestroyed) return@aoFicarPronto
                if (categoriaAtualId == categoria.id) carregarFilmes(categoria)
            }
            return
        }
        val emRepositorio = ContentRepository.getVodsByCategory(categoria.id)
        if (emRepositorio.isNotEmpty()) {
            emRepositorio.take(30).forEach { vod ->
                val nomeChave = vod.name.ifEmpty { vod.title ?: "" }
                val cached = gridCachePrefs.getString("logo_$nomeChave", null)
                if (cached != null) logoMemoryCache[nomeChave] = cached
            }
            val items = emRepositorio.map {
                VodStream(it.stream_id, it.name, it.title, it.stream_icon, it.container_extension, it.rating)
            }
            val itemsFiltrados = filtrarFilmesAdultos(items)
            moviesAdapter?.submitList(itemsFiltrados)
            preLoadImages(itemsFiltrados)
            atualizarEmBackground(categoria)
            return
        }

        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getVodStreams(username, password, categoryId = categoria.id)
            .enqueue(object : retrofit2.Callback<List<VodStream>> {
                override fun onResponse(
                    call: retrofit2.Call<List<VodStream>>,
                    response: retrofit2.Response<List<VodStream>>
                ) {
                    progressBar.visibility = View.GONE
                    if (!response.isSuccessful || response.body() == null) return
                    val filmes = response.body()!!
                    moviesCache[categoria.id] = filmes
                    if (categoriaAtualId == categoria.id) {
                        val filtrados = filtrarFilmesAdultos(filmes)
                        moviesAdapter?.submitList(filtrados)
                        preLoadImages(filtrados)
                    }
                    salvarNoBancoERepositorio(categoria.id, filmes)
                    marcarCategoriaSincronizada(categoria.id)
                }
                override fun onFailure(call: retrofit2.Call<List<VodStream>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                }
            })
    }

    private fun salvarNoBancoERepositorio(categoryId: String, filmes: List<VodStream>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val entities = filmes.map {
                    VodEntity(it.stream_id, it.name, it.title, it.stream_icon,
                        it.container_extension, it.rating, categoryId, System.currentTimeMillis())
                }
                database.streamDao().insertVodStreams(entities)
                ContentRepository.atualizarCategoriaVod(categoryId, entities)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun carregarFilmesFavoritos() {
        categoriaAtualId = "FAV"
        tvCategoryTitle.text = "FAVORITOS"
        val favIds = getFavMovies(this)
        if (favIds.isEmpty()) { moviesAdapter?.submitList(emptyList()); return }
        val listaNoCache = moviesCache.values.flatten().distinctBy { it.id }.filter { favIds.contains(it.id) }
        if (listaNoCache.size >= favIds.size) {
            moviesAdapter?.submitList(filtrarFilmesAdultos(listaNoCache)); return
        }
        progressBar.visibility = View.VISIBLE
        XtreamApi.service.getVodStreams(username, password, categoryId = "0")
            .enqueue(object : retrofit2.Callback<List<VodStream>> {
                override fun onResponse(
                    call: retrofit2.Call<List<VodStream>>,
                    response: retrofit2.Response<List<VodStream>>
                ) {
                    progressBar.visibility = View.GONE
                    if (!response.isSuccessful || response.body() == null) return
                    val todos = response.body()!!
                    moviesCache["ALL_FOR_FAV"] = todos
                    val favs = todos.filter { favIds.contains(it.id) }
                    if (categoriaAtualId == "FAV") {
                        val favsFiltrados = filtrarFilmesAdultos(favs)
                        moviesAdapter?.submitList(favsFiltrados)
                        preLoadImages(favsFiltrados)
                    }
                }
                override fun onFailure(call: retrofit2.Call<List<VodStream>>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    if (categoriaAtualId == "FAV") moviesAdapter?.submitList(filtrarFilmesAdultos(listaNoCache))
                }
            })
    }

    private fun abrirDetalhes(filme: VodStream) {
        val nomeValido = when {
            !filme.name.isNullOrBlank() -> filme.name
            !filme.title.isNullOrBlank() -> filme.title
            else -> "Sem Título"
        }
        startActivity(Intent(this, DetailsActivity::class.java).apply {
            putExtra("stream_id", filme.id)
            putExtra("name", nomeValido)
            putExtra("title", nomeValido)
            putExtra("icon", filme.icon)
            putExtra("rating", filme.rating ?: "0.0")
            putExtra("PROFILE_NAME", currentProfile)
            putExtra("PROFILE_ICON", currentProfileIcon)
        })
    }

    private fun getFavMovies(context: Context): MutableSet<Int> {
        val p = context.getSharedPreferences("vltv_favoritos", Context.MODE_PRIVATE)
        return p.getStringSet("${currentProfile}_favoritos", emptySet())
            ?.mapNotNull { it.toIntOrNull() }?.toMutableSet() ?: mutableSetOf()
    }

    private fun mostrarMenuDownload(filme: VodStream) {
        val popup = PopupMenu(this, findViewById(android.R.id.content))
        menuInflater.inflate(R.menu.menu_download, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_download) {
                val nomeFilme = filme.name.ifEmpty { filme.title ?: "" }
                Toast.makeText(this, "Baixando: $nomeFilme", Toast.LENGTH_LONG).show()
            }
            true
        }
        popup.show()
    }

    inner class VodCategoryAdapter(
        private val list: List<LiveCategory>,
        initialSelectedPos: Int = 0,
        private val onClick: (LiveCategory) -> Unit
    ) : RecyclerView.Adapter<VodCategoryAdapter.VH>() {

        private var selectedPos = initialSelectedPos

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_category, p, false))

        override fun onBindViewHolder(h: VH, p: Int) {
            val item = list[p]
            val chip = h.tvName
            chip.text = item.name
            val isSel = selectedPos == p

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

            val isTV = this@VodActivity.isTelevisionDevice()
            h.itemView.isFocusable = isTV
            h.itemView.isClickable = true
            if (isTV) {
                h.itemView.setOnFocusChangeListener { view, hasFocus ->
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
            h.itemView.setOnClickListener {
                val oldPos = selectedPos
                selectedPos = h.adapterPosition
                notifyItemChanged(oldPos)
                notifyItemChanged(selectedPos)
                onClick(item)
            }
        }

        override fun getItemCount() = list.size
    }

    inner class VodAdapter(
        private val onItemClick: (VodStream) -> Unit,
        private val onDownloadClick: (VodStream) -> Unit
    ) : RecyclerView.Adapter<VodAdapter.VH>() {

        private val items = mutableListOf<VodStream>()

        fun submitList(newList: List<VodStream>) {
            val listaOrdenada = newList.sortedByDescending { extrairAnoFilme(it.name.ifEmpty { it.title ?: "" }) }
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
            rvMovies.scrollToPosition(0)
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView     = v.findViewById(R.id.tvName)
            val imgPoster: ImageView = v.findViewById(R.id.imgPoster)
            val imgLogo: ImageView   = v.findViewById(R.id.imgLogo)
            var job: Job? = null
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_vod, p, false))

        override fun onBindViewHolder(h: VH, p: Int) {
            h.job?.cancel()
            val item = items[p]

            val nomeExibicao = when {
                !item.name.isNullOrBlank() -> item.name
                !item.title.isNullOrBlank() -> item.title
                else -> "Sem Título"
            }

            h.tvName.text = nomeExibicao
            h.tvName.visibility  = View.VISIBLE
            h.imgLogo.setImageDrawable(null)
            h.imgLogo.visibility = View.INVISIBLE

            Glide.with(h.itemView.context)
                .load(item.icon)
                .format(DecodeFormat.PREFER_ARGB_8888)
                .override(240, 360)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .priority(Priority.HIGH)
                .centerCrop()
                .into(h.imgPoster)

            val memCached = logoMemoryCache[nomeExibicao]
            if (memCached != null) {
                h.tvName.visibility  = View.GONE
                h.imgLogo.visibility = View.VISIBLE
                Glide.with(h.itemView.context).load(memCached)
                    .diskCacheStrategy(DiskCacheStrategy.ALL).dontAnimate().into(h.imgLogo)
            } else {
                val diskCached = gridCachePrefs.getString("logo_${nomeExibicao}", null)
                if (diskCached != null) {
                    logoMemoryCache[nomeExibicao] = diskCached
                    h.tvName.visibility  = View.GONE
                    h.imgLogo.visibility = View.VISIBLE
                    Glide.with(h.itemView.context).load(diskCached)
                        .diskCacheStrategy(DiskCacheStrategy.ALL).dontAnimate().into(h.imgLogo)
                }
            }

            h.itemView.isFocusable = this@VodActivity.isTelevisionDevice()
            h.itemView.isClickable = true
            h.itemView.setOnClickListener { onItemClick(item) }

            if (this@VodActivity.isTelevisionDevice()) {
                h.itemView.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        v.animate().scaleX(1.08f).scaleY(1.08f).translationZ(16f).setDuration(180).start()
                        v.findViewById<View>(R.id.viewFocusBorder)?.visibility = View.VISIBLE
                    } else {
                        v.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(180).start()
                        v.findViewById<View>(R.id.viewFocusBorder)?.visibility = View.INVISIBLE
                    }
                }
            }
        }

        override fun getItemCount() = items.size
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
