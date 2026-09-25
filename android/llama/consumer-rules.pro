# Called from llama_jni.cpp by name.
-keep class app.langboard.llama.TokenSink { *; }
-keepclasseswithmembernames class app.langboard.llama.LlamaNative { native <methods>; }
