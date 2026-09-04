package com.vltv.play.retro

import android.content.Intent
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.vltv.play.R
import com.vltv.play.isTelevisionDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tela "Jogos Retrô" da Home.
 * Busca o catálogo em https://cdn.vltvplay.tech/retro/games.json e exibe em grade.
 *
 * Quando o aparelho é AVANCADO (DeviceTierHelper) E o catálogo tem pelo
 * menos 1 jogo marcado "avancado" no games.json, mostra abas no topo —
 * "Clássicos", "Nintendo 64" e "PS1" — permitindo alternar entre os
 * catálogos sem sair da tela. A troca de aba só refiltra a lista que já
 * está em memória (não busca o games.json de novo).
 *
 * As abas "Nintendo 64" e "PS1" usam a MESMA trava de tier "avancado"
 * (DeviceTierHelper) — a diferença entre elas é só o "core" do jogo
 * (n64 x psx), verificado dentro da lista de avançados.
 *
 * Em aparelhos BASICO, ou quando o catálogo ainda não tem nenhum jogo
 * avançado, as abas ficam ocultas e a tela funciona exatamente como
 * antes — só a lista de jogos básicos, sem nenhuma opção extra. Nesse
 * caso o paddingTop do RecyclerView também é reduzido dinamicamente
 * (ver ajustarPaddingTopRecycler), pra não sobrar um vão vazio no topo
 * reservado pra uma barra de abas que não existe na tela.
 */
class RetroGamesActivity : AppCompatActivity() {

    companion object {
        private const val CATALOG_URL = "https://cdn.vltvplay.tech/retro/games.json"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyView: TextView

    private lateinit var layoutTabs: android.widget.LinearLayout
    private lateinit var tabClassicos: TextView
    private lateinit var tabN64: TextView
    private lateinit var tabPs1: TextView

    private lateinit var deviceTier: RetroDeviceTier

    private var jogosBasico: List<RetroGame> = emptyList()
    private var jogosN64: List<RetroGame> = emptyList()
    private var jogosPs1: List<RetroGame> = emptyList()
    private var abaAtual: String = "basico"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_retro_games)

        recyclerView = findViewById(R.id.recyclerRetroGames)
        progressBar = findViewById(R.id.progressRetroGames)
        emptyView = findViewById(R.id.textRetroEmpty)
        layoutTabs = findViewById(R.id.layoutRetroTabs)
        tabClassicos = findViewById(R.id.tabRetroClassicos)
        tabN64 = findViewById(R.id.tabRetroN64)
        tabPs1 = findViewById(R.id.tabRetroPs1)

        deviceTier = DeviceTierHelper.detectarTier(this)

        val spanCount = if (isTelevisionDevice()) 5 else 3
        recyclerView.layoutManager = GridLayoutManager(this, spanCount)

        tabClassicos.setOnClickListener { selecionarAba("basico") }
        tabN64.setOnClickListener { selecionarAba("n64") }
        tabPs1.setOnClickListener { selecionarAba("ps1") }

        loadCatalog()
    }

    private fun loadCatalog() {
        progressBar.visibility = ProgressBar.VISIBLE
        emptyView.visibility = TextView.GONE
        layoutTabs.visibility = android.view.View.GONE

        CoroutineScope(Dispatchers.Main).launch {
            val resultado = withContext(Dispatchers.IO) { fetchGamesComDiagnostico() }

            progressBar.visibility = ProgressBar.GONE

            when (resultado) {
                is ResultadoCatalogo.Sucesso -> {
                    val todosOsJogos = resultado.games

                    jogosBasico = todosOsJogos.filter { it.tierEfetivo != "avancado" }
                    val avancados = todosOsJogos.filter { it.tierEfetivo == "avancado" }

                    // ✅ Dentro dos "avançados", separa por core: n64 numa
                    // aba, psx na outra. Qualquer outro core avançado que
                    // surgir no futuro simplesmente não aparece em nenhuma
                    // das duas (evita misturar consoles na aba errada).
                    jogosN64 = avancados.filter { it.core.equals("n64", ignoreCase = true) }
                    jogosPs1 = avancados.filter { it.core.equals("psx", ignoreCase = true) }

                    val mostrarTabN64 = jogosN64.isNotEmpty()
                    val mostrarTabPs1 = jogosPs1.isNotEmpty()
                    val mostrarAbas = deviceTier == RetroDeviceTier.AVANCADO && (mostrarTabN64 || mostrarTabPs1)

                    layoutTabs.visibility = if (mostrarAbas) android.view.View.VISIBLE else android.view.View.GONE
                    tabN64.visibility = if (mostrarTabN64) android.view.View.VISIBLE else android.view.View.GONE
                    tabPs1.visibility = if (mostrarTabPs1) android.view.View.VISIBLE else android.view.View.GONE
                    ajustarPaddingTopRecycler(mostrarAbas)

                    abaAtual = "basico"
                    atualizarEstiloAbas()
                    exibirListaDaAbaAtual()
                }
                is ResultadoCatalogo.Erro -> {
                    emptyView.visibility = TextView.VISIBLE
                    emptyView.text = "Erro ao carregar: ${resultado.mensagem}"
                    Toast.makeText(this@RetroGamesActivity, "Erro: ${resultado.mensagem}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Com a barra de abas visível, o RecyclerView precisa do paddingTop
     * grande definido no XML (100dp) pra não ficar embaixo dela. Sem a
     * barra (aparelho básico ou catálogo sem jogos avançados), reduz
     * pra um respiro pequeno — senão sobra um vão vazio no topo sem
     * nenhuma barra pra justificar o espaço.
     */
    private fun ajustarPaddingTopRecycler(mostrarAbas: Boolean) {
        val density = resources.displayMetrics.density
        val paddingTopDp = if (mostrarAbas) 100f else 12f
        val paddingTopPx = (paddingTopDp * density).toInt()
        recyclerView.setPadding(
            recyclerView.paddingLeft,
            paddingTopPx,
            recyclerView.paddingRight,
            recyclerView.paddingBottom
        )
    }

    private fun selecionarAba(aba: String) {
        if (aba == abaAtual) return
        abaAtual = aba
        atualizarEstiloAbas()
        exibirListaDaAbaAtual()
    }

    private fun atualizarEstiloAbas() {
        val selecionado = R.drawable.bg_retro_tab_selected
        val naoSelecionado = R.drawable.bg_retro_tab_unselected
        val branco = android.graphics.Color.WHITE
        val cinza = android.graphics.Color.parseColor("#999999")

        val abas = listOf(tabClassicos to "basico", tabN64 to "n64", tabPs1 to "ps1")
        for ((tab, id) in abas) {
            if (id == abaAtual) {
                tab.setBackgroundResource(selecionado)
                tab.setTextColor(branco)
            } else {
                tab.setBackgroundResource(naoSelecionado)
                tab.setTextColor(cinza)
            }
        }
    }

    private fun exibirListaDaAbaAtual() {
        val lista = when (abaAtual) {
            "n64" -> jogosN64
            "ps1" -> jogosPs1
            else -> jogosBasico
        }

        if (lista.isEmpty()) {
            recyclerView.adapter = null
            emptyView.visibility = TextView.VISIBLE
            emptyView.text = getString(R.string.retro_games_empty)
            return
        }

        emptyView.visibility = TextView.GONE
        recyclerView.adapter = RetroGameAdapter(lista) { game ->
            openGame(game)
        }
    }

    private sealed class ResultadoCatalogo {
        data class Sucesso(val games: List<RetroGame>) : ResultadoCatalogo()
        data class Erro(val mensagem: String) : ResultadoCatalogo()
    }

    private fun fetchGamesComDiagnostico(): ResultadoCatalogo {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(CATALOG_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "GET"
            connection.connect()

            val code = connection.responseCode
            if (code !in 200..299) {
                return ResultadoCatalogo.Erro("HTTP $code ao buscar o catálogo")
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            val type = com.google.gson.reflect.TypeToken.getParameterized(
                List::class.java, RetroGame::class.java
            ).type
            val games: List<RetroGame> = Gson().fromJson(response, type) ?: emptyList()
            ResultadoCatalogo.Sucesso(games)
        } catch (e: java.net.UnknownHostException) {
            ResultadoCatalogo.Erro("DNS/host não encontrado (${e.message})")
        } catch (e: javax.net.ssl.SSLException) {
            ResultadoCatalogo.Erro("Falha de SSL/HTTPS (${e.message})")
        } catch (e: java.net.SocketTimeoutException) {
            ResultadoCatalogo.Erro("Timeout (VPS demorou pra responder)")
        } catch (e: com.google.gson.JsonSyntaxException) {
            ResultadoCatalogo.Erro("JSON inválido no games.json (${e.message})")
        } catch (e: Exception) {
            ResultadoCatalogo.Erro("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun openGame(game: RetroGame) {
        val intent = Intent(this, RetroGamePlayerActivity::class.java).apply {
            putExtra(RetroGamePlayerActivity.EXTRA_ROM_URL, game.romUrl)
            putExtra(RetroGamePlayerActivity.EXTRA_CORE, game.core)
            putExtra(RetroGamePlayerActivity.EXTRA_TITLE, game.name)
            // ✅ Só vai junto quando o jogo tiver bios definido no games.json
            // (hoje, só os jogos de PS1) — pra qualquer outro console, essa
            // extra simplesmente não é adicionada ao Intent.
            game.bios?.let { putExtra(RetroGamePlayerActivity.EXTRA_BIOS, it) }
        }
        startActivity(intent)
    }
}

private fun BufferedReader.readText(): String {
    val sb = StringBuilder()
    var line: String?
    while (this.readLine().also { line = it } != null) {
        sb.append(line)
    }
    return sb.toString()
}
