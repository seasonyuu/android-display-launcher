plugins { alias(libs.plugins.android.application) }
val gitVersion = rootProject.extra["gitVersion"] as List<*>

android {
    namespace = "me.seasonyuu.displaylauncher.fixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "me.seasonyuu.displaylauncher.fixture"
        minSdk = 30
        targetSdk = 36
        versionCode = gitVersion[0].toString().toInt()
        versionName = gitVersion[1].toString()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(21) }
