import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

val gitVersion = rootProject.extra["gitVersion"] as List<*>

val signingProperties = Properties().apply {
    val explicitPath = providers.environmentVariable("SIGNING_PROPERTIES_FILE").orNull
    val path = explicitPath ?: if (providers.environmentVariable("CI").orNull != "true")
        "${System.getProperty("user.home")}/.android/keystores/android-display-launcher/signing.properties" else null
    if (path != null) {
        val contents = providers.fileContents(layout.file(providers.provider { file(path) })).asText.orElse("").get()
        load(contents.reader())
    }
}
fun signingValue(environment: String, property: String): String? =
    providers.environmentVariable(environment).orNull ?: signingProperties.getProperty(property)
val signingPath = signingValue("RELEASE_KEYSTORE_FILE", "storeFile")
val signingStorePassword = signingValue("RELEASE_STORE_PASSWORD", "storePassword")
val signingAlias = signingValue("RELEASE_KEY_ALIAS", "keyAlias")
val signingKeyPassword = signingValue("RELEASE_KEY_PASSWORD", "keyPassword")
val signingReady = listOf(signingPath, signingStorePassword, signingAlias, signingKeyPassword).all { !it.isNullOrBlank() }

android {
  namespace = "me.seasonyuu.displaylauncher"
  compileSdk = 36
  defaultConfig {
    applicationId = "me.seasonyuu.displaylauncher"
    testApplicationId = "me.seasonyuu.displaylauncher.test"
    minSdk = 30
    targetSdk = 36
    versionCode = gitVersion[0].toString().toInt()
    versionName = gitVersion[1].toString()
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  val sharedSigning = signingConfigs.create("shared") {
    if (signingReady) {
      storeFile = file(signingPath!!)
      storePassword = signingStorePassword
      keyAlias = signingAlias
      keyPassword = signingKeyPassword
    }
  }
  buildTypes {
    debug {
      signingConfig = sharedSigning
      versionNameSuffix = "-debug"
    }
    release {
      signingConfig = sharedSigning
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = true
    buildConfig = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(21)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation(libs.androidx.datastore.preferences)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  implementation(libs.shizuku.api)
  implementation(libs.shizuku.provider)

}

// Checks only packaging: PR compilation, unit tests and Lint do not need secrets.
val validateSharedSigning by tasks.registering {
    val configured = signingReady
    doLast {
        check(configured) { "Signing is required for Debug and Release APKs. Configure RELEASE_KEYSTORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD, or the local signing.properties documented in README." }
    }
}
tasks.matching { it.name in setOf("packageDebug", "packageRelease", "validateSigningDebug", "validateSigningRelease") }.configureEach {
    dependsOn(validateSharedSigning)
}
