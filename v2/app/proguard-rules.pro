# Java-WebSocket journalise via SLF4J (absent sur Android)
-dontwarn org.slf4j.**
# Modèles et protocole sérialisés en JSON
-keep class fr.lulu.unison.model.** { *; }
-keep class fr.lulu.unison.session.Msg* { *; }
-keep class fr.lulu.unison.session.Cmd* { *; }
# Bibliothèques Google (ML Kit, Nearby, Play Services) : elles fournissent leurs propres règles.
-dontwarn com.google.android.gms.**
-dontwarn okhttp3.**
# Moteur YouTube Music (InnerTubeX) : modèles sérialisés et solveur de signature
-keep class com.metrolist.innertubex.** { *; }
-keep class com.metrolist.quackjs.** { *; }
-dontwarn com.metrolist.**
-dontwarn io.ktor.**
-dontwarn org.brotli.**
-dontwarn java.lang.management.**
