import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.dshmobile.shell"
  compileSdk = 36
  ndkVersion = "27.2.12479018"

  defaultConfig {
    applicationId = "com.dshmobile.shell"
    minSdk = 26
    // targetSdk 34: Android 15+ forbids exec of app-data ELF for targetSdk 35+
    // (covered by the /system/bin/linker64 fallback in startWithArgs); 34 also
    // keeps the engine's direct exec working on Android 10-14, where the
    // untrusted_app domain allows exec of app_data_file (AOSP sepolicy).
    // Huawei/EMUI devices enforce a stricter W^X (executable files must not
    // be writable) — handled in SnapshotExtractor by stripping the write bit
    // from extracted executables.
    targetSdk = 34
    // Version comes from the release tag in CI (-PversionName/-PversionCode,
    // e.g. v0.1.0 -> 0.1.0 / 100); local builds keep the defaults.
    versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
    versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
    // One ABI per CI matrix leg (-PabiFilter=arm64-v8a); local builds keep
    // all ABIs so the universal libexec-hook.so works everywhere.
    val abiFilter = project.findProperty("abiFilter") as String?
    if (abiFilter != null) {
      ndk { abiFilters += abiFilter }
    }
  }

  androidResources {
    // snapshot.tar.xz is already xz-compressed; double-compressing it breaks openFd.
    noCompress += "xz"
  }

  // Universal exec reroute hook (src/main/cpp): built for both ABIs and
  // shipped as libexec-hook.so, LD_PRELOADed into the engine process tree.
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
    }
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
      // AGP 9 disables v1 signing by default for minSdk >= 24; some OEM
      // installers (Huawei/EMUI) reject v2-only APKs with "no certificate",
      // so force v1 on for sideload compatibility.
      enableV1Signing = true
      enableV2Signing = true
      enableV3Signing = true
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
  implementation("dev.rikka.shizuku:shizuku-user:13.1.5")
}