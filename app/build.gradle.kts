import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val oauthClientId = providers.gradleProperty("flarego.oauthClientId").orElse("").get()
val oauthRedirect = providers.gradleProperty("flarego.oauthRedirectUri")
    .orElse("https://oauth-unconfigured.invalid/cloudflare/callback").get()
val oauthScopes = providers.gradleProperty("flarego.oauthScopes").orElse("account-settings.read").get()
val oauthUri = URI(oauthRedirect)
require(oauthUri.scheme == "https" && oauthUri.host != null && oauthUri.rawQuery == null &&
    oauthUri.rawFragment == null && oauthUri.rawUserInfo == null && (oauthUri.port == -1 || oauthUri.port == 443) &&
    !oauthUri.rawPath.isNullOrBlank() && oauthUri.rawPath != "/") { "OAuth redirect must be an exact HTTPS callback without query/fragment" }

android {
    namespace = "com.flarego"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.flarego"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("flarego.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("flarego.versionName").orNull ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CLOUDFLARE_OAUTH_CLIENT_ID", quoted(oauthClientId))
        buildConfigField("String", "CLOUDFLARE_OAUTH_REDIRECT_URI", quoted(oauthRedirect))
        buildConfigField("String", "CLOUDFLARE_OAUTH_SCOPES", quoted(oauthScopes))
        manifestPlaceholders["oauthCallbackHost"] = oauthUri.host
        manifestPlaceholders["oauthCallbackPath"] = oauthUri.rawPath
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        create("release") {
            System.getenv("ANDROID_KEYSTORE_PATH")?.let { storeFile = file(it) }
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release") } }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared-core"))
    implementation(project(":shared-ui"))
    implementation(project(":android-platform"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Pure (non-instrumented) unit tests for the frame-barrier matching rule only.
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.03.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:monitor:1.8.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-idling-resource:3.7.0")
}
