plugins {
  id("com.android.application") version "9.3.1" apply false
  id("org.jetbrains.kotlin.android") version "2.4.10" apply false
}

// ktlint via Maven Central CLI (CN-network friendly; plugins.gradle.org is
// unreachable there). Same engine as the standalone jar — CI runs
// `./gradlew ktlintCheck`, local devs with a JDK can run it too. The android
// ruleset is enabled by `ktlint_android = true` in .editorconfig.
val ktlintConfig by configurations.creating

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
