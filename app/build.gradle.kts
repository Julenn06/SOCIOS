plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlin.serialization)
}

// Firma de release: la CI (o tu entorno local) define estas variables. Sin ellas,
// assembleRelease genera un APK sin firmar en lugar de usar una clave cualquiera.
val releaseKeystorePath = providers.environmentVariable("SOCIOS_KEYSTORE_PATH").orNull

android {
    namespace = "com.julen.socios"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.julen.socios"
        minSdk = 26
        targetSdk = 37
        // La CI pasa el número de ejecución para que cada APK publicado sea una actualización real.
        versionCode = providers.environmentVariable("SOCIOS_VERSION_CODE").orNull?.toInt() ?: 1
        versionName = providers.environmentVariable("SOCIOS_VERSION_NAME").orNull ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = providers.environmentVariable("SOCIOS_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("SOCIOS_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("SOCIOS_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    // Copias de seguridad JSON. Además fija kotlinx-serialization-core: androidx.savedstate trae la 1.7.3,
    // room-testing necesita una más reciente y los tests instrumentados usan la versión de la app.
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
