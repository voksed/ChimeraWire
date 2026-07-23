plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    compileSdk = 34
    namespace = "com.carnelia.vpn"

    defaultConfig {
        minSdk = 26
        targetSdk = 34
        // applicationId, versionCode, versionName задаются в каждом flavor отдельно
    }

    signingConfigs {
        // Ключи оригинального Carnelia VPN
        create("carnelia") {
            storeFile = file("release.jks")
            storePassword = "***REMOVED***"
            keyAlias = "carnelia"
            keyPassword = "***REMOVED***"
        }
        // Ключи null vpn
        create("nullvpn") {
            storeFile = file("nullvpn.jks")
            storePassword = "***REMOVED***"
            keyAlias = "nullvpn"
            keyPassword = "***REMOVED***"
        }
    }

    // Два измерения: brand (carnelia / null) + edition (vanilla / wallet)
    flavorDimensions += listOf("brand", "edition")

    productFlavors {

        // ---- Бренд: Carnelia VPN ----
        create("carnelia") {
            dimension = "brand"
            applicationId = "com.carnelia.vpn"
            versionCode = 34
            versionName = "3.0.0"
            signingConfig = signingConfigs.getByName("carnelia")
            // app_name берётся из strings.xml ("Carnelia VPN")
        }

        // ---- Бренд: null vpn ----
        create("null") {
            dimension = "brand"
            applicationId = "com.null.vpn"
            versionCode = 1
            versionName = "1.0.0"
            signingConfig = signingConfigs.getByName("nullvpn")
            resValue("string", "app_name", "null vpn")
        }

        // ---- Редакция: без кошелька ----
        create("vanilla") {
            dimension = "edition"
            buildConfigField("boolean", "WALLET_ENABLED", "false")
        }

        // ---- Редакция: с крипто-кошельком ----
        create("wallet") {
            dimension = "edition"
            buildConfigField("boolean", "WALLET_ENABLED", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // signingConfig не задаём здесь — берётся из flavor
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.6"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes.add("META-INF/native-image/**")
            excludes.add("META-INF/*.kotlin_module")
            excludes.add("META-INF/DEPENDENCIES")
            excludes.add("META-INF/LICENSE*")
            excludes.add("META-INF/NOTICE*")
            excludes.add("DebugProbesKt.bin")
            excludes.add("kotlin-tooling-metadata.json")
            // Resolve Go class conflict
            pickFirsts.add("go/**")
            pickFirsts.add("go/Seq.class")
            pickFirsts.add("go/Seq$*.class")
            pickFirsts.add("go/Universe.class")
            pickFirsts.add("go/Universe$*.class")
            pickFirsts.add("go/error.class")
        }
    }
}

dependencies {
    // Kotlin & Coroutines
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")

    // Jetpack Compose & Material3
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // AndroidX Core
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")

    implementation("androidx.lifecycle:lifecycle-runtime:2.6.1")
    
    // DataStore for settings persistence — removed (VpnConfigRepository unused, using SharedPreferences)

    // VPN & Networking
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    // JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")

    // Tun2Socks bridge (used by XrayVpnProtocol and SingboxVpnProtocol)
    implementation(files("libs/tun2socks.aar"))

    // Logging
    implementation("com.google.code.findbugs:jsr305:3.0.2")

    // Testing
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.6.0")
    testImplementation("junit:junit:4.13.2")

    // QR Code
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.2")

    // TON Wallet — TweetNaCl bundled as source (com.iwebpp.crypto.TweetNaclFast)
    implementation("androidx.security:security-crypto:1.1.0-alpha06") // EncryptedSharedPreferences
    // Image loading for NFT / Jetton icons
    implementation("io.coil-kt:coil-compose:2.5.0")

    // OSM tile map
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
