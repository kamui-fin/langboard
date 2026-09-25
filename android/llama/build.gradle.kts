// llama.cpp for on-device inference: a small JNI bridge (src/main/cpp/llama_jni.cpp) over the
// pinned checkout in third_party/llama.cpp (run tools/fetch_llama_cpp.sh first). Knows nothing
// about Langboard, prompts or Chinese; the app wraps it.
plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "app.langboard.llama"
  compileSdk {
    version = release(37)
  }
  ndkVersion = "27.1.12297006"

  defaultConfig {
    minSdk = 28
    consumerProguardFiles("consumer-rules.pro")
    ndk {
      abiFilters += listOf("arm64-v8a")
    }
    externalNativeBuild {
      cmake {
        arguments += listOf(
          "-DCMAKE_BUILD_TYPE=Release",
          "-DANDROID_STL=c++_shared",
          // 16 KB page devices (Android 15+ requirement for Play).
          "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
          "-DBUILD_SHARED_LIBS=ON",
          "-DLLAMA_BUILD_COMMON=OFF",
          "-DLLAMA_BUILD_TOOLS=OFF",
          "-DLLAMA_BUILD_EXAMPLES=OFF",
          "-DLLAMA_BUILD_TESTS=OFF",
          "-DLLAMA_BUILD_SERVER=OFF",
          "-DLLAMA_CURL=OFF",
          "-DLLAMA_OPENSSL=OFF",
          // One CPU backend per ARM feature level (dotprod, i8mm, SVE, SME...), picked at runtime.
          "-DGGML_NATIVE=OFF",
          "-DGGML_BACKEND_DL=ON",
          "-DGGML_CPU_ALL_VARIANTS=ON",
          "-DGGML_LLAMAFILE=OFF",
          "-DGGML_OPENMP=OFF",
        )
      }
    }
  }

  buildTypes {
    // Native code is always built optimized; a debug llama.cpp is too slow to judge anything by.
    debug {
      externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } }
    }
  }

  externalNativeBuild {
    cmake {
      path("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  implementation(libs.kotlinx.coroutines.core)
}
