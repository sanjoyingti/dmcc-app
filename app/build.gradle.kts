import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appPackage = "com.dmcc26.app"
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val repoSlug = System.getenv("GITHUB_REPOSITORY") ?: ""

// Reads app/google-services.json (from your own Firebase project) without the google-services plugin.
@Suppress("UNCHECKED_CAST")
fun readFirebase(f: File, pkg: String): Map<String, String>? {
    if (!f.exists()) return null
    val root = JsonSlurper().parse(f) as Map<String, Any?>
    val proj = root["project_info"] as Map<String, Any?>
    val clients = root["client"] as List<Map<String, Any?>>
    val client = clients.firstOrNull {
        val info = it["client_info"] as Map<String, Any?>
        (info["android_client_info"] as Map<String, Any?>)["package_name"] == pkg
    } ?: return null
    val info = client["client_info"] as Map<String, Any?>
    val key = (client["api_key"] as List<Map<String, Any?>>).first()["current_key"].toString()
    return mapOf(
        "google_app_id" to info["mobilesdk_app_id"].toString(),
        "gcm_defaultSenderId" to proj["project_number"].toString(),
        "google_api_key" to key,
        "google_crash_reporting_api_key" to key,
        "project_id" to proj["project_id"].toString(),
        "google_storage_bucket" to (proj["storage_bucket"]?.toString() ?: ""),
    )
}
val firebase = readFirebase(file("google-services.json"), appPackage)
if (firebase == null) println("WARNING: app/google-services.json missing or not for $appPackage - app will show the setup screen.")

android {
    namespace = appPackage
    compileSdk = 37
    defaultConfig {
        applicationId = appPackage
        minSdk = 26
        targetSdk = 37
        versionCode = runNumber
        versionName = "1.0.$runNumber"
        firebase?.forEach { (k, v) -> resValue("string", k, v) }
        buildConfigField("boolean", "FIREBASE_READY", (firebase != null).toString())
        buildConfigField("String", "REPO", "\"$repoSlug\"")
    }
    signingConfigs {
        getByName("debug") {
            if (file("signing.keystore").exists()) {
                storeFile = file("signing.keystore")
                storePassword = "android"
                keyAlias = "app"
                keyPassword = "android"
            }
        }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/LICENSE", "META-INF/NOTICE", "META-INF/*.kotlin_module")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation(platform("com.google.firebase:firebase-bom:34.2.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-messaging")
    implementation("io.getstream:stream-webrtc-android:1.3.8")
}
