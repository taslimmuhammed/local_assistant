package com.local.assistant

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Guards the reason this app ships its own SQLite.
 *
 * Android's platform SQLite is compiled without FTS5 -- `CREATE VIRTUAL TABLE ...
 * USING fts5` fails with "no such module: fts5" even on API 36 -- so the recall
 * layer runs on `androidx.sqlite:sqlite-bundled` instead. If a future dependency
 * bump ever ships a build without FTS5 or without `bm25()`, this fails loudly here
 * rather than silently degrading search in the app.
 */
@RunWith(AndroidJUnit4::class)
class Fts5ProbeTest {
    @Test
    fun bundledSqliteHasFts5() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File(ctx.cacheDir, "probe-${System.nanoTime()}.db")
        val conn = BundledSQLiteDriver().open(f.absolutePath)
        conn.prepare("CREATE VIRTUAL TABLE t USING fts5(x)").use { it.step() }
        conn.prepare("INSERT INTO t(x) VALUES ('hello world')").use { it.step() }
        conn.prepare("SELECT x, bm25(t) FROM t WHERE t MATCH 'hello' ORDER BY bm25(t)").use {
            check(it.step()) { "no rows" }
            println("FTS5_OK text=" + it.getText(0) + " bm25=" + it.getDouble(1))
        }
        conn.prepare("SELECT sqlite_version()").use {
            it.step(); println("SQLITE_VERSION=" + it.getText(0))
        }
        conn.close(); f.delete()
    }
}
