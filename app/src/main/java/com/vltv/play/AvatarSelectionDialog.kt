package com.vltv.play.ui

import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vltv.play.R

/**
 * AvatarSelectionDialog — v4 (2026)
 * - Catálogo totalmente renovado: personagens atuais de Marvel, DC, Disney/Pixar,
 *   Star Wars, Séries, Ação, Anime e um novo grupo Infantil (desenhos).
 * - Corrige o bug de enquadramento: antes a imagem era esticada em setBounds()
 *   direto no view, distorcendo e "cortando" pôsteres que não eram quadrados.
 *   Agora fazemos um center-crop de verdade (com leve viés para o topo em
 *   imagens retrato, onde o rosto do personagem costuma estar), então o avatar
 *   sempre aparece inteiro, centralizado e sem esticar.
 */
class AvatarSelectionDialog(
    context: Context,
    private val onAvatarSelected: (String) -> Unit
) : Dialog(context) {

    // ─── Modelo ────────────────────────────────────────────────────────────────

    data class AvatarItem(
        val id: String,
        val nome: String,
        val drawableRes: Int,
        val categoria: String
    )

    // ─── Catálogo completo (atualizado 2026) ───────────────────────────────────

    private val todosAvatares = listOf(

        // ── Marvel ──────────────────────────────────────────────────────────
        AvatarItem("av_spider_man",       "Homem-Aranha",          R.drawable.av_spider_man,       "Marvel"),
        AvatarItem("av_deadpool",         "Deadpool",              R.drawable.av_deadpool,         "Marvel"),
        AvatarItem("av_wolverine",        "Wolverine",             R.drawable.av_wolverine,        "Marvel"),
        AvatarItem("av_capitao_america",  "Capitão América",       R.drawable.av_capitao_america,  "Marvel"),
        AvatarItem("av_thunderbolts",     "Thunderbolts",          R.drawable.av_thunderbolts,     "Marvel"),
        AvatarItem("av_quarteto_fantastico","Quarteto Fantástico", R.drawable.av_quarteto_fantastico,"Marvel"),
        AvatarItem("av_loki",             "Loki",                  R.drawable.av_loki,             "Marvel"),
        AvatarItem("av_wanda",            "Wanda",                 R.drawable.av_wanda,            "Marvel"),
        AvatarItem("av_venom",            "Venom",                 R.drawable.av_venom,            "Marvel"),
        AvatarItem("av_x_men",            "X-Men",                 R.drawable.av_x_men,            "Marvel"),

        // ── DC ──────────────────────────────────────────────────────────────
        AvatarItem("av_superman",         "Superman",              R.drawable.av_superman,         "DC"),
        AvatarItem("av_batman",           "Batman",                R.drawable.av_batman,           "DC"),
        AvatarItem("av_wonder_woman",     "Mulher Maravilha",      R.drawable.av_wonder_woman,     "DC"),
        AvatarItem("av_harley_quinn",     "Arlequina",             R.drawable.av_harley_quinn,     "DC"),
        AvatarItem("av_joker",            "Coringa",               R.drawable.av_joker,            "DC"),
        AvatarItem("av_penguim",          "Pinguim",               R.drawable.av_penguim,          "DC"),
        AvatarItem("av_peacemaker",       "Peacemaker",            R.drawable.av_peacemaker,       "DC"),
        AvatarItem("av_supergirl",        "Supergirl",             R.drawable.av_supergirl,        "DC"),
        AvatarItem("av_lanterna_verde",   "Lanterna Verde",        R.drawable.av_lanterna_verde,   "DC"),
        AvatarItem("av_creature_commandos","Creature Commandos",   R.drawable.av_creature_commandos,"DC"),

        // ── Disney / Pixar ───────────────────────────────────────────────────
        AvatarItem("av_moana",            "Moana",                 R.drawable.av_moana,            "Disney"),
        AvatarItem("av_elsa",             "Elsa",                  R.drawable.av_elsa,             "Disney"),
        AvatarItem("av_asha",             "Asha",                  R.drawable.av_asha,             "Disney"),
        AvatarItem("av_joy",              "Alegria",               R.drawable.av_joy,              "Disney"),
        AvatarItem("av_mufasa",           "Mufasa",                R.drawable.av_mufasa,           "Disney"),
        AvatarItem("av_judy_hopps",       "Judy Hopps",            R.drawable.av_judy_hopps,       "Disney"),
        AvatarItem("av_elio",             "Elio",                  R.drawable.av_elio,             "Disney"),
        AvatarItem("av_stitch",           "Stitch",                R.drawable.av_stitch,           "Disney"),
        AvatarItem("av_encanto",          "Mirabel",               R.drawable.av_encanto,          "Disney"),
        AvatarItem("av_luca",             "Luca",                  R.drawable.av_luca,             "Disney"),

        // ── Star Wars ────────────────────────────────────────────────────────
        AvatarItem("av_ahsoka",           "Ahsoka",                R.drawable.av_ahsoka,           "Star Wars"),
        AvatarItem("av_mandalorian",      "Mandalorian",           R.drawable.av_mandalorian,      "Star Wars"),
        AvatarItem("av_grogu",            "Grogu",                 R.drawable.av_grogu,            "Star Wars"),
        AvatarItem("av_andor",            "Cassian Andor",         R.drawable.av_andor,            "Star Wars"),
        AvatarItem("av_boba_fett",        "Boba Fett",             R.drawable.av_boba_fett,        "Star Wars"),
        AvatarItem("av_skeleton_crew",    "Skeleton Crew",         R.drawable.av_skeleton_crew,    "Star Wars"),
        AvatarItem("av_darth_vader",      "Darth Vader",           R.drawable.av_darth_vader,      "Star Wars"),
        AvatarItem("av_rey",              "Rey",                   R.drawable.av_rey,              "Star Wars"),
        AvatarItem("av_kylo_ren",         "Kylo Ren",              R.drawable.av_kylo_ren,         "Star Wars"),
        AvatarItem("av_acolyte",          "The Acolyte",           R.drawable.av_acolyte,          "Star Wars"),

        // ── Séries ───────────────────────────────────────────────────────────
        AvatarItem("av_stranger_things",  "Stranger Things",       R.drawable.av_stranger_things,  "Séries"),
        AvatarItem("av_wednesday",        "Wandinha",              R.drawable.av_wednesday,        "Séries"),
        AvatarItem("av_the_last_of_us",   "The Last of Us",        R.drawable.av_the_last_of_us,   "Séries"),
        AvatarItem("av_house_dragon",     "House of the Dragon",   R.drawable.av_house_dragon,     "Séries"),
        AvatarItem("av_the_boys",         "The Boys",              R.drawable.av_the_boys,         "Séries"),
        AvatarItem("av_squid_game",       "Round 6",               R.drawable.av_squid_game,       "Séries"),
        AvatarItem("av_arcane",           "Arcane",                R.drawable.av_arcane,           "Séries"),
        AvatarItem("av_bridgerton",       "Bridgerton",            R.drawable.av_bridgerton,       "Séries"),
        AvatarItem("av_severance",        "Severance",             R.drawable.av_severance,        "Séries"),
        AvatarItem("av_fallout",          "Fallout",               R.drawable.av_fallout,          "Séries"),

        // ── Ação ─────────────────────────────────────────────────────────────
        AvatarItem("av_john_wick",        "John Wick",             R.drawable.av_john_wick,        "Ação"),
        AvatarItem("av_ethan_hunt",       "Ethan Hunt",            R.drawable.av_ethan_hunt,       "Ação"),
        AvatarItem("av_dune",             "Paul Atreides",         R.drawable.av_dune,             "Ação"),
        AvatarItem("av_top_gun",          "Top Gun",               R.drawable.av_top_gun,          "Ação"),
        AvatarItem("av_gladiador",        "Gladiador",             R.drawable.av_gladiador,        "Ação"),
        AvatarItem("av_furiosa",          "Furiosa",               R.drawable.av_furiosa,          "Ação"),
        AvatarItem("av_f1",               "F1",                    R.drawable.av_f1,               "Ação"),
        AvatarItem("av_venganca",         "The Beekeeper",         R.drawable.av_venganca,         "Ação"),
        AvatarItem("av_equalizer",        "Equalizer",             R.drawable.av_equalizer,        "Ação"),
        AvatarItem("av_matrix",           "Matrix",                R.drawable.av_matrix,           "Ação"),

        // ── Anime ─────────────────────────────────────────────────────────────
        AvatarItem("av_gojo",             "Gojo Satoru",           R.drawable.av_gojo,             "Anime"),
        AvatarItem("av_tanjiro",          "Tanjiro",               R.drawable.av_tanjiro,          "Anime"),
        AvatarItem("av_denji",            "Chainsaw Man",          R.drawable.av_denji,            "Anime"),
        AvatarItem("av_luffy",            "Luffy",                 R.drawable.av_luffy,            "Anime"),
        AvatarItem("av_eren",             "Eren Jaeger",           R.drawable.av_eren,             "Anime"),
        AvatarItem("av_anya",             "Anya Forger",           R.drawable.av_anya,             "Anime"),
        AvatarItem("av_deku",             "Deku",                  R.drawable.av_deku,             "Anime"),
        AvatarItem("av_jinwoo",           "Sung Jinwoo",           R.drawable.av_jinwoo,           "Anime"),
        AvatarItem("av_frieren",          "Frieren",               R.drawable.av_frieren,          "Anime"),
        AvatarItem("av_naruto",           "Naruto",                R.drawable.av_naruto,           "Anime"),

        // ── Infantil ─────────────────────────────────────────────────────────
        AvatarItem("av_bluey",            "Bluey",                 R.drawable.av_bluey,            "Infantil"),
        AvatarItem("av_mario",            "Mario",                 R.drawable.av_mario,            "Infantil"),
        AvatarItem("av_sonic",            "Sonic",                 R.drawable.av_sonic,            "Infantil"),
        AvatarItem("av_patrulha_canina",  "Patrulha Canina",       R.drawable.av_patrulha_canina,  "Infantil"),
        AvatarItem("av_gabby",            "Gabby's Dollhouse",     R.drawable.av_gabby,            "Infantil"),
        AvatarItem("av_turma_monica",     "Turma da Mônica",       R.drawable.av_turma_monica,     "Infantil"),
        AvatarItem("av_minions",          "Minions",               R.drawable.av_minions,          "Infantil"),
        AvatarItem("av_pj_masks",         "PJ Masks",              R.drawable.av_pj_masks,         "Infantil"),
        AvatarItem("av_peppa",            "Peppa Pig",             R.drawable.av_peppa,            "Infantil"),
        AvatarItem("av_kung_fu_panda",    "Kung Fu Panda",         R.drawable.av_kung_fu_panda,    "Infantil"),
    )

    // ─── Cores de anel por categoria ──────────────────────────────────────────

    private val ringColorByCat = mapOf(
        "Marvel"    to Color.parseColor("#FF6B6B"),
        "DC"        to Color.parseColor("#4A90D9"),
        "Disney"    to Color.parseColor("#E040FB"),
        "Star Wars" to Color.parseColor("#00B4D8"),
        "Séries"    to Color.parseColor("#27AE60"),
        "Ação"      to Color.parseColor("#E67E22"),
        "Anime"     to Color.parseColor("#FF6EC7"),
        "Infantil"  to Color.parseColor("#FFC107"),
    )

    // ─── Estado ───────────────────────────────────────────────────────────────

    private val categorias = listOf("Todos", "Marvel", "DC", "Disney", "Star Wars", "Séries", "Ação", "Anime", "Infantil")
    private var categoriaAtual = "Todos"
    private var idSelecionado: String? = null

    private var btnConfirmar: TextView? = null
    private var gridAdapter: AvatarGridAdapter? = null
    private val chipViews = mutableMapOf<String, TextView>()

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private val Int.dp: Int get() = (this * context.resources.displayMetrics.density).toInt()
    private val Float.dp: Float get() = (this * context.resources.displayMetrics.density)

    private fun filtrados() =
        if (categoriaAtual == "Todos") todosAvatares
        else todosAvatares.filter { it.categoria == categoriaAtual }

    private fun atualizarBotao() {
        val ativo = idSelecionado != null
        btnConfirmar?.apply {
            isEnabled = ativo
            val bg = background as? android.graphics.drawable.GradientDrawable
            if (ativo) {
                bg?.setColor(Color.WHITE)
                bg?.setStroke(0, Color.TRANSPARENT)
                setTextColor(Color.BLACK)
            } else {
                bg?.setColor(Color.parseColor("#1A1A1A"))
                bg?.setStroke(1.dp, Color.parseColor("#2A2A2A"))
                setTextColor(Color.parseColor("#444444"))
            }
        }
    }

    private fun atualizarChips(selecionada: String) {
        chipViews.forEach { (cat, chip) ->
            val bg = chip.background as? android.graphics.drawable.GradientDrawable
            if (cat == selecionada) {
                bg?.setStroke(2.dp, Color.parseColor("#FFD700"))
                chip.setTextColor(Color.WHITE)
                chip.typeface = Typeface.DEFAULT_BOLD
            } else {
                bg?.setStroke(1.dp, Color.parseColor("#333333"))
                chip.setTextColor(Color.parseColor("#777777"))
                chip.typeface = Typeface.DEFAULT
            }
        }
    }

    // ─── onCreate ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        fun divider() = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 1)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dp, 18.dp, 20.dp, 14.dp)
        }
        header.addView(TextView(context).apply {
            text = "Escolher Avatar"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        header.addView(TextView(context).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.parseColor("#888888"))
            setPadding(16.dp, 8.dp, 4.dp, 8.dp)
            setOnClickListener { dismiss() }
        })

        // Chips de categoria
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        }
        categorias.forEach { cat ->
            val chip = TextView(context).apply {
                text = cat
                textSize = 12f
                setPadding(16.dp, 7.dp, 16.dp, 7.dp)
                setTextColor(Color.parseColor("#777777"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = 20f.dp
                    setStroke(1.dp, Color.parseColor("#333333"))
                }
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    setMargins(4.dp, 0, 4.dp, 0)
                }
                setOnClickListener {
                    categoriaAtual = cat
                    atualizarChips(cat)
                    idSelecionado = null
                    atualizarBotao()
                    gridAdapter?.updateList(filtrados())
                }
            }
            chipViews[cat] = chip
            row.addView(chip)
        }
        atualizarChips("Todos")
        scroll.addView(row)

        // Grid de avatares
        gridAdapter = AvatarGridAdapter(filtrados()) { id ->
            idSelecionado = id
            atualizarBotao()
        }
        val recycler = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            layoutManager = GridLayoutManager(context, 3)
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            clipToPadding = false
            adapter = gridAdapter
            setHasFixedSize(false)
            setItemViewCacheSize(30)
        }

        // Footer / Botão confirmar
        val btn = TextView(context).apply {
            text = "Confirmar Avatar"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isEnabled = false
            setTextColor(Color.parseColor("#444444"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A"))
                cornerRadius = 8f.dp
                setStroke(1.dp, Color.parseColor("#2A2A2A"))
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, 52.dp)
            setOnClickListener {
                val id = idSelecionado ?: return@setOnClickListener
                onAvatarSelected(id)
                dismiss()
            }
        }
        btnConfirmar = btn

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            setPadding(16.dp, 8.dp, 16.dp, 16.dp)
        }
        footer.addView(divider())
        footer.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 8.dp)
        })
        footer.addView(btn)

        root.addView(header)
        root.addView(divider())
        root.addView(scroll)
        root.addView(divider())
        root.addView(recycler)
        root.addView(footer)

        setContentView(root)

        window?.apply {
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#0D0D0D"))
                cornerRadius = 16f.dp
            })
            val p = attributes
            p.width  = (context.resources.displayMetrics.widthPixels  * 0.93).toInt()
            p.height = (context.resources.displayMetrics.heightPixels * 0.85).toInt()
            attributes = p
        }
    }

    // ─── Adapter ──────────────────────────────────────────────────────────────

    inner class AvatarGridAdapter(
        private var list: List<AvatarItem>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<AvatarGridAdapter.VH>() {

        private var selectedPos = -1

        fun updateList(nova: List<AvatarItem>) {
            selectedPos = -1
            list = nova
            notifyDataSetChanged()
        }

        private val AVATAR_SIZE get() = 88.dp
        private val RING_STROKE get() = 3.dp

        inner class VH(val container: LinearLayout) : RecyclerView.ViewHolder(container) {
            val avatarView: AvatarDrawView = container.getChildAt(0) as AvatarDrawView
            val nameText: TextView         = container.getChildAt(1) as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = ViewGroup.MarginLayoutParams(MATCH, WRAP).apply {
                    setMargins(4.dp, 10.dp, 4.dp, 10.dp)
                }
                isClickable = true
                isFocusable = true
            }

            val avatarView = AvatarDrawView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(AVATAR_SIZE, AVATAR_SIZE)
            }

            val nameText = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = 6.dp
                }
                textSize = 10f
                setTextColor(Color.parseColor("#888888"))
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            container.addView(avatarView)
            container.addView(nameText)
            return VH(container)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            val sel  = selectedPos == position
            val ringColor = ringColorByCat[item.categoria] ?: Color.parseColor("#FFD700")

            holder.avatarView.bind(
                drawableRes  = item.drawableRes,
                ringColor    = ringColor,
                isSelected   = sel,
                ringStrokePx = RING_STROKE
            )

            holder.nameText.text = item.nome
            holder.nameText.setTextColor(if (sel) Color.WHITE else Color.parseColor("#888888"))
            holder.nameText.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

            holder.container.setOnClickListener {
                val prev = selectedPos
                selectedPos = holder.adapterPosition
                if (prev >= 0) notifyItemChanged(prev)
                notifyItemChanged(selectedPos)
                onClick(item.id)
            }

            holder.container.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.08f else 1f)
                            .scaleY(if (hasFocus) 1.08f else 1f)
                            .setDuration(120).start()
            }
        }

        override fun getItemCount() = list.size
    }

    // ─── View customizada para desenhar o avatar via Canvas ───────────────────

    /**
     * Desenha:
     *  1. O drawable (pôster/arte do personagem) com CENTER-CROP real dentro do
     *     círculo — nunca esticado, nunca cortado de forma aleatória.
     *  2. Anel colorido da categoria (fino, sempre visível)
     *  3. Anel dourado de seleção (quando selecionado)
     *
     * Correção do bug relatado: antes usávamos bgDrawable.setBounds(0,0,w,h),
     * que ESTICA a imagem inteira para caber no quadrado do view — se a
     * imagem original não fosse quadrada (a maioria dos pôsteres é retrato,
     * 2:3), o resultado distorcia e dava a impressão de personagem cortado ou
     * fora de posição. Agora extraímos o bitmap e recortamos um quadrado
     * central (com viés leve para cima em imagens retrato, já que o rosto do
     * personagem costuma estar na metade superior do pôster), então o
     * personagem sempre aparece inteiro e centralizado.
     */
    inner class AvatarDrawView(ctx: Context) : View(ctx) {

        private var drawableRes  = -1
        private var ringColor    = Color.YELLOW
        private var isSelected   = false
        private var ringStrokePx = 3.dp

        private var bgDrawable: Drawable? = null
        private var cachedBitmap: Bitmap? = null

        // Paint do anel
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }

        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        fun bind(
            drawableRes: Int,
            ringColor: Int,
            isSelected: Boolean,
            ringStrokePx: Int
        ) {
            this.drawableRes  = drawableRes
            this.ringColor    = ringColor
            this.isSelected   = isSelected
            this.ringStrokePx = ringStrokePx
            bgDrawable = ContextCompat.getDrawable(context, drawableRes)
            cachedBitmap = extractBitmap(bgDrawable)
            invalidate()
        }

        /** Converte qualquer Drawable num Bitmap para permitir o recorte manual (srcRect/dstRect). */
        private fun extractBitmap(drawable: Drawable?): Bitmap? {
            if (drawable == null) return null
            if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap

            val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 200
            val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 200
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            return bmp
        }

        override fun onDraw(canvas: Canvas) {
            val w      = width.toFloat()
            val h      = height.toFloat()
            val cx     = w / 2f
            val cy     = h / 2f
            val radius = (w.coerceAtMost(h) / 2f) - ringStrokePx

            // 1. Clip circular + desenha o poster com center-crop real
            val clipPath = Path().apply {
                addCircle(cx, cy, radius, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clipPath)

            val bmp = cachedBitmap
            if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                val bmpW = bmp.width.toFloat()
                val bmpH = bmp.height.toFloat()
                val cropSize = minOf(bmpW, bmpH)

                val srcLeft = (bmpW - cropSize) / 2f
                // Viés para cima em imagens retrato (rosto costuma ficar no terço superior)
                val topBias = if (bmpH > bmpW) 0.18f else 0.5f
                val srcTop = (bmpH - cropSize) * topBias

                val srcRect = Rect(
                    srcLeft.toInt(),
                    srcTop.toInt(),
                    (srcLeft + cropSize).toInt(),
                    (srcTop + cropSize).toInt()
                )
                val dstRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

                canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
            } else {
                // Fallback: sem imagem disponível, preenche com cinza escuro
                canvas.drawColor(Color.parseColor("#1A1A1A"))
            }

            canvas.restore()

            // 2. Anel de seleção dourado OU anel sutil da categoria
            if (isSelected) {
                ringPaint.color       = Color.parseColor("#FFD700")
                ringPaint.strokeWidth = ringStrokePx.toFloat()
                canvas.drawCircle(cx, cy, radius - ringStrokePx / 2f, ringPaint)
            } else {
                ringPaint.color       = ringColor
                ringPaint.strokeWidth = 1.5f.dp
                canvas.drawCircle(cx, cy, radius - 1.dp, ringPaint)
            }
        }
    }

    // ─── Constantes de layout ──────────────────────────────────────────────────

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT
}
