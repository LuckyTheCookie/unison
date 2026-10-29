# Java-WebSocket journalise via SLF4J (absent sur Android)
-dontwarn org.slf4j.**
# Modèles et protocole sérialisés en JSON
-keep class fr.lulu.unison.model.** { *; }
-keep class fr.lulu.unison.session.Msg* { *; }
-keep class fr.lulu.unison.session.Cmd* { *; }
-keepclassmembers class fr.lulu.unison.deezer.DeezerApi$* { *; }
# Bibliothèques Google (ML Kit, Nearby, Play Services) : R8 casse leur code interne
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-keep class com.google.firebase.components.** { *; }
-keep class com.google.android.odml.** { *; }
-dontwarn com.google.android.gms.**
# Serveur WebSocket (et sa dépendance de journalisation SLF4J)
-keep class org.java_websocket.** { *; }
-keep class org.slf4j.** { *; }
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class fr.lulu.unison.lyrics.** { *; }
