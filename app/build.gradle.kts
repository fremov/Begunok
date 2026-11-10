import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val signingProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.reader(Charsets.UTF_8)?.use { load(it) }
}

fun signingValue(name: String): String? = System.getenv(name) ?: signingProps.getProperty(name)

android {
    namespace = "ru.begunok"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.begunok"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 4
        versionName = System.getenv("VERSION_NAME")?.removePrefix("v") ?: "0.4"
        // Подключение к серверу Sintence по умолчанию — из local.properties (в git не попадает):
        //   lol.server=https://адрес   lol.me=Имя#TAG
        fun quoted(key: String): String {
            val raw = signingProps.getProperty(key) ?: localProps.getProperty(key) ?: ""
            return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        buildConfigField("String", "LOL_SERVER_DEFAULT", quoted("lol.server"))
        buildConfigField("String", "LOL_ME_DEFAULT", quoted("lol.me"))
    }

    signingConfigs {
        create("release") {
            signingValue("RELEASE_STORE_FILE")?.let { path ->
                storeFile = file(path)
                storePassword = signingValue("RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("RELEASE_KEY_ALIAS")
                keyPassword = signingValue("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signingValue("RELEASE_STORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.osmdroid)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.health.connect)
    testImplementation(libs.junit)
    // В юнит-тестах JVM org.json из Android — заглушка; настоящая нужна для разбора ответов Riot.
    testImplementation("org.json:json:20240303")
}
