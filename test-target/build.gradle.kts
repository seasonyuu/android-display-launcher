plugins { alias(libs.plugins.android.application) }
android {
    namespace = "me.seasonyuu.displaylauncher.fixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "me.seasonyuu.displaylauncher.fixture"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(21) }
