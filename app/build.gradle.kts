import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cloud.morris.wcast"
    compileSdk = 34

    defaultConfig {
        applicationId = "cloud.morris.wcast"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        // Defaults to 0.1 for local/pre-release builds. CI overrides this with
        // the pushed tag (e.g. `-PversionName=0.1`) so releases are labelled
        // correctly. See AGENTS.md for the project's versioning conventions.
        versionName = (project.findProperty("versionName") as? String) ?: "0.4"

        // Timestamp of this build (ISO-8601, America/Los_Angeles i.e. Pacific
        // time), shown on the in-app About screen so a user's report can be
        // matched to the exact APK they have. Regenerates on every build; that is
        // intentional. NB: keep this in sync with versionName when cutting a
        // release — see the reminder in AGENTS.md.
        val buildTimePt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss z", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("America/Los_Angeles") }
            .format(Date())
        buildConfigField("String", "BUILD_TIME_PT", "\"$buildTimePt\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Jetpack Compose (managed by the Compose BOM)
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // HTML parsing & DOM scrubbing
    implementation("org.jsoup:jsoup:1.17.2")

    // JVM unit tests for the pure normalization logic
    testImplementation("junit:junit:4.13.2")
}