package com.gptgongjakso.naverwriterhelper.store.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.gptgongjakso.naverwriterhelper.model.PostHistoryRecord

/**
 * 발행/작성 이력 DB (지시서 18). v1.0.0 신규.
 *
 * Room/어노테이션 프로세서 대신 SQLiteOpenHelper 를 사용해 빌드 의존성을 최소화한다
 * (v0.1.1 과 동일한 의존성 집합 유지 → CI 빌드 안정성). 중복 판정 로직은 순수 클래스
 * [com.gptgongjakso.naverwriterhelper.dedup.DuplicateChecker] 가 담당하고,
 * 이 클래스는 저장/조회만 책임진다.
 *
 * 저장 위치는 앱 내부 DB(백업 제외 대상, data_extraction_rules 로 클라우드/D2D 백업에서 제외).
 */
class HistoryStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "gptgongjakso_history.db"
        private const val DB_VERSION = 1
        private const val TABLE = "post_history"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                post_id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                body_text TEXT NOT NULL,
                board_key TEXT NOT NULL,
                topic_key TEXT,
                topic_angle TEXT,
                tags TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                publish_status TEXT NOT NULL,
                zip_sha256 TEXT NOT NULL,
                body_sha256 TEXT NOT NULL,
                body_fingerprint TEXT NOT NULL,
                content_version INTEGER NOT NULL,
                last_verdict TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_zip ON $TABLE(zip_sha256)")
        db.execSQL("CREATE INDEX idx_body ON $TABLE(body_sha256)")
        db.execSQL("CREATE INDEX idx_fp ON $TABLE(body_fingerprint)")
        db.execSQL("CREATE INDEX idx_created ON $TABLE(created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 초기 버전. 향후 스키마 변경 시 마이그레이션 추가.
    }

    /** 이력 저장(동일 post_id 는 대체). */
    fun upsert(record: PostHistoryRecord) {
        val values = ContentValues().apply {
            put("post_id", record.postId)
            put("title", record.title)
            put("body_text", record.bodyText)
            put("board_key", record.boardKey)
            put("topic_key", record.topicKey)
            put("topic_angle", record.topicAngle)
            put("tags", record.tags.joinToString("\u001F"))
            put("created_at", record.createdAt)
            put("publish_status", record.publishStatus)
            put("zip_sha256", record.zipSha256)
            put("body_sha256", record.bodySha256)
            put("body_fingerprint", record.bodyFingerprint)
            put("content_version", record.contentVersion)
            put("last_verdict", record.lastVerdict)
        }
        writableDatabase.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 전체 이력(중복 검사 입력용). 규모가 작아 메모리 로드 후 판정. */
    fun all(): List<PostHistoryRecord> {
        val list = ArrayList<PostHistoryRecord>()
        readableDatabase.rawQuery("SELECT * FROM $TABLE ORDER BY created_at DESC", null).use { c ->
            while (c.moveToNext()) list.add(fromCursor(c))
        }
        return list
    }

    /** 특정 시각 이후 이력만(주의 판정 최적화용, 선택 사용). */
    fun since(sinceMillis: Long): List<PostHistoryRecord> {
        val list = ArrayList<PostHistoryRecord>()
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE WHERE created_at >= ? ORDER BY created_at DESC",
            arrayOf(sinceMillis.toString())
        ).use { c -> while (c.moveToNext()) list.add(fromCursor(c)) }
        return list
    }

    /** 사용자 확인 발행 상태 갱신(지시서 19). */
    fun updatePublishStatus(postId: String, status: String) {
        val values = ContentValues().apply { put("publish_status", status) }
        writableDatabase.update(TABLE, values, "post_id = ?", arrayOf(postId))
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 이력 전체 삭제(초기화). */
    fun clearAll() {
        writableDatabase.delete(TABLE, null, null)
    }

    private fun fromCursor(c: android.database.Cursor): PostHistoryRecord {
        fun s(name: String): String = c.getString(c.getColumnIndexOrThrow(name)) ?: ""
        fun sn(name: String): String? = c.getString(c.getColumnIndexOrThrow(name))
        fun i(name: String): Int = c.getInt(c.getColumnIndexOrThrow(name))
        fun l(name: String): Long = c.getLong(c.getColumnIndexOrThrow(name))
        val tags = s("tags").split("\u001F").filter { it.isNotEmpty() }
        return PostHistoryRecord(
            postId = s("post_id"),
            title = s("title"),
            bodyText = s("body_text"),
            boardKey = s("board_key"),
            topicKey = sn("topic_key"),
            topicAngle = sn("topic_angle"),
            tags = tags,
            createdAt = l("created_at"),
            publishStatus = s("publish_status"),
            zipSha256 = s("zip_sha256"),
            bodySha256 = s("body_sha256"),
            bodyFingerprint = s("body_fingerprint"),
            contentVersion = i("content_version"),
            lastVerdict = s("last_verdict")
        )
    }
}
