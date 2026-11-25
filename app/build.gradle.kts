import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing. The key file is in signing/ (it is encrypted with its password); the password
// itself is never committed: it comes from the SIGNING_PASSWORD environment variable (a GitHub
// Actions secret) or from a local, git-ignored keystore.properties. Without a password the
// release build is signed with the local debug key so that it can still be installed.
val signingProps = Properties().apply {
    listOf("signing/signing.properties", "keystore.properties")
        .map { rootProject.file(it) }
        .filter { it.exists() }
        .forEach { f -> f.inputStream().use { load(it) } } // keystore.properties overrides
}
val signingPassword: String? = System.getenv("SIGNING_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseStoreFile: String? = signingProps.getProperty("storeFile")
val releaseStorePassword: String? = signingProps.getProperty("storePassword") ?: signingPassword
val releaseKeyPassword: String? = signingProps.getProperty("keyPassword") ?: signingPassword

android {
    namespace = "bg.evolution.callrecorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "bg.evolution.callrecorder"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // GitHub repository whose Releases page the app checks for updates.
        buildConfigField("String", "UPDATE_REPO", "\"evolutiondevil-coder/Call-Recording\"")
    }

    signingConfigs {
        if (releaseStoreFile != null && releaseStorePassword != null && releaseKeyPassword != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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

    androidResources {
        generateLocaleConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/previous-compilation-data.bin")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.animation.ExperimentalAnimationApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

// Robolectric's Android framework jar is resolved by Gradle (cached, retried) instead of
// Robolectric's own downloader, and the tests run in offline mode.
val robolectricRuntime: Configuration by configurations.creating
val robolectricJarsDir = layout.buildDirectory.dir("robolectric-jars")
val prepareRobolectric by tasks.registering(Copy::class) {
    from(robolectricRuntime)
    into(robolectricJarsDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectric)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricJarsDir.get().asFile.absolutePath)
    maxHeapSize = "3g"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Pure-Java LAME port used for MP3 encoding (core classes only, no javax.sound).
    implementation(libs.jump3r)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    robolectricRuntime(libs.robolectric.android.all)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
