plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystorePath = providers.environmentVariable("MESSAGE487_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("MESSAGE487_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("MESSAGE487_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("MESSAGE487_KEY_PASSWORD").orNull
val signingInputs = listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword)
if (signingInputs.any { it != null } && signingInputs.any { it.isNullOrBlank() }) {
    throw GradleException("Release signing environment is incomplete")
}

android {
    namespace = "life.andre.message487"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "life.andre.message487"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.0.3"
    }
    signingConfigs {
        if (signingInputs.all { !it.isNullOrBlank() }) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "DEFAULT_WEBHOOK_URL", "\"http://10.0.2.2:5678/webhook/message487/receive\"")
        }
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("String", "DEFAULT_WEBHOOK_URL", "\"\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
