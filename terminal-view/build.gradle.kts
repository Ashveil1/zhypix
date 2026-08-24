// Vendored from termux-app v0.118.0 (Apache-2.0) — see NOTICE.md
plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.termux.view"
  compileSdk = 35

  defaultConfig {
    minSdk = 24
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  api(project(":terminal-emulator"))
  implementation(libs.androidx.annotation)
  testImplementation(libs.junit)
}
