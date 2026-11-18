package fr.lulu.unison

import androidx.sqlite.db.SupportSQLiteDatabase
import fr.lulu.unison.library.LibraryMigrations
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * Les migrations écrites à la main doivent créer exactement les tables que Room attend :
 * on compare leur SQL à celui que Room génère pour la base (schéma non exporté).
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

    @Test fun migrationsMatchRoomSchema() {
        val generated = File("build/generated/ksp/debug/kotlin/fr/lulu/unison/library/LibraryDb_Impl.kt")
        assumeTrue("code Room généré absent", generated.exists())
        val text = generated.readText()
        for (m in LibraryMigrations.ALL) {
            for (statement in sqlOf { m.migrate(it) }) {
                assertTrue("SQL de migration ${m.startVersion}→${m.endVersion} différent de Room : $statement", text.contains(statement))
            }
        }
    }
}
