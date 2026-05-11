import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingPropertiesPath =
    providers.environmentVariable("NFC_COMMUNICATOR_SIGNING_PROPERTIES").orNull
        ?: "${System.getProperty("user.home")}/.config/nfccommunicator/keystore.properties"
val signingPropertiesFile = File(signingPropertiesPath).absoluteFile
val signingPropertiesDir = signingPropertiesFile.parentFile ?: rootProject.projectDir
val keystoreProperties =
    Properties().apply {
        if (signingPropertiesFile.exists()) {
            FileInputStream(signingPropertiesFile).use(::load)
        }
    }

fun Properties.valueOrNull(name: String): String? = getProperty(name)?.takeIf { it.isNotBlank() }

val releaseStoreFile =
    keystoreProperties.valueOrNull("storeFile")?.let { configuredPath ->
        val configuredFile = File(configuredPath)
        if (configuredFile.isAbsolute) {
            configuredFile
        } else {
            signingPropertiesDir.resolve(configuredPath)
        }
    }
val hasReleaseSigning =
    signingPropertiesFile.exists() &&
        releaseStoreFile?.exists() == true &&
        keystoreProperties.valueOrNull("storePassword") != null &&
        keystoreProperties.valueOrNull("keyAlias") != null &&
        keystoreProperties.valueOrNull("keyPassword") != null

android {
    namespace = "dev.alsatianconsulting.NFCommunicator"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.alsatianconsulting.nfcommunicator"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProperties.valueOrNull("storePassword")
                keyAlias = keystoreProperties.valueOrNull("keyAlias")
                keyPassword = keystoreProperties.valueOrNull("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("boolean", "SECURE_WINDOW", "true")
        }
        debug {
            buildConfigField("boolean", "SECURE_WINDOW", "false")
        }
        create("screenshot") {
            initWith(getByName("debug"))
            isDebuggable = false
            buildConfigField("boolean", "SECURE_WINDOW", "false")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-tooling")
}
