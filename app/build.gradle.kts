import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
}

// One fixed signing key so every new build installs over the previous one (your bills are kept).
val signing = Properties().apply {
  val f = rootProject.file("keystore/keystore.properties")
  if (f.exists()) f.inputStream().use { load(it) }
}
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
  namespace = "com.expensescanner.app"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }

  defaultConfig {
    applicationId = "com.expensescanner.app"
    minSdk = 31
    targetSdk = 36
    versionCode = runNumber
    versionName = "1.0.$runNumber"
    // Phones that can run the AI reader (8 GB+) are all 64-bit ARM; this keeps the APK small.
    ndk { abiFilters.add("arm64-v8a") }
  }

  signingConfigs {
    create("release") {
      storeFile = rootProject.file(signing.getProperty("storeFile", "keystore/release.jks"))
      storePassword = signing.getProperty("storePassword")
      keyAlias = signing.getProperty("keyAlias")
      keyPassword = signing.getProperty("keyPassword")
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("release")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures { compose = true }
  packaging { jniLibs { pickFirsts.add("**/liblitertlm_jni.so") } }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
    // LiteRT-LM 0.18.1 is compiled with newer Kotlin metadata (same flag as Google's AI Edge Gallery).
    freeCompilerArgs.addAll("-Xskip-metadata-version-check")
  }
}

dependencies {
  implementation("androidx.core:core-ktx:1.15.0")
  implementation("androidx.activity:activity-compose:1.10.1")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
  implementation(platform("androidx.compose:compose-bom:2026.02.00"))
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-graphics")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended:1.7.8")
  implementation("io.coil-kt:coil-compose:2.7.0")
  implementation("androidx.exifinterface:exifinterface:1.4.1")
  implementation("com.google.mlkit:text-recognition:16.0.1")
  implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
}
