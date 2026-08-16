import org.gradle.api.tasks.testing.Test

plugins {
  id("com.android.application") version "9.3.1" apply false
  id("org.jetbrains.kotlin.android") version "2.4.10" apply false
}

// Test diagnostics: surface the full failure stack and stderr in CI logs.
tasks.withType<Test>().configureEach {
  testLogging {
    events("failed", "standardError")
    showExceptions = true
    showStackTraces = true
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

// ktlint via Maven Central CLI (CN-network friendly; plugins.gradle.org is
// unreachable there). Same engine as the standalone jar — CI runs
// `./gradlew ktlintCheck`, local devs with a JDK can run it too. The android
// ruleset is enabled by `ktlint_android = true` in .editorconfig.
// The ktlint-cli artifact publishes both an `external` and a `shadowed`
// (fat jar) variant; a plain resolvable configuration cannot choose between
// them, so pin the attribute to the fat jar we want to run.
val ktlintConfig by configurations.creating {
  attributes {
    attribute(org.gradle.api.attributes.Bundling.BUNDLING_ATTRIBUTE, objects.named(org.gradle.api.attributes.Bundling.SHADOWED))
  }
}

dependencies {
  ktlintConfig("com.pinterest.ktlint:ktlint-cli:1.8.0")
}

val ktlintCheck by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Check Kotlin code style with ktlint"
  classpath = ktlintConfig
  mainClass.set("com.pinterest.ktlint.Main")
}

val ktlintFormat by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Format Kotlin code with ktlint"
  classpath = ktlintConfig
  mainClass.set("com.pinterest.ktlint.Main")
  args("--format")
}
