// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
    // ✅ NOVO: declara a versão do plugin do Crashlytics aqui (raiz), com
    // apply false — é exatamente o mesmo padrão já usado pros outros
    // plugins do Google acima. Sem essa linha, o "id("com.google.firebase.crashlytics")"
    // do app/build.gradle não seria encontrado e o build quebraria com
    // "Plugin not found".
    id("com.google.firebase.crashlytics") version "3.0.6" apply false
}
