plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.compose) }
android {
    namespace = "dev.fieldnotes"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.fieldnotes"
        minSdk = 26; targetSdk = 37
        versionCode = 1; versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")
    }
    flavorDimensions += "environment"
    productFlavors {
        create("demo") { dimension = "environment"; applicationIdSuffix = ".demo"; buildConfigField("boolean", "DEMO", "true") }
        create("local") { dimension = "environment"; applicationIdSuffix = ".local"; buildConfigField("boolean", "DEMO", "false") }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true; isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            isDebuggable = false
        }
        create("profile") {
            initWith(getByName("benchmark"))
            isMinifyEnabled = false; isShrinkResources = false
            matchingFallbacks += "release"
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true; checkReleaseBuilds = true }
}
dependencies {
    implementation(project(":domain")); implementation(project(":data")); implementation(project(":feature")); implementation(project(":legacy"))
    implementation(libs.androidx.core)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.work.runtime)
    implementation(libs.room.runtime)
    implementation(libs.compose.runtime)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.coroutines.android)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.core)
    implementation(libs.profileinstaller)
    testImplementation(libs.test.junit)
    testImplementation(libs.test.robolectric)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.test.android.junit)
    androidTestImplementation(libs.test.android.runner)
    androidTestImplementation(libs.test.android.rules)
    androidTestImplementation(libs.test.espresso)
    androidTestImplementation(libs.compose.test.junit4)
    androidTestImplementation(libs.work.testing)
    debugImplementation(libs.compose.test.manifest)
}
