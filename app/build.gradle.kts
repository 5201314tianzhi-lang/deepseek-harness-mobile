import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("jacoco")
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
    // The CI quality gate runs `lintDebug` and fails on errors (abortOnError).
    // checkReleaseBuilds stays off: release builds are not blocked by lint —
    // the gate is explicit, not implicit on the release path.
    checkReleaseBuilds = false
    abortOnError = true
  }

  testOptions {
    unitTests {
      // Robolectric needs real Android resources/asset access.
      isIncludeAndroidResources = true
    }
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

// Unit-test coverage report (CI uploads it; not a hard gate).
tasks.register<JacocoReport>("jacocoTestReport") {
  dependsOn("testDebugUnitTest")
  reports {
    xml.required.set(true)
    html.required.set(true)
  }
  sourceDirectories.setFrom(files("src/main/java"))
  classDirectories.setFrom(fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")))
  executionData.setFrom(
    fileTree(layout.buildDirectory.dir("outputs/unit_test_code_coverage")) { include("**/*.exec") },
  )
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
            "从上游 Releases（kelai141/dsh-mobile-apk）下载 snapshot-<abi>.tar.xz 后放到 " +
            "app/src/main/assets/snapshot.tar.xz（见 README.md）。/ " +
            "Download snapshot-<abi>.tar.xz from the upstream release " +
            "(kelai141/dsh-mobile-apk) into app/src/main/assets/snapshot.tar.xz (see README.md).",
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

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.robolectric:robolectric:4.14.1")
  testImplementation("io.mockk:mockk:1.13.13")
}
