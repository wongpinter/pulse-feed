package com.simon.harmonichackernews.feed.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the bundled SQLite driver works on every non-iOS KMP target before the feed store depends
 * on it: statements bind and read, FTS5 is compiled in, and WAL is available for file databases.
 */
class FeedSqliteDriverTest {

    @Test
    fun bundledDriverBindsReadsAndReportsFts5() {
        BundledSQLiteDriver().open(":memory:").use { connection ->
            connection.execSQL(
                """
                CREATE TABLE items (
                    id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    published INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            connection.prepare("INSERT INTO items (id, title, published) VALUES (?, ?, ?)").use { insert ->
                insert.bindText(1, "a")
                insert.bindText(2, "Kotlin coroutines")
                insert.bindLong(3, 1_700_000_000_000L)
                // INSERT steps return false: there is no result row, and the write already happened.
                assertFalse(insert.step())
                insert.reset()
                insert.clearBindings()
            }

            connection.prepare("SELECT title, published FROM items WHERE id = ?").use { select ->
                select.bindText(1, "a")
                assertTrue(select.step())
                assertEquals("Kotlin coroutines", select.getText(0))
                assertEquals(1_700_000_000_000L, select.getLong(1))
                assertTrue(!select.step())
            }

            val ftsAvailable = runCatching {
                connection.execSQL("CREATE VIRTUAL TABLE fts_probe USING fts5(title)")
            }.isSuccess
            assertTrue(ftsAvailable, "bundled SQLite must compile FTS5 for stored-article search")
        }
    }

    @Test
    fun fileDatabaseEnablesWalAndPersists() {
        val path = java.io.File.createTempFile("pulse-feed", ".db").apply { delete() }.absolutePath
        try {
            BundledSQLiteDriver().open(path).use { connection ->
                // The bundled engine keeps the default rollback journal; WAL is opt-in per database.
                connection.execSQL("PRAGMA journal_mode=WAL")
                assertEquals("wal", pragma(connection, "journal_mode"))
                connection.execSQL("PRAGMA synchronous=NORMAL")
                connection.execSQL("CREATE TABLE t (v TEXT)")
                connection.execSQL("INSERT INTO t VALUES ('persisted')")
            }
            BundledSQLiteDriver().open(path).use { connection ->
                connection.prepare("SELECT v FROM t").use { select ->
                    assertTrue(select.step())
                    assertEquals("persisted", select.getText(0))
                }
            }
        } finally {
            SystemFileSystem.delete(Path(path), mustExist = false)
        }
    }

    private fun pragma(connection: SQLiteConnection, name: String): String =
        connection.prepare("PRAGMA $name").use { statement ->
            if (!statement.step()) "unknown" else statement.getText(0).lowercase()
        }
}