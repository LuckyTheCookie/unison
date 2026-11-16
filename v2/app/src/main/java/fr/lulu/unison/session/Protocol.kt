package fr.lulu.unison.session

import fr.lulu.unison.model.RepeatMode
import fr.lulu.unison.model.SessionSnapshot
import fr.lulu.unison.model.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Ce qu'un membre peut demander ; l'hôte applique et diffuse le nouvel état. */
@Serializable
sealed interface Cmd {
    @Serializable @SerialName("add")
    data class AddTrack(val track: Track, val playNext: Boolean = false) : Cmd

    @Serializable @SerialName("addMany")
    data class AddTracks(val tracks: List<Track>, val playNext: Boolean = false, val auto: Boolean = false) : Cmd

    /** Remplace la file par [tracks] et lance celui d'indice [index] (« Tout lire », toucher un titre d'album). */
    @Serializable @SerialName("playAll")
    data class PlayTracks(val tracks: List<Track>, val index: Int = 0) : Cmd

    @Serializable @SerialName("remove")
    data class Remove(val uid: String) : Cmd

    @Serializable @SerialName("move")
    data class Move(val uid: String, val toIndex: Int) : Cmd

    @Serializable @SerialName("playNow")
    data class PlayNow(val uid: String) : Cmd

    @Serializable @SerialName("skip")
    data object Skip : Cmd

    @Serializable @SerialName("previous")
    data object Previous : Cmd

    @Serializable @SerialName("pause")
    data object Pause : Cmd

    @Serializable @SerialName("resume")
    data object Resume : Cmd

    @Serializable @SerialName("seek")
    data class Seek(val positionMs: Long) : Cmd

    /** Mélange les titres à venir. */
    @Serializable @SerialName("shuffle")
    data object Shuffle : Cmd

    @Serializable @SerialName("clear")
    data object Clear : Cmd

    @Serializable @SerialName("repeat")
    data class SetRepeat(val mode: RepeatMode) : Cmd

    @Serializable @SerialName("autoplay")
    data class SetAutoplay(val on: Boolean) : Cmd
}

@Serializable
sealed interface Msg {
    @Serializable @SerialName("hello")
    data class Hello(val deviceId: String, val name: String, val color: Int, val secret: String, val v: Int = 1) : Msg

    @Serializable @SerialName("welcome")
    data class Welcome(val memberId: String, val snapshot: SessionSnapshot) : Msg

    @Serializable @SerialName("rejected")
    data class Rejected(val reason: String) : Msg

    @Serializable @SerialName("state")
    data class State(val snapshot: SessionSnapshot) : Msg

    @Serializable @SerialName("cmd")
    data class Command(val cmd: Cmd) : Msg

    @Serializable @SerialName("ping")
    data class Ping(val clientTime: Long) : Msg

    @Serializable @SerialName("pong")
    data class Pong(val clientTime: Long, val hostTime: Long) : Msg

    @Serializable @SerialName("bye")
    data object Bye : Msg
}

val ProtocolJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    classDiscriminator = "t"
}

fun Msg.encode(): String = ProtocolJson.encodeToString(Msg.serializer(), this)
fun decodeMsg(text: String): Msg? = runCatching { ProtocolJson.decodeFromString(Msg.serializer(), text) }.getOrNull()
