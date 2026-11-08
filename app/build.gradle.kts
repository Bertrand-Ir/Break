import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ── Versioning ────────────────────────────────────────────────────────────────
// CI (GitHub Actions) passes BREAK_VERSION_CODE = the workflow run number, so every
// push to main produces a strictly increasing versionCode that the in-app updater
// compares against the latest GitHub Release.
val breakVersionCode = (System.getenv("BREAK_VERSION_CODE") ?: "1").toInt()
val breakVersionName = "1.0.$breakVersionCode"

// "owner/repo" the updater polls. CI injects GITHUB_REPOSITORY automatically.
val breakRepo: String = System.getenv("BREAK_REPO")
    ?: (project.findProperty("breakRepo") as String?)
    ?: "OWNER/break"

// ── Release signing ──────────────────────────────────────────────────────────
// Every update MUST be signed with the same key, otherwise Android refuses to
// install it over the previous version. Key comes from env (CI) or keystore.properties (local).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env) ?: keystoreProps.getProperty(prop)

android {
    namespace = "app.breakpiano"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "app.breakpiano"
        minSdk = 26
        targetSdk = 36
        versionCode = breakVersionCode
        versionName = breakVersionName

        buildConfigField("String", "GITHUB_REPO", "\"$breakRepo\"")

        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-ffast-math")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("BREAK_KEYSTORE", "storeFile")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = signingValue("BREAK_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("BREAK_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("BREAK_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }

    // The soundfont is read straight from the APK via mmap — keep it uncompressed.
    androidResources {
        noCompress += listOf("sf2")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Name the APK "Break-<version>.apk"
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName?.set("Break-${breakVersionName}-${variant.buildType}.apk")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.oboe:oboe:1.9.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
