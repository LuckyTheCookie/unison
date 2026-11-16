package fr.lulu.unison.library

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import fr.lulu.unison.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Colonnes d'un titre, communes à toutes les tables. */
data class TrackCols(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val cover: String,
    val durationMs: Long,
    val explicit: Boolean,
) {
    fun toTrack() = Track(trackId, title, artist, album, cover, durationMs, explicit)
}

fun Track.toCols() = TrackCols(id, title, artist, album, cover, durationMs, explicit)

@Entity(tableName = "favorite", primaryKeys = ["trackId"])
data class FavoriteRow(@Embedded val track: TrackCols, val addedAt: Long)

@Entity(tableName = "history", primaryKeys = ["trackId"])
data class HistoryRow(@Embedded val track: TrackCols, val playedAt: Long, val plays: Int)

@Entity(tableName = "playlist")
data class PlaylistRow(@PrimaryKey(autoGenerate = true) val pid: Long = 0, val name: String, val createdAt: Long)

@Entity(tableName = "playlist_track", indices = [Index("pid")])
data class PlaylistTrackRow(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val pid: Long,
    val position: Long,
    @Embedded val track: TrackCols,
)

/** Album ou playlist YouTube Music gardé dans la bibliothèque. */
@Entity(tableName = "saved", primaryKeys = ["browseId"])
data class SavedRow(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnail: String,
    val isAlbum: Boolean,
    val addedAt: Long,
)

data class PlaylistSummary(val pid: Long, val name: String, val count: Int, val cover: String?)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM favorite ORDER BY addedAt DESC")
    fun favorites(): Flow<List<FavoriteRow>>

    @Query("SELECT trackId FROM favorite")
    fun favoriteIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(row: FavoriteRow)

    @Query("DELETE FROM favorite WHERE trackId = :id")
    suspend fun removeFavorite(id: String)

    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT :limit")
    fun history(limit: Int): Flow<List<HistoryRow>>

    @Query("SELECT plays FROM history WHERE trackId = :id")
    suspend fun plays(id: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putHistory(row: HistoryRow)

    @Query("DELETE FROM history WHERE trackId NOT IN (SELECT trackId FROM history ORDER BY playedAt DESC LIMIT :keep)")
    suspend fun trimHistory(keep: Int)

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    @Query(
        "SELECT p.pid AS pid, p.name AS name, " +
            "(SELECT COUNT(*) FROM playlist_track t WHERE t.pid = p.pid) AS count, " +
            "(SELECT t.cover FROM playlist_track t WHERE t.pid = p.pid ORDER BY t.position LIMIT 1) AS cover " +
            "FROM playlist p ORDER BY p.createdAt DESC",
    )
    fun playlists(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlist WHERE pid = :pid")
    fun playlist(pid: Long): Flow<PlaylistRow?>

    @Insert
    suspend fun createPlaylist(row: PlaylistRow): Long

    @Query("UPDATE playlist SET name = :name WHERE pid = :pid")
    suspend fun renamePlaylist(pid: Long, name: String)

    @Query("DELETE FROM playlist WHERE pid = :pid")
    suspend fun deletePlaylistRow(pid: Long)

    @Query("DELETE FROM playlist_track WHERE pid = :pid")
    suspend fun clearPlaylist(pid: Long)

    @Transaction
    suspend fun deletePlaylist(pid: Long) {
        clearPlaylist(pid); deletePlaylistRow(pid)
    }

    @Query("SELECT * FROM playlist_track WHERE pid = :pid ORDER BY position")
    fun playlistTracks(pid: Long): Flow<List<PlaylistTrackRow>>

    @Query("SELECT COALESCE(MAX(position), 0) FROM playlist_track WHERE pid = :pid")
    suspend fun lastPosition(pid: Long): Long

    @Query("SELECT COUNT(*) FROM playlist_track WHERE pid = :pid AND trackId = :trackId")
    suspend fun countInPlaylist(pid: Long, trackId: String): Int

    @Insert
    suspend fun insertPlaylistTracks(rows: List<PlaylistTrackRow>)

    @Transaction
    suspend fun appendToPlaylist(pid: Long, tracks: List<Track>): Int {
        var position = lastPosition(pid)
        val fresh = tracks.distinctBy { it.id }.filter { countInPlaylist(pid, it.id) == 0 }
        insertPlaylistTracks(fresh.map { PlaylistTrackRow(pid = pid, position = ++position, track = it.toCols()) })
        return fresh.size
    }

    @Query("DELETE FROM playlist_track WHERE rowId = :rowId")
    suspend fun removePlaylistTrack(rowId: Long)

    @Query("SELECT * FROM saved ORDER BY addedAt DESC")
    fun saved(): Flow<List<SavedRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: SavedRow)

    @Query("DELETE FROM saved WHERE browseId = :browseId")
    suspend fun unsave(browseId: String)
}

@Database(
    entities = [FavoriteRow::class, HistoryRow::class, PlaylistRow::class, PlaylistTrackRow::class, SavedRow::class],
    version = 1,
    exportSchema = false,
)
abstract class LibraryDb : RoomDatabase() {
    abstract fun dao(): LibraryDao
}

/** Favoris, playlists et historique : tout reste sur le téléphone, sans compte. */
class Library(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val dao = Room.databaseBuilder(context, LibraryDb::class.java, "library.db").build().dao()

    val favorites: Flow<List<Track>> = dao.favorites().map { rows -> rows.map { it.track.toTrack() } }
    val favoriteIds: Flow<Set<String>> = dao.favoriteIds().map { it.toSet() }
    val history: Flow<List<Track>> = dao.history(200).map { rows -> rows.map { it.track.toTrack() } }
    val playlists: Flow<List<PlaylistSummary>> = dao.playlists()
    val saved: Flow<List<SavedRow>> = dao.saved()

    suspend fun setFavorite(track: Track, on: Boolean) {
        if (on) dao.addFavorite(FavoriteRow(track.toCols(), now())) else dao.removeFavorite(track.id)
    }

    suspend fun recordPlay(track: Track) {
        dao.putHistory(HistoryRow(track.toCols(), now(), (dao.plays(track.id) ?: 0) + 1))
        dao.trimHistory(500)
    }

    suspend fun clearHistory() = dao.clearHistory()

    fun playlist(pid: Long): Flow<PlaylistRow?> = dao.playlist(pid)
    fun playlistTracks(pid: Long): Flow<List<PlaylistTrackRow>> = dao.playlistTracks(pid)
    suspend fun createPlaylist(name: String): Long = dao.createPlaylist(PlaylistRow(name = name.trim().take(60), createdAt = now()))
    suspend fun renamePlaylist(pid: Long, name: String) = dao.renamePlaylist(pid, name.trim().take(60))
    suspend fun deletePlaylist(pid: Long) = dao.deletePlaylist(pid)
    /** Ajoute les titres absents de la playlist ; renvoie le nombre réellement ajouté. */
    suspend fun addToPlaylist(pid: Long, tracks: List<Track>): Int = dao.appendToPlaylist(pid, tracks)
    suspend fun removeFromPlaylist(rowId: Long) = dao.removePlaylistTrack(rowId)

    suspend fun setSaved(row: SavedRow, on: Boolean) {
        if (on) dao.save(row.copy(addedAt = now())) else dao.unsave(row.browseId)
    }
}
