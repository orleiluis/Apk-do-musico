plugins {
    id("com.android.library")
}

// Timbre Chord: acordes ao vivo, timbre e separação em 6 faixas.
// Código vindo de github.com/orleiluis/timbre-chord (Java).
android {
    namespace = "com.orleiluis.timbrechord"
    compileSdk = 35

    defaultConfig {
        // Captura do som de outros apps e serviços com tipo exigem Android 10.
        // O app abre o Timbre Chord só a partir dessa versão.
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Separação de faixas: a rede (htdemucs_6s) roda no próprio aparelho.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
