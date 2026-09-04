# ==============================================================
# VLTV+ — ProGuard / R8 Rules
# Coloque este arquivo em: app/proguard-rules.pro
# ==============================================================

# ── Atributos gerais ──────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes SourceFile,LineNumberTable   # Mantém linha nos stack traces

# ── Kotlin ────────────────────────────────────────────────────
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Lazy {
    <fields>;
    <methods>;
}

# ── Retrofit + OkHttp ─────────────────────────────────────────
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-dontwarn retrofit2.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ── Gson / Modelos JSON ───────────────────────────────────────
# ⚠️ IMPORTANTE: o Gson casa cada chave do JSON com o NOME do campo
# Kotlin via reflection (nenhum desses modelos usa @SerializedName,
# diferente de RetroGame.kt). Se o R8 renomear esses campos, o app
# COMPILA normal mas o login/canais/filmes/séries voltam vazios, sem
# nenhum erro visível. Por isso, ao contrário do resto do app, estes
# modelos específicos precisam manter os nomes de campo originais.
#
# Antes esta regra tentava proteger com.vltv.play.model/api (pacotes
# que não existem neste projeto — não protegiam nada de verdade) e
# com.vltv.play.data inteiro (protegia demais: mantinha até lógica
# como AuthManager sem embaralhar). Agora protege só os campos das
# classes que realmente são alvo de Gson.fromJson/Retrofit — o resto
# do app (Activities, helpers, lógica de negócio) continua 100%
# embaralhado.
#
# 👉 Se no futuro adicionar um novo modelo de resposta de API sem
# @SerializedName, ele PRECISA entrar nesta lista (ou receber
# @SerializedName em cada campo, como já é feito em RetroGame.kt).
-keep class com.vltv.play.XtreamLoginResponse { <fields>; }
-keep class com.vltv.play.UserInfo { <fields>; }
-keep class com.vltv.play.ServerInfo { <fields>; }
-keep class com.vltv.play.LiveCategory { <fields>; }
-keep class com.vltv.play.LiveStream { <fields>; }
-keep class com.vltv.play.VodStream { <fields>; }
-keep class com.vltv.play.SeriesStream { <fields>; }
-keep class com.vltv.play.EpgWrapper { <fields>; }
-keep class com.vltv.play.EpgResponseItem { <fields>; }
-keep class com.vltv.play.SeriesInfoResponse { <fields>; }
-keep class com.vltv.play.EpisodeStream { <fields>; }
-keep class com.vltv.play.EpisodeInfo { <fields>; }
-keep class com.vltv.play.VodInfoResponse { <fields>; }
-keep class com.vltv.play.VodInfoData { <fields>; }
-keep class com.vltv.play.TmdbSearchResponse { <fields>; }
-keep class com.vltv.play.TmdbMovie { <fields>; }
-keep class com.vltv.play.data.TmdbResponse { <fields>; }
-keep class com.vltv.play.data.TmdbPerson { <fields>; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# ── Room Database ─────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }
-keepclassmembers @androidx.room.Entity class * { *; }

# ── Firebase ──────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ── Glide ─────────────────────────────────────────────────────
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.AppGlideModule { *; }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-dontwarn com.bumptech.glide.**

# ── ExoPlayer / Media3 ────────────────────────────────────────
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── Cast Framework ────────────────────────────────────────────
-keep class com.google.android.gms.cast.** { *; }
-keep class com.google.android.gms.cast.framework.** { *; }
-dontwarn com.google.android.gms.cast.**

# ── AndroidX / Material ───────────────────────────────────────
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

# ── Activities / Fragments ────────────────────────────────────
-keep class * extends android.app.Activity
-keep class * extends android.app.Application
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.content.ContentProvider
-keep class * extends androidx.fragment.app.Fragment

# ── ViewBinding ───────────────────────────────────────────────
-keep class com.vltv.play.databinding.** { *; }

# ── Enums ─────────────────────────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Parcelable ────────────────────────────────────────────────
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ── Serializable ──────────────────────────────────────────────
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ── Coroutines ────────────────────────────────────────────────
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ── MultiDex ──────────────────────────────────────────────────
-keep class androidx.multidex.** { *; }

# ── Correção de ClassCastException em TypeToken<...>() {} anônimos ──
# O app cria "object : TypeToken<List<X>>() {}" anônimo em 6 lugares
# (LiveTvActivity, VodActivity, SeriesActivity, KidsActivity,
# FavoritesManager, XtreamApi) pra dizer ao Gson que tipo de lista
# ele está lendo. Em bytecode essas classes anônimas ficam vazias e
# idênticas entre si — o tipo genérico só sobrevive no atributo
# Signature, não no código executável. O R8 tem uma otimização de
# "fusão de classes" que junta classes que parecem iguais; ao fundir
# duas TypeToken de tipos diferentes, o Gson perde a informação de
# qual tipo real montar e o app quebra com ClassCastException dentro
# de onResponse — afetando canais, filmes, séries e Kids ao mesmo
# tempo, porque todos usam esse padrão.
#
# A flag "-optimizations !class/merging/..." NÃO resolve isso: ela é
# só compatibilidade de sintaxe com o ProGuard antigo, o R8 ignora o
# efeito real dela. A regra que o R8 realmente respeita é proteger a
# própria TypeToken e suas subclasses com allowobfuscation/
# allowshrinking — assim o R8 pode continuar embaralhando os nomes,
# mas não funde/remove essas classes a ponto de perder o tipo.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
