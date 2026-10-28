plugins { alias(libs.plugins.android.library); `maven-publish` }
android {
    namespace = "dev.fieldnotes.legacy"; compileSdk = 37
    defaultConfig { minSdk = 26; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    publishing { singleVariant("release") { withSourcesJar() } }
}
dependencies {
    implementation(libs.fragment)
    implementation(libs.activity.core)
    testImplementation(libs.test.junit)
}
afterEvaluate {
    publishing {
        publications { create<MavenPublication>("legacy") {
            from(components["release"])
            groupId = "dev.fieldnotes"; artifactId = "legacy"; version = "0.1.0"
        } }
        repositories { maven { name = "study"; url = uri(layout.buildDirectory.dir("study-maven")) } }
    }
}
