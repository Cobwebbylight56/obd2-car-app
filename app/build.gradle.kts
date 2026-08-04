plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.rhys.obd2"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rhys.obd2"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // A fixed debug key, committed to the repository on purpose.
        //
        // Without it, the Android plugin generates a throwaway debug keystore on each CI
        // runner, so every build is signed with a different key and Android refuses to
        // install one over another — "App not installed", signature mismatch. Updating
        // then means uninstalling first, which takes the app's settings and every
        // recorded trip log with it.
        //
        // A debug key is not a meaningful secret: it grants nothing, and AOSP's own is
        // public. It must never be used to sign anything published to an app store, and
        // it isn't — only the debug build type references it.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // The design gallery renders real Compose on the JVM through Robolectric, so
            // the unit test classpath needs the packaged Android resources.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

/**
 * The gallery renderer only runs when it is asked to record or verify.
 *
 * Without this it would run inside the ordinary `test` task with no baseline to compare
 * against and fail the build, taking the APK with it. The contrast checks are deliberately
 * not excluded — those are pure JVM assertions with no images involved, and they are the
 * guard that stops an unreadable theme shipping again, so they belong in every build.
 */
tasks.withType<Test>().configureEach {
    val renderingRequested = project.hasProperty("roborazzi.test.record") ||
        project.hasProperty("roborazzi.test.verify") ||
        project.hasProperty("roborazzi.test.compare")
    if (!renderingRequested) {
        filter {
            excludeTestsMatching("com.rhys.obd2.design.DesignReviewTest")
            isFailOnNoMatchingTests = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)

    // Design review harness — see ui/gallery/Gallery.kt and DesignReviewTest.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    debugImplementation(libs.androidx.ui.test.manifest)
}
