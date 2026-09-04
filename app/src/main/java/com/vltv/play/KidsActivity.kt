package com.vltv.play

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vltv.play.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.TimeUnit

class KidsActivity : AppCompatActivity() {

    private lateinit var rvHubChannels: RecyclerView
    private lateinit var rvRecentKids: RecyclerView
    private lateinit var rvMoviesKids: RecyclerView
    private lateinit var rvSeriesKids: RecyclerView
    private lateinit var tvTitleRecent: TextView
    private lateinit var tvSectionMovies: TextView
    private lateinit var tvSectionSeries: TextView
    private lateinit var tvSemResultados: TextView
    private lateinit var sectionHubWrapper: LinearLayout
    private lateinit var etSearchKids: EditText
    private lateinit var layoutSearchBar: LinearLayout
    private lateinit var layoutContinueHeader: LinearLayout
    private lateinit var prefs: SharedPreferences
    private var user = ""
    private var pass = ""
    // ✅ NOVO: referência de classe pro BottomNavigationView — precisa ser
    // acessível tanto em setupBottomNavigation() quanto no botão de voltar
    // do topo e no callback do botão/gesto de voltar do sistema, já que os
    // três agora disparam a MESMA confirmação de saída da Área Kids.
    private lateinit var bottomNav: BottomNavigationView

    private lateinit var btnVoiceKids: LinearLayout
    private var speechRecognizerKids: SpeechRecognizer? = null
    private var voiceDialogKids: android.app.Dialog? = null
    private val pulseAnimatorsKids = mutableListOf<ObjectAnimator>()

    private val micPermissionLauncherKids = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            iniciarBuscaPorVozKids()
        } else {
            Toast.makeText(this, "Permissão de microfone necessária para busca por voz", Toast.LENGTH_SHORT).show()
        }
    }

    // ✅ CORREÇÃO CRÍTICA DE DOWNLOAD: antes a KidsActivity nunca informava
    // qual perfil estava ativo pra DetailsActivity/SeriesDetailsActivity
    // (não tinha nenhum putExtra("PROFILE_NAME", ...) em nenhuma navegação
    // vinda daqui). Sem esse extra, DetailsActivity/SeriesDetailsActivity
    // caíam no fallback "Padrao" (ver `currentProfile = intent.getStringExtra
    // ("PROFILE_NAME") ?: "Padrao"`), e QUALQUER download iniciado a partir
    // do catálogo do Kids era gravado no banco com profile_name = "Padrao"
    // — nunca com o nome real do perfil Kids (ex: "Infantil"). Como
    // KidsDownloadsActivity filtra pelo nome real do perfil (lido de
    // last_profile_name), o download nunca aparecia na tela, mesmo tendo
    // sido baixado com sucesso.
    //
    // Esse "perfilAtivo" usa exatamente o mesmo SharedPreferences/chave que
    // KidsDownloadsActivity e KidsMovieDownloadActivity já usam — então
    // agora o nome gravado no download e o nome usado pra filtrar são
    // SEMPRE o mesmo, independente do PIN de troca de perfil estar ativo
    // ou não (o PIN nunca teve relação com esse bug).
    private val perfilAtivo: String
        get() = prefs.getString("last_profile_name", "") ?: ""

    // ✅ NOVO: mesma fonte (last_profile_icon) usada pelo
    // BottomNavProfileHelper pra mostrar o avatar do perfil Kids ativo no
    // rodapé, igual já acontece nas telas do perfil adulto.
    private val perfilIconAtivo: String?
        get() = prefs.getString("last_profile_icon", null)?.takeIf { it.isNotEmpty() }

    // ✅ NOVO: listas "mestras" com TUDO que já foi carregado do catálogo
    // Kids (filmes e séries), independente do que está sendo exibido na
    // tela no momento. É nelas que a busca interna filtra — assim a busca
    // NUNCA precisa abrir a SearchActivity geral (que não tem nenhum
    // filtro de conteúdo adulto).
    private val kidsMoviesAll = mutableListOf<VodStream>()
    private val kidsSeriesAll = mutableListOf<SeriesStream>()
    private var emBusca = false

    // Filtro duplo: palavras proibidas na busca + conteúdo adulto
    private val termosProibidosBusca = listOf(
        "adulto", "xxx", "sexo", "sexy", "porn", "18+", "erótico",
        "violência", "terror", "horror", "assassinato", "guerra",
        "pânico", "morte", "nude", "hentai", "strip"
    )

    // ✅ NOVO: filtro de verdade pro catálogo Kids — não confia só no nome
    // da categoria do provedor IPTV (que costuma vir bagunçado, misturando
    // filme adulto dentro de categoria chamada "Kids"/"Animação"). Antes de
    // qualquer filme/série entrar no catálogo infantil, verifica no TMDB se
    // o gênero real é Animação (16) ou Família (10751). Se não for, ou se
    // não encontrar o título no TMDB, BLOQUEIA por padrão — prefere deixar
    // de fora um título legítimo a arriscar mostrar algo impróprio.
    //
    // ✅ OTIMIZAÇÃO (era o motivo dos ~20s de demora): antes usava
    // HttpURLConnection puro, que só permite pouquíssimas conexões
    // simultâneas pro mesmo servidor — as consultas ficavam praticamente
    // enfileiradas uma atrás da outra. Agora usa um OkHttpClient dedicado
    // com até 20 requisições em paralelo pro TMDB, e principalmente: guarda
    // o resultado de cada título verificado num cache local (SharedPreferences).
    // Da segunda vez que o Kids abrir em diante, os títulos já vistos nem
    // precisam consultar o TMDB de novo — é praticamente instantâneo.
    private val TMDB_API_KEY_KIDS = "9b73f5dd15b8165b1b57419be2f29128"
    private val GENEROS_KIDS_PERMITIDOS = setOf(16, 10751) // Animação, Família

    private val tmdbKidsClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .dispatcher(Dispatcher().apply {
                maxRequests = 40
                maxRequestsPerHost = 20
            })
            .build()
    }

    private val genreCachePrefs by lazy {
        getSharedPreferences("vltv_kids_genre_cache", Context.MODE_PRIVATE)
    }

    private fun limparNomeParaBuscaTmdb(nome: String): String {
        return nome
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("(?i)\\b(4K|FULL HD|HD|SD|DUBLADO|LEGENDADO|DUAL|BLURAY|WEB-DL|HEVC|H264|H265|UHD|FHD|HDR)\\b"), "")
            .trim()
            .take(50)
    }

    private suspend fun ehGeneroKidsPermitido(titulo: String, isSeries: Boolean): Boolean {
        val chaveCache = "${if (isSeries) "tv" else "movie"}_${titulo.trim().lowercase()}"

        // ✅ Cache primeiro — se esse título já foi verificado antes (em
        // qualquer sessão anterior), usa o resultado salvo, sem rede.
        genreCachePrefs.getString(chaveCache, null)?.let { return it == "1" }

        val aprovado = try {
            val tipo = if (isSeries) "tv" else "movie"
            val nomeLimpo = limparNomeParaBuscaTmdb(titulo)
            if (nomeLimpo.isBlank()) {
                false
            } else {
                val query = java.net.URLEncoder.encode(nomeLimpo, "UTF-8")
                val url = "https://api.themoviedb.org/3/search/$tipo?api_key=$TMDB_API_KEY_KIDS&query=$query&language=pt-BR"
                val response = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(url).build()
                    tmdbKidsClient.newCall(request).execute().use { it.body?.string() ?: "" }
                }
                val results = JSONObject(response).optJSONArray("results")
                if (results == null || results.length() == 0) {
                    false
                } else {
                    val generos = results.getJSONObject(0).optJSONArray("genre_ids")
                    var achou = false
                    if (generos != null) {
                        for (i in 0 until generos.length()) {
                            if (GENEROS_KIDS_PERMITIDOS.contains(generos.getInt(i))) { achou = true; break }
                        }
                    }
                    achou
                }
            }
        } catch (e: Exception) { false }

        // Salva no cache pra próxima vez (mesmo quando bloqueado — assim
        // não fica reconsultando o TMDB pra sempre pra títulos negados).
        genreCachePrefs.edit().putString(chaveCache, if (aprovado) "1" else "0").apply()
        return aprovado
    }

    // ✅ Agora processa e exibe cada título de forma INDEPENDENTE — assim
    // que um filme/série é aprovado, ele já aparece na tela na hora, sem
    // esperar todos os outros da mesma categoria terminarem de verificar.
    // Isso reduz muito a demora percebida, principalmente porque os itens
    // que já estão em cache aparecem quase que imediatamente.
    private fun processarEExibirFilmes(lista: List<VodStream>) {
        lista.forEach { item ->
            lifecycleScope.launch(Dispatchers.IO) {
                val aprovado = ehGeneroKidsPermitido(item.name, false)
                if (!aprovado) return@launch
                withContext(Dispatchers.Main) {
                    if (kidsMoviesAll.none { it.id == item.id }) {
                        kidsMoviesAll.add(item)
                        if (!emBusca) {
                            rvMoviesKids.adapter = KidsVodAdapter(kidsMoviesAll) { filme ->
                                salvarNosRecentes(filme.id.toString(), "movie")
                                startActivity(Intent(this@KidsActivity, DetailsActivity::class.java).apply {
                                    putExtra("stream_id", filme.id)
                                    putExtra("stream_ext", filme.extension ?: "mp4")
                                    putExtra("name", filme.name)
                                    putExtra("icon", filme.icon)
                                    putExtra("rating", filme.rating ?: "0.0")
                                    // ✅ CORREÇÃO: sem isso o download feito
                                    // dentro dessa tela era salvo com o
                                    // perfil errado (ver comentário no topo
                                    // da classe).
                                    putExtra("PROFILE_NAME", perfilAtivo)
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    private fun processarEExibirSeries(lista: List<SeriesStream>) {
        lista.forEach { item ->
            lifecycleScope.launch(Dispatchers.IO) {
                val aprovada = ehGeneroKidsPermitido(item.name, true)
                if (!aprovada) return@launch
                withContext(Dispatchers.Main) {
                    if (kidsSeriesAll.none { it.id == item.id }) {
                        kidsSeriesAll.add(item)
                        if (!emBusca) {
                            rvSeriesKids.adapter = KidsSeriesAdapter(kidsSeriesAll) { serie ->
                                salvarNosRecentes(serie.id.toString(), "series")
                                startActivity(Intent(this@KidsActivity, SeriesDetailsActivity::class.java).apply {
                                    putExtra("series_id", serie.id)
                                    putExtra("name", serie.name)
                                    putExtra("icon", serie.icon)
                                    // ✅ CORREÇÃO: idem ao filme acima.
                                    putExtra("PROFILE_NAME", perfilAtivo)
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = false

        setContentView(R.layout.activity_kids)

        // ✅ NOVO: impede que o teclado empurre o layout inteiro pra cima ao
        // abrir a busca. Sem isso, a Activity usa o modo padrão (adjustResize),
        // que encolhe a janela quando o teclado aparece — como o rodapé
        // (Início/Buscar/Perfil) fica grudado na base do layout, ele "sobe"
        // junto com o teclado e fica flutuando no meio da tela, com um vão
        // preto entre o conteúdo e o rodapé. Com adjustNothing, o layout não
        // é redimensionado: o teclado só sobrepõe a parte de baixo da tela
        // (cobrindo o rodapé), e como o campo de busca fica no topo, ele
        // continua totalmente visível — exatamente "só o teclado sobe".
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        user = prefs.getString("username", "") ?: ""
        pass = prefs.getString("password", "") ?: ""

        tvTitleRecent        = findViewById(R.id.tvTitleRecent)
        tvSectionMovies      = findViewById(R.id.tvSectionMovies)
        tvSectionSeries      = findViewById(R.id.tvSectionSeries)
        tvSemResultados      = findViewById(R.id.tvSemResultados)
        sectionHubWrapper    = findViewById(R.id.sectionHubWrapper)
        rvHubChannels        = findViewById(R.id.rvHubChannels)
        rvRecentKids         = findViewById(R.id.rvRecentKids)
        rvMoviesKids         = findViewById(R.id.rvMoviesKids)
        rvSeriesKids         = findViewById(R.id.rvSeriesKids)
        etSearchKids         = findViewById(R.id.etSearchKids)
        layoutSearchBar      = findViewById(R.id.layoutSearchBar)
        layoutContinueHeader = findViewById(R.id.layoutContinueHeader)
        btnVoiceKids         = findViewById(R.id.btnVoiceKids)

        // ── Voltar (topo) ────────────────────────────────────────────────
        // ✅ AJUSTE: antes chamava finish() direto, saindo da Área Kids sem
        // nenhuma confirmação. Agora mostra o mesmo dialog premium usado
        // pelo ícone "Perfil" da barra inferior — qualquer forma de sair
        // passa pela mesma confirmação (e, se o PIN de troca de perfil
        // estiver ativado, pelo mesmo PIN).
        bottomNav = findViewById(R.id.bottomNavigation)
        findViewById<LinearLayout>(R.id.btnBackKids).setOnClickListener {
            mostrarDialogSairAreaInfantil(bottomNav)
        }

        // ── Botão lupa (header): expande/recolhe campo de busca ───────────
        findViewById<LinearLayout>(R.id.btnSearchKids).setOnClickListener { alternarBarraBusca() }

        // ✅ NOVO: botão de Downloads no cabeçalho da Área Kids — abre a
        // tela de downloads DEDICADA da Área Kids (KidsDownloadsActivity),
        // nunca a tela de downloads do perfil adulto. Injetado
        // dinamicamente ao lado do botão de busca, sem precisar de
        // nenhum id novo no activity_kids.xml.
        adicionarBotaoDownloadsKids()

        // ── Fechar busca ──────────────────────────────────────────────────
        findViewById<TextView>(R.id.btnCloseSearch).setOnClickListener { fecharBusca() }

        // ── Campo de busca — 100% INTERNO, filtra o que já foi carregado.
        // ✅ CORREÇÃO DE SEGURANÇA: antes, ao confirmar a busca, o app abria
        // a SearchActivity GERAL (sem filtro nenhum de conteúdo adulto) com
        // a palavra digitada. Uma criança podia então digitar qualquer outra
        // coisa naquela tela sem restrição alguma. Agora a busca só filtra,
        // na hora, os filmes/séries que já pertencem ao catálogo Kids
        // (kidsMoviesAll / kidsSeriesAll) — nunca sai da Área Kids.
        etSearchKids.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                processarConsultaBuscaKids(v.text.toString())
                true
            } else false
        }

        setupVoiceSearchKids()

        setupLayouts()
        setupHubChannels()
        carregarConteudoKids()
        setupBottomNavigation()
        animarEntrada()
    }

    // ── Abre/fecha a barra de busca (chamado pelo header E pela barra inferior) ──
    private fun alternarBarraBusca() {
        if (layoutSearchBar.visibility == View.GONE) {
            layoutSearchBar.visibility = View.VISIBLE
            layoutSearchBar.alpha = 0f
            layoutSearchBar.animate().alpha(1f).setDuration(200).start()
            etSearchKids.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etSearchKids, InputMethodManager.SHOW_IMPLICIT)
        } else {
            fecharBusca()
        }
    }

    // ── Filtra o catálogo já carregado — sem sair da tela ─────────────────
    private fun aplicarBuscaInterna(query: String) {
        emBusca = true
        sectionHubWrapper.visibility = View.GONE
        layoutContinueHeader.visibility = View.GONE
        rvRecentKids.visibility = View.GONE

        val filmesFiltrados  = kidsMoviesAll.filter { it.name.contains(query, ignoreCase = true) }
        val seriesFiltradas  = kidsSeriesAll.filter { it.name.contains(query, ignoreCase = true) }

        tvSectionMovies.text = "Filmes — \"$query\""
        tvSectionSeries.text = "Séries — \"$query\""

        rvMoviesKids.adapter = KidsVodAdapter(filmesFiltrados) { filme ->
            salvarNosRecentes(filme.id.toString(), "movie")
            startActivity(Intent(this, DetailsActivity::class.java).apply {
                putExtra("stream_id", filme.id)
                putExtra("stream_ext", filme.extension ?: "mp4")
                putExtra("name", filme.name)
                putExtra("icon", filme.icon)
                putExtra("rating", filme.rating ?: "0.0")
                // ✅ CORREÇÃO: idem — necessário pro download salvar com o
                // perfil Kids certo.
                putExtra("PROFILE_NAME", perfilAtivo)
            })
        }
        rvSeriesKids.adapter = KidsSeriesAdapter(seriesFiltradas) { serie ->
            salvarNosRecentes(serie.id.toString(), "series")
            startActivity(Intent(this, SeriesDetailsActivity::class.java).apply {
                putExtra("series_id", serie.id)
                putExtra("name", serie.name)
                putExtra("icon", serie.icon)
                putExtra("PROFILE_NAME", perfilAtivo)
            })
        }

        val semResultado = filmesFiltrados.isEmpty() && seriesFiltradas.isEmpty()
        tvSemResultados.visibility = if (semResultado) View.VISIBLE else View.GONE
        rvMoviesKids.visibility = if (filmesFiltrados.isEmpty()) View.GONE else View.VISIBLE
        rvSeriesKids.visibility = if (seriesFiltradas.isEmpty()) View.GONE else View.VISIBLE
    }

    // ── Restaura o catálogo completo (sai do "modo busca") ────────────────
    private fun restaurarCatalogoCompleto() {
        if (!emBusca) return
        emBusca = false
        sectionHubWrapper.visibility = View.VISIBLE
        tvSemResultados.visibility = View.GONE
        rvMoviesKids.visibility = View.VISIBLE
        rvSeriesKids.visibility = View.VISIBLE
        tvSectionMovies.text = "Filmes e Animações"
        tvSectionSeries.text = "Séries e Desenhos"

        rvMoviesKids.adapter = KidsVodAdapter(kidsMoviesAll) { filme ->
            salvarNosRecentes(filme.id.toString(), "movie")
            startActivity(Intent(this, DetailsActivity::class.java).apply {
                putExtra("stream_id", filme.id)
                putExtra("stream_ext", filme.extension ?: "mp4")
                putExtra("name", filme.name)
                putExtra("icon", filme.icon)
                putExtra("rating", filme.rating ?: "0.0")
                putExtra("PROFILE_NAME", perfilAtivo)
            })
        }
        rvSeriesKids.adapter = KidsSeriesAdapter(kidsSeriesAll) { serie ->
            salvarNosRecentes(serie.id.toString(), "series")
            startActivity(Intent(this, SeriesDetailsActivity::class.java).apply {
                putExtra("series_id", serie.id)
                putExtra("name", serie.name)
                putExtra("icon", serie.icon)
                putExtra("PROFILE_NAME", perfilAtivo)
            })
        }
        atualizarRecentesVisual()
    }

    // ── Fecha o campo de busca com animação ───────────────────────────────
    private fun fecharBusca() {
        layoutSearchBar.animate().alpha(0f).setDuration(150).withEndAction {
            layoutSearchBar.visibility = View.GONE
            etSearchKids.setText("")
            etSearchKids.clearFocus()
        }.start()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearchKids.windowToken, 0)
        restaurarCatalogoCompleto()
    }

    // ── Busca por voz — mesmo texto digitado passa pelo mesmo filtro de
    // segurança (termosProibidosBusca) usado na busca por teclado ─────────
    private fun processarConsultaBuscaKids(queryRaw: String) {
        val query = queryRaw.trim()
        val contemProibido = termosProibidosBusca.any { query.contains(it, ignoreCase = true) }
        when {
            contemProibido -> {
                Toast.makeText(this, "Busca bloqueada na Área Kids 🛡️", Toast.LENGTH_LONG).show()
                etSearchKids.setText("")
            }
            query.isEmpty() -> restaurarCatalogoCompleto()
            else -> aplicarBuscaInterna(query)
        }
    }

    private fun setupVoiceSearchKids() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            btnVoiceKids.visibility = View.GONE
            return
        }
        btnVoiceKids.setOnClickListener {
            val permissao = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            if (permissao == PackageManager.PERMISSION_GRANTED) {
                iniciarBuscaPorVozKids()
            } else {
                micPermissionLauncherKids.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun iniciarBuscaPorVozKids() {
        mostrarDialogoVozKids()

        speechRecognizerKids?.destroy()
        speechRecognizerKids = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    voiceDialogKids?.findViewById<TextView>(R.id.tvVoiceStatusKids)?.text = "Procurando... 🔎"
                }

                override fun onError(error: Int) {
                    fecharDialogoVozKids()
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não entendi, tenta de novo! 😅"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sem internet no momento"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissão de microfone negada"
                        else -> null
                    }
                    if (msg != null) Toast.makeText(this@KidsActivity, msg, Toast.LENGTH_SHORT).show()
                }

                override fun onResults(results: Bundle?) {
                    fecharDialogoVozKids()
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val texto = matches?.firstOrNull()
                    if (!texto.isNullOrBlank()) {
                        if (layoutSearchBar.visibility == View.GONE) {
                            layoutSearchBar.visibility = View.VISIBLE
                            layoutSearchBar.alpha = 1f
                        }
                        etSearchKids.setText(texto)
                        processarConsultaBuscaKids(texto)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        speechRecognizerKids?.startListening(intent)
    }

    private fun mostrarDialogoVozKids() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setContentView(R.layout.dialog_voice_search_kids)
        dialog.setCancelable(true)
        dialog.setOnCancelListener {
            speechRecognizerKids?.stopListening()
            pararPulsosKids()
        }
        dialog.show()
        voiceDialogKids = dialog

        val ring1 = dialog.findViewById<View>(R.id.viewPulseRing1)
        val ring2 = dialog.findViewById<View>(R.id.viewPulseRing2)
        val ring3 = dialog.findViewById<View>(R.id.viewPulseRing3)
        val micIcon = dialog.findViewById<View>(R.id.ivMicKids)

        pulseAnimatorsKids.clear()
        listOf(ring1 to 0L, ring2 to 200L, ring3 to 400L).forEach { (view, delay) ->
            val sx = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.6f, 1f).apply {
                duration = 1000; startDelay = delay; repeatCount = ObjectAnimator.INFINITE
            }
            val sy = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.6f, 1f).apply {
                duration = 1000; startDelay = delay; repeatCount = ObjectAnimator.INFINITE
            }
            val alpha = ObjectAnimator.ofFloat(view, "alpha", 0.6f, 0f, 0.6f).apply {
                duration = 1000; startDelay = delay; repeatCount = ObjectAnimator.INFINITE
            }
            sx.start(); sy.start(); alpha.start()
            pulseAnimatorsKids.add(sx); pulseAnimatorsKids.add(sy); pulseAnimatorsKids.add(alpha)
        }

        val bounce = ObjectAnimator.ofFloat(micIcon, "translationY", 0f, -10f, 0f).apply {
            duration = 700
            repeatCount = ObjectAnimator.INFINITE
            interpolator = DecelerateInterpolator()
        }
        bounce.start()
        pulseAnimatorsKids.add(bounce)
    }

    private fun pararPulsosKids() {
        pulseAnimatorsKids.forEach { it.cancel() }
        pulseAnimatorsKids.clear()
    }

    private fun fecharDialogoVozKids() {
        pararPulsosKids()
        voiceDialogKids?.dismiss()
        voiceDialogKids = null
    }

    // ── Animação de entrada nas seções ────────────────────────────────────
    private fun animarEntrada() {
        val views = listOf(rvHubChannels, rvMoviesKids, rvSeriesKids)
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 40f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(350)
                .setStartDelay((index * 80).toLong())
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    // ── Bottom Navigation ─────────────────────────────────────────────────
    // ✅ Usa o menu exclusivo do Kids (bottom_nav_menu_kids, definido no
    // activity_kids.xml) — só tem Início / Buscar / Perfil. "Buscar" agora
    // só abre a barra interna (não navega mais). "Perfil" pede confirmação
    // amigável antes de sair da Área Kids.
    //
    // ✅ CORREÇÃO CRÍTICA: antes, "Início" (nav_home) chamava finish() — como
    // a KidsActivity muitas vezes é a ÚNICA activity na pilha (perfil trocado
    // com FLAG_ACTIVITY_CLEAR_TASK) ou tem outra activity "adulta" logo
    // abaixo dela na pilha (ex: veio da ProfilesActivity sem CLEAR_TASK),
    // isso literalmente tirava a criança da Área Kids sem pedir PIN nenhum —
    // exatamente o mesmo buraco de segurança que o botão de voltar e o gesto
    // do sistema já tinham (e que já foram corrigidos). A KidsActivity JÁ É a
    // "home" da Área Kids, então o ícone de início não deve navegar pra lugar
    // nenhum — só fecha a busca (se estiver aberta) e volta pro topo do
    // conteúdo, permanecendo 100% dentro do perfil infantil.
    //
    // ✅ NOVO: o ícone "Perfil" agora mostra o nome + avatar do perfil Kids
    // ativo (mesmo padrão usado nas telas do perfil adulto via
    // BottomNavProfileHelper), em vez do ícone genérico padrão — mesmo esse
    // item aqui continuar disparando a confirmação de saída ao ser tocado.
    private fun setupBottomNavigation() {
        BottomNavProfileHelper.aplicarPerfilNoRodape(this, bottomNav, perfilAtivo, perfilIconAtivo)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home    -> {
                    if (layoutSearchBar.visibility == View.VISIBLE) fecharBusca()
                    rvHubChannels.scrollToPosition(0)
                    true
                }
                R.id.nav_search  -> { alternarBarraBusca(); false }
                R.id.nav_profile -> { mostrarDialogSairAreaInfantil(bottomNav); false }
                else -> false
            }
        }
    }

    // ✅ AJUSTE: trocado de AlertDialog.Builder (janela cinza padrão do
    // Android) para BottomSheetDialog com layout customizado — mesmo
    // padrão visual "premium" (fundo escuro, cantos arredondados) usado
    // no resto do app (ex: exclusão/edição de perfil no ProfilesActivity).
    //
    // ✅ CORREÇÃO DE SEGURANÇA: se o ProfileSwitchPinManager estiver
    // ativado, confirmar aqui não sai mais direto — abre a verificação de
    // PIN primeiro. Só navega pra ProfilesActivity depois do PIN correto
    // (ou da recuperação via pergunta secreta). Se o PIN NÃO estiver
    // ativado, mantém o comportamento antigo (só a confirmação).
    private fun mostrarDialogSairAreaInfantil(nav: BottomNavigationView) {
        val bottomSheet = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_sair_area_infantil, null)
        bottomSheet.setContentView(view)

        view.findViewById<View>(R.id.btnConfirmarSairKids).setOnClickListener {
            bottomSheet.dismiss()

            fun sairDeVerdade() {
                val intent = Intent(this, ProfilesActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }

            if (ProfileSwitchPinManager.isEnabled(this)) {
                pedirPinTrocaPerfil(
                    onSucesso = { sairDeVerdade() },
                    onCancelar = { nav.menu.findItem(R.id.nav_home)?.isChecked = true }
                )
            } else {
                sairDeVerdade()
            }
        }

        view.findViewById<View>(R.id.btnCancelarSairKids).setOnClickListener {
            bottomSheet.dismiss()
            // ✅ IMPORTANTE: usar `selectedItemId = ...` aqui dispararia o
            // listener de novo (chamaria finish() sem querer). `isChecked`
            // só reseta o ícone marcado visualmente, sem acionar o clique.
            nav.menu.findItem(R.id.nav_home)?.isChecked = true
        }

        // Se o usuário tocar fora do dialog pra fechar, mantém o ícone
        // "Início" marcado (mesmo comportamento do botão Cancelar).
        bottomSheet.setOnCancelListener {
            nav.menu.findItem(R.id.nav_home)?.isChecked = true
        }

        bottomSheet.window?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        )?.setBackgroundResource(android.R.color.transparent)

        bottomSheet.show()
    }

    // ══════════════════════════════════════════════════════════════════
    // ✅ NOVO: PIN de troca de perfil (ProfileSwitchPinManager)
    // ══════════════════════════════════════════════════════════════════
    // Dialogs construídos 100% em código (mesmo padrão usado no
    // SettingsActivity para o PIN do Controle Parental) — não depende de
    // nenhum layout XML novo.

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    // ══════════════════════════════════════════════════════════════════
    // ✅ NOVO: Botão de Downloads da Área Kids
    // ══════════════════════════════════════════════════════════════════
    // Injetado dinamicamente no cabeçalho, do lado do botão de busca —
    // mesmo padrão visual (círculo semi-transparente). Abre a
    // KidsDownloadsActivity, uma tela 100% separada da DownloadsActivity
    // do perfil adulto (layout próprio, navegação própria).

    private fun adicionarBotaoDownloadsKids() {
        val btnSearch = findViewById<LinearLayout>(R.id.btnSearchKids)
        val parent = btnSearch.parent as? ViewGroup ?: return
        if (parent.findViewWithTag<View>("btn_downloads_kids") != null) return

        val tamanho = 44.dp
        val btnDownloads = LinearLayout(this).apply {
            tag = "btn_downloads_kids"
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(tamanho, tamanho).apply {
                marginStart = 10.dp
            }
            isClickable = true
            isFocusable = true
            addView(TextView(this@KidsActivity).apply {
                text = "⬇️"
                textSize = 17f
                gravity = Gravity.CENTER
            })
            setOnClickListener { abrirDownloadsKids() }
        }

        val index = parent.indexOfChild(btnSearch)
        parent.addView(btnDownloads, index + 1)
    }

    private fun abrirDownloadsKids() {
        startActivity(Intent(this, KidsDownloadsActivity::class.java))
    }

    private fun pedirPinTrocaPerfil(onSucesso: () -> Unit, onCancelar: () -> Unit) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(24.dp, 24.dp, 24.dp, 20.dp)
        }

        root.addView(TextView(this).apply {
            text = "🔒 Sair da Área Kids"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6.dp }
        })
        root.addView(TextView(this).apply {
            text = "Peça para um responsável digitar o PIN"
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dp }
        })

        val etPin = EditText(this).apply {
            hint = "••••"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
            textSize = 22f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#444444"))
            gravity = Gravity.CENTER
            letterSpacing = 0.5f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A"))
                cornerRadius = 8.dp.toFloat()
                setStroke(1.dp, Color.parseColor("#333333"))
            }
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
        }
        root.addView(etPin)

        root.addView(TextView(this).apply {
            text = "Esqueci o PIN"
            textSize = 12f
            setTextColor(Color.parseColor("#4FC3F7"))
            gravity = Gravity.CENTER
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12.dp }
            setOnClickListener {
                dialog.dismiss()
                mostrarRecuperarPinTrocaPerfil(onSucesso = onSucesso, onCancelar = onCancelar)
            }
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16.dp }
        }
        btnRow.addView(TextView(this).apply {
            text = "Cancelar"
            textSize = 14f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 6.dp }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat()
            }
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss(); onCancelar() }
        })
        btnRow.addView(TextView(this).apply {
            text = "Confirmar"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginStart = 6.dp }
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = 8.dp.toFloat()
            }
            isClickable = true; isFocusable = true
            setOnClickListener {
                val digitado = etPin.text.toString()
                if (ProfileSwitchPinManager.verifyPin(this@KidsActivity, digitado)) {
                    dialog.dismiss()
                    onSucesso()
                } else {
                    etPin.setText("")
                    etPin.setHintTextColor(Color.parseColor("#FF5252"))
                    etPin.hint = "PIN incorreto"
                }
            }
        })
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor("#141414")); cornerRadius = 16.dp.toFloat()
            })
            val p = attributes
            p.width = (resources.displayMetrics.widthPixels * 0.82).toInt()
            attributes = p
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.setCancelable(true)
        dialog.setOnCancelListener { onCancelar() }
        dialog.show()
        etPin.requestFocus()
    }

    // ✅ Fluxo "Esqueci o PIN": exige a resposta da pergunta secreta
    // configurada em Configurações antes de liberar a saída. Sem pergunta
    // secreta configurada, não dá pra recuperar por aqui — orienta a
    // procurar um responsável.
    private fun mostrarRecuperarPinTrocaPerfil(onSucesso: () -> Unit, onCancelar: () -> Unit) {
        if (!ProfileSwitchPinManager.hasSecretQuestion(this)) {
            android.app.Dialog(this).apply {
                requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                val root = LinearLayout(this@KidsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#141414"))
                    setPadding(24.dp, 24.dp, 24.dp, 20.dp)
                }
                root.addView(TextView(this@KidsActivity).apply {
                    text = "Pergunta secreta não configurada"
                    textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 10.dp }
                })
                root.addView(TextView(this@KidsActivity).apply {
                    text = "Peça para um responsável sair usando o PIN diretamente, ou configurar a pergunta secreta em Configurações → PIN de Perfis."
                    textSize = 13f; setTextColor(Color.parseColor("#AAAAAA")); setLineSpacing(0f, 1.4f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 20.dp }
                })
                root.addView(TextView(this@KidsActivity).apply {
                    text = "OK"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.BLACK); gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48.dp)
                    background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8.dp.toFloat() }
                    isClickable = true; isFocusable = true
                    setOnClickListener { dismiss(); onCancelar() }
                })
                setContentView(root)
                window?.apply {
                    setBackgroundDrawable(GradientDrawable().apply {
                        setColor(Color.parseColor("#141414")); cornerRadius = 16.dp.toFloat()
                    })
                    val p = attributes; p.width = (resources.displayMetrics.widthPixels * 0.85).toInt(); attributes = p
                }
                setOnCancelListener { onCancelar() }
            }.show()
            return
        }

        val pergunta = ProfileSwitchPinManager.getSecretQuestion(this) ?: return

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(24.dp, 24.dp, 24.dp, 20.dp)
        }
        root.addView(TextView(this).apply {
            text = "🔐 Recuperar acesso"
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp }
        })
        root.addView(TextView(this).apply {
            text = pergunta
            textSize = 13f; setTextColor(Color.parseColor("#AAAAAA")); setLineSpacing(0f, 1.4f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14.dp }
        })
        val etResposta = EditText(this).apply {
            hint = "Sua resposta"
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#555555"))
            textSize = 14f; setSingleLine(true)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat()
                setStroke(1.dp, Color.parseColor("#333333"))
            }
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dp }
        }
        root.addView(etResposta)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        btnRow.addView(TextView(this).apply {
            text = "Cancelar"; textSize = 14f; setTextColor(Color.parseColor("#888888")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 6.dp }
            background = GradientDrawable().apply { setColor(Color.parseColor("#1A1A1A")); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss(); onCancelar() }
        })
        btnRow.addView(TextView(this).apply {
            text = "Confirmar"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginStart = 6.dp }
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8.dp.toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener {
                val resposta = etResposta.text.toString()
                if (ProfileSwitchPinManager.verifySecretAnswer(this@KidsActivity, resposta)) {
                    dialog.dismiss()
                    onSucesso()
                } else {
                    etResposta.setText("")
                    etResposta.setHintTextColor(Color.parseColor("#FF5252"))
                    etResposta.hint = "Resposta incorreta"
                }
            }
        })
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor("#141414")); cornerRadius = 16.dp.toFloat()
            })
            val p = attributes
            p.width = (resources.displayMetrics.widthPixels * 0.85).toInt()
            attributes = p
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.setOnCancelListener { onCancelar() }
        dialog.show()
        etResposta.requestFocus()
    }

    // ✅ NOVO: intercepta o botão/gesto FÍSICO de voltar do Android (fora
    // do app, na barra de navegação do sistema). Sem isso, dava pra sair
    // da Área Kids sem passar pela confirmação só usando o botão de voltar
    // do celular — mesma brecha que existia no botão de voltar do topo.
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        mostrarDialogSairAreaInfantil(bottomNav)
    }

    override fun onResume() {
        super.onResume()
        WindowCompat.setDecorFitsSystemWindows(window, true)
        etSearchKids.setText("")
        etSearchKids.clearFocus()
        fecharBusca()
        atualizarRecentesVisual()
        // ✅ Reaplica nome + avatar do perfil Kids no rodapé — mesma lógica
        // usada nas telas do perfil adulto, garante que fica correto mesmo
        // se algo mudar no perfil enquanto a Activity estava em background.
        BottomNavProfileHelper.aplicarPerfilNoRodape(this, bottomNav, perfilAtivo, perfilIconAtivo)
    }

    private fun setupLayouts() {
        rvHubChannels.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        rvRecentKids.layoutManager  = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        rvMoviesKids.layoutManager  = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        rvSeriesKids.layoutManager  = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
    }

    // ── Canais Hub ────────────────────────────────────────────────────────
    private fun setupHubChannels() {
        val nomesDesejados = listOf(
            "Cartoon Network", "Discovery Kids", "Gloob",
            "Cartoonito", "Nickelodeon", "Disney Channel", "Disney Junior"
        )

        XtreamApi.service.getLiveStreams(user, pass, categoryId = "0")
            .enqueue(object : Callback<List<LiveStream>> {
                override fun onResponse(call: Call<List<LiveStream>>, response: Response<List<LiveStream>>) {
                    if (response.isSuccessful && response.body() != null) {
                        val todosCanais = response.body()!!
                        val listaHub = mutableListOf<LiveStream>()
                        nomesDesejados.forEach { nomeBusca ->
                            todosCanais.firstOrNull { it.name.contains(nomeBusca, ignoreCase = true) }
                                ?.let { listaHub.add(it) }
                        }
                        rvHubChannels.adapter = HubAdapter(listaHub) { canal ->
                            startActivity(Intent(this@KidsActivity, PlayerActivity::class.java).apply {
                                putExtra("stream_id", canal.id)
                                putExtra("name", canal.name)
                                putExtra("title", canal.name)
                                putExtra("type", "live")
                                putExtra("epg_channel_id", canal.epg_channel_id)
                                // ✅ NOVO: mesma correção estendida aqui —
                                // garante consistência de perfil também nos
                                // canais ao vivo do Hub Kids.
                                putExtra("PROFILE_NAME", perfilAtivo)
                            })
                        }
                    }
                }
                override fun onFailure(call: Call<List<LiveStream>>, t: Throwable) {}
            })
    }

    // ── Conteúdo Kids (filmes + séries) ───────────────────────────────────
    // ✅ Agora acumula tudo em kidsMoviesAll / kidsSeriesAll (listas mestras)
    // — é nelas que a busca interna filtra depois. Os adapters só são
    // atualizados aqui se NÃO estivermos em modo busca no momento (senão
    // ia "atropelar" os resultados filtrados que o usuário está vendo).
    private fun carregarConteudoKids() {
        // Filmes kids
        XtreamApi.service.getVodCategories(user, pass)
            .enqueue(object : Callback<ResponseBody> {
                override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                    if (!response.isSuccessful || response.body() == null) return
                    try {
                        val rawJson = response.body()!!.string()
                        val listaCategorias = parseCategorias(rawJson)
                        val kidsCats = listaCategorias.filter { cat ->
                            val n = cat.name.lowercase()
                            n.contains("kids") || n.contains("infantil") ||
                            n.contains("desenho") || n.contains("disney") ||
                            n.contains("animação") || n.contains("animacao") ||
                            n.contains("cartoon")
                        }

                        kidsCats.forEach { cat ->
                            XtreamApi.service.getVodStreams(user, pass, categoryId = cat.id)
                                .enqueue(object : Callback<List<VodStream>> {
                                    override fun onResponse(call: Call<List<VodStream>>, res: Response<List<VodStream>>) {
                                        if (!res.isSuccessful || res.body() == null) return
                                        // ✅ Filtro real: só entra no catálogo Kids quem o
                                        // TMDB confirma ser Animação/Família — a categoria
                                        // do provedor IPTV sozinha não é confiável. Cada
                                        // título é verificado e exibido de forma
                                        // independente (aparece assim que aprovado).
                                        processarEExibirFilmes(res.body()!!)
                                    }
                                    override fun onFailure(call: Call<List<VodStream>>, t: Throwable) {}
                                })
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {}
            })

        // Séries kids
        XtreamApi.service.getSeriesCategories(user, pass)
            .enqueue(object : Callback<ResponseBody> {
                override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                    if (!response.isSuccessful || response.body() == null) return
                    try {
                        val rawJson = response.body()!!.string()
                        val listaCategorias = parseCategorias(rawJson)
                        val kidsSeriesCats = listaCategorias.filter { cat ->
                            val n = cat.name.lowercase()
                            n.contains("kids") || n.contains("infantil") ||
                            n.contains("desenho") || n.contains("disney") ||
                            n.contains("animação") || n.contains("animacao") ||
                            n.contains("cartoon")
                        }

                        kidsSeriesCats.forEach { cat ->
                            XtreamApi.service.getSeries(user, pass, categoryId = cat.id)
                                .enqueue(object : Callback<List<SeriesStream>> {
                                    override fun onResponse(call: Call<List<SeriesStream>>, res: Response<List<SeriesStream>>) {
                                        if (!res.isSuccessful || res.body() == null) return
                                        processarEExibirSeries(res.body()!!)
                                    }
                                    override fun onFailure(call: Call<List<SeriesStream>>, t: Throwable) {}
                                })
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {}
            })
    }

    private fun parseCategorias(rawJson: String): List<LiveCategory> {
        val lista = mutableListOf<LiveCategory>()
        val gson = Gson()
        return try {
            if (rawJson.trim().startsWith("[")) {
                val listType = object : TypeToken<List<LiveCategory>>() {}.type
                gson.fromJson(rawJson, listType)
            } else if (rawJson.trim().startsWith("{")) {
                val jsonObject = JSONObject(rawJson)
                jsonObject.keys().forEach { key ->
                    val cat = gson.fromJson(jsonObject.getJSONObject(key).toString(), LiveCategory::class.java)
                    lista.add(cat)
                }
                lista
            } else lista
        } catch (e: Exception) { lista }
    }

    // ── Recentes ──────────────────────────────────────────────────────────
    private fun salvarNosRecentes(id: String, tipo: String) {
        val key = if (tipo == "movie") "kids_recent_vod" else "kids_recent_series"
        val atuais = prefs.getStringSet(key, mutableSetOf())?.toMutableList() ?: mutableListOf()
        atuais.remove(id)
        atuais.add(0, id) // mais recente primeiro
        prefs.edit().putStringSet(key, atuais.take(10).toSet()).apply()
    }

    private fun atualizarRecentesVisual() {
        if (emBusca) return
        val recentVodIds    = prefs.getStringSet("kids_recent_vod", emptySet()) ?: emptySet()
        val recentSeriesIds = prefs.getStringSet("kids_recent_series", emptySet()) ?: emptySet()
        if (recentVodIds.isEmpty() && recentSeriesIds.isEmpty()) return

        val listaRecentes = mutableListOf<KidsRecentItem>()

        if (recentVodIds.isNotEmpty()) {
            XtreamApi.service.getAllVodStreams(user, pass)
                .enqueue(object : Callback<List<VodStream>> {
                    override fun onResponse(call: Call<List<VodStream>>, response: Response<List<VodStream>>) {
                        response.body()
                            ?.filter { recentVodIds.contains(it.id.toString()) }
                            ?.forEach { listaRecentes.add(KidsRecentItem(it.id.toString(), it.name, it.icon ?: "", "movie", it, null)) }
                        exibirRecentes(listaRecentes)
                    }
                    override fun onFailure(call: Call<List<VodStream>>, t: Throwable) {}
                })
        }

        if (recentSeriesIds.isNotEmpty()) {
            XtreamApi.service.getAllSeries(user, pass)
                .enqueue(object : Callback<List<SeriesStream>> {
                    override fun onResponse(call: Call<List<SeriesStream>>, response: Response<List<SeriesStream>>) {
                        response.body()
                            ?.filter { recentSeriesIds.contains(it.id.toString()) }
                            ?.forEach { listaRecentes.add(KidsRecentItem(it.id.toString(), it.name, it.icon ?: "", "series", null, it)) }
                        exibirRecentes(listaRecentes)
                    }
                    override fun onFailure(call: Call<List<SeriesStream>>, t: Throwable) {}
                })
        }
    }

    private fun exibirRecentes(itens: List<KidsRecentItem>) {
        if (emBusca) return
        val listaFinal = itens.distinctBy { it.id }
        if (listaFinal.isEmpty()) return
        layoutContinueHeader.visibility = View.VISIBLE
        tvTitleRecent.visibility        = View.VISIBLE
        rvRecentKids.visibility         = View.VISIBLE
        rvRecentKids.adapter = KidsRecentAdapter(listaFinal) { item ->
            if (item.tipo == "movie" && item.filmeObj != null) {
                startActivity(Intent(this, DetailsActivity::class.java).apply {
                    putExtra("stream_id", item.filmeObj.id)
                    putExtra("name", item.filmeObj.name)
                    putExtra("icon", item.filmeObj.icon)
                    putExtra("PROFILE_NAME", perfilAtivo)
                })
            } else if (item.tipo == "series" && item.serieObj != null) {
                startActivity(Intent(this, SeriesDetailsActivity::class.java).apply {
                    putExtra("series_id", item.serieObj.id)
                    putExtra("name", item.serieObj.name)
                    putExtra("icon", item.serieObj.icon)
                    putExtra("PROFILE_NAME", perfilAtivo)
                })
            }
        }
    }

    // ══ DATA CLASS ════════════════════════════════════════════════════════
    data class KidsRecentItem(
        val id: String,
        val nome: String,
        val capa: String,
        val tipo: String,
        val filmeObj: VodStream?,
        val serieObj: SeriesStream?
    )

    // ══════════════════════════════════════════════════════════════════════
    // ADAPTER: HubAdapter — cards dos canais com cores por rede
    // ══════════════════════════════════════════════════════════════════════
    inner class HubAdapter(
        val list: List<LiveStream>,
        val onClick: (LiveStream) -> Unit
    ) : RecyclerView.Adapter<HubAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView       = v.findViewById(R.id.imgLogoHub)
            val txt: TextView        = v.findViewById(R.id.tvNameHub)
            val container: LinearLayout = v.findViewById(R.id.containerHub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_hub_kids, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            val nomeUpper = item.name.uppercase()
            holder.txt.text = item.name

            Glide.with(holder.itemView.context)
                .load(item.icon)
                .format(DecodeFormat.PREFER_ARGB_8888)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .transition(DrawableTransitionOptions.withCrossFade(200))
                .fitCenter()
                .into(holder.img)

            // Cor de fundo por rede — gradiente sutil
            val (corA, corB) = when {
                nomeUpper.contains("CARTOON")   -> "#1A1A1A" to "#333333"
                nomeUpper.contains("DISCOVERY") -> "#003D7A" to "#0066CC"
                nomeUpper.contains("NICK")      -> "#CC4400" to "#FF6600"
                nomeUpper.contains("GLOOB")     -> "#8B0000" to "#E30613"
                nomeUpper.contains("DISNEY")    -> "#6600AA" to "#AA00FF"
                nomeUpper.contains("JUNIOR")    -> "#004D99" to "#0080FF"
                else                            -> "#1A0A4D" to "#330D7A"
            }

            val grad = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(corA), Color.parseColor(corB)))
            grad.cornerRadius = 14f * resources.displayMetrics.density
            holder.container.background = grad

            holder.itemView.setOnClickListener {
                holder.itemView.animate().scaleX(0.93f).scaleY(0.93f).setDuration(80)
                    .withEndAction {
                        holder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                        onClick(item)
                    }.start()
            }
        }
        override fun getItemCount() = list.size
    }

    // ══════════════════════════════════════════════════════════════════════
    // ADAPTER: KidsVodAdapter — filmes/animações com poster grande
    // ══════════════════════════════════════════════════════════════════════
    inner class KidsVodAdapter(
        val list: List<VodStream>,
        val onClick: (VodStream) -> Unit
    ) : RecyclerView.Adapter<KidsVodAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.imgPoster)
            val txt: TextView  = v.findViewById(R.id.tvName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_kids_card, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.txt.text = item.name

            Glide.with(holder.itemView.context)
                .load(item.icon)
                .format(DecodeFormat.PREFER_ARGB_8888)
                .override(200, 300)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .thumbnail(0.15f)
                .transition(DrawableTransitionOptions.withCrossFade(200))
                .centerCrop()
                .into(holder.img)

            holder.itemView.setOnClickListener {
                animarClick(holder.itemView) { onClick(item) }
            }
        }
        override fun getItemCount() = list.size
    }

    // ══════════════════════════════════════════════════════════════════════
    // ADAPTER: KidsSeriesAdapter — séries/desenhos
    // ══════════════════════════════════════════════════════════════════════
    inner class KidsSeriesAdapter(
        val list: List<SeriesStream>,
        val onClick: (SeriesStream) -> Unit
    ) : RecyclerView.Adapter<KidsSeriesAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.imgPoster)
            val txt: TextView  = v.findViewById(R.id.tvName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_kids_card, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.txt.text = item.name

            Glide.with(holder.itemView.context)
                .load(item.icon)
                .format(DecodeFormat.PREFER_ARGB_8888)
                .override(200, 300)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .thumbnail(0.15f)
                .transition(DrawableTransitionOptions.withCrossFade(200))
                .centerCrop()
                .into(holder.img)

            holder.itemView.setOnClickListener {
                animarClick(holder.itemView) { onClick(item) }
            }
        }
        override fun getItemCount() = list.size
    }

    // ══════════════════════════════════════════════════════════════════════
    // ADAPTER: KidsRecentAdapter — continuar assistindo
    // ══════════════════════════════════════════════════════════════════════
    inner class KidsRecentAdapter(
        val list: List<KidsRecentItem>,
        val onClick: (KidsRecentItem) -> Unit
    ) : RecyclerView.Adapter<KidsRecentAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.imgPoster)
            val txt: TextView  = v.findViewById(R.id.tvName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_kids_card, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.txt.text = item.nome

            Glide.with(holder.itemView.context)
                .load(item.capa)
                .format(DecodeFormat.PREFER_ARGB_8888)
                .override(200, 300)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .thumbnail(0.15f)
                .transition(DrawableTransitionOptions.withCrossFade(200))
                .centerCrop()
                .into(holder.img)

            holder.itemView.setOnClickListener {
                animarClick(holder.itemView) { onClick(item) }
            }
        }
        override fun getItemCount() = list.size
    }

    // ── Animação de clique (escala) ───────────────────────────────────────
    private fun animarClick(view: View, onEnd: () -> Unit) {
        view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(80)
                    .withEndAction { onEnd() }.start()
            }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizerKids?.destroy()
    }
}
