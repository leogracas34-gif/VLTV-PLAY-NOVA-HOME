package com.vltv.play

import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.vltv.play.data.AppDatabase
import com.vltv.play.data.ProfileEntity
import com.vltv.play.data.SeriesEntity
import com.vltv.play.data.VodEntity
import com.vltv.play.databinding.ActivityProfileSelectionBinding
import com.vltv.play.databinding.ItemProfileCircleBinding
import com.vltv.play.ui.AvatarSelectionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URL

class ProfilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileSelectionBinding
    private var isEditMode = false
    private lateinit var adapter: ProfileAdapter
    private val db by lazy { AppDatabase.getDatabase(this) }
    private val listaPerfis = mutableListOf<ProfileEntity>()
    private var isCreating = false
    private val mutex = Mutex()
    private val tmdbApiKey = TmdbConfig.API_KEY

    private val defaultAvatarId1 = "av_iron_man"
    private val defaultAvatarId2 = "av_batman"
    private val defaultAvatarId3 = "av_elsa"
    private val defaultAvatarId4 = "av_naruto"
    private val defaultAvatarIdInfantil = "av_infantil"

    // ══════════════════════════════════════════════════════════════
    // ARCO (PAINEL DE PERFIS) — raio elíptico igual Netflix
    // ══════════════════════════════════════════════════════════════
    private val radioArcoX by lazy { resources.displayMetrics.widthPixels / 2f }
    private val radioArcoY by lazy { dpToPx(36f) }

    // ══════════════════════════════════════════════════════════════
    // BACKGROUND DINÂMICO
    // ══════════════════════════════════════════════════════════════

    private val client = SharedHttpClient.client
    private val handler = Handler(Looper.getMainLooper())

    data class BgItem(
        val backdropUrl: String,
        val title: String,
        val badge: String,
        val badgeIcon: String,
        val tmdbId: Int,
        val mediaType: String
    )

    private val bgItems = mutableListOf<BgItem>()
    private var bgIndex = 0
    private var corAtualPainel = Color.parseColor("#CC000000")
    private var rotacaoIniciada = false
    private var mostrouItemCache = false

    private val BG_INTERVALO_MS  = 5000L
    private val KEN_BURNS_MS     = 8000L
    private val COR_TRANSICAO_MS = 1200L

    private val bgRotator = object : Runnable {
        override fun run() {
            if (bgItems.isNotEmpty()) {
                trocarBackground(bgItems[bgIndex % bgItems.size])
                bgIndex++
            }
            handler.postDelayed(this, BG_INTERVALO_MS)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        aplicarPainelInicial()
        showShimmer(true)
        loadProfilesFromDb()

        carregarUltimoBgItemCache()?.let { itemCache ->
            mostrouItemCache = true
            trocarBackground(itemCache)
        }

        binding.layoutAddProfile.setOnClickListener { addNewProfile() }
        binding.layoutEditProfiles.setOnClickListener {
            isEditMode = !isEditMode
            binding.tvEditProfiles.text = if (isEditMode) "CONCLUÍDO" else "Editar"
            adapter.setEditMode(isEditMode)
        }

        carregarBackgroundsDinamicos()
        iniciarPreCarregamentoEmBackground()
    }

    override fun onResume() {
        super.onResume()
        loadProfilesFromDb()
        if (bgItems.isNotEmpty()) {
            handler.removeCallbacks(bgRotator)
            handler.post(bgRotator)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(bgRotator)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(bgRotator)
        client.dispatcher.cancelAll()
    }

    // ══════════════════════════════════════════════════════════════
    // BACKGROUND DINÂMICO — busca TMDB
    // ══════════════════════════════════════════════════════════════

    private fun carregarBackgroundsDinamicos() {
        val urls = listOf(
            "https://api.themoviedb.org/3/trending/all/week?api_key=$tmdbApiKey&language=pt-BR&region=BR",
            "https://api.themoviedb.org/3/movie/now_playing?api_key=$tmdbApiKey&language=pt-BR&region=BR",
            "https://api.themoviedb.org/3/tv/on_the_air?api_key=$tmdbApiKey&language=pt-BR&region=BR"
        )
        var respostas = 0
        urls.forEachIndexed { idx, url ->
            fetchJson(url) { json ->
                try {
                    val results = json.optJSONArray("results") ?: return@fetchJson
                    for (i in 0 until minOf(results.length(), 5)) {
                        val item     = results.getJSONObject(i)
                        val backdrop = item.optString("backdrop_path")
                        if (backdrop.isBlank()) continue
                        val media  = item.optString("media_type", if (idx == 2) "tv" else "movie")
                        val title  = item.optString("title").ifBlank { item.optString("name") }
                        val tmdbId = item.optInt("id", 0)
                        if (tmdbId == 0) continue

                        val (badge, icon) = when (idx) {
                            0 -> {
                                val tipo = if (media == "movie") "filmes" else "séries"
                                "Top ${i + 1} em $tipo hoje" to "TOP10"
                            }
                            1 -> "Novo • Em cartaz agora" to "NEW"
                            else -> {
                                val d = item.optString("first_air_date")
                                val dataEstreia: java.util.Date? = try {
                                    if (d.length >= 10) {
                                        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                                            .apply { isLenient = false }
                                            .parse(d)
                                    } else null
                                } catch (e: Exception) { null }

                                val ehFutura = dataEstreia != null && dataEstreia.after(java.util.Date())

                                val texto = if (ehFutura) {
                                    val meses = listOf("","jan","fev","mar","abr","mai","jun",
                                                       "jul","ago","set","out","nov","dez")
                                    val p = d.split("-")
                                    val m = p.getOrNull(1)?.toIntOrNull() ?: 0
                                    "Estreia: ${p[2]} de ${meses.getOrElse(m) { "" }}"
                                } else "Episódios novos toda semana"
                                texto to "NEW"
                            }
                        }
                        bgItems.add(BgItem(
                            backdropUrl = VpsConfig.tmdbImage(backdrop, "original"),
                            title       = title,
                            badge       = badge,
                            badgeIcon   = icon,
                            tmdbId      = tmdbId,
                            mediaType   = if (idx == 2) "tv" else media.ifBlank { "movie" }
                        ))
                    }
                } catch (e: Exception) { }
                respostas++
                if (!rotacaoIniciada && bgItems.isNotEmpty()) {
                    rotacaoIniciada = true
                    bgItems.shuffle()
                    if (mostrouItemCache) {
                        handler.postDelayed(bgRotator, BG_INTERVALO_MS)
                    } else {
                        handler.post(bgRotator)
                    }
                }
            }
        }
    }

    private fun trocarBackground(item: BgItem) {
        val img = binding.imgDynamicBackground
        img.animate().cancel()

        img.animate()
            .alpha(0f).setDuration(500)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                Glide.with(this@ProfilesActivity)
                    .asBitmap()
                    .load(item.backdropUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(1280, 720)
                    .into(object : CustomTarget<Bitmap>() {
                        override fun onResourceReady(bitmap: Bitmap, transition: Transition<in Bitmap>?) {
                            img.scaleType = ImageView.ScaleType.CENTER_CROP
                            img.setImageBitmap(bitmap)

                            val screenW = resources.displayMetrics.widthPixels.toFloat()
                            val screenH = resources.displayMetrics.heightPixels.toFloat()
                            val imgRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                            val alturaEscalada = screenW / imgRatio
                            val sobra = (alturaEscalada - screenH).coerceAtLeast(0f)
                            img.translationY = -(sobra * 0.15f)
                            img.scaleX = 1.0f
                            img.scaleY = 1.0f

                            img.animate()
                                .alpha(0.9f).setDuration(700)
                                .setInterpolator(DecelerateInterpolator())
                                .withEndAction {
                                    img.animate()
                                        .scaleX(1.10f).scaleY(1.10f)
                                        .setDuration(KEN_BURNS_MS)
                                        .setInterpolator(LinearInterpolator())
                                        .start()
                                }.start()

                            Palette.from(bitmap).generate { palette ->
                                val corBase = palette?.let {
                                    it.getDominantColor(0).takeIf { c -> c != 0 }
                                        ?: it.getVibrantColor(0).takeIf { c -> c != 0 }
                                        ?: it.getMutedColor(0).takeIf { c -> c != 0 }
                                } ?: Color.parseColor("#1A1A2E")
                                val corEscura = escurecerCor(corBase, 0.30f)
                                animarCorPainel(corAtualPainel, corEscura)
                                corAtualPainel = corEscura
                            }

                            buscarLogoTMDB(item.tmdbId, item.mediaType, item.title)
                            atualizarBadge(item)
                            salvarUltimoBgItemCache(item)
                        }
                        override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) { }
                    })
            }.start()
    }

    private fun salvarUltimoBgItemCache(item: BgItem) {
        try {
            val json = JSONObject().apply {
                put("backdropUrl", item.backdropUrl)
                put("title", item.title)
                put("badge", item.badge)
                put("badgeIcon", item.badgeIcon)
                put("tmdbId", item.tmdbId)
                put("mediaType", item.mediaType)
            }
            getSharedPreferences("vltv_profiles_cache", Context.MODE_PRIVATE).edit()
                .putString("ultimo_bg_item", json.toString())
                .apply()
        } catch (e: Exception) { }
    }

    private fun carregarUltimoBgItemCache(): BgItem? {
        return try {
            val raw = getSharedPreferences("vltv_profiles_cache", Context.MODE_PRIVATE)
                .getString("ultimo_bg_item", null) ?: return null
            val json = JSONObject(raw)
            BgItem(
                backdropUrl = json.getString("backdropUrl"),
                title       = json.getString("title"),
                badge       = json.getString("badge"),
                badgeIcon   = json.getString("badgeIcon"),
                tmdbId      = json.getInt("tmdbId"),
                mediaType   = json.getString("mediaType")
            )
        } catch (e: Exception) { null }
    }

    private fun buscarLogoTMDB(tmdbId: Int, mediaType: String, tituloFallback: String) {
        val tipo = if (mediaType == "tv") "tv" else "movie"
        val url  = "https://api.themoviedb.org/3/$tipo/$tmdbId/images" +
                   "?api_key=$tmdbApiKey&include_image_language=pt-BR,pt,en,null"

        fetchJson(url) { json ->
            try {
                val logos = json.optJSONArray("logos")
                if (logos == null || logos.length() == 0) {
                    mostrarTituloTexto(tituloFallback); return@fetchJson
                }

                var logoPath: String? = null
                for (prefs in listOf("pt" to "BR", "pt" to "", "en" to "")) {
                    for (i in 0 until logos.length()) {
                        val logo = logos.getJSONObject(i)
                        val lang = logo.optString("iso_639_1", "").trim().lowercase()
                        val reg  = logo.optString("iso_3166_1", "").trim().uppercase()
                        val match = lang == prefs.first && (prefs.second.isEmpty() || reg == prefs.second)
                        if (match) {
                            val fp = logo.optString("file_path", "")
                            if (fp.isNotEmpty()) { logoPath = fp; break }
                        }
                    }
                    if (!logoPath.isNullOrEmpty()) break
                }
                if (logoPath.isNullOrEmpty() && logos.length() > 0)
                    logoPath = logos.getJSONObject(0).optString("file_path", "")

                if (!logoPath.isNullOrEmpty()) mostrarLogo(VpsConfig.tmdbImage(logoPath, "w500"))
                else mostrarTituloTexto(tituloFallback)
            } catch (e: Exception) {
                mostrarTituloTexto(tituloFallback)
            }
        }
    }

    private fun mostrarLogo(logoUrl: String) {
        binding.imgDynamicLogo.visibility = View.VISIBLE
        binding.tvDynamicTitle.visibility = View.GONE
        binding.imgDynamicLogo.alpha      = 0f
        Glide.with(this@ProfilesActivity)
            .load(logoUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(binding.imgDynamicLogo)
        binding.imgDynamicLogo.animate()
            .alpha(1f).setStartDelay(200).setDuration(500)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun mostrarTituloTexto(titulo: String) {
        binding.imgDynamicLogo.visibility = View.GONE
        binding.tvDynamicTitle.apply {
            visibility   = View.VISIBLE
            text         = titulo
            translationY = 30f
            alpha        = 0f
            animate().translationY(0f).alpha(1f)
                .setStartDelay(200).setDuration(500)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun atualizarBadge(item: BgItem) {
        binding.tvDynamicBadge.apply {
            text  = item.badge
            alpha = 0f
            animate().alpha(1f).setStartDelay(400).setDuration(400).start()
        }
        when (item.badgeIcon) {
            "TOP10" -> { binding.imgBadgeIcon.setImageResource(R.drawable.ic_top10);      binding.imgBadgeIcon.visibility = View.VISIBLE }
            "NEW"   -> { binding.imgBadgeIcon.setImageResource(R.drawable.ic_new_badge);  binding.imgBadgeIcon.visibility = View.VISIBLE }
            "LIVE"  -> { binding.imgBadgeIcon.setImageResource(R.drawable.ic_live_badge); binding.imgBadgeIcon.visibility = View.VISIBLE }
            else    -> binding.imgBadgeIcon.visibility = View.GONE
        }
        binding.imgBadgeIcon.apply {
            alpha = 0f
            animate().alpha(1f).setStartDelay(400).setDuration(400).start()
        }
    }

    private fun aplicarPainelInicial() {
        val painel = binding.layoutProfilesPanel
        val gradient = GradientDrawable()
        gradient.setColor(corAtualPainel)
        gradient.cornerRadii = floatArrayOf(
            radioArcoX, radioArcoY,
            radioArcoX, radioArcoY,
            0f, 0f,
            0f, 0f
        )
        painel.background = gradient
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    private fun escurecerCor(cor: Int, factor: Float): Int {
        val r = (Color.red(cor)   * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(cor) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(cor)  * factor).toInt().coerceIn(0, 255)
        return Color.argb(230, r, g, b)
    }

    private fun animarCorPainel(corAnterior: Int, corNova: Int) {
        val painel   = binding.layoutProfilesPanel
        val animator = ValueAnimator.ofObject(ArgbEvaluator(), corAnterior, corNova)
        animator.duration     = COR_TRANSICAO_MS
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { anim ->
            val c = anim.animatedValue as Int
            val gradient = GradientDrawable()
            gradient.setColor(c)
            gradient.cornerRadii = floatArrayOf(
                radioArcoX, radioArcoY,
                radioArcoX, radioArcoY,
                0f, 0f,
                0f, 0f
            )
            painel.background = gradient
        }
        animator.start()
    }

    private fun fetchJson(url: String, onResult: (JSONObject) -> Unit) {
        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try { runOnUiThread { onResult(JSONObject(body)) } } catch (e: Exception) { }
            }
        })
    }

    // ══════════════════════════════════════════════════════════════
    // PERFIS
    // ══════════════════════════════════════════════════════════════

    private fun iniciarPreCarregamentoEmBackground() {
        val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
        val dns   = prefs.getString("dns",      null) ?: return
        val user  = prefs.getString("username", null) ?: return
        val pass  = prefs.getString("password", null) ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (db.streamDao().getVodCount() > 0) return@launch
                var base = dns.trim()
                if (base.contains("player_api.php")) base = base.substringBefore("player_api.php")
                if (!base.startsWith("http://") && !base.startsWith("https://")) base = "http://$base"
                if (!base.endsWith("/")) base += "/"

                try {
                    val arr   = org.json.JSONArray(URL("${base}player_api.php?username=$user&password=$pass&action=get_vod_streams").readText())
                    val batch = mutableListOf<VodEntity>()
                    for (i in 0 until minOf(50, arr.length())) {
                        val o = arr.getJSONObject(i)
                        batch.add(VodEntity(stream_id=o.optInt("stream_id"), name=o.optString("name"),
                            title=o.optString("name"), stream_icon=o.optString("stream_icon"),
                            container_extension=o.optString("container_extension"),
                            rating=o.optString("rating"), category_id=o.optString("category_id"),
                            added=o.optLong("added")))
                    }
                    if (batch.isNotEmpty()) db.streamDao().insertVodStreams(batch)
                } catch (e: Exception) { }

                try {
                    val arr   = org.json.JSONArray(URL("${base}player_api.php?username=$user&password=$pass&action=get_series").readText())
                    val batch = mutableListOf<SeriesEntity>()
                    for (i in 0 until minOf(50, arr.length())) {
                        val o = arr.getJSONObject(i)
                        batch.add(SeriesEntity(series_id=o.optInt("series_id"), name=o.optString("name"),
                            cover=o.optString("cover"), rating=o.optString("rating"),
                            category_id=o.optString("category_id"), last_modified=o.optLong("last_modified")))
                    }
                    if (batch.isNotEmpty()) db.streamDao().insertSeriesStreams(batch)
                } catch (e: Exception) { }
            } catch (e: Exception) { }
        }
    }

    private fun showShimmer(show: Boolean) {
        if (show) binding.rvProfiles.alpha = 0f
        else binding.rvProfiles.animate().alpha(1f).setDuration(120)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun setupRecyclerView() {
        adapter = ProfileAdapter(listaPerfis)
        binding.rvProfiles.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvProfiles.adapter       = adapter
        binding.rvProfiles.itemAnimator  = null
    }

    private fun loadProfilesFromDb() {
        if (isCreating) return
        lifecycleScope.launch {
            mutex.withLock {
                val perfis = withContext(Dispatchers.IO) {
                    val lista = db.streamDao().getAllProfiles()

                    lista.map { p ->
                        val pareceKidsPeloNome = p.name.contains("infantil", ignoreCase = true) ||
                                                  p.name.contains("kids", ignoreCase = true)
                        if (pareceKidsPeloNome && !p.isKids) {
                            val corrigido = p.copy(isKids = true)
                            db.streamDao().updateProfile(corrigido)
                            corrigido
                        } else p
                    }
                }
                if (perfis.isEmpty()) createDefaultProfiles()
                else {
                    listaPerfis.clear(); listaPerfis.addAll(perfis)
                    withContext(Dispatchers.Main) {
                        adapter.notifyDataSetChanged()
                        showShimmer(false)
                        animateCardsIn()
                    }
                }
            }
        }
    }

    private fun animateCardsIn() {
        binding.rvProfiles.post {
            val lm = binding.rvProfiles.layoutManager as LinearLayoutManager
            for (i in lm.findFirstVisibleItemPosition()..lm.findLastVisibleItemPosition()) {
                val v = lm.findViewByPosition(i) ?: continue
                v.translationY = 20f; v.alpha = 0f
                v.animate().translationY(0f).alpha(1f)
                    .setStartDelay((i * 15L).coerceAtMost(60L))
                    .setDuration(140).setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    private suspend fun createDefaultProfiles() {
        isCreating = true
        val padrao = listOf(
            ProfileEntity(name = "Perfil 1", imageUrl = defaultAvatarId1),
            ProfileEntity(name = "Perfil 2", imageUrl = defaultAvatarId2),
            ProfileEntity(name = "Infantil", imageUrl = defaultAvatarIdInfantil, isKids = true)
        )
        withContext(Dispatchers.IO) {
            if (db.streamDao().getAllProfiles().isEmpty())
                padrao.forEach { db.streamDao().insertProfile(it) }
        }
        val criados = withContext(Dispatchers.IO) { db.streamDao().getAllProfiles() }
        withContext(Dispatchers.Main) {
            listaPerfis.clear(); listaPerfis.addAll(criados)
            adapter.notifyDataSetChanged(); showShimmer(false); animateCardsIn()
            isCreating = false
        }
    }

    private fun addNewProfile() {
        val bottomSheet = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_novo_perfil, null)
        bottomSheet.setContentView(view)

        val etNome      = view.findViewById<EditText>(R.id.etNovoNome)
        val btnSalvar   = view.findViewById<View>(R.id.btnSalvarNovoPerfil)
        val btnCancelar = view.findViewById<View>(R.id.btnCancelarNovoPerfil)

        btnSalvar.setOnClickListener {
            val nome = etNome.text.toString().trim()
            if (nome.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    db.streamDao().insertProfile(ProfileEntity(name = nome, imageUrl = defaultAvatarId1))
                    withContext(Dispatchers.Main) { loadProfilesFromDb() }
                }
                bottomSheet.dismiss()
            } else {
                etNome.error = "Digite um nome"
            }
        }
        btnCancelar.setOnClickListener { bottomSheet.dismiss() }
        bottomSheet.show()
    }

    private fun abrirEdicaoCompleta(perfil: ProfileEntity) {
        val bottomSheet = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_editar_perfil, null)
        bottomSheet.setContentView(view)

        bottomSheet.behavior.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        bottomSheet.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        )

        val ivAvatar  = view.findViewById<ImageView>(R.id.ivEditarAvatar)
        val ivLapis   = view.findViewById<ImageView>(R.id.ivEditarAvatarLapis)
        val etNome    = view.findViewById<EditText>(R.id.etEditarNomeCompleto)
        val btnSalvar = view.findViewById<View>(R.id.btnSalvarEdicaoCompleta)
        val tvExcluir = view.findViewById<View>(R.id.tvExcluirPerfilCompleto)

        var avatarSelecionado = perfil.imageUrl
        exibirAvatar(ivAvatar, avatarSelecionado)

        etNome.setText(perfil.name)
        etNome.setSelection(perfil.name.length)
        etNome.requestFocus()

        if (perfil.isKids) {
            ivLapis.visibility = View.GONE
            ivAvatar.isClickable = false
        } else {
            ivLapis.visibility = View.VISIBLE
            ivAvatar.isClickable = true
            val abrirSeletorDeAvatar = {
                AvatarSelectionDialog(this) { drawableId ->
                    avatarSelecionado = drawableId
                    exibirAvatar(ivAvatar, avatarSelecionado)
                }.show()
            }
            ivAvatar.setOnClickListener { abrirSeletorDeAvatar() }
            ivLapis.setOnClickListener { abrirSeletorDeAvatar() }
        }

        btnSalvar.setOnClickListener {
            val novoNome = etNome.text.toString().trim()
            if (novoNome.isEmpty()) {
                etNome.error = "Digite um nome"
                return@setOnClickListener
            }
            updateProfileInDb(perfil.copy(name = novoNome, imageUrl = avatarSelecionado))
            bottomSheet.dismiss()
        }

        tvExcluir.setOnClickListener {
            bottomSheet.dismiss()
            confirmarExclusao(perfil)
        }

        bottomSheet.window?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        )?.setBackgroundResource(android.R.color.transparent)

        bottomSheet.show()
    }

    private fun confirmarExclusao(perfil: ProfileEntity) {
        AlertDialog.Builder(this, R.style.AlertDialogThemeDark)
            .setTitle("Excluir perfil")
            .setMessage("Deseja excluir \"${perfil.name}\"? Esta ação não pode ser desfeita.")
            .setPositiveButton("Excluir") { _, _ -> deleteProfile(perfil) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateProfileInDb(perfil: ProfileEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.streamDao().updateProfile(perfil)
            val prefs = getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE)
            if (prefs.getString("last_profile_name", null) == perfil.name) {
                prefs.edit()
                    .putString("last_profile_name", perfil.name)
                    .putString("last_profile_icon", perfil.imageUrl ?: "")
                    .apply()
            }
            withContext(Dispatchers.Main) { loadProfilesFromDb() }
        }
    }

    private fun deleteProfile(perfil: ProfileEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.streamDao().deleteProfile(perfil)
            withContext(Dispatchers.Main) { loadProfilesFromDb() }
        }
    }

    private fun exibirAvatar(imageView: ImageView, drawableId: String?) {
        val resId = when (drawableId) {
            "av_iron_man" -> R.drawable.av_iron_man
            "av_batman" -> R.drawable.av_batman
            "av_elsa" -> R.drawable.av_elsa
            "av_naruto" -> R.drawable.av_naruto
            "av_infantil" -> R.drawable.av_infantil
            else -> if (!drawableId.isNullOrEmpty())
                resources.getIdentifier(drawableId, "drawable", packageName)
            else 0
        }

        val drawable = if (resId != 0)
            ContextCompat.getDrawable(this, resId)
        else
            ContextCompat.getDrawable(this, R.drawable.ic_profile_placeholder)

        if (drawable == null) { imageView.setImageDrawable(null); return }

        val size = imageView.layoutParams?.width
            ?.takeIf { it > 0 }
            ?: (96 * resources.displayMetrics.density).toInt()

        val sourceBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val sourceCanvas = Canvas(sourceBitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(sourceCanvas)

        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint  = Paint(Paint.ANTI_ALIAS_FLAG)

        val raio = size * 0.22f
        val rect = android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawRoundRect(rect, raio, raio, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(sourceBitmap, 0f, 0f, paint)

        val paintBorda = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = size * 0.025f
            color       = Color.parseColor("#33FFFFFF")
        }
        val inset = paintBorda.strokeWidth / 2f
        val rectBorda = android.graphics.RectF(inset, inset, size - inset, size - inset)
        canvas.drawRoundRect(rectBorda, raio, raio, paintBorda)

        imageView.setImageBitmap(output)
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP

        imageView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                if (view.width > 0 && view.height > 0) {
                    outline.setRoundRect(0, 0, view.width, view.height, raio)
                }
            }
        }
        imageView.clipToOutline = true
        imageView.elevation = 6f * resources.displayMetrics.density
    }

    // ══════════════════════════════════════════════════════════════
    // ADAPTER
    // ══════════════════════════════════════════════════════════════

    inner class ProfileAdapter(private val perfis: List<ProfileEntity>) :
        RecyclerView.Adapter<ProfileAdapter.VH>() {

        private var editMode = false
        fun setEditMode(e: Boolean) { editMode = e; notifyDataSetChanged() }

        inner class VH(val b: ItemProfileCircleBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemProfileCircleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val perfil = perfis[position]
            holder.b.tvProfileName.text = perfil.name
            exibirAvatar(holder.b.ivProfileAvatar, perfil.imageUrl)

            if (editMode) {
                holder.b.ivEditOverlay?.visibility = View.VISIBLE
                startWobble(holder.b.root)
            } else {
                holder.b.ivEditOverlay?.visibility = View.GONE
                holder.b.root.clearAnimation()
                holder.b.root.rotation = 0f
            }

            holder.b.root.setOnClickListener {
                if (editMode) { abrirEdicaoCompleta(perfil); return@setOnClickListener }
                it.animate().scaleX(0.90f).scaleY(0.90f).setDuration(80).withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    val icon = perfil.imageUrl ?: ""
                    getSharedPreferences("vltv_prefs", Context.MODE_PRIVATE).edit()
                        .putString("last_profile_name", perfil.name)
                        .putString("last_profile_icon", icon).apply()

                    transicionarParaPerfil(holder.b.ivProfileAvatar, perfil, icon)
                }.start()
            }
        }

        override fun getItemCount() = perfis.size

        private fun startWobble(view: View) {
            AnimatorSet().apply {
                playSequentially(
                    ObjectAnimator.ofFloat(view, "rotation", 0f, -2.5f).apply { duration = 100 },
                    ObjectAnimator.ofFloat(view, "rotation", -2.5f, 2.5f).apply { duration = 200 },
                    ObjectAnimator.ofFloat(view, "rotation", 2.5f, 0f).apply { duration = 100 }
                )
                startDelay = (Math.random() * 120).toLong(); start()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // TRANSIÇÃO DE PERFIL
    // ══════════════════════════════════════════════════════════════
    private fun transicionarParaPerfil(avatarOrigemView: ImageView, perfil: ProfileEntity, icon: String) {
        val ehPerfilInfantil = perfil.isKids

        val rootOverlay = binding.root as ViewGroup

        if (!ehPerfilInfantil) {
            val overlaySimples = View(this).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.BLACK)
                alpha = 0f
            }
            rootOverlay.addView(overlaySimples)
            overlaySimples.animate()
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    SessionManager.marcarSessaoAtiva()
                    startActivity(Intent(this@ProfilesActivity, HomeActivity::class.java).apply {
                        putExtra("PROFILE_NAME", perfil.name)
                        putExtra("PROFILE_ICON", icon)
                    })
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    finish()
                }
                .start()
            return
        }

        val locOrigem = IntArray(2)
        avatarOrigemView.getLocationInWindow(locOrigem)
        val locRoot = IntArray(2)
        rootOverlay.getLocationInWindow(locRoot)
        val origemX = (locOrigem[0] - locRoot[0]).toFloat()
        val origemY = (locOrigem[1] - locRoot[1]).toFloat()
        val larguraOrigem = avatarOrigemView.width.coerceAtLeast(1)
        val alturaOrigem  = avatarOrigemView.height.coerceAtLeast(1)

        val overlay = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            alpha = 0f
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.parseColor("#2E7D32"),
                    Color.parseColor("#F57C00"),
                    Color.parseColor("#E91E8C"),
                    Color.parseColor("#3F51B5")
                )
            )
        }

        val avatarClone = ImageView(this).apply {
            setImageDrawable(avatarOrigemView.drawable)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(larguraOrigem, alturaOrigem).apply {
                leftMargin = origemX.toInt()
                topMargin  = origemY.toInt()
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.18f)
                }
            }
        }
        overlay.addView(avatarClone)

        val tvLogoInfantil = TextView(this).apply {
            text = "INFANTIL"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.08f
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                topMargin = (resources.displayMetrics.heightPixels * 0.62f).toInt()
            }
        }
        overlay.addView(tvLogoInfantil)

        rootOverlay.addView(overlay)

        binding.rvProfiles.animate().alpha(0f).setDuration(180).start()
        binding.tvDynamicTitle.animate().alpha(0f).setDuration(180).start()
        binding.imgDynamicLogo.animate().alpha(0f).setDuration(180).start()
        binding.tvDynamicBadge.animate().alpha(0f).setDuration(180).start()

        overlay.animate().alpha(1f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()

        val tamanhoFinal = (150 * resources.displayMetrics.density).toInt()
        val destinoX = (resources.displayMetrics.widthPixels - tamanhoFinal) / 2f
        val destinoY = (resources.displayMetrics.heightPixels - tamanhoFinal) / 2f - (60 * resources.displayMetrics.density)

        avatarClone.animate()
            .x(destinoX).y(destinoY)
            .scaleX(tamanhoFinal.toFloat() / larguraOrigem)
            .scaleY(tamanhoFinal.toFloat() / alturaOrigem)
            .setStartDelay(70)
            .setDuration(480)
            .setInterpolator(OvershootInterpolator(0.9f))
            .withEndAction {
                tvLogoInfantil.animate().alpha(1f).setDuration(250).start()

                Handler(Looper.getMainLooper()).postDelayed({
                    SessionManager.marcarSessaoAtiva()
                    startActivity(Intent(this@ProfilesActivity, KidsActivity::class.java).apply {
                        putExtra("PROFILE_NAME", perfil.name)
                        putExtra("PROFILE_ICON", icon)
                    })
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    finish()
                }, 450L)
            }
            .start()
    }
}
