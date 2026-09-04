package com.vltv.play

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * EscudoHelper
 *
 * Resolve o escudo (imagem) de clubes de futebol a partir do nome do time.
 *
 * Diferente do BandeiraHelper (que retorna um emoji, ou seja, texto puro),
 * escudo é uma IMAGEM — não existe "emoji de escudo". Por isso aqui não
 * guardamos um arquivo fixo, e sim o título do artigo na Wikipédia em
 * português. Em tempo de execução, buscamos a imagem atual do infobox
 * daquele artigo via API REST oficial da Wikipédia (page/summary), que
 * sempre aponta pro escudo vigente do clube — sem precisar de API paga
 * e sem precisar adivinhar nome de arquivo (isso é frágil e dá escudo errado).
 *
 * O resultado é cacheado em memória (igual ao padrão do BannerAssets),
 * então cada time só é buscado uma vez por sessão do app.
 *
 * USO:
 *   val url = EscudoHelper.buscarEscudoUrl("Flamengo")
 *   if (url != null) Glide.with(context).load(url).into(imageView)
 *
 *   // Para compor imagens (ex: ConfrontoImageHelper), use:
 *   val bitmap = EscudoHelper.buscarEscudoBitmap("Flamengo")
 */
object EscudoHelper {

    // Mapa: nome em português (lowercase, sem acento de busca) → título do artigo na Wikipédia PT
    private val artigosWikipedia = mapOf(
        // Brasileirão Série A 2026
        "palmeiras"          to "Sociedade Esportiva Palmeiras",
        "flamengo"           to "Clube de Regatas do Flamengo",
        "cruzeiro"           to "Cruzeiro Esporte Clube",
        "botafogo"           to "Botafogo de Futebol e Regatas",
        "fluminense"         to "Fluminense Football Club",
        "sao paulo"          to "São Paulo Futebol Clube",
        "são paulo"          to "São Paulo Futebol Clube",
        "bragantino"         to "Red Bull Bragantino",
        "red bull bragantino" to "Red Bull Bragantino",
        "corinthians"        to "Sport Club Corinthians Paulista",
        "gremio"             to "Grêmio Foot-Ball Porto Alegrense",
        "grêmio"             to "Grêmio Foot-Ball Porto Alegrense",
        "vasco"              to "Club de Regatas Vasco da Gama",
        "vasco da gama"      to "Club de Regatas Vasco da Gama",
        "atletico mineiro"   to "Clube Atlético Mineiro",
        "atlético mineiro"   to "Clube Atlético Mineiro",
        "atletico-mg"        to "Clube Atlético Mineiro",
        "atlético-mg"        to "Clube Atlético Mineiro",
        "internacional"      to "Sport Club Internacional",
        "santos"             to "Santos Futebol Clube",
        "bahia"              to "Esporte Clube Bahia",
        "vitoria"            to "Esporte Clube Vitória",
        "vitória"            to "Esporte Clube Vitória",
        "mirassol"           to "Mirassol Futebol Clube",
        "coritiba"           to "Coritiba Foot Ball Club",
        "athletico-pr"       to "Club Athletico Paranaense",
        "athletico paranaense" to "Club Athletico Paranaense",
        "chapecoense"        to "Associação Chapecoense de Futebol",
        "remo"               to "Clube do Remo",

        // Brasileirão Série B 2026 (times que não estão na Série A)
        "sport"              to "Sport Club do Recife",
        "sport recife"       to "Sport Club do Recife",
        "juventude"          to "Esporte Clube Juventude",
        "fortaleza"          to "Fortaleza Esporte Clube",
        "ceara"              to "Ceará Sporting Club",
        "ceará"              to "Ceará Sporting Club",
        "goias"              to "Goiás Esporte Clube",
        "goiás"              to "Goiás Esporte Clube",
        "ponte preta"        to "Associação Atlética Ponte Preta",
        "nautico"            to "Clube Náutico Capibaribe",
        "náutico"            to "Clube Náutico Capibaribe",
        "londrina"           to "Londrina Esporte Clube",
        "sao bernardo"       to "São Bernardo Futebol Clube",
        "são bernardo"       to "São Bernardo Futebol Clube",
        "crb"                to "Clube de Regatas Brasil",
        "america mineiro"    to "América Futebol Clube (Belo Horizonte)",
        "américa mineiro"    to "América Futebol Clube (Belo Horizonte)",
        "america-mg"         to "América Futebol Clube (Belo Horizonte)",
        "avai"               to "Avaí Futebol Clube",
        "avaí"               to "Avaí Futebol Clube",
        "botafogo-sp"        to "Botafogo Futebol Clube (Ribeirão Preto)",
        "operario-pr"        to "Operário Ferroviário Esporte Clube",
        "operário-pr"        to "Operário Ferroviário Esporte Clube",
        "novorizontino"      to "Grêmio Novorizontino",
        "athletic club"      to "Athletic Club (Minas Gerais)",
        "amazonas"           to "Amazonas Futebol Clube",
        "volta redonda"      to "Volta Redonda Futebol Clube",
        "vila nova"          to "Vila Nova Futebol Clube",
        "cuiaba"             to "Cuiabá Esporte Clube",
        "cuiabá"             to "Cuiabá Esporte Clube",
        "criciuma"           to "Criciúma Esporte Clube",
        "criciúma"           to "Criciúma Esporte Clube",

        // Copa do Brasil (clubes brasileiros comuns que não estão em A/B acima)
        "atletico-go"        to "Atlético Clube Goianiense",
        "atlético-go"        to "Atlético Clube Goianiense",
        "atletico goianiense" to "Atlético Clube Goianiense",
        "paysandu"           to "Paysandu Sport Club",
        "ferroviaria"        to "Associação Ferroviária de Esportes",
        "ferroviária"        to "Associação Ferroviária de Esportes",
        "csa"                to "Centro Sportivo Alagoano",
        "abc"                to "ABC Futebol Clube",
        "confianca"          to "Associação Desportiva Confiança",
        "confiança"          to "Associação Desportiva Confiança",

        // Libertadores / Sul-Americana (clubes fora do Brasil, mais comuns)
        "river plate"        to "Club Atlético River Plate",
        "boca juniors"       to "Club Atlético Boca Juniors",
        "racing"             to "Racing Club",
        "independiente"      to "Club Atlético Independiente",
        "san lorenzo"        to "Club Atlético San Lorenzo de Almagro",
        "velez sarsfield"    to "Club Atlético Vélez Sarsfield",
        "vélez sarsfield"    to "Club Atlético Vélez Sarsfield",
        "estudiantes"        to "Club Estudiantes de La Plata",
        "talleres"           to "Club Atlético Talleres",
        "penarol"            to "Club Atlético Peñarol",
        "peñarol"            to "Club Atlético Peñarol",
        "nacional"           to "Club Nacional de Football",
        "colo-colo"          to "Club Social y Deportivo Colo-Colo",
        "universidad de chile" to "Club Universidad de Chile",
        "universidad catolica" to "Club Deportivo Universidad Católica",
        "universidad católica" to "Club Deportivo Universidad Católica",
        "libertad"           to "Club Libertad",
        "cerro porteno"      to "Club Cerro Porteño",
        "cerro porteño"      to "Club Cerro Porteño",
        "olimpia"            to "Club Olimpia",
        "the strongest"      to "Club The Strongest",
        "bolivar"            to "Club Bolívar",
        "bolívar"            to "Club Bolívar",
        "emelec"             to "Club Sport Emelec",
        "barcelona sc"       to "Barcelona Sporting Club",
        "liga de quito"      to "Liga Deportiva Universitaria de Quito",
        "millonarios"        to "Millonarios Fútbol Club",
        "atletico nacional"  to "Atlético Nacional",
        "atlético nacional"  to "Atlético Nacional",
        "junior barranquilla" to "Club Deportivo Popular Junior",
        "sporting cristal"   to "Club Sporting Cristal",
        "alianza lima"       to "Club Alianza Lima",
        "rosario central"    to "Club Atlético Rosario Central",
        "independiente del valle" to "Independiente del Valle",
        "cusco"              to "Cusco Fútbol Club",
        "universitario"      to "Club Universitario de Deportes",
        "carabobo"           to "Carabobo Fútbol Club",
        "deportivo la guaira" to "Deportivo La Guaira",
        "platense"           to "Club Atlético Platense",
        "coquimbo unido"     to "Club de Deportes Coquimbo Unido",
        "independiente santa fe" to "Independiente Santa Fe",
        "santa fe"           to "Independiente Santa Fe",
        "deportes tolima"    to "Deportes Tolima",
        "independiente medellin" to "Independiente Medellín",
        "independiente medellín" to "Independiente Medellín",
        "o'higgins"          to "Club Deportivo O'Higgins",
        "ohiggins"           to "Club Deportivo O'Higgins",
        "universidad central" to "Universidad Central Fútbol Club"
    )

    // Cache em memória: nome buscado → URL do escudo (ou null se não encontrado)
    private val cache = ConcurrentHashMap<String, String?>()

    // Cache em memória: URL do escudo → Bitmap já baixado e decodificado
    private val bitmapCache = ConcurrentHashMap<String, Bitmap?>()

    private val client = SharedHttpClient.client

    /**
     * Busca a URL da imagem do escudo do time. Faz chamada de rede na primeira
     * vez; das próximas, usa o cache em memória. Deve ser chamada de uma
     * coroutine (é suspend).
     *
     * @return URL da imagem, ou null se o time não foi encontrado no mapa
     * ou a Wikipédia não retornou imagem para o artigo.
     */
    suspend fun buscarEscudoUrl(nomeTime: String): String? {
        val chave = normalizar(nomeTime)
        val artigo = resolverArtigo(chave) ?: return null

        cache[artigo]?.let { return it }
        // Também cobre o caso em que já buscamos esse artigo e confirmamos null
        if (cache.containsKey(artigo)) return null

        val url = withContext(Dispatchers.IO) { buscarThumbnailWikipedia(artigo) }
        cache[artigo] = url
        return url
    }

    /**
     * Busca o escudo do time já como Bitmap decodificado (URL + download + decode).
     * Útil para composição de imagens (ex: ConfrontoImageHelper), onde precisamos
     * do bitmap em memória e não apenas da URL para um ImageView/Glide.
     *
     * Cacheia o bitmap por URL, então times repetidos em confrontos diferentes
     * não geram download duplicado.
     *
     * @return Bitmap do escudo, ou null se o time não foi encontrado ou o
     * download/decode falhar.
     */
    suspend fun buscarEscudoBitmap(nomeTime: String): Bitmap? {
        val url = buscarEscudoUrl(nomeTime) ?: return null

        bitmapCache[url]?.let { return it }
        if (bitmapCache.containsKey(url)) return null

        val bitmap = withContext(Dispatchers.IO) { baixarBitmap(url) }
        bitmapCache[url] = bitmap
        return bitmap
    }

    /**
     * Versão que não suspende — dispara a busca e entrega o resultado via callback.
     * Útil em locais que ainda não usam coroutines diretamente.
     *
     * ATENÇÃO: usa GlobalScope. Prefira chamar buscarEscudoUrl() diretamente
     * de um CoroutineScope amarrado ao ciclo de vida (lifecycleScope, viewModelScope)
     * sempre que possível, para evitar vazamento de coroutines.
     */
    fun buscarEscudoUrlAsync(nomeTime: String, onResult: (String?) -> Unit) {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            val url = buscarEscudoUrl(nomeTime)
            onResult(url)
        }
    }

    private fun resolverArtigo(chave: String): String? {
        artigosWikipedia[chave]?.let { return it }
        return artigosWikipedia.entries.firstOrNull { (k, _) -> chave.contains(k) || k.contains(chave) }?.value
    }

    private fun normalizar(nome: String): String {
        return nome.lowercase().trim()
            .replace("á", "a").replace("â", "a").replace("ã", "a")
            .replace("é", "e").replace("ê", "e")
            .replace("í", "i")
            .replace("ó", "o").replace("ô", "o").replace("õ", "o")
            .replace("ú", "u")
            .replace("ç", "c")
    }

    private fun buscarThumbnailWikipedia(artigo: String): String? {
        return try {
            val artigoCodificado = java.net.URLEncoder.encode(artigo.replace(" ", "_"), "UTF-8")
            val url = "https://pt.wikipedia.org/api/rest_v1/page/summary/$artigoCodificado"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "VLTVPlay/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val thumbnail = json.optJSONObject("thumbnail") ?: return null
                thumbnail.optString("source").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun baixarBitmap(url: String): Bitmap? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "VLTVPlay/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            null
        }
    }
}
