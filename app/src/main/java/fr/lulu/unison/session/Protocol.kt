package fr.lulu.unison.session

import fr.lulu.unison.model.SessionSnapshot
import fr.lulu.unison.model.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Commandes qu'un membre peut demander à l'hôte. */
@Serializable
sealed interface Cmd {
    @Serializable @SerialName("add")
    data class AddTrack(val track: Track, val playNext: Boolean = false) : Cmd

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
}

@Serializable
sealed interface Msg {
    @Serializable @SerialName("hello")
    data class Hello(val deviceId: String, val name: String, val color: Int, val secret: String) : Msg

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
