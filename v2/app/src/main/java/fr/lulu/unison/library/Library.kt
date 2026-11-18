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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

/** Titre gardé sur le téléphone pour l'écouter sans réseau (miroir de l'état du gestionnaire de Media3). */
@Entity(tableName = "download", primaryKeys = ["trackId"])
data class DownloadRow(
    @Embedded val track: TrackCols,
    /** Voir [DownloadState]. */
    val state: Int,
    val bytes: Long,
    val totalBytes: Long,
    /** Format du flux enregistré (itag YouTube), 0 tant qu'il n'est pas connu. */
    val itag: Int,
    /** Niveau sonore annoncé par YouTube, pour égaliser le volume hors ligne. */
    val loudnessDb: Double?,
    val addedAt: Long,
)

/** Artiste suivi : ses titres nourrissent l'accueil et la radio. */
@Entity(tableName = "subscription", primaryKeys = ["browseId"])
data class SubscriptionRow(
    /** Identifiant de la page de l'artiste sur YouTube Music (chaîne « UC… »). */
    val browseId: String,
    val name: String,
    val thumbnail: String,
    val addedAt: Long,
)

object DownloadState {
    const val QUEUED = 0
    const val DOWNLOADING = 1
    const val PAUSED = 2
    const val DONE = 3
    const val FAILED = 4
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM favorite ORDER BY addedAt DESC")
    fun favorites(): Flow<List<FavoriteRow>>

    @Query("SELECT trackId FROM favorite")
    fun favoriteIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(row: FavoriteRow)

    /** Ajoute des favoris sans toucher à ceux qui existent déjà (ni à leur date). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFavoritesIfAbsent(rows: List<FavoriteRow>)

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

    @Query("SELECT pid FROM playlist WHERE name = :name ORDER BY createdAt LIMIT 1")
    suspend fun playlistIdByName(name: String): Long?

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

    @Query("SELECT * FROM download ORDER BY addedAt DESC")
    fun downloads(): Flow<List<DownloadRow>>

    @Query("SELECT * FROM download")
    suspend fun allDownloads(): List<DownloadRow>

    @Query("SELECT * FROM download WHERE trackId = :id")
    suspend fun download(id: String): DownloadRow?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDownloads(rows: List<DownloadRow>)

    @Query("UPDATE download SET state = :state, bytes = :bytes, totalBytes = :total WHERE trackId = :id")
    suspend fun setDownloadState(id: String, state: Int, bytes: Long, total: Long)

    @Query("UPDATE download SET itag = :itag, loudnessDb = :loudnessDb WHERE trackId = :id")
    suspend fun setDownloadFormat(id: String, itag: Int, loudnessDb: Double?)

    @Query("DELETE FROM download WHERE trackId = :id")
    suspend fun deleteDownload(id: String)

    @Query("SELECT * FROM subscription ORDER BY addedAt DESC")
    fun subscriptions(): Flow<List<SubscriptionRow>>

    @Query("SELECT * FROM subscription ORDER BY addedAt DESC")
    suspend fun allSubscriptions(): List<SubscriptionRow>

    /** Un artiste déjà suivi garde sa date d'abonnement. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun subscribe(rows: List<SubscriptionRow>)

    @Query("DELETE FROM subscription WHERE browseId = :browseId")
    suspend fun unsubscribe(browseId: String)
}

@Database(
    entities = [FavoriteRow::class, HistoryRow::class, PlaylistRow::class, PlaylistTrackRow::class, SavedRow::class, DownloadRow::class, SubscriptionRow::class],
    version = 3,
    exportSchema = false,
)
abstract class LibraryDb : RoomDatabase() {
    abstract fun dao(): LibraryDao
}

/** Migrations de la base : on ne perd jamais les favoris ni les playlists en mettant à jour l'app. */
object LibraryMigrations {
    /** 2.1 : titres téléchargés. */
    val V1_V2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `download` (`state` INTEGER NOT NULL, `bytes` INTEGER NOT NULL, " +
                    "`totalBytes` INTEGER NOT NULL, `itag` INTEGER NOT NULL, `loudnessDb` REAL, `addedAt` INTEGER NOT NULL, " +
                    "`trackId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, " +
                    "`cover` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `explicit` INTEGER NOT NULL, PRIMARY KEY(`trackId`))",
            )
        }
    }

    /** 2.1 : artistes suivis. */
    val V2_V3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `subscription` (`browseId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`thumbnail` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`browseId`))",
            )
        }
    }

    val ALL = arrayOf(V1_V2, V2_V3)
}

/** Ce dont les abonnements ont besoin de la bibliothèque (une version en mémoire sert aux tests). */
interface SubscriptionStore {
    val subscriptions: Flow<List<SubscriptionRow>>
    suspend fun allSubscriptions(): List<SubscriptionRow>

    /** Suit ces artistes ; le premier de la liste devient le plus récent, un artiste déjà suivi garde sa date. */
    suspend fun subscribe(rows: List<SubscriptionRow>)
    suspend fun unsubscribe(browseId: String)
}

/** Favoris, playlists et historique : tout reste sur le téléphone, sans compte. */
class Library(context: Context, private val now: () -> Long = System::currentTimeMillis) : SubscriptionStore {
    private val dao = Room.databaseBuilder(context, LibraryDb::class.java, "library.db")
        .addMigrations(*LibraryMigrations.ALL)
        .build().dao()

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

    /** Favoris importés, le premier du fichier devenant le plus récent ; les favoris existants sont gardés tels quels. */
    suspend fun importFavorites(tracks: List<Track>) {
        val t = now()
        dao.addFavoritesIfAbsent(tracks.distinctBy { it.id }.mapIndexed { i, tr -> FavoriteRow(tr.toCols(), t - i) })
    }

    /** Playlist importée : complète la playlist du même nom si elle existe (reprise d'un import), sinon la crée. */
    suspend fun importPlaylist(name: String, tracks: List<Track>): Int {
        val clean = name.trim().take(60).ifBlank { "Import" }
        val pid = dao.playlistIdByName(clean) ?: createPlaylist(clean)
        return addToPlaylist(pid, tracks)
    }

    suspend fun setSaved(row: SavedRow, on: Boolean) {
        if (on) dao.save(row.copy(addedAt = now())) else dao.unsave(row.browseId)
    }

    // --- Téléchargements ---

    val downloads: Flow<List<DownloadRow>> = dao.downloads()
    suspend fun allDownloads(): List<DownloadRow> = dao.allDownloads()
    suspend fun download(id: String): DownloadRow? = dao.download(id)

    /** Ajoute les titres absents à la liste des téléchargements ; renvoie ceux qui sont nouveaux. */
    suspend fun addDownloads(tracks: List<Track>): List<Track> {
        val known = dao.allDownloads().map { it.track.trackId }.toSet()
        val fresh = tracks.distinctBy { it.id }.filter { it.id !in known }
        val t = now()
        dao.insertDownloads(fresh.mapIndexed { i, tr -> DownloadRow(tr.toCols(), DownloadState.QUEUED, 0, 0, 0, null, t - i) })
        return fresh
    }

    // --- Abonnements ---

    override val subscriptions: Flow<List<SubscriptionRow>> = dao.subscriptions()
    override suspend fun allSubscriptions(): List<SubscriptionRow> = dao.allSubscriptions()

    override suspend fun subscribe(rows: List<SubscriptionRow>) {
        val t = now()
        dao.subscribe(rows.distinctBy { it.browseId }.mapIndexed { i, r -> r.copy(addedAt = t - i) })
    }

    override suspend fun unsubscribe(browseId: String) = dao.unsubscribe(browseId)

    suspend fun setDownloadState(id: String, state: Int, bytes: Long, total: Long) = dao.setDownloadState(id, state, bytes, total)
    suspend fun setDownloadFormat(id: String, itag: Int, loudnessDb: Double?) = dao.setDownloadFormat(id, itag, loudnessDb)
    suspend fun deleteDownload(id: String) = dao.deleteDownload(id)
}
