plugins {
    alias(libs.plugins.android.application)
}

val readOnlyReaderSources = layout.buildDirectory.dir("generated/evReader")
val stageReadOnlyReader by tasks.registering(Sync::class) {
    from(rootProject.file("shared/src/main/java/com/shilapi/xcertplay/vehicle/leapmotor")) {
        include("LeapmotorCarPropertyReader.kt", "ReflectiveCarPropertyAccess.kt", "LeapmotorCameraSnapshots.kt")
    }
    into(readOnlyReaderSources)
}

android {
    namespace = "com.southcrux.c11diagnostics"
    compileSdk = 37
    useLibrary("android.car", false)
    defaultConfig {
        applicationId = "com.southcrux.c11diagnostics"
        minSdk = 30
        targetSdk = 37
        versionCode = 6
        versionName = "0.6"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // Stage selected readers and the explicit camera capture bridge. No OEM JAR/native bundling.
    sourceSets.getByName("main").kotlin.srcDir(readOnlyReaderSources.get().asFile)
    testOptions.unitTests.isIncludeAndroidResources = true
}

tasks.named("preBuild") { dependsOn(stageReadOnlyReader) }

dependencies {
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
