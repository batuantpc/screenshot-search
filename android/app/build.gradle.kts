plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Dagitim imzasi. Anahtar ve sifreler REPODA DEGIL: keystore.properties dosyasindan okunur
// (gitignore'da). Dosya yoksa release build imzasiz uretilir ve kurulamaz - bu bilincli:
// gizli bilgiyi surum kontrolune sokmaktansa build'in eksik kalmasi iyidir.
// Not: java.util.Properties bu Gradle/Kotlin DSL surumunde cozumlenmedigi icin dosya
// duz metin olarak ayristiriliyor. Format basit: anahtar=deger, satir basi # yorum.
val keystoreProperties: Map<String, String> = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.readLines()
    ?.mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
        val index = trimmed.indexOf('=')
        if (index <= 0) null else trimmed.substring(0, index).trim() to
            trimmed.substring(index + 1).trim()
    }
    ?.toMap()
    ?: emptyMap()

android {
    namespace = "com.projectmemento.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.projectmemento.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties["storeFile"] != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"]!!)
                storePassword = keystoreProperties["storePassword"]
                keyAlias = keystoreProperties["keyAlias"]
                keyPassword = keystoreProperties["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // Debug build ölçüm/geliştirme için okunaklı kalır. Dağıtım paketi ise R8 ile
            // erişilemeyen Kotlin/Compose/bağımlılık kodunu ve kullanılmayan kaynakları atar.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Coil 3: thumbnail'lar icin bellek + disk cache. Onceki BitmapFactory yolu her
    // scroll'da diskten decode ediyordu; cache'siz liste kaydirma jank'in ana kaynagiydi.
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")

    // Bundled Latin model: OCR is available immediately and screenshots stay on-device.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Unbundled image labeling: model Play Services'ten bir kez iner ve uygulamalar arası
    // paylaşılır; bundled varyant APK'yı ~43 MB şişirdiği için bilinçli tercih.
    // İşleme yine tamamen cihaz üzerinde yapılır, görüntü cihazdan çıkmaz.
    implementation("com.google.android.gms:play-services-mlkit-image-labeling:16.0.8")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // ext:junit yalnız JUnit adaptörüdür; runner transitif gelmediği için test APK'sı
    // cihazda AndroidJUnitRunner'ı başlatamıyordu. Çalışabilir entegrasyon testi için açık.
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Yalnız instrumented benchmark APK'sında: ana uygulamayı/modeli şişirmeden gerçek
    // cihazda semantic embedding kalitesi ve gecikmesi ölçülür.
    androidTestImplementation("com.google.mediapipe:tasks-text:0.10.35")
}
