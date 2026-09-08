package com.vltv.play

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

// IMPORTAÇÃO DA DATABASE E ENTIDADES
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.VodEntity
import com.vltv.play.data.SeriesEntity
import com.vltv.play.data.LiveStreamEntity

class SearchActivity : AppCompatActivity(), CoroutineScope {

    private lateinit var etQuery: EditText
    private lateinit var btnDoSearch: ImageButton
    private lateinit var btnVoiceSearch: ImageButton
    private lateinit var rvResults: RecyclerView
    private lateinit var adapter: SearchResultAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView

    // DATABASE INICIALIZADA VIA LAZY
    private val database by lazy { AppDatabase.getDatabase(this) }

    // Variáveis da Busca Otimizada
    private val supervisor = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + supervisor

    // LISTA MESTRA: Guarda tudo na memória para busca instantânea
    private var catalogoCompleto: List<SearchResultItem> = emptyList()
    private var isCarregandoDados = false
    private var jobBuscaInstantanea: Job? = null

    // ✅ CORREÇÃO: Guarda a última query digitada para reaplicar após o carregamento
    private var ultimaQueryDigitada: String = ""

    // Guarda de onde o usuário veio ("filmes", "series" ou "tudo")
    private var tipoPesquisa: String = "tudo"

    // --- BUSCA POR VOZ ---
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceDialog: Dialog? = null
    private var pulseAnimator: ObjectAnimator? = null

    // Rodapé de navegação (pill) — mesmo padrão das outras telas de phone
    private var currentProfile: String = "Padrao"
    private var currentProfileIcon: String? = null
    private var bottomNavigation: BottomNavigationView? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            iniciarBuscaPorVoz()
        } else {
            Toast.makeText(this, "Permissão de microfone necessária para busca por voz", Toast.LENGTH_SHORT).show()
        }
    }

    // Detecção de TV centralizada em DeviceUtils.kt (context.isTelevisionDevice()),
    // usada em todo o app — não reimplementar localmente aqui.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        val vltvPrefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        currentProfile = intent.getStringExtra("PROFILE_NAME")
            ?: vltvPrefs.getString("last_profile_name", null)
            ?: "Padrao"
        currentProfileIcon = intent.getStringExtra("PROFILE_ICON")
            ?.takeIf { it.isNotEmpty() }
            ?: vltvPrefs.getString("last_profile_icon", null)?.takeIf { it.isNotEmpty() }

        // Configuração de Tela Cheia / Barras
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (this.isTelevisionDevice()) {
            windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
        }

        // Captura a etiqueta enviada pela tela anterior (Padrão é "tudo")
        tipoPesquisa = intent.getStringExtra("tipo_pesquisa") ?: "tudo"

        initViews()
        setupBottomNavigation()
        setupRecyclerView()
        setupSearchLogic()
        setupVoiceSearch()

        // Carregamento Híbrido: Primeiro Database, depois API
        carregarDadosIniciais()
    }

    private fun initViews() {
        etQuery = findViewById(R.id.etQuery)
        btnDoSearch = findViewById(R.id.btnDoSearch)
        btnVoiceSearch = findViewById(R.id.btnVoiceSearch)
        rvResults = findViewById(R.id.rvResults)
        progressBar = findViewById(R.id.progressBar)
        tvEmpty = findViewById(R.id.tvEmpty)
        bottomNavigation = findViewById(R.id.bottomNavigation)

        // Ajuste para o teclado não cobrir a tela
        etQuery.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    }

    // Pill de navegação é só pra layout de telefone — na TV essa tela usa
    // D-pad/foco normal e não precisa da barra flutuante.
    private fun setupBottomNavigation() {
        if (this.isTelevisionDevice()) {
            bottomNavigation?.visibility = View.GONE
            return
        }
        BottomNavProfileHelper.aplicarPerfilNoRodape(this, bottomNavigation, currentProfile, currentProfileIcon)
        bottomNavigation?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home      -> { finish(); true }
                R.id.nav_search    -> true // já está aqui
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
        if (!this.isTelevisionDevice()) {
            BottomNavProfileHelper.aplicarPerfilNoRodape(this, bottomNavigation, currentProfile, currentProfileIcon)
        }
    }

    private fun setupRecyclerView() {
        adapter = SearchResultAdapter { item ->
            abrirDetalhes(item)
        }

        // 5 colunas se for TV, 3 colunas se for Celular
        val spanCount = if (this.isTelevisionDevice()) 5 else 3

        rvResults.layoutManager = GridLayoutManager(this, spanCount)
        rvResults.adapter = adapter
        rvResults.isFocusable = true
        rvResults.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    private fun setupSearchLogic() {
        // TextWatcher: Detecta cada letra digitada
        etQuery.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val texto = s.toString().trim()

                // ✅ CORREÇÃO: Salva a query SEMPRE, mesmo durante o carregamento
                ultimaQueryDigitada = texto

                // Se ainda está carregando, apenas salva e aguarda o finalizarUI()
                if (isCarregandoDados) return

                jobBuscaInstantanea?.cancel()
                jobBuscaInstantanea = launch {
                    // ✅ CORREÇÃO: 150ms para acomodar digitação rápida sem cancelamentos prematuros
                    delay(150)
                    filtrarNaMemoria(texto)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnDoSearch.setOnClickListener {
            filtrarNaMemoria(etQuery.text.toString().trim())
        }

        etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                filtrarNaMemoria(etQuery.text.toString().trim())
                true
            } else false
        }
    }

    // --- BUSCA POR VOZ: SETUP ---

    private fun setupVoiceSearch() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            btnVoiceSearch.visibility = View.GONE
            return
        }

        btnVoiceSearch.setOnClickListener {
            val permissao = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            if (permissao == PackageManager.PERMISSION_GRANTED) {
                iniciarBuscaPorVoz()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun iniciarBuscaPorVoz() {
        mostrarDialogoVoz()

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    voiceDialog?.findViewById<TextView>(R.id.tvVoiceStatus)?.text = "Buscando..."
                }

                override fun onError(error: Int) {
                    fecharDialogoVoz()
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não entendi, tente novamente"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sem conexão com a internet"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissão de microfone negada"
                        else -> null
                    }
                    if (msg != null) {
                        Toast.makeText(this@SearchActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onResults(results: Bundle?) {
                    fecharDialogoVoz()
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val texto = matches?.firstOrNull()
                    if (!texto.isNullOrBlank()) {
                        etQuery.setText(texto)
                        etQuery.setSelection(texto.length)
                        // O TextWatcher já dispara a busca automaticamente ao setar o texto
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
        speechRecognizer?.startListening(intent)
    }

    private fun mostrarDialogoVoz() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setContentView(R.layout.dialog_voice_search)
        dialog.setCancelable(true)
        dialog.setOnCancelListener {
            speechRecognizer?.stopListening()
            pulseAnimator?.cancel()
        }
        dialog.show()
        voiceDialog = dialog

        val pulseView = dialog.findViewById<View>(R.id.viewPulse)
        pulseAnimator = ObjectAnimator.ofFloat(pulseView, "scaleX", 1f, 1.4f, 1f).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        val pulseAnimatorY = ObjectAnimator.ofFloat(pulseView, "scaleY", 1f, 1.4f, 1f).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        pulseAnimator?.start()
        pulseAnimatorY.start()
    }

    private fun fecharDialogoVoz() {
        pulseAnimator?.cancel()
        voiceDialog?.dismiss()
        voiceDialog = null
    }

    private fun carregarDadosIniciais() {
        isCarregandoDados = true
        progressBar.visibility = View.VISIBLE
        tvEmpty.text = "Carregando catálogo..."
        tvEmpty.visibility = View.VISIBLE
        etQuery.isEnabled = false

        val prefs = getSharedPreferences("vltv_prefs", MODE_PRIVATE)
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""

        launch {
            try {
                val resultadosLocal = withContext(Dispatchers.IO) {
                    val filmes = if (tipoPesquisa == "tudo" || tipoPesquisa == "filmes") {
                        database.streamDao().getAllVods().map {
                            SearchResultItem(
                                id = it.stream_id,
                                title = it.name ?: "Sem título",
                                type = "movie",
                                extraInfo = it.rating,
                                iconUrl = it.stream_icon
                            )
                        }
                    } else emptyList()

                    val series = if (tipoPesquisa == "tudo" || tipoPesquisa == "series") {
                        database.streamDao().getAllSeries().map {
                            SearchResultItem(
                                id = it.series_id,
                                title = it.name ?: "Sem título",
                                type = "series",
                                extraInfo = it.rating,
                                iconUrl = it.cover
                            )
                        }
                    } else emptyList()

                    filmes + series
                }

                if (resultadosLocal.isNotEmpty()) {
                    catalogoCompleto = resultadosLocal
                    finalizarUI()
                }

                val resultadosAPI = withContext(Dispatchers.IO) {
                    val deferredFilmes = if (tipoPesquisa == "tudo" || tipoPesquisa == "filmes") async { buscarFilmes(username, password) } else null
                    val deferredSeries = if (tipoPesquisa == "tudo" || tipoPesquisa == "series") async { buscarSeries(username, password) } else null
                    val deferredCanais = if (tipoPesquisa == "tudo") async { buscarCanais(username, password) } else null

                    val apiFilmes = deferredFilmes?.await() ?: emptyList()
                    val apiSeries = deferredSeries?.await() ?: emptyList()
                    val apiCanais = deferredCanais?.await() ?: emptyList()

                    apiFilmes + apiSeries + apiCanais
                }

                if (resultadosAPI.isNotEmpty()) {
                    catalogoCompleto = resultadosAPI
                    finalizarUI()
                }

            } catch (e: Exception) {
                isCarregandoDados = false
                progressBar.visibility = View.GONE
                tvEmpty.text = "Erro ao carregar dados."
                tvEmpty.visibility = View.VISIBLE
                etQuery.isEnabled = true
            }
        }
    }

    private fun finalizarUI() {
        isCarregandoDados = false
        progressBar.visibility = View.GONE
        tvEmpty.visibility = View.GONE
        etQuery.isEnabled = true
        etQuery.requestFocus()

        val initial = intent.getStringExtra("initial_query")
        if (!initial.isNullOrBlank()) {
            etQuery.setText(initial)
            ultimaQueryDigitada = initial
            filtrarNaMemoria(initial)
        } else if (ultimaQueryDigitada.isNotEmpty()) {
            filtrarNaMemoria(ultimaQueryDigitada)
        } else {
            tvEmpty.text = "Digite para buscar..."
            tvEmpty.visibility = View.VISIBLE
        }
    }

    private fun filtrarNaMemoria(query: String) {
        if (catalogoCompleto.isEmpty() && !isCarregandoDados) return

        if (query.length < 1) {
            adapter.submitList(emptyList())
            tvEmpty.text = "Digite para buscar..."
            tvEmpty.visibility = View.VISIBLE
            return
        }

        val qNorm = query.lowercase().trim()

        val resultadosFiltrados = catalogoCompleto.filter { item ->
            val matchNome = item.title.lowercase().contains(qNorm)

            val matchTipo = when (tipoPesquisa) {
                "filmes" -> item.type == "movie"
                "series" -> item.type == "series"
                else -> true
            }

            matchNome && matchTipo
        }.take(100)

        adapter.submitList(resultadosFiltrados)

        if (resultadosFiltrados.isEmpty()) {
            tvEmpty.text = "Nenhum resultado encontrado."
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
        }
    }

    // --- FUNÇÕES DE API ---

    private fun buscarFilmes(u: String, p: String): List<SearchResultItem> {
        return try {
            val response = XtreamApi.service.getAllVodStreams(user = u, pass = p).execute()
            if (response.isSuccessful && response.body() != null) {
                response.body()!!.map {
                    SearchResultItem(
                        id = it.id,
                        title = it.name ?: "Sem Título",
                        type = "movie",
                        extraInfo = it.rating,
                        iconUrl = it.icon
                    )
                }
            } else emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun buscarSeries(u: String, p: String): List<SearchResultItem> {
        return try {
            val response = XtreamApi.service.getAllSeries(user = u, pass = p).execute()
            if (response.isSuccessful && response.body() != null) {
                response.body()!!.map {
                    SearchResultItem(
                        id = it.id,
                        title = it.name ?: "Sem Título",
                        type = "series",
                        extraInfo = it.rating,
                        iconUrl = it.icon
                    )
                }
            } else emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun buscarCanais(u: String, p: String): List<SearchResultItem> {
        return try {
            val response = XtreamApi.service.getLiveStreams(user = u, pass = p, categoryId = "0").execute()
            if (response.isSuccessful && response.body() != null) {
                response.body()!!.map {
                    SearchResultItem(
                        id = it.id,
                        title = it.name ?: "Sem Nome",
                        type = "live",
                        extraInfo = null,
                        iconUrl = it.icon
                    )
                }
            } else emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun abrirDetalhes(item: SearchResultItem) {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val profileName = prefs.getString("last_profile_name", "Padrao") ?: "Padrao"

        when (item.type) {
            "movie" -> {
                val i = Intent(this, DetailsActivity::class.java)
                i.putExtra("stream_id", item.id)
                i.putExtra("name", item.title)
                i.putExtra("icon", item.iconUrl ?: "")
                i.putExtra("rating", item.extraInfo ?: "0.0")
                i.putExtra("PROFILE_NAME", profileName)
                startActivity(i)
            }
            "series" -> {
                val i = Intent(this, SeriesDetailsActivity::class.java)
                i.putExtra("series_id", item.id)
                i.putExtra("name", item.title)
                i.putExtra("icon", item.iconUrl ?: "")
                i.putExtra("rating", item.extraInfo ?: "0.0")
                i.putExtra("PROFILE_NAME", profileName)
                startActivity(i)
            }
            "live" -> {
                val i = Intent(this, PlayerActivity::class.java)
                i.putExtra("stream_id", item.id)
                i.putExtra("stream_type", "live")
                i.putExtra("channel_name", item.title)
                i.putExtra("PROFILE_NAME", profileName)
                startActivity(i)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        supervisor.cancel()
    }
}
