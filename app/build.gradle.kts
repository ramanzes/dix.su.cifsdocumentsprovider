import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

// Release signing is optional at the Gradle level: absent on CI / F-Droid's own build server,
// which don't have (and don't need) our private key. Falls back to debug signing when missing.
val releaseKeystoreProperties = file("$rootDir/keystore.properties").let { f ->
    if (f.exists()) Properties().apply { load(f.inputStream()) } else null
}

val applicationId: String by rootProject.extra
val storeApplicationId: String by rootProject.extra
val javaVersion: JavaVersion by rootProject.extra
val androidCompileSdk: Int by rootProject.extra
val androidMinSdk: Int by rootProject.extra
val appVersionName: String by rootProject.extra
val appVersionCode: Int by rootProject.extra

android {
    compileSdk = androidCompileSdk
    namespace = applicationId
    compileOptions {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    defaultConfig {
        applicationId = storeApplicationId
        minSdk = androidMinSdk
        targetSdk = androidCompileSdk
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        base.archivesName.set("CIFSDocumentsProvider-${versionName}")
    }

    signingConfigs  {
        getByName("debug") {
            storeFile = file("$rootDir/debug.keystore")
        }
        if (releaseKeystoreProperties != null) {
            create("release") {
                storeFile = file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "D"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystoreProperties != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    kotlin {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of(javaVersion.majorVersion))
        }
    }

    packaging {
        // For commons-vfs2-jackrabbit2
        resources.excludes.addAll(setOf(
            "META-INF/*",
            "META-INF/versions/*/OSGI-INF/MANIFEST.MF",
        ))
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to arrayOf("*.jar"))))
    implementation(project(":common"))
    implementation(project(":presentation"))
    implementation(project(":domain"))

    implementation(libs.androidx.appcompat)
    implementation(libs.hilt.android)
    implementation(libs.androidx.work.runtime)
    ksp(libs.hilt.android.compiler)
}
