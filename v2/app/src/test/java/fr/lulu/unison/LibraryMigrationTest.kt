package fr.lulu.unison

import androidx.sqlite.db.SupportSQLiteDatabase
import fr.lulu.unison.library.LibraryMigrations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * Les migrations écrites à la main doivent donner exactement les tables que Room attend :
 * - les créations de tables sont comparées au SQL que Room génère pour la base ;
 * - la migration 3 → 4 est jouée sur une vraie base SQLite et comparée au schéma exporté (schemas/…/4.json).
 */
class LibraryMigrationTest {
    private fun sqlOf(migrate: (SupportSQLiteDatabase) -> Unit): List<String> {
        val sql = mutableListOf<String>()
        val db = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, m, args ->
            if (m.name == "execSQL") sql += args[0] as String
            null
        } as SupportSQLiteDatabase
        migrate(db)
        return sql
    }

    /** Une base SupportSQLite minimale qui exécute le SQL sur [connection]. */
    private fun jdbc(connection: Connection) = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, m, args ->
        if (m.name == "execSQL") connection.createStatement().use { it.execute(args[0] as String) }
        null
    } as SupportSQLiteDatabase

    @Test fun migrationsMatchRoomSchema() {
        val generated = File("build/generated/ksp/debug/kotlin/fr/lulu/unison/library/LibraryDb_Impl.kt")
        assumeTrue("code Room généré absent", generated.exists())
        val text = generated.readText()
        for (m in LibraryMigrations.ALL) {
            // La table `download` a changé depuis sa création (3 → 4) : la chaîne complète est vérifiée plus bas.
            for (statement in sqlOf { m.migrate(it) }.filter { it.startsWith("CREATE TABLE") && !it.contains("`download`") }) {
                assertTrue("SQL de migration ${m.startVersion}→${m.endVersion} différent de Room : $statement", text.contains(statement))
            }
        }
    }

    private data class Column(val name: String, val type: String, val notNull: Boolean, val default: String?)

    private fun columns(c: Connection, table: String): Set<Column> = c.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            buildSet {
                while (rs.next()) add(Column(rs.getString("name"), rs.getString("type"), rs.getInt("notnull") == 1, rs.getString("dflt_value")))
            }
        }
    }

    @Test fun downloadsKeepTheirRowsAndGainSourceAndFormat() {
        val schema = File("schemas/fr.lulu.unison.library.LibraryDb/4.json")
        assumeTrue("schéma exporté absent", schema.exists())
        val download = Json.parseToJsonElement(schema.readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject }.first { it["tableName"]!!.jsonPrimitive.content == "download" }
        val createV4 = download["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "download")

        DriverManager.getConnection("jdbc:sqlite::memory:").use { old ->
            // Base de la 2.2 (version 3) : la table telle que la créent les migrations précédentes, avec un titre téléchargé.
            sqlOf { LibraryMigrations.V1_V2.migrate(it) }.forEach { sql -> old.createStatement().use { it.execute(sql) } }
            old.createStatement().use {
                it.execute(
                    "INSERT INTO download (state, bytes, totalBytes, itag, loudnessDb, addedAt, trackId, title, artist, album, cover, durationMs, explicit) " +
                        "VALUES (3, 100, 100, 251, -2.5, 1, 'khnokW3Mw24', 'Titre', 'Artiste', '', '', 200000, 0)",
                )
            }
            LibraryMigrations.V3_V4.migrate(jdbc(old))

            DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
                fresh.createStatement().use { it.execute(createV4) }
                assertEquals("table migrée différente de celle de Room", columns(fresh, "download"), columns(old, "download"))
            }
            old.createStatement().use { st ->
                st.executeQuery("SELECT trackId, itag, sourceId, codec, bitDepth FROM download").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("khnokW3Mw24", rs.getString(1))
                    assertEquals(251, rs.getInt(2))
                    assertEquals("yt", rs.getString(3))
                    assertEquals(null, rs.getString(4))
                    assertEquals(null, rs.getObject(5))
                }
            }
        }
        // Le schéma exporté décrit bien les nouvelles colonnes comme Room les valide.
        val fields = download["fields"]!!.jsonArray.map { it.jsonObject }.associateBy { (it["columnName"] as kotlinx.serialization.json.JsonPrimitive).content }
        assertEquals("'yt'", fields["sourceId"]!!["defaultValue"]!!.jsonPrimitive.content)
        assertEquals(false, (fields["codec"] as JsonObject)["notNull"]?.jsonPrimitive?.content?.toBoolean() ?: false)
    }
}
