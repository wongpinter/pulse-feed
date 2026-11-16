package com.simon.harmonichackernews.feed.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.simon.harmonichackernews.feed.model.FeedItem

/**
 * SQLite-backed storage for cached feed articles.
 *
 * The previous layout serialized every cached article into one JSON string, so a single read toggle
 * or a single fetched full article rewrote megabytes. Articles are rows here: a read flag is one
 * `UPDATE`, a fetched body is one `UPDATE`, and retention is one `DELETE`.
 *
 * The database is synchronous by design — the driver is blocking anyway — and is serialized by the
 * owning [FeedSubscriptionStore]'s dispatcher and mutex, so no connection is shared concurrently.
 *
 * Feeds, folders, and view preferences stay in [com.simon.harmonichackernews.settings.KeyValueStore];
 * they are a few kilobytes and never rewritten per article.
 */
class FeedItemDatabase(
    path: String,
    private val driver: SQLiteDriver = BundledSQLiteDriver(),
) {
    private val connection: SQLiteConnection = driver.open(path.withCreatedParent()).apply { configure() }

    private fun SQLiteConnection.configure() {
        if (!isMemoryDatabase()) {
            // WAL keeps a write from blocking readers; the bundled engine defaults to the rollback
            // journal, so WAL has to be requested explicitly.
            execSQL("PRAGMA journal_mode=WAL")
            execSQL("PRAGMA synchronous=NORMAL")
        }
        execSQL("PRAGMA foreign_keys=ON")
        execSQL(
            """
            CREATE TABLE IF NOT EXISTS items (
                id TEXT NOT NULL PRIMARY KEY,
                feed_id TEXT NOT NULL,
                title TEXT NOT NULL,
                url TEXT NOT NULL,
                author TEXT,
                published_epoch_ms INTEGER NOT NULL DEFAULT 0,
                content_html TEXT,
                content_text TEXT,
                full_content_html TEXT,
                full_content_fetched_epoch_ms INTEGER,
                lead_image_url TEXT,
                is_read INTEGER NOT NULL DEFAULT 0,
                is_bookmarked INTEGER NOT NULL DEFAULT 0,
                topics TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        execSQL("CREATE INDEX IF NOT EXISTS items_published ON items(published_epoch_ms DESC)")
        execSQL("CREATE INDEX IF NOT EXISTS items_feed_published ON items(feed_id, published_epoch_ms DESC)")
        execSQL("CREATE INDEX IF NOT EXISTS items_saved ON items(is_bookmarked, is_read)")
        createFullTextIndex()
    }

    private fun SQLiteConnection.isMemoryDatabase(): Boolean = this.prepare("PRAGMA database_list")
        .let { statement ->
            try {
                if (!statement.step()) true else statement.isNull(2) || statement.getText(2).isBlank()
            } finally {
                statement.close()
            }
        }

    /**
     * External-content FTS5 index. Triggers keep it in step with [items] so article bodies are stored
     * once, not twice. If a build lacks FTS5 the caller still gets a working store; search degrades
     * to a scan.
     */
    private fun SQLiteConnection.createFullTextIndex() {
        val created = runCatching {
            execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS items_fts USING fts5(
                    title, author, content_text,
                    content='items',
                    content_rowid='rowid'
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS items_fts_insert AFTER INSERT ON items BEGIN
                    INSERT INTO items_fts(rowid, title, author, content_text)
                    VALUES (new.rowid, new.title, new.author, new.content_text);
                END
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS items_fts_delete AFTER DELETE ON items BEGIN
                    INSERT INTO items_fts(items_fts, rowid, title, author, content_text)
                    VALUES ('delete', old.rowid, old.title, old.author, old.content_text);
                END
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS items_fts_update AFTER UPDATE ON items BEGIN
                    INSERT INTO items_fts(items_fts, rowid, title, author, content_text)
                    VALUES ('delete', old.rowid, old.title, old.author, old.content_text);
                    INSERT INTO items_fts(rowid, title, author, content_text)
                    VALUES (new.rowid, new.title, new.author, new.content_text);
                END
                """.trimIndent(),
            )
        }
        fullTextAvailable = created.isSuccess
    }

    /** False when the engine was built without FTS5; search then falls back to a table scan. */
    var fullTextAvailable: Boolean = false
        private set

    fun loadItems(limit: Int): List<FeedItem> = prepareEach(
        sql = SELECT_COLUMNS + " FROM items ORDER BY published_epoch_ms DESC LIMIT ?",
        bind = { it.bindLong(1, limit.toLong()) },
        read = ::readItem,
    )

    fun itemCount(): Int = prepare("SELECT COUNT(*) FROM items") { statement ->
        if (statement.step()) statement.getLong(0).toInt() else 0
    }

    fun upsertItems(items: List<FeedItem>) {
        if (items.isEmpty()) return
        transaction {
            prepare(UPSERT_ITEM) { statement ->
                for (item in items) {
                    // A finished step leaves the statement in DONE; rebinding without reset is misuse.
                    statement.reset()
                    statement.clearBindings()
                    statement.bindText(1, item.id)
                    statement.bindText(2, item.feedId)
                    statement.bindText(3, item.title)
                    statement.bindText(4, item.url)
                    statement.bindTextOrNull(5, item.author)
                    statement.bindLong(6, item.publishedEpochMs)
                    statement.bindTextOrNull(7, item.contentHtml)
                    statement.bindTextOrNull(8, item.contentText)
                    statement.bindTextOrNull(9, item.fullContentHtml)
                    statement.bindLongOrNull(10, item.fullContentFetchedEpochMs)
                    statement.bindTextOrNull(11, item.leadImageUrl)
                    statement.bindBoolean(12, item.isRead)
                    statement.bindBoolean(13, item.isBookmarked)
                    statement.bindText(14, item.topics.joinToString(TOPIC_SEPARATOR))
                    check(!statement.step())
                }
                statement.reset()
                statement.clearBindings()
            }
        }
    }

    fun setRead(itemId: String, isRead: Boolean) = update(
        "UPDATE items SET is_read = ? WHERE id = ?",
    ) { statement ->
        statement.bindBoolean(1, isRead)
        statement.bindText(2, itemId)
    }

    fun setBookmarked(itemId: String, isBookmarked: Boolean) = update(
        "UPDATE items SET is_bookmarked = ? WHERE id = ?",
    ) { statement ->
        statement.bindBoolean(1, isBookmarked)
        statement.bindText(2, itemId)
    }

    fun setFeedRead(feedId: String) {
        execute("UPDATE items SET is_read = 1 WHERE feed_id = ? AND is_read = 0") { statement ->
            statement.bindText(1, feedId)
        }
    }

    fun setFullArticle(itemId: String, html: String, text: String, fetchedEpochMs: Long) = update(
        """
        UPDATE items
        SET full_content_html = ?, content_text = ?, full_content_fetched_epoch_ms = ?
        WHERE id = ?
        """.trimIndent(),
    ) { statement ->
        statement.bindText(1, html)
        statement.bindText(2, text)
        statement.bindLong(3, fetchedEpochMs)
        statement.bindText(4, itemId)
    }

    fun deleteFeedItems(feedId: String) {
        execute("DELETE FROM items WHERE feed_id = ?") { it.bindText(1, feedId) }
    }

    /**
     * Enforces the retention bound in SQL: bookmarked articles are always kept, and of the rest the
     * already-read ones go first so unread content outlives content the user has seen.
     */
    fun trimToRetention(maxItems: Int) {
        val saved = prepare("SELECT COUNT(*) FROM items WHERE is_bookmarked = 1") { statement ->
            if (statement.step()) statement.getLong(0).toInt() else 0
        }
        val allowedUnsaved = (maxItems - saved).coerceAtLeast(0)
        transaction {
            execute(
                """
                DELETE FROM items WHERE is_bookmarked = 0 AND id NOT IN (
                    SELECT id FROM items WHERE is_bookmarked = 0
                    ORDER BY is_read ASC, published_epoch_ms DESC
                    LIMIT $allowedUnsaved
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * Ranks stored articles the same way the in-memory timeline does — title prefix, title, author,
     * body, then recency — but only over rows the FTS index matched, so the scan stays proportional
     * to the hit set rather than the whole library.
     */
    fun search(query: String, limit: Int): List<FeedItem> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty() || limit <= 0) return emptyList()
        val ftsQuery = buildFullTextQuery(needle)
        if (fullTextAvailable && ftsQuery != null) {
            val hits = runCatching {
                searchRows(
                    where = "WHERE rowid IN (SELECT rowid FROM items_fts WHERE items_fts MATCH ?)",
                    matchQuery = ftsQuery,
                    likePatterns = emptyList(),
                    needle = needle,
                    limit = limit,
                )
            }
            if (hits.isSuccess) return hits.getOrThrow()
        }
        // FTS5 absent, or the engine rejected the rewritten query: fall back to a plain scan.
        val escaped = escapeLike(needle)
        val contains = "%$escaped%"
        return searchRows(
            where = "WHERE lower(title) LIKE ? ESCAPE '\\' OR lower(ifnull(author, '')) LIKE ? ESCAPE '\\' " +
                "OR lower(ifnull(content_text, '')) LIKE ? ESCAPE '\\'",
            matchQuery = null,
            likePatterns = listOf(contains, contains, contains),
            needle = needle,
            limit = limit,
        )
    }

    private fun searchRows(
        where: String,
        matchQuery: String?,
        likePatterns: List<String>,
        needle: String,
        limit: Int,
    ): List<FeedItem> {
        val escapedNeedle = escapeLike(needle)
        val sql = SELECT_COLUMNS + " FROM items " + where + """
             ORDER BY CASE
                WHEN lower(title) LIKE ? ESCAPE '\' THEN 0
                WHEN lower(title) LIKE ? ESCAPE '\' THEN 1
                WHEN lower(ifnull(author, '')) LIKE ? ESCAPE '\' THEN 2
                WHEN lower(ifnull(content_text, '')) LIKE ? ESCAPE '\' THEN 3
                ELSE 4
             END, published_epoch_ms DESC
             LIMIT ?
        """.trimIndent()
        return prepareEach(
            sql = sql,
            bind = { statement ->
                var index = 1
                if (matchQuery != null) statement.bindText(index++, matchQuery)
                for (pattern in likePatterns) statement.bindText(index++, pattern)
                statement.bindText(index++, "$escapedNeedle%")
                statement.bindText(index++, "%$escapedNeedle%")
                statement.bindText(index++, "%$escapedNeedle%")
                statement.bindText(index++, "%$escapedNeedle%")
                statement.bindLong(index, limit.toLong())
            },
            read = ::readItem,
        )
    }

    private fun escapeLike(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    /**
     * FTS5 needs explicit syntax; free text can carry quotes, operators, or punctuation that would
     * otherwise be parsed as a query. Every token is quoted and prefix-matched, so a half-typed word
     * still hits.
     */
    private fun buildFullTextQuery(needle: String): String? {
        val tokens = needle
            .split(' ', '\n', '\t')
            .map { token -> token.filter { it.isLetterOrDigit() || it == '_' } }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "\"$it\"*" }
    }

    fun close() {
        runCatching { connection.close() }
    }

    private fun <T> prepare(sql: String, block: (SQLiteStatement) -> T): T {
        val statement = connection.prepare(sql)
        try {
            return block(statement)
        } finally {
            statement.close()
        }
    }

    private fun <T> prepareEach(
        sql: String,
        bind: (SQLiteStatement) -> Unit,
        read: (SQLiteStatement) -> T,
    ): List<T> = prepare(sql) { statement ->
        bind(statement)
        val rows = ArrayList<T>()
        try {
            while (statement.step()) rows.add(read(statement))
        } finally {
            statement.reset()
        }
        rows
    }

    private fun update(sql: String, bind: (SQLiteStatement) -> Unit) = execute(sql, bind)

    private fun execute(sql: String, bind: (SQLiteStatement) -> Unit = {}) {
        prepare(sql) { statement ->
            bind(statement)
            statement.step()
        }
    }

    private fun <T> transaction(block: () -> T): T {
        if (connection.inTransaction()) return block()
        connection.execSQL("BEGIN IMMEDIATE")
        val result = try {
            block().also { connection.execSQL("COMMIT") }
        } catch (error: Throwable) {
            runCatching { connection.execSQL("ROLLBACK") }
            throw error
        }
        return result
    }

    private fun readItem(statement: SQLiteStatement): FeedItem = FeedItem(
        id = statement.getText(0),
        feedId = statement.getText(1),
        title = statement.getText(2),
        url = statement.getText(3),
        author = statement.textOrNull(4),
        publishedEpochMs = statement.getLong(5),
        contentHtml = statement.textOrNull(6),
        contentText = statement.textOrNull(7),
        fullContentHtml = statement.textOrNull(8),
        fullContentFetchedEpochMs = if (statement.isNull(9)) null else statement.getLong(9),
        leadImageUrl = statement.textOrNull(10),
        isRead = statement.getLong(11) != 0L,
        isBookmarked = statement.getLong(12) != 0L,
        topics = statement.getText(13)
            .split(TOPIC_SEPARATOR)
            .filter { it.isNotEmpty() },
    )

    private companion object {
        const val TOPIC_SEPARATOR = "|"

        val SELECT_COLUMNS = """
            SELECT id, feed_id, title, url, author, published_epoch_ms, content_html, content_text,
                   full_content_html, full_content_fetched_epoch_ms, lead_image_url, is_read,
                   is_bookmarked, topics
        """.trimIndent()

        val UPSERT_ITEM = """
            INSERT INTO items (id, feed_id, title, url, author, published_epoch_ms, content_html,
                               content_text, full_content_html, full_content_fetched_epoch_ms,
                               lead_image_url, is_read, is_bookmarked, topics)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                feed_id = excluded.feed_id,
                title = excluded.title,
                url = excluded.url,
                author = excluded.author,
                published_epoch_ms = excluded.published_epoch_ms,
                content_html = excluded.content_html,
                content_text = excluded.content_text,
                full_content_html = excluded.full_content_html,
                full_content_fetched_epoch_ms = excluded.full_content_fetched_epoch_ms,
                lead_image_url = excluded.lead_image_url,
                is_read = excluded.is_read,
                is_bookmarked = excluded.is_bookmarked,
                topics = excluded.topics
        """.trimIndent()
    }
}

/**
 * SQLite creates the database file but not the directory holding it, and every host keeps this file
 * in its own app-scoped storage tree.
 */
private fun String.withCreatedParent(): String {
    if (this == ":memory:" || isBlank()) return this
    val parent = substringBeforeLast('/', missingDelimiterValue = "")
    if (parent.isNotEmpty()) {
        runCatching {
            kotlinx.io.files.SystemFileSystem.createDirectories(kotlinx.io.files.Path(parent))
        }
    }
    return this
}

private fun SQLiteStatement.bindTextOrNull(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindText(index, value)
}

private fun SQLiteStatement.bindLongOrNull(index: Int, value: Long?) {
    if (value == null) bindNull(index) else bindLong(index, value)
}

private fun SQLiteStatement.textOrNull(index: Int): String? =
    if (isNull(index)) null else getText(index)