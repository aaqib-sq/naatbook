plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.hilt.android)
}

android {
  namespace = "com.aistudio.mynaatnotebook"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.aaqib.naatbook"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("ANDROID_SIGNING_KEYSTORE_PATH")
        ?: System.getenv("KEYSTORE_PATH")
        ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("ANDROID_SIGNING_STORE_PASSWORD")
        ?: System.getenv("STORE_PASSWORD")
      keyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS") ?: "upload"
      keyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")
        ?: System.getenv("KEY_PASSWORD")
    }
    // Shared debug key so every CI build signs with the same key.
    // Without this, each GitHub Actions run generates a fresh debug keystore,
    // and Android refuses to update the app (signature mismatch) — forcing
    // an uninstall that wipes all user data.
    create("debugShared") {
      storeFile = file("${rootDir}/app/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    // The immutable CI workflow publishes app-debug.apk. Make that artifact a real
    // release-like test build: it is debug-signed for installation, but not debuggable
    // and uses the same R8/resource-shrinking policy as release.
    getByName("debug") {
      isDebuggable = false
      isMinifyEnabled = true
      isShrinkResources = true
      isCrunchPngs = false
      signingConfig = signingConfigs.getByName("debugShared")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }

    // Keep an explicitly named unminified variant for Android Studio development.
    // It has a separate package name so it can coexist with the release-like test app.
    create("dev") {
      initWith(getByName("debug"))
      isDebuggable = true
      isMinifyEnabled = false
      isShrinkResources = false
      applicationIdSuffix = ".dev"
      versionNameSuffix = "-dev"
      matchingFallbacks += listOf("debug")
    }

    release {
      isDebuggable = false
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Only sign release builds when a real upload keystore is available,
      // so local/CI validation does not require a keystore checked into the repository.
      val releaseKeystore = File(
        System.getenv("ANDROID_SIGNING_KEYSTORE_PATH")
          ?: System.getenv("KEYSTORE_PATH")
          ?: "${rootDir}/my-upload-key.jks"
      )
      if (releaseKeystore.exists()) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

hilt {
  enableAggregatingTask = true
}

tasks.configureEach {
  if (name == "assembleDebug") dependsOn("testDebugUnitTest")
}
tasks.withType<Test>().configureEach {
  filter {
    // The sample context smoke test adds no product coverage; run all focused tests.
    excludeTestsMatching("*.ExampleRobolectricTest")
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.hilt.android)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.media3.common)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.session)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.play.services.auth)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  // Inspection and Compose test-manifest support are intentionally absent from the
  // optimized CI handoff APK; use assembleDev when working in Android Studio.
  add("devImplementation", libs.androidx.compose.ui.test.manifest)
  add("devImplementation", libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.hilt.compiler)
}
