import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// CI 签名配置：从环境变量或 gradle.properties 读取
fun getSigningProperty(key: String): String? {
    return System.getenv(key) ?: findProperty(key)?.toString()
}

android {
    namespace = "com.bf1.admin.tool"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bf1.admin.tool"
        minSdk = 26
        targetSdk = 35
        versionName = "1.5.1"
        val versionParts = versionName?.split(".")
        versionCode = 10000 * versionParts?.get(0)!!.toInt() + 100 * versionParts[1].toInt() + versionParts[2].toInt()
    }

    signingConfigs {
        create("release") {
            val keystorePath = getSigningProperty("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = getSigningProperty("KEYSTORE_PASSWORD")
                keyAlias = getSigningProperty("KEY_ALIAS")
                keyPassword = getSigningProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            val keystorePath = getSigningProperty("KEYSTORE_PATH")
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
