# LiteRT-LM resolves @Tool-annotated methods reflectively via kotlin-reflect.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,Signature,InnerClasses,EnclosingMethod
-keep @com.google.ai.edge.litertlm.Tool class * { *; }
-keepclassmembers class * {
    @com.google.ai.edge.litertlm.Tool <methods>;
}
# Our ToolSet implementations are only ever reached by reflection.
-keep class com.local.assistant.llm.tools.** { *; }
-keep class kotlin.Metadata { *; }
