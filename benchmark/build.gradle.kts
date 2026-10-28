plugins { alias(libs.plugins.android.test) }
android {
    namespace = "dev.fieldnotes.benchmark"; compileSdk = 37
    targetProjectPath = ":app"
    defaultConfig { minSdk = 28; targetSdk = 37; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; missingDimensionStrategy("environment", "demo") }
    buildTypes {
        create("benchmark") { isDebuggable = true; matchingFallbacks += listOf("benchmark", "release") }
        create("profile") { isDebuggable = true; matchingFallbacks += listOf("profile", "release") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
androidComponents { beforeVariants(selector().all()) { it.enable = it.buildType in listOf("benchmark", "profile") } }
dependencies {
    implementation(libs.test.android.junit)
    implementation(libs.test.android.runner)
    implementation(libs.test.uiautomator)
    implementation(libs.benchmark.macro)
}
