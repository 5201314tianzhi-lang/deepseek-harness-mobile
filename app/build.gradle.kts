import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.dshmobile.shell"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.dshmobile.shell"
    minSdk = 26
    // targetSdk 34: Android 15+ forbids exec of app-data ELF for targetSdk 35+
    // (the embedded engine, bash, and every child command would need linker64
    // wrappers); 34 keeps native exec working on Android 15/16 devices.
    targetSdk = 34
    versionCode = 1
    versionName = "0.1.0"
  }

  androidResources {
    // snapshot.tar.xz is already xz-compressed; double-compressing it breaks openFd.
    noCompress += "xz"
  }

  // CI signing: the release workflow drops a release.keystore at the project
  // root and signs the release variant with it; local builds without the
  // keystore keep producing the unsigned APK as before.
  if (rootProject.file("release.keystore").exists()) {
    signingConfigs.create("ci") {
      storeFile = rootProject.file("release.keystore")
      storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: "android"
      keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "release"
      keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: "android"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      if (rootProject.file("release.keystore").exists()) {
        signingConfig = signingConfigs.getByName("ci")
      }
    }
  }

  lint {
    // Offline environments have no lint-gradle dependency cache (CN network);
    // lint is not on the release critical path.
    checkReleaseBuilds = false
    abortOnError = false
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

// Kotlin 2.4 removes the kotlinOptions DSL; use the compilerOptions API instead.
kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

// The runtime snapshot ships from GitHub Releases (the large file is not in
// the repo); fail the build with fetch instructions when it is missing.
tasks.whenTaskAdded {
  if (name == "mergeDebugAssets" || name == "mergeReleaseAssets") {
    doFirst {
      val snap = file("src/main/assets/snapshot.tar.xz")
      if (!snap.exists()) {
        throw GradleException(
          "缺少运行时快照 assets/snapshot.tar.xz / Missing runtime snapshot assets/snapshot.tar.xz — " +
            "从 GitHub Releases 下载 snapshot-x86_64.tar.xz 后放到 app/src/main/assets/snapshot.tar.xz，或按 " +
            "scripts/make-snapshot.sh 在 Termux 设备自打后拉取（见 README.md）。/ " +
            "Download snapshot-x86_64.tar.xz from GitHub Releases into app/src/main/assets/snapshot.tar.xz, or " +
            "build it on a Termux device with scripts/make-snapshot.sh (see README.md).",
        )
      }
    }
  }
}

dependencies {
  implementation("androidx.activity:activity-ktx:1.13.0")
  implementation("org.apache.commons:commons-compress:1.28.0")
  implementation("org.tukaani:xz:1.12")
  implementation("dev.rikka.shizuku:api:13.1.5")
  implementation("dev.rikka.shizuku:provider:13.1.5")
}