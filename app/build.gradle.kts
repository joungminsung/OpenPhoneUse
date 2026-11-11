import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
}

android {
    namespace = "com.phoneuse"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.phoneuse"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.0.2"
    }

    // The release key stays outside the repository: in ~/.openphoneuse/keystore.properties on the maintainer's
    // machine, or in RELEASE_* environment variables in the release workflow. A build without either stays unsigned.
    val releaseKeys = Properties().apply {
        val file = File(System.getProperty("user.home"), ".openphoneuse/keystore.properties")
        if (file.isFile) file.inputStream().use { load(it) }
        mapOf(
            "storeFile" to "RELEASE_STORE_FILE",
            "storePassword" to "RELEASE_STORE_PASSWORD",
            "keyAlias" to "RELEASE_KEY_ALIAS",
            "keyPassword" to "RELEASE_KEY_PASSWORD",
        ).forEach { (key, env) -> System.getenv(env)?.takeIf { it.isNotEmpty() }?.let { setProperty(key, it) } }
    }
    signingConfigs {
        if (releaseKeys.isNotEmpty()) create("release") {
            storeFile = file(releaseKeys.getProperty("storeFile"))
            storePassword = releaseKeys.getProperty("storePassword")
            keyAlias = releaseKeys.getProperty("keyAlias")
            keyPassword = releaseKeys.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // User-facing text follows the device language (i18n/Lang); tests pin it to Korean.
        unitTests.all {
            it.systemProperty("user.language", "ko")
            it.systemProperty("user.country", "KR")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.text.google.fonts)
    implementation(libs.activity.compose)
    implementation(libs.fragment) // A library brings 1.1.0, too old for registerForActivityResult (release lint).
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.datastore.preferences)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.service)
    implementation(libs.savedstate)
    implementation(libs.exifinterface)
    implementation(libs.osmdroid)
    implementation(libs.window)
    // Place routines: geofences (Galaxy phones have Play services; without them place routines are off).
    implementation(libs.play.services.location)
    // Home-screen widgets (widget/): Glance, and WorkManager for the Today brief's background refresh.
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.work.runtime)
    // Claude (Anthropic API key). Its Jackson Kotlin module needs kotlin-reflect at the compiler's version.
    implementation(libs.anthropic.java)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Runs the chat.db schema and its FTS queries on real SQLite in JVM tests.
    testImplementation(libs.sqlite.jdbc)
}
