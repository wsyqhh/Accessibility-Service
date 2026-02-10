plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.orb.a11y"
  compileSdk = 34

  defaultConfig {
    applicationId = "com.orb.a11y"
    minSdk = 26
    targetSdk = 34
    versionCode = 1
    versionName = "0.1"
  }

  buildTypes {
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
}

dependencies {
  // Tiny embedded HTTP server
  implementation("org.nanohttpd:nanohttpd:2.3.1")
}
