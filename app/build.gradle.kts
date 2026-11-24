import java.util.Properties

val versionPropsFile = file("version.properties")
val versionProps = Properties().apply {
  if (versionPropsFile.exists()) {
    versionPropsFile.inputStream().use { load(it) }
  }
}
val autoBump = providers.gradleProperty("autoBump").orNull == "true"
var appVersionCode = versionProps.getProperty("VERSION_CODE", "2").toIntOrNull() ?: 2
var appVersionName = versionProps.getProperty("VERSION_NAME", "1.0.1")
if (autoBump) {
  appVersionCode += 1
  val parts = appVersionName.split(".").toMutableList()
  if (parts.size >= 3) {
    val patch = parts[2].toIntOrNull() ?: 0
    parts[2] = (patch + 1).toString()
    appVersionName = parts.joinToString(".")
  }
  versionProps.setProperty("VERSION_CODE", appVersionCode.toString())
  versionProps.setProperty("VERSION_NAME", appVersionName)
  versionPropsFile.outputStream().use { versionProps.store(it, "Auto-managed Nova VPN version") }
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.novavpn.kxmvpr"
    minSdk = 26
    targetSdk = 36
    versionCode = appVersionCode
    versionName = appVersionName

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ndk {
      abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    }
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    // Only create the debug config if the keystore file actually exists.
    // This prevents CI builds (where the keystore is not committed) from failing.
    if (file("${rootDir}/debug.keystore").exists()) {
      create("debugConfig") {
        storeFile = file("${rootDir}/debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Use the custom debugConfig if it exists; otherwise fall back to AGP's default debug signing.
      signingConfigs.findByName("debugConfig")?.let {
        signingConfig = it
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
  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Unused template dependencies are commented out to keep APK size minimal.
dependencies {
  implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.biometric)
  // implementation(libs.androidx.fragment.ktx)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  // implementation(libs.androidx.room.ktx)
  // implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  implementation(libs.zxing.core)
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
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}

tasks.register("bumpAppVersion") {
  doLast {
    val props = Properties()
    if (versionPropsFile.exists()) {
      versionPropsFile.inputStream().use { props.load(it) }
    }
    val nextCode = (props.getProperty("VERSION_CODE", "1").toIntOrNull() ?: 1) + 1
    val curName = props.getProperty("VERSION_NAME", "1.0.0")
    val parts = curName.split(".").toMutableList()
    val nextName = if (parts.size >= 3) {
      parts[2] = ((parts[2].toIntOrNull() ?: 0) + 1).toString()
      parts.joinToString(".")
    } else curName
    props.setProperty("VERSION_CODE", nextCode.toString())
    props.setProperty("VERSION_NAME", nextName)
    versionPropsFile.outputStream().use { props.store(it, "Auto-managed Nova VPN version") }
    println("Bumped Nova VPN version to $nextName ($nextCode)")
  }
}
