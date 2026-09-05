package com.vltv.play

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.SeriesEntity
import com.vltv.play.data.VodEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class NovidadesActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NovidadesActivity"
        private const val MAX_TENTATIVAS = 4
        private const val DELAY_RETRY_MS = 1500L
        private const val CACHE_PREFS_NAME = "vltv_novidades_cache"

        // ── DIAGNÓSTICO TEMPORÁRIO ────────────────────────────────────────────
        // Como o build é feito só via GitHub Actions e não há acesso ao Logcat,
        // essa flag liga um Toast/Dialog na tela mostrando exatamente por que
        // uma aba falhou ou veio vazia. Deixado ligado a pedido do Leandro,
        // caso apareça algum outro problema além do já corrigido (Em Breve).
        private const val MODO_DIAGNOSTICO_VISUAL = true
    }

    private lateinit var tabEmBreve: TextView
    private lateinit var tabTodoMundo: TextView
    private lateinit var tabTopSeries: TextView
    private lateinit var tabTopFilmes: TextView
    private lateinit var recyclerNovidades: RecyclerView
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var adapter: NovidadesAdapter

    private val listaEmBreve   = mutableListOf<NovidadeItem>()
    private val listaTodoMundo = mutableListOf<NovidadeItem>()
    private val listaTopSeries = mutableListOf<NovidadeItem>()
    private val listaTopFilmes = mutableListOf<NovidadeItem>()

    private val apiKey = "9b73f5dd15b8165b1b57419be2f29128"

    // ── OkHttpClient compartilhado com timeouts curtos ────────────────────────
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS))
        .build()

    private var currentProfile = "Padrao"
    private var currentProfileIcon: String? = null
    private val database by lazy { AppDatabase.getDatabase(this) }

    // ── Cache local: permite exibir a tela instantaneamente, sem esperar
    // rede, usando o último resultado bem-sucedido salvo no dispositivo ────
    private val cachePrefs by lazy { getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE) }

    // ── Abas carregadas: evita refetch ao trocar de aba ──────────────────────
    private val abasCarregadas = mutableSetOf<String>()

    // Guarda qual aba está visível agora, pra decidir se o resultado que
    // acabou de chegar deve ser empurrado pro RecyclerView na hora.
    private lateinit var abaAtiva: TextView

    // ── Mapas de banco carregados UMA VEZ, passados ao adapter ───────────────
    private var vodsMap: Map<String, VodEntity> = emptyMap()
    private var seriesMap: Map<String, SeriesEntity> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_novidades)

        val vltvPrefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        currentProfile = intent.getStringExtra("PROFILE_NAME")
            ?: vltvPrefs.getString("last_profile_name", null)
            ?: "Padrao"
        currentProfileIcon = intent.getStringExtra("PROFILE_ICON")
            ?.takeIf { it.isNotEmpty() }
            ?: vltvPrefs.getString("last_profile_icon", null)?.takeIf { it.isNotEmpty() }

        tabEmBreve        = findViewById(R.id.tabEmBreve)
        tabTodoMundo      = findViewById(R.id.tabBombando)
        tabTopSeries      = findViewById(R.id.tabTopSeries)
        tabTopFilmes      = findViewById(R.id.tabTopFilmes)
        recyclerNovidades = findViewById(R.id.recyclerNovidades)
        bottomNavigation  = findViewById(R.id.bottomNavigation)

        adapter = NovidadesAdapter(emptyList(), currentProfile, database, emptyMap(), emptyMap())
        recyclerNovidades.layoutManager = LinearLayoutManager(this)

        // ── Otimizações do RecyclerView ───────────────────────────────────────
        recyclerNovidades.setHasFixedSize(true)
        recyclerNovidades.setItemViewCacheSize(6)
        recyclerNovidades.recycledViewPool.setMaxRecycledViews(0, 8)
        recyclerNovidades.adapter = adapter

        configurarAbas()
        configurarRodape()
        BottomNavProfileHelper.aplicarPerfilNoRodape(this, bottomNavigation, currentProfile, currentProfileIcon)

        // ── Carregamento instantâneo: se já existe um resultado salvo de uma
        // sessão anterior, ele é exibido na hora — a rede só entra depois,
        // em segundo plano, pra atualizar o conteúdo.
        carregarListasDoCache()

        CoroutineScope(Dispatchers.Main).launch {
            val bancoDeferido = async(Dispatchers.IO) {
                val todasVods   = database.streamDao().getAllVods()
                val todasSeries = database.streamDao().getAllSeries()
                Pair(
                    todasVods.associateBy   { normalizarNomeBanco(it.name) },
                    todasSeries.associateBy { normalizarNomeBanco(it.name) }
                )
            }

            carregarTudo()

            val (vMap, sMap) = bancoDeferido.await()
            vodsMap   = vMap
            seriesMap = sMap
            adapter.atualizarMapas(vMap, sMap)
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

    private fun configurarRodape() {
        bottomNavigation.selectedItemId = R.id.nav_novidades
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { finish(); true }
                R.id.nav_search -> {
                    startActivity(Intent(this, SearchActivity::class.java).apply {
                        putExtra("PROFILE_NAME", currentProfile)
                    })
                    finish(); true
                }
                R.id.nav_novidades -> true
                R.id.nav_profile -> {
                    startActivity(Intent(this, SettingsActivity::class.java).apply {
                        putExtra("PROFILE_NAME", currentProfile)
                    })
                    finish(); true
                }
                else -> false
            }
        }
    }

    private fun configurarAbas() {
        ativarAba(tabEmBreve)

        tabEmBreve.setOnClickListener {
            ativarAba(tabEmBreve)
            adapter.atualizarLista(listaEmBreve)
            recyclerNovidades.scrollToPosition(0)
        }
        tabTodoMundo.setOnClickListener {
            ativarAba(tabTodoMundo)
            adapter.atualizarLista(listaTodoMundo)
            recyclerNovidades.scrollToPosition(0)
        }
        tabTopSeries.setOnClickListener {
            ativarAba(tabTopSeries)
            adapter.atualizarLista(listaTopSeries)
            recyclerNovidades.scrollToPosition(0)
        }
        tabTopFilmes.setOnClickListener {
            ativarAba(tabTopFilmes)
            adapter.atualizarLista(listaTopFilmes)
            recyclerNovidades.scrollToPosition(0)
        }
    }

    private fun ativarAba(aba: TextView) {
        abaAtiva = aba
        listOf(tabEmBreve, tabTodoMundo, tabTopSeries, tabTopFilmes).forEach {
            if (it == aba) {
                it.setBackgroundResource(R.drawable.bg_aba_selecionada)
                it.setTextColor(Color.BLACK)
            } else {
                it.setBackgroundResource(R.drawable.bg_aba_inativa)
                it.setTextColor(Color.WHITE)
            }
        }
    }

    // ── Diagnóstico visual: mostra um AlertDialog com o motivo exato (Toast
    // estava sendo cortado em 2 linhas pela skin do Android). Só existe pra
    // dar visibilidade sem precisar de Logcat — ver MODO_DIAGNOSTICO_VISUAL.
    private fun diag(msg: String) {
        if (!MODO_DIAGNOSTICO_VISUAL) return
        Log.e(TAG, msg)
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            android.app.AlertDialog.Builder(this)
                .setTitle("Diagnóstico Novidades")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .setCancelable(true)
                .show()
        }
    }

    // ── Carregamento instantâneo a partir do cache local ─────────────────────
    private fun carregarListasDoCache() {
        val cacheEmBreve   = carregarCache("em_breve")
        val cacheTodoMundo = carregarCache("todo_mundo")
        val cacheTopSeries = carregarCache("top_series")
        val cacheTopFilmes = carregarCache("top_filmes")

        if (cacheEmBreve.isNotEmpty())   { listaEmBreve.clear();   listaEmBreve.addAll(cacheEmBreve) }
        if (cacheTodoMundo.isNotEmpty()) { listaTodoMundo.clear(); listaTodoMundo.addAll(cacheTodoMundo) }
        if (cacheTopSeries.isNotEmpty()) { listaTopSeries.clear(); listaTopSeries.addAll(cacheTopSeries) }
        if (cacheTopFilmes.isNotEmpty()) { listaTopFilmes.clear(); listaTopFilmes.addAll(cacheTopFilmes) }

        if (::abaAtiva.isInitialized && abaAtiva == tabEmBreve && listaEmBreve.isNotEmpty()) {
            adapter.atualizarLista(listaEmBreve)
        }
    }

    private fun salvarCache(tag: String, lista: List<NovidadeItem>) {
        try {
            val array = JSONArray()
            lista.forEach { item ->
                val obj = JSONObject()
                obj.put("idTMDB", item.idTMDB)
                obj.put("titulo", item.titulo)
                obj.put("sinopse", item.sinopse)
                obj.put("imagemFundoUrl", item.imagemFundoUrl)
                obj.put("tagline", item.tagline)
                obj.put("isSerie", item.isSerie)
                obj.put("isEmBreve", item.isEmBreve)
                obj.put("isTop10", item.isTop10)
                obj.put("posicaoTop10", item.posicaoTop10)
                array.put(obj)
            }
            cachePrefs.edit().putString("cache_$tag", array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Erro salvando cache \"$tag\": ${e.message}")
        }
    }

    private fun carregarCache(tag: String): List<NovidadeItem> {
        return try {
            val json = cachePrefs.getString("cache_$tag", null) ?: return emptyList()
            val array = JSONArray(json)
            val resultado = mutableListOf<NovidadeItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                resultado.add(
                    NovidadeItem(
                        idTMDB         = obj.optInt("idTMDB"),
                        titulo         = obj.optString("titulo"),
                        sinopse        = obj.optString("sinopse"),
                        imagemFundoUrl = obj.optString("imagemFundoUrl"),
                        tagline        = obj.optString("tagline"),
                        isSerie        = obj.optBoolean("isSerie"),
                        isEmBreve      = obj.optBoolean("isEmBreve"),
                        isTop10        = obj.optBoolean("isTop10"),
                        posicaoTop10   = obj.optInt("posicaoTop10")
                    )
                )
            }
            resultado
        } catch (e: Exception) {
            Log.e(TAG, "Erro lendo cache \"$tag\": ${e.message}")
            emptyList()
        }
    }

    private fun carregarTudo() {
        // ── Em Breve — usa /discover em vez de /movie/upcoming.
        // O endpoint /movie/upcoming sem parâmetro "region" devolve filmes que já
        // foram lançados há semanas/meses (é um problema conhecido do TMDB — o
        // cálculo interno de "em breve" depende da região e fica inconsistente
        // sem ela). /discover/movie + /discover/tv com "primary_release_date.gte"
        // / "first_air_date.gte" força o TMDB a só devolver itens com estreia
        // igual ou depois de hoje. Também traz SÉRIES, não só filmes.
        carregarEmBreve()

        // ── Bombando — trending da semana ─────────────────────────────────────
        buscarTMDB(
            url          = "https://api.themoviedb.org/3/trending/all/week" +
                           "?api_key=$apiKey&language=pt-BR",
            destino      = listaTodoMundo,
            isTop10      = false,
            isEmBreve    = false,
            isSerie      = false,
            tagFixa      = "Bombando no Mundo",
            usarPoster   = false,
            limite       = 20,
            detectarTipo = true
        ) {
            salvarCache("todo_mundo", listaTodoMundo)
            if (abaAtiva == tabTodoMundo) adapter.atualizarLista(listaTodoMundo)
        }

        // ── Top 10 Séries ──────────────────────────────────────────────────────
        buscarTMDB(
            url          = "https://api.themoviedb.org/3/tv/popular" +
                           "?api_key=$apiKey&language=pt-BR&page=1",
            destino      = listaTopSeries,
            isTop10      = true,
            isEmBreve    = false,
            isSerie      = true,
            tagFixa      = "Top 10 Séries",
            usarPoster   = false,
            limite       = 10,
            detectarTipo = false
        ) {
            salvarCache("top_series", listaTopSeries)
            if (abaAtiva == tabTopSeries) adapter.atualizarLista(listaTopSeries)
        }

        // ── Top 10 Filmes ──────────────────────────────────────────────────────
        buscarTMDB(
            url          = "https://api.themoviedb.org/3/movie/popular" +
                           "?api_key=$apiKey&language=pt-BR&page=1",
            destino      = listaTopFilmes,
            isTop10      = true,
            isEmBreve    = false,
            isSerie      = false,
            tagFixa      = "Top 10 Filmes",
            usarPoster   = false,
            limite       = 10,
            detectarTipo = false
        ) {
            salvarCache("top_filmes", listaTopFilmes)
            if (abaAtiva == tabTopFilmes) adapter.atualizarLista(listaTopFilmes)
        }
    }

    // ── Busca combinada filmes + séries realmente futuros ─────────────
    // Faz duas chamadas em paralelo (/discover/movie e /discover/tv), cada uma
    // já filtrada por data de estreia >= hoje. Junta os dois resultados,
    // remove duplicados, ordena por data de estreia (mais próxima primeiro)
    // e corta em 20 itens. Cada chamada tem sua própria lógica de retry
    // (mesmo padrão de backoff usado no resto da tela).
    //
    // ✅ CORRIGIDO: as URLs usavam sort_by=primary_release_date.asc /
    // first_air_date.asc (ordenar pela estreia mais próxima primeiro). Isso
    // fazia o TMDB devolver, na página 1, um monte de título obscuro
    // (documentários, produções regionais pequenas) que têm data de estreia
    // cadastrada mas quase nunca têm poster_path preenchido — e como o
    // filtro abaixo descarta qualquer item sem pôster, a lista final vinha
    // vazia mesmo com a API respondendo "ok". Trocado para
    // sort_by=popularity.desc: continua só trazendo itens com estreia >=
    // hoje (o filtro de data é por parâmetro, não pela ordenação), mas
    // agora prioriza os lançamentos mais conhecidos — que quase sempre têm
    // pôster. A ordenação final por data mais próxima continua acontecendo
    // depois, no sortedBy abaixo, então a ordem de exibição não muda.
    private fun carregarEmBreve() {
        val hoje = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lock = Any()
        val resultados = mutableListOf<Pair<String, NovidadeItem>>() // (dataISO, item) — dataISO só serve pra ordenar
        var pendentes = 2
        var erroFilmes: String? = null
        var erroSeries: String? = null

        fun finalizarSeCompleto() {
            val completo: Boolean
            synchronized(lock) {
                pendentes--
                completo = pendentes == 0
            }
            if (!completo) return

            runOnUiThread {
                val ordenado = resultados
                    .distinctBy { (_, item) -> "${item.idTMDB}_${item.isSerie}" }
                    .sortedBy { it.first }
                    .map { it.second }
                    .take(20)

                listaEmBreve.clear()
                listaEmBreve.addAll(ordenado)
                salvarCache("em_breve", listaEmBreve)
                if (::abaAtiva.isInitialized && abaAtiva == tabEmBreve) {
                    adapter.atualizarLista(listaEmBreve)
                }

                if (ordenado.isEmpty()) {
                    diag(
                        "Em Breve: nenhum item futuro encontrado.\n" +
                        "Filmes: ${erroFilmes ?: "ok, mas 0 itens futuros"}\n" +
                        "Séries: ${erroSeries ?: "ok, mas 0 itens futuros"}"
                    )
                }
            }
        }

        fun buscarDiscover(url: String, isSerie: Boolean, tentativa: Int, onFim: (String?) -> Unit) {
            val request = Request.Builder().url(url).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (tentativa >= MAX_TENTATIVAS) {
                        onFim("falha de rede: ${e.message}")
                        return
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        buscarDiscover(url, isSerie, tentativa + 1, onFim)
                    }, DELAY_RETRY_MS * (tentativa + 1))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!response.isSuccessful) {
                            val codigo = response.code
                            val retryAfterSegundos = response.header("Retry-After")?.toLongOrNull()
                            response.close()
                            if (tentativa >= MAX_TENTATIVAS) {
                                onFim("HTTP $codigo")
                                return
                            }
                            val delay = retryAfterSegundos?.times(1000) ?: (DELAY_RETRY_MS * (tentativa + 1))
                            Handler(Looper.getMainLooper()).postDelayed({
                                buscarDiscover(url, isSerie, tentativa + 1, onFim)
                            }, delay)
                            return
                        }

                        val body    = response.body?.string()
                        val results = body?.let { JSONObject(it).optJSONArray("results") }
                        if (results == null) {
                            if (tentativa >= MAX_TENTATIVAS) {
                                onFim("resposta sem campo 'results'")
                                return
                            }
                            Handler(Looper.getMainLooper()).postDelayed({
                                buscarDiscover(url, isSerie, tentativa + 1, onFim)
                            }, DELAY_RETRY_MS * (tentativa + 1))
                            return
                        }

                        for (i in 0 until results.length()) {
                            val obj = results.getJSONObject(i)
                            val titulo = obj.optString("title", obj.optString("name", ""))
                            if (titulo.isEmpty()) continue

                            val dataLancamento = obj.optString(
                                "release_date", obj.optString("first_air_date", "")
                            )
                            // Filtro real: só entra quem realmente ainda não lançou.
                            if (dataLancamento.isEmpty() || dataLancamento <= hoje) continue

                            val posterPath = obj.optString("poster_path", "")
                            if (posterPath.isEmpty()) continue

                            val sinopse = obj.optString("overview", "Descrição indisponível.")

                            synchronized(lock) {
                                resultados.add(
                                    dataLancamento to NovidadeItem(
                                        idTMDB         = obj.optInt("id"),
                                        titulo         = titulo,
                                        sinopse        = sinopse,
                                        imagemFundoUrl = "https://cdn.vltvplay.tech/t/p/w780$posterPath",
                                        tagline        = formatarData(dataLancamento),
                                        isSerie        = isSerie,
                                        isEmBreve      = true,
                                        isTop10        = false,
                                        posicaoTop10   = 0
                                    )
                                )
                            }
                        }
                        onFim(null)
                    } catch (e: Exception) {
                        if (tentativa >= MAX_TENTATIVAS) {
                            onFim("erro processando resposta: ${e.message}")
                            return
                        }
                        Handler(Looper.getMainLooper()).postDelayed({
                            buscarDiscover(url, isSerie, tentativa + 1, onFim)
                        }, DELAY_RETRY_MS * (tentativa + 1))
                    }
                }
            })
        }

        // ✅ sort_by=popularity.desc (era primary_release_date.asc /
        // first_air_date.asc) — ver explicação no comentário acima da função.
        val urlFilmes = "https://api.themoviedb.org/3/discover/movie" +
                        "?api_key=$apiKey&language=pt-BR&region=BR" +
                        "&sort_by=popularity.desc" +
                        "&primary_release_date.gte=$hoje" +
                        "&include_adult=false&page=1"

        val urlSeries = "https://api.themoviedb.org/3/discover/tv" +
                        "?api_key=$apiKey&language=pt-BR" +
                        "&sort_by=popularity.desc" +
                        "&first_air_date.gte=$hoje" +
                        "&include_adult=false&page=1"

        buscarDiscover(urlFilmes, false, 0) { erro ->
            erroFilmes = erro
            finalizarSeCompleto()
        }
        buscarDiscover(urlSeries, true, 0) { erro ->
            erroSeries = erro
            finalizarSeCompleto()
        }
    }

    private fun buscarTMDB(
        url: String,
        destino: MutableList<NovidadeItem>,
        isTop10: Boolean,
        isEmBreve: Boolean,
        isSerie: Boolean,
        tagFixa: String,
        usarPoster: Boolean,
        limite: Int,
        detectarTipo: Boolean,
        tentativa: Int = 0,
        onSucesso: () -> Unit
    ) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Falha de rede ($tagFixa), tentativa $tentativa: ${e.message}")
                tentarNovamenteSePossivel(
                    url, destino, isTop10, isEmBreve, isSerie, tagFixa,
                    usarPoster, limite, detectarTipo, tentativa, onSucesso,
                    motivo = "falha de rede: ${e.message}"
                )
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        val retryAfterSegundos = response.header("Retry-After")?.toLongOrNull()
                        val codigo = response.code
                        Log.e(TAG, "TMDB respondeu $codigo pra \"$tagFixa\", tentativa $tentativa")
                        response.close()
                        tentarNovamenteSePossivel(
                            url, destino, isTop10, isEmBreve, isSerie, tagFixa,
                            usarPoster, limite, detectarTipo, tentativa, onSucesso,
                            motivo = "HTTP $codigo",
                            delayOverrideMs = retryAfterSegundos?.times(1000)
                        )
                        return
                    }

                    val body    = response.body?.string()
                    val results = body?.let { JSONObject(it).optJSONArray("results") }
                    if (results == null) {
                        Log.e(TAG, "TMDB sem \"results\" pra \"$tagFixa\": $body")
                        tentarNovamenteSePossivel(
                            url, destino, isTop10, isEmBreve, isSerie, tagFixa,
                            usarPoster, limite, detectarTipo, tentativa, onSucesso,
                            motivo = "resposta sem campo 'results'"
                        )
                        return
                    }

                    val totalRecebido = results.length()
                    val temp    = mutableListOf<NovidadeItem>()
                    var posicao = 1
                    val hoje = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

                    // ── DIAGNÓSTICO: contadores por motivo de descarte, pra
                    // saber exatamente qual "continue" abaixo está zerando a lista.
                    var descartadoSemTitulo = 0
                    var descartadoJaLancado = 0
                    var descartadoSemPoster = 0
                    var primeiroExemplo: String? = null

                    for (i in 0 until results.length()) {
                        if (temp.size >= limite) break
                        val obj = results.getJSONObject(i)

                        val tipoDetectado = if (detectarTipo)
                            obj.optString("media_type") == "tv"
                        else
                            isSerie

                        if (detectarTipo && obj.optString("media_type") == "person") continue

                        val titulo = obj.optString("title", obj.optString("name", ""))
                        if (titulo.isEmpty()) { descartadoSemTitulo++; continue }

                        val releaseDate = obj.optString("release_date",
                                              obj.optString("first_air_date", ""))

                        if (primeiroExemplo == null) {
                            primeiroExemplo = "$titulo (release_date=\"$releaseDate\", hoje=\"$hoje\")"
                        }

                        if (isEmBreve && releaseDate.isNotEmpty() && releaseDate <= hoje) {
                            descartadoJaLancado++; continue
                        }

                        val pathImagem = if (usarPoster)
                            obj.optString("poster_path", "")
                        else
                            obj.optString("backdrop_path", obj.optString("poster_path", ""))
                        if (pathImagem.isEmpty()) { descartadoSemPoster++; continue }

                        val sinopse  = obj.optString("overview", "Descrição indisponível.")
                        val tagFinal = if (isEmBreve && releaseDate.isNotEmpty())
                            formatarData(releaseDate) else tagFixa

                        temp.add(NovidadeItem(
                            idTMDB         = obj.optInt("id"),
                            titulo         = titulo,
                            sinopse        = sinopse,
                            imagemFundoUrl = "https://cdn.vltvplay.tech/t/p/w780$pathImagem",
                            tagline        = tagFinal,
                            isSerie        = tipoDetectado,
                            isEmBreve      = isEmBreve,
                            isTop10        = isTop10,
                            posicaoTop10   = posicao++
                        ))
                    }

                    runOnUiThread {
                        destino.clear()
                        destino.addAll(temp)
                        onSucesso()

                        // ── DIAGNÓSTICO: chegou resposta válida do TMDB (sem
                        // erro HTTP, sem exceção), mas a lista final ficou
                        // vazia. Isso NÃO gera retry (não é um erro) — por
                        // isso a tela "carrega" só que fica em branco, sem
                        // nenhum log de erro aparecer. Este Toast entrega
                        // exatamente esse caso.
                        if (temp.isEmpty()) {
                            if (totalRecebido == 0) {
                                diag("$tagFixa: TMDB devolveu 0 resultados")
                            } else {
                                diag(
                                    "$tagFixa: $totalRecebido itens. Descartados -> " +
                                    "sem título: $descartadoSemTitulo, já lançado: $descartadoJaLancado, " +
                                    "sem pôster: $descartadoSemPoster. Ex: $primeiroExemplo"
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro processando resposta ($tagFixa), tentativa $tentativa: ${e.message}")
                    tentarNovamenteSePossivel(
                        url, destino, isTop10, isEmBreve, isSerie, tagFixa,
                        usarPoster, limite, detectarTipo, tentativa, onSucesso,
                        motivo = "erro processando resposta: ${e.message}"
                    )
                }
            }
        })
    }

    private fun tentarNovamenteSePossivel(
        url: String,
        destino: MutableList<NovidadeItem>,
        isTop10: Boolean,
        isEmBreve: Boolean,
        isSerie: Boolean,
        tagFixa: String,
        usarPoster: Boolean,
        limite: Int,
        detectarTipo: Boolean,
        tentativa: Int,
        onSucesso: () -> Unit,
        motivo: String,
        delayOverrideMs: Long? = null
    ) {
        if (tentativa >= MAX_TENTATIVAS) {
            diag("$tagFixa: falhou após ${tentativa + 1} tentativas — $motivo")
            return
        }
        // Backoff progressivo: 1.5s, 3s, 4.5s, 6s... a menos que o próprio
        // TMDB tenha mandado um Retry-After explícito, que tem prioridade.
        val delay = delayOverrideMs ?: (DELAY_RETRY_MS * (tentativa + 1))
        Handler(Looper.getMainLooper()).postDelayed({
            buscarTMDB(
                url, destino, isTop10, isEmBreve, isSerie, tagFixa,
                usarPoster, limite, detectarTipo, tentativa + 1, onSucesso
            )
        }, delay)
    }

    private fun formatarData(dataIngles: String): String {
        return try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dataIngles)
            if (date != null)
                SimpleDateFormat("'Estreia' dd 'de' MMM", Locale("pt", "BR")).format(date)
            else
                "Estreia em breve"
        } catch (e: Exception) {
            "Estreia em breve"
        }
    }

    private fun normalizarNomeBanco(nome: String): String {
        var n = nome.lowercase()
        listOf("fhd", "hd", "sd", "4k", "8k", "h265", "leg", "dublado", "dub",
               "nacional", "legendado", "|", "-", "_", ".", "(", ")")
            .forEach { n = n.replace(it, " ") }
        return n.trim().replace(Regex("\\s+"), " ")
    }
}
