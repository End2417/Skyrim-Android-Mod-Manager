plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// The workflow passes -PversionCode=<n> so every build has a higher version than the last.
val code = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
android {
    namespace = "com.example.skyrimmm"
    compileSdk = 34
    defaultConfig { applicationId = "com.example.skyrimmm"; minSdk = 26; targetSdk = 34; versionCode = code; versionName = (project.findProperty("versionName") as String?) ?: "1.3.6" }
    // One fixed key for every build, so a new APK installs over the previous one instead of conflicting with it.
    signingConfigs {
        create("fixed") {
            storeFile = file("skyrimmm.keystore"); storeType = "pkcs12"
            // The keystore file and its password come from repository secrets at build time (see the workflow).
            storePassword = System.getenv("KEYSTORE_PASSWORD"); keyAlias = "skyrimmm"; keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("fixed") }
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST", "META-INF/versions/**") }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
    implementation("com.github.junrar:junrar:7.5.5")
}
