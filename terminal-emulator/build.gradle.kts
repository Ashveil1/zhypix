// Vendored from termux-app v0.118.0 (Apache-2.0) — see NOTICE.md
plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.termux.terminal"
  compileSdk = 35
  ndkVersion = "28.2.13676358"

  defaultConfig {
    minSdk = 24

    externalNativeBuild {
      ndkBuild {
        cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Os", "-fno-stack-protector", "-Wl,--gc-sections")
      }
    }

    ndk {
      abiFilters.add("arm64-v8a")
      abiFilters.add("x86_64")
    }
  }

  externalNativeBuild {
    ndkBuild {
      path("src/main/jni/Android.mk")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  testOptions {
    unitTests.isReturnDefaultValues = true
  }
}

dependencies {
  testImplementation(libs.junit)
}
