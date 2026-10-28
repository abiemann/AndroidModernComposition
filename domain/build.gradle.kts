plugins { alias(libs.plugins.kotlin.jvm); `java-library`; `maven-publish` }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; withSourcesJar() }
dependencies {
    implementation(libs.coroutines.core)
    testImplementation(libs.test.junit)
    testImplementation(libs.coroutines.test)
}
tasks.test { useJUnit() }
publishing {
    publications { create<MavenPublication>("domain") {
        from(components["java"])
        groupId = "dev.fieldnotes"; artifactId = "domain"; version = "0.1.0"
    } }
    repositories { maven { name = "study"; url = uri(layout.buildDirectory.dir("study-maven")) } }
}
