import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.billmongan.herdrdroid"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.billmongan.herdrdroid"
        minSdk = 26
        targetSdk = 34
        versionCode = 105
        versionName = "2.0.0-rc.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The Compose UI tests live in src/sharedTest: Robolectric runs them with the unit tests, and an
    // emulator runs the same file as instrumented tests in CI.
    sourceSets {
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("robolectric.logging", "stdout") }
        }
    }

    // The sideload signing key, so every build installs over the last. Its folder is HD_KEYSTORE_DIR
    // when set (build-in-docker.sh mounts a folder kept outside the source there), else keystore/
    // (CI writes it there from the repository's secrets); git ignores both files.
    val keyDir = System.getenv("HD_KEYSTORE_DIR")?.takeIf { it.isNotBlank() }?.let { file(it) } ?: rootProject.file("keystore")
    val signing = Properties().apply {
        val f = File(keyDir, "signing.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    // Without the key (a CI run with no signing secrets), the release APK is built unsigned.
    val hasKey = File(keyDir, "herdrdroid-sideload.jks").exists() && signing.getProperty("storePassword") != null
    signingConfigs {
        create("sideload") {
            storeFile = File(keyDir, "herdrdroid-sideload.jks")
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // The saved secrets' fingerprint window (1.3.1): 4 hours.
            buildConfigField("int", "VAULT_WINDOW_SECONDS", "14400")
            isMinifyEnabled = false
            signingConfig = if (hasKey) signingConfigs.getByName("sideload") else null
        }
        debug {
            // A short window, so the emulator test can see it expire. Never shipped.
            buildConfigField("int", "VAULT_WINDOW_SECONDS", "180")
            if (hasKey) signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/BCKEY.DSA",
                "META-INF/BCKEY.SF",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("com.hierynomus:sshj:0.39.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.slf4j:slf4j-nop:2.0.13")
    testImplementation("junit:junit:4.13.2")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.robolectric:robolectric:4.13")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
