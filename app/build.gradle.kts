import java.security.MessageDigest
import java.util.Base64

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  // 100% offline kiosk build: cloud/secrets plugins disabled
  // alias(libs.plugins.secrets)
  // alias(libs.plugins.google.services)
}

abstract class PrepareDebugKeystoreTask : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val encodedKeystoreFile: RegularFileProperty

  @get:Input
  abstract val expectedSha256: Property<String>

  @get:OutputFile
  abstract val outputKeystoreFile: RegularFileProperty

  @TaskAction
  fun restoreKeystore() {
    val sourceFile = encodedKeystoreFile.get().asFile
    if (!sourceFile.isFile) {
      throw GradleException(
        "Original debug keystore backup not found at ${sourceFile.path}. " +
          "Refusing to generate a replacement key."
      )
    }

    val rawBase64 = sourceFile.readText(Charsets.UTF_8)
      .lineSequence()
      .joinToString("") { it.trim() }
    if (rawBase64.isEmpty()) {
      throw GradleException(
        "Original debug keystore backup at ${sourceFile.path} is empty."
      )
    }

    val decodedBytes = try {
      Base64.getDecoder().decode(rawBase64)
    } catch (e: IllegalArgumentException) {
      throw GradleException(
        "Failed to decode Base64 debug keystore backup at ${sourceFile.path}.",
        e
      )
    }

    val actualSha256 = MessageDigest.getInstance("SHA-256")
      .digest(decodedBytes)
      .joinToString("") { "%02x".format(it) }
    val expected = expectedSha256.get().lowercase()
    if (actualSha256 != expected) {
      throw GradleException(
        "Debug keystore SHA-256 verification failed! Expected=$expected Actual=$actualSha256."
      )
    }

    val targetFile = outputKeystoreFile.get().asFile
    targetFile.parentFile?.mkdirs()
    if (!targetFile.isFile || !targetFile.readBytes().contentEquals(decodedBytes)) {
      targetFile.writeBytes(decodedBytes)
    }

    val writtenSha256 = MessageDigest.getInstance("SHA-256")
      .digest(targetFile.readBytes())
      .joinToString("") { "%02x".format(it) }
    if (writtenSha256 != expected) {
      targetFile.delete()
      throw GradleException(
        "Restored debug keystore failed post-write SHA-256 verification (Actual=$writtenSha256)."
      )
    }
  }
}

val restoredDebugKeystoreFile = layout.buildDirectory.file("generated/signing/debug.keystore")

val prepareDebugKeystore by tasks.registering(PrepareDebugKeystoreTask::class) {
  group = "signing"
  description = "Restores and SHA-256 verifies the original RetroTok debug keystore for debugConfig."
  encodedKeystoreFile.set(
    rootProject.layout.projectDirectory.file("gradle/signing/debug.keystore.base64")
  )
  expectedSha256.set("40259fc6d1a7214d190e04960a18ff1b6220115e80223588b37797b86b5a0bae")
  outputKeystoreFile.set(restoredDebugKeystoreFile)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.retrotok.kiosk"
    minSdk = 23
    targetSdk = 36
    versionCode = 2
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    buildConfigField("String", "CLOUD_API_BASE_URL", "\"https://wildfirexstream.com/retrotok/api/v1/\"")
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
    create("debugConfig") {
      storeFile = restoredDebugKeystoreFile.get().asFile
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
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
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

tasks.configureEach {
  if (name == "validateSigningDebug") {
    dependsOn(prepareDebugKeystore)
  }
}

// Some unused dependencies are commented out below instead of being removed.
// RetroTok is 100% offline: cloud/networking SDKs remain disabled to reduce APK size and prevent
// manifest permission merges (such as INTERNET or ACCESS_NETWORK_STATE).
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.video)
  implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.common)
  implementation(libs.androidx.media3.ui)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // implementation(libs.firebase.firestore)
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
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
  "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}
