import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM port of https://github.com/edvardsr/cc-cedict (MIT). No Android dependencies,
// so the same parser and lookup semantics can run in the app, in unit tests, and in build tools.
plugins {
  alias(libs.plugins.kotlin.jvm)
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
  }
}

dependencies {
  testImplementation(libs.junit)
}

tasks.test {
  // Optional full-dictionary test: CEDICT_U8=/path/to/cedict_ts.u8 ./gradlew :cedict:test
  System.getenv("CEDICT_U8")?.let { environment("CEDICT_U8", it) }
}
