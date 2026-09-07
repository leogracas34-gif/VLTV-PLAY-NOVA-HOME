package com.vltv.play

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.SeriesEntity
import com.vltv.play.data.VodEntity
import com.vltv.play.databinding.ActivityLoginBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    // ✅ ÚNICA lista de DNS do app — os 7 servidores realmente em uso.
    // Antes existia uma segunda lista hardcoded só pro fallback
    // (dentro de iniciarLoginTurbo), com vários DNS antigos que você já
    // não usa mais (infiprotec.site, blackdns.shop, tlfp.fun,
    // telefunplay.xyz, tvblack.shop) e sem alguns que você usa
    // (cmdtv.casa, cmdtv.pro). Isso fazia a etapa rápida e a etapa de
    // fallback testarem listas diferentes entre si. Agora o fallback usa
    // esta mesma lista (SERVERS) — só um lugar pra manter atualizado daqui
    // pra frente.
    private val SERVERS = listOf(
        "http://fibercdn.sbs",
        "http://ranos.sbs",
        "http://cmdtv.casa",
        "http://cmdtv.pro",
        "http://cmdtv.sbs",
        "http://cmdtv.top",
        "http://cmdbr.life",
        "http://supertv.red",
        "http://kodexk.click",
        "http://maisplaytech.space",
        "http://pthdtv.sbs",
        "http://pthdtv.top",
        "http://cdnsec.cyou",
        "http://fx12.sbs",
        "http://cybertronplay.space"
    )

    private val clientRapido = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val clientLento = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val dotsHandler = Handler(Looper.getMainLooper())
    private var dotsJob: Runnable? = null
    private var dotsCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // ✅ REMOVIDO: installSplashScreen() saiu daqui. A LoginActivity não
        // é mais a porta de entrada do app — quem cobre esse papel agora é
        // a SplashActivity (splash própria e animada, com o wordmark "VLTV
        // PLAY" surgindo letra por letra). O tema desta Activity voltou a
        // ser o normal (Theme.VLTVPlay), configurado no AndroidManifest.
        super.onCreate(savedInstanceState)

        // ✅ NOVO: precisa rodar ANTES de ler "vltv_prefs" logo abaixo.
        // Ver explicação completa na função.
        limparLoginRestauradoSeInstalacaoNova()

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        aplicarModoImersivo()

        requestedOrientation = if (isTelevisionDevice()) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        val prefs     = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val savedUser = prefs.getString("username", null)
        val savedPass = prefs.getString("password", null)
        val savedDns  = prefs.getString("dns", null)

        if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank() && !savedDns.isNullOrBlank()) {
            binding.root.visibility = View.INVISIBLE
            verificarEIniciarRapido(savedDns, savedUser, savedPass)
        } else {
            setupUI()
        }
    }

    // ✅ NOVO: resolve o caso "desinstalei o app, reinstalei, e o login
    // antigo voltou sozinho". Isso acontece por causa do Auto Backup do
    // Android: ele tira snapshots periódicos de "vltv_prefs" (onde ficam
    // username/password/dns/last_profile_name) e os restaura sozinho ao
    // reinstalar o app na mesma conta Google — mesmo que o usuário tenha
    // desinstalado JUSTAMENTE pra trocar de login.
    //
    // A solução usa um SEGUNDO arquivo de preferências, "vltv_device_marker",
    // que é EXCLUÍDO do backup (ver res/xml/backup_rules.xml e
    // data_extraction_rules.xml — só esse arquivo é excluído, o resto do
    // backup continua normal). Esse marcador só existe fisicamente no
    // aparelho onde o app rodou pelo menos uma vez; ele nunca "volta" pelo
    // backup.
    //
    // Lógica: se o marcador NÃO existe, mas já existe login salvo em
    // "vltv_prefs", esse login só pode ter chegado ali via restauração de
    // backup (não tem como ser de uma sessão real, já que esse é o
    // primeiro onCreate desta instalação) — então apaga só as chaves de
    // login/perfil e deixa o app cair normalmente na tela de login. Depois
    // disso o marcador é gravado, e essa limpeza não roda de novo até a
    // próxima desinstalação/reinstalação.
    private fun limparLoginRestauradoSeInstalacaoNova() {
        val marcador = getSharedPreferences("vltv_device_marker", Context.MODE_PRIVATE)
        val jaRodouNesteAparelho = marcador.getBoolean("instalado", false)

        if (!jaRodouNesteAparelho) {
            getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit()
                .remove("username")
                .remove("password")
                .remove("dns")
                .remove("last_profile_name")
                .remove("last_profile_icon")
                .apply()

            marcador.edit().putBoolean("instalado", true).apply()
        }
    }

    // Detecção de TV centralizada em DeviceUtils.kt (isTelevisionDevice()),
    // usada em todo o app — não reimplementar localmente aqui.

    private fun aplicarModoImersivo() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun setupUI() {
        binding.btnLogin.isFocusableInTouchMode = false
        binding.btnLogin.isFocusable = false

        binding.etUsername.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                binding.etPassword.requestFocus(); true
            } else false
        }

        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                binding.btnLogin.callOnClick(); true
            } else false
        }

        var ultimoClique = 0L
        binding.btnLogin.setOnClickListener {
            val agora = System.currentTimeMillis()
            if (agora - ultimoClique < 800L) return@setOnClickListener
            ultimoClique = agora

            val user = binding.etUsername.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()

            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Preencha usuário e senha!", Toast.LENGTH_SHORT).show()
            } else {
                iniciarLoginTurbo(user, pass)
            }
        }

        binding.etUsername.requestFocus()
    }

    private fun iniciarAnimacaoPontinhos() {
        dotsJob = object : Runnable {
            override fun run() {
                dotsCount = (dotsCount + 1) % 4
                try { binding.tvLoadingDots?.text = ".".repeat(dotsCount) } catch (e: Exception) {}
                dotsHandler.postDelayed(this, 400)
            }
        }
        dotsHandler.post(dotsJob!!)
    }

    private fun pararAnimacaoPontinhos() {
        dotsJob?.let { dotsHandler.removeCallbacks(it) }
        dotsJob = null
    }

    private fun mostrarLoading() {
        binding.btnLogin.isEnabled = false
        binding.etUsername.isEnabled = false
        binding.etPassword.isEnabled = false
        try { binding.layoutLoading?.visibility = View.VISIBLE } catch (e: Exception) {
            binding.progressBar.visibility = View.VISIBLE
        }
        iniciarAnimacaoPontinhos()
    }

    private fun esconderLoading() {
        pararAnimacaoPontinhos()
        try { binding.layoutLoading?.visibility = View.GONE } catch (e: Exception) {
            binding.progressBar.visibility = View.GONE
        }
        binding.btnLogin.isEnabled = true
        binding.etUsername.isEnabled = true
        binding.etPassword.isEnabled = true
    }

    // ── Fluxo para usuário já logado ──────────────────────────────────────────
    private fun verificarEIniciarRapido(dns: String, user: String, pass: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            val temConteudo = db.streamDao().getVodCount() > 0

            withContext(Dispatchers.Main) {
                if (temConteudo) {
                    decidirProximaTela()
                    launch(Dispatchers.IO) {
                        preCarregarLoteMinimo(dns, user, pass)
                    }
                } else {
                    launch(Dispatchers.IO) {
                        preCarregarLoteMinimo(dns, user, pass)
                        withContext(Dispatchers.Main) { decidirProximaTela() }
                    }
                }
            }
        }
    }

    // ── Fluxo de login novo ───────────────────────────────────────────────────
    private fun iniciarLoginTurbo(user: String, pass: String) {
        mostrarLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            var dnsVencedor: String? = null

            try {
                val canal = Channel<String>(Channel.UNLIMITED)
                val jobs = SERVERS.map { url ->
                    launch(Dispatchers.IO) {
                        val r = testarServidor(url, user, pass, clientRapido)
                        if (r != null) canal.trySend(r)
                    }
                }
                dnsVencedor = withTimeoutOrNull(18_000L) { canal.receive() }
                jobs.forEach { it.cancel() }
                canal.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // ✅ Fallback agora usa a MESMA lista (SERVERS), só que com o
            // client mais tolerante (clientLento: timeout maior e retry
            // ativado) — pra dar uma segunda chance aos mesmos 7 DNS reais
            // antes de desistir, em vez de testar servidores que você não
            // usa mais.
            if (dnsVencedor == null) {
                for (servidor in SERVERS) {
                    val r = testarServidor(servidor, user, pass, clientLento)
                    if (r != null) { dnsVencedor = r; break }
                }
            }

            if (dnsVencedor != null) {
                val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
                val usuarioAnterior = prefs.getString("username", null)
                if (usuarioAnterior != null && usuarioAnterior != user) {
                    limparBancoPorTrocaDeUsuario()
                }

                val dnsFinal = normalizarBaseUrl(dnsVencedor)
                salvarCredenciais(dnsFinal, user, pass)

                // ── CORREÇÃO: dispara ContentRepository ANTES de navegar ───────
                // O usuário verá a ProfilesActivity enquanto os dados carregam em
                // background. Quando ele clicar no perfil e a HomeActivity abrir,
                // ContentRepository.pronto já será true (ou estará muito próximo).
                ContentRepository.recarregar(applicationContext)

                // Pré-carrega lote mínimo no banco em paralelo (sem bloquear navegação)
                launch(Dispatchers.IO) {
                    preCarregarLoteMinimo(dnsFinal, user, pass)
                }

                withContext(Dispatchers.Main) {
                    pararAnimacaoPontinhos()
                    val intent = Intent(this@LoginActivity, ProfilesActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }

            } else {
                withContext(Dispatchers.Main) {
                    esconderLoading()
                    mostrarErro("Servidor não encontrado. Verifique login e senha.")
                }
            }
        }
    }

    private fun testarServidor(baseUrl: String, user: String, pass: String, httpClient: OkHttpClient): String? {
        val urlBase = normalizarBaseUrl(baseUrl)
        val urlSemBarra = urlBase.removeSuffix("/")
        return try {
            val request = Request.Builder()
                .url("$urlSemBarra/player_api.php?username=$user&password=$pass")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val valido = body.contains("user_info") &&
                            body.contains("server_info") &&
                            !body.contains("\"auth\":0") &&
                            !body.contains("\"auth\": 0") &&
                            !body.contains("\"auth\":\"0\"") &&
                            !body.contains("\"status\":\"Disabled\"") &&
                            !body.contains("\"status\":\"Expired\"")
                    if (valido) urlBase else null
                } else null
            }
        } catch (e: Exception) { null }
    }

    private suspend fun preCarregarLoteMinimo(dns: String, user: String, pass: String) {
        val base = normalizarBaseUrl(dns)

        withTimeoutOrNull(20_000L) {
            try {
                val db = AppDatabase.getDatabase(applicationContext)

                coroutineScope {
                    val jVod = async(Dispatchers.IO) {
                        buscarJsonLimitado(
                            url = "${base}player_api.php?username=$user&password=$pass&action=get_vod_streams",
                            maxBytes = 300_000
                        )?.let { json ->
                            try {
                                val arr = JSONArray(json)
                                val batch = mutableListOf<VodEntity>()
                                for (i in 0 until minOf(12, arr.length())) {
                                    val o = arr.getJSONObject(i)
                                    batch.add(VodEntity(
                                        o.optInt("stream_id"),
                                        o.optString("name"),
                                        o.optString("name"),
                                        o.optString("stream_icon"),
                                        o.optString("container_extension"),
                                        o.optString("rating"),
                                        o.optString("category_id"),
                                        o.optLong("added")
                                    ))
                                }
                                if (batch.isNotEmpty()) {
                                    db.streamDao().insertVodStreams(batch)
                                    // Notifica o ContentRepository dos novos dados
                                    val atualizados = db.streamDao().getRecentVods(200)
                                    ContentRepository.atualizarVods(atualizados)
                                }
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                    }

                    val jSeries = async(Dispatchers.IO) {
                        buscarJsonLimitado(
                            url = "${base}player_api.php?username=$user&password=$pass&action=get_series",
                            maxBytes = 300_000
                        )?.let { json ->
                            try {
                                val arr = JSONArray(json)
                                val batch = mutableListOf<SeriesEntity>()
                                for (i in 0 until minOf(12, arr.length())) {
                                    val o = arr.getJSONObject(i)
                                    batch.add(SeriesEntity(
                                        o.optInt("series_id"),
                                        o.optString("name"),
                                        o.optString("cover"),
                                        o.optString("rating"),
                                        o.optString("category_id"),
                                        o.optLong("last_modified")
                                    ))
                                }
                                if (batch.isNotEmpty()) {
                                    db.streamDao().insertSeriesStreams(batch)
                                    val atualizadas = db.streamDao().getRecentSeries(200)
                                    ContentRepository.atualizarSeries(atualizadas)
                                }
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                    }

                    jVod.await()
                    jSeries.await()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun buscarJsonLimitado(url: String, maxBytes: Int = 300_000): String? {
        return try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout    = 12_000
                requestMethod  = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                setRequestProperty("Accept", "application/json")
            }

            if (conn.responseCode != 200) { conn.disconnect(); return null }

            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val sb = StringBuilder()
                val buffer = CharArray(8192)
                var totalLido = 0
                var lido: Int
                while (reader.read(buffer).also { lido = it } != -1) {
                    sb.append(buffer, 0, lido)
                    totalLido += lido
                    if (totalLido >= maxBytes) break
                }
                conn.disconnect()
                sb.toString().takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun limparBancoPorTrocaDeUsuario() {
        try {
            val db = AppDatabase.getDatabase(applicationContext)
            withContext(Dispatchers.IO) {
                db.streamDao().clearLive()
                db.openHelper.writableDatabase.execSQL("DELETE FROM vod_streams")
                db.openHelper.writableDatabase.execSQL("DELETE FROM series_streams")
                db.openHelper.writableDatabase.execSQL("DELETE FROM watch_history")
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun normalizarBaseUrl(dns: String): String {
        var url = dns.trim()
        if (url.contains("player_api.php")) url = url.substringBefore("player_api.php")
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        if (!url.endsWith("/")) url += "/"
        return url
    }

    private fun salvarCredenciais(dns: String, user: String, pass: String) {
        getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit().apply {
            putString("dns", dns)
            putString("username", user)
            putString("password", pass)
            apply()
        }
        XtreamApi.salvarDns(this, dns)
    }

    // ✅ COMPORTAMENTO (estilo Netflix), usando SessionManager:
    //
    //   - Se a sessão ainda está ativa neste processo (ou seja, o app nunca
    //     chegou a ser encerrado de verdade desde a última vez que um perfil
    //     foi selecionado) E já existe um perfil salvo → pula direto pro
    //     Home... mas AGORA respeitando QUAL perfil estava ativo:
    //     se era o perfil Infantil, pula pra KidsActivity, não pra Home.
    //
    //   - Se o processo é novo (app foi fechado/matou/celular reiniciou),
    //     SessionManager.sessaoAtiva nasce `false` de novo → força passar
    //     pela tela de seleção de perfil, mesmo que já exista perfil salvo.
    //
    // ✅ CORREÇÃO CRÍTICA (perfil Infantil caindo no perfil adulto): antes,
    // esse método só verificava SE havia perfil salvo, mas sempre montava o
    // Intent apontando pra HomeActivity — mesmo quando o `perfilSalvo` era
    // "Infantil"/"Kids". Era esse o motivo de "fechar o app e reabrir" (ou
    // qualquer caminho que passasse de novo pela LoginActivity com a sessão
    // ainda viva) levar direto pro perfil adulto, mesmo tendo saído no
    // perfil das crianças. Agora, igual ao roteamento já usado em
    // SettingsActivity.executarTrocaPerfil() e ProfilesActivity, o nome do
    // perfil salvo é checado e o destino é escolhido de acordo.
    private fun decidirProximaTela() {
        val prefs       = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val perfilSalvo = prefs.getString("last_profile_name", null)
        val iconeSalvo  = prefs.getString("last_profile_icon", null)

        val ehPerfilInfantilSalvo = perfilSalvo?.contains("infantil", ignoreCase = true) == true ||
                                    perfilSalvo?.contains("kids", ignoreCase = true) == true

        val intent = when {
            isTelevisionDevice() -> Intent(this, HomeActivity::class.java).apply {
                putExtra("PROFILE_NAME", "TV_Box")
            }
            SessionManager.sessaoAtiva && !perfilSalvo.isNullOrBlank() -> {
                val destino = if (ehPerfilInfantilSalvo) KidsActivity::class.java else HomeActivity::class.java
                Intent(this, destino).apply {
                    putExtra("PROFILE_NAME", perfilSalvo)
                    putExtra("PROFILE_ICON", iconeSalvo ?: "")
                }
            }
            else -> Intent(this, ProfilesActivity::class.java)
        }

        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun mostrarErro(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        pararAnimacaoPontinhos()
        super.onDestroy()
    }
}
