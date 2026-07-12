package com.gptgongjakso.naverwriterhelper.parser

import com.gptgongjakso.naverwriterhelper.dedup.ContentFingerprint
import com.gptgongjakso.naverwriterhelper.model.ImageRole
import com.gptgongjakso.naverwriterhelper.model.NaverPostData
import com.gptgongjakso.naverwriterhelper.model.ParsedImage
import com.gptgongjakso.naverwriterhelper.model.PostMetadata
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** 자료 ZIP 파싱 중 발생하는 검증 예외 (사용자에게 메시지 표시). */
class PackageParseException(message: String) : IllegalArgumentException(message)

/**
 * naver_package.zip 을 파싱한다.
 *
 * v0.1.1 검증 보안 로직(자원 제한/압축폭탄/경로탈출/제어문자/중복 방어)을 그대로 이식한다.
 * (파싱 우선순위/파일명 규칙/제한 상수는 변경하지 않음)
 *
 * v1.0.0 추가(최소 변경):
 *  - ZIP 원본 SHA-256 계산(스트림 소비 바이트 기준, 중복 검사용) — 지시서 8/18
 *  - metadata.json 을 schema 2.1(PostMetadata)로 파싱 + 구버전 변환 — 지시서 9
 *  - 본문 SHA-256/지문 계산 — 지시서 18
 *  이 추가는 기존 보안 판정 흐름을 바꾸지 않는다.
 */
object PackageParser {

    // ---------- 자원 제한 상수 (v0.1.1 유지) ----------
    private const val MAX_ZIP_INPUT_BYTES = 50L * 1024 * 1024          // 원본 ZIP 최대 50MB
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 100L * 1024 * 1024 // 전체 해제 최대 100MB
    private const val MAX_ENTRY_UNCOMPRESSED_BYTES = 25L * 1024 * 1024  // 단일 엔트리 최대 25MB
    private const val MAX_TEXT_ENTRY_BYTES = 2L * 1024 * 1024           // 텍스트 최대 2MB
    private const val MAX_ENTRY_COUNT = 50                             // 처리 엔트리 최대 50개
    private const val MAX_IMAGE_COUNT = 10                             // 이미지 최대 10개
    private const val MAX_COMPRESSION_RATIO = 100L                     // 최대 압축률(해제/압축)

    /** 이미지로 인정하는 확장자 (기존 v0.1 호환: webp 유지, 동일 제한 적용) */
    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp")

    /** 읽어들이는 텍스트/메타 파일명 (그 외 확장자는 읽지 않고 건너뜀) */
    private val TEXT_META_NAMES = setOf(
        "title.txt", "content.txt", "content.md", "content.html",
        "tags.txt", "naver_tags.txt", "metadata.json"
    )

    /** body-01, body01, body_1 등에서 숫자를 뽑기 위한 정규식 */
    private val BODY_INDEX_REGEX = Regex("body[^0-9]*([0-9]+)", RegexOption.IGNORE_CASE)

    /**
     * ZIP InputStream 을 파싱한다.
     * @throws PackageParseException 유효한 자료가 없거나 제한을 초과한 경우
     */
    fun parse(input: InputStream): NaverPostData {
        val entries = LinkedHashMap<String, ByteArray>()
        val keptLower = HashSet<String>()
        var entryCount = 0
        var totalUncompressed = 0L
        var imageCount = 0

        // 원본 ZIP 크기 제한 + SHA-256(v1.0.0 추가) : 읽은 바이트로 카운트/해시
        val counting = CountingInputStream(input, MAX_ZIP_INPUT_BYTES)

        try {
            ZipInputStream(counting.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    // (1) 전체 엔트리 수 제한 (스킵 대상 포함) → 다량 엔트리 ZIP 방어
                    entryCount++
                    if (entryCount > MAX_ENTRY_COUNT) {
                        throw PackageParseException("ZIP 안의 파일 수가 너무 많습니다. (최대 ${MAX_ENTRY_COUNT}개)")
                    }
                    if (!entry.isDirectory) {
                        val name = baseName(entry.name)           // (9) 디렉터리 경로는 저장 경로로 쓰지 않음
                        validateName(name)                        // (8) 빈/제어문자 이름 거부

                        val ext = name.substringAfterLast('.', "").lowercase()
                        val lower = name.lowercase()
                        val isText = lower in TEXT_META_NAMES
                        val isImage = ext in IMAGE_EXTS && isAllowedImageName(lower)

                        if (isText || isImage) {
                            // (7) 같은 basename 중복 거부(덮어쓰지 않음)
                            if (!keptLower.add(lower)) {
                                throw PackageParseException("같은 이름의 파일이 중복되어 있습니다: $name")
                            }
                            // (5) 이미지 수 제한
                            if (isImage) {
                                imageCount++
                                if (imageCount > MAX_IMAGE_COUNT) {
                                    throw PackageParseException("이미지 파일이 너무 많습니다. (최대 ${MAX_IMAGE_COUNT}개)")
                                }
                            }

                            // (3)(4)(10) 엔트리별 크기 제한 + OOM 이전 중단
                            val perEntryCap = if (isText) MAX_TEXT_ENTRY_BYTES else MAX_ENTRY_UNCOMPRESSED_BYTES
                            val bytes = readEntryLimited(zis, perEntryCap, isText)

                            // (2) 전체 해제 누적 제한
                            totalUncompressed += bytes.size
                            if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                throw PackageParseException("압축 해제 크기가 비정상적으로 큽니다.")
                            }

                            // (6) 비정상 고압축률(압축 폭탄) 방어 (엔트리를 다 읽은 뒤 압축크기 확인)
                            val compressed = entry.compressedSize
                            if (compressed in 1..Long.MAX_VALUE) {
                                val ratio = bytes.size.toLong() / compressed
                                if (ratio > MAX_COMPRESSION_RATIO) {
                                    throw PackageParseException("비정상적으로 높은 압축률이 감지되었습니다.")
                                }
                            }

                            entries[name] = bytes
                        }
                        // (11) 허용 확장자 외 파일은 읽지 않고 건너뜀
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: java.io.IOException) {
            // 손상되었거나 올바르지 않은 ZIP → 앱을 죽이지 않고 친절한 예외로 중단
            throw PackageParseException("자료 ZIP을 읽는 중 오류가 발생했습니다. 손상되었거나 올바른 ZIP이 아닐 수 있습니다.")
        }

        if (entries.isEmpty()) {
            throw PackageParseException("ZIP 안에서 사용할 수 있는 자료 파일을 찾지 못했습니다.")
        }

        // 대소문자 무시 조회용 (소문자 파일명 -> 원본 파일명)
        val lowerIndex = HashMap<String, String>()
        for (key in entries.keys) lowerIndex[key.lowercase()] = key

        fun textOf(fileName: String): String? {
            val original = lowerIndex[fileName.lowercase()] ?: return null
            return entries[original]?.toString(Charsets.UTF_8)
        }

        // metadata.json → PostMetadata (schema 2.1, 구버전 변환) : v1.0.0
        val metadata: PostMetadata =
            textOf("metadata.json")?.let { MetadataParser.fromJsonStringOrNull(it) } ?: PostMetadata.EMPTY

        // 제목/본문/태그: 우선순위 규칙 유지(metadata 는 보조)
        val title = parseTitle(textOf("title.txt"), metadata.title, textOf("content.txt") ?: textOf("content.md"))
        val body = parseBody(textOf("content.txt"), textOf("content.md"), textOf("content.html"))
        val rawTags = textOf("tags.txt")
            ?: textOf("naver_tags.txt")
            ?: metadata.tags.takeIf { it.isNotEmpty() }?.joinToString(" ")
        val tags = TagNormalizer.normalize(rawTags)
        val images = parseImages(entries)

        // 해시/지문 (v1.0.0, 중복 검사용)
        val zipSha = counting.hash()
        val bodySha = ContentFingerprint.sha256(body)
        val bodyFp = ContentFingerprint.fingerprint(body)

        return NaverPostData(
            title = title,
            body = body,
            tags = tags,
            images = images,
            metadata = metadata,
            zipSha256 = zipSha,
            bodySha256 = bodySha,
            bodyFingerprint = bodyFp
        )
    }

    // ---------- 제목 ---------- (v0.1.1 규칙 유지, metadata title 은 String? 로 전달)
    private fun parseTitle(titleTxt: String?, metaTitle: String?, contentForFallback: String?): String {
        titleTxt?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.lineSequence().first().trim() }
        metaTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        contentForFallback?.lineSequence()
            ?.map { it.trim().removePrefix("#").trim() }
            ?.firstOrNull { it.isNotEmpty() }
            ?.let { return it.take(100) }
        return "(제목 없음)"
    }

    // ---------- 본문 ---------- (v0.1.1 유지)
    private fun parseBody(contentTxt: String?, contentMd: String?, contentHtml: String?): String {
        contentTxt?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        contentMd?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        contentHtml?.takeIf { it.isNotBlank() }?.let { return htmlToText(it) }
        return ""
    }

    private fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</p>"), "\n")
        s = s.replace(Regex("(?i)</div>"), "\n")
        s = s.replace(Regex("(?i)</h[1-6]>"), "\n")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s.trim()
    }

    // ---------- 이미지 ---------- (v0.1.1 유지 + orderIndex 기록)
    private fun parseImages(entries: Map<String, ByteArray>): List<ParsedImage> {
        var thumbnail: ParsedImage? = null
        val bodyList = ArrayList<Pair<Int, ParsedImage>>()

        for ((name, bytes) in entries) {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in IMAGE_EXTS) continue
            val lower = name.lowercase()
            val mime = mimeOf(ext)

            if (lower.contains("thumbnail") || lower.contains("thumb")) {
                if (thumbnail == null) {
                    thumbnail = ParsedImage(
                        role = ImageRole.THUMBNAIL,
                        originalName = name,
                        saveName = "gptgongjakso_thumbnail.$ext",
                        bytes = bytes,
                        mimeType = mime,
                        orderIndex = 0
                    )
                }
            } else if (lower.contains("body")) {
                val index = BODY_INDEX_REGEX.find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: (bodyList.size + 1)
                bodyList.add(
                    index to ParsedImage(
                        role = ImageRole.BODY,
                        originalName = name,
                        saveName = "gptgongjakso_body_%02d.%s".format(index, ext),
                        bytes = bytes,
                        mimeType = mime,
                        orderIndex = index
                    )
                )
            }
        }

        bodyList.sortBy { it.first }

        val result = ArrayList<ParsedImage>()
        thumbnail?.let { result.add(it) }
        bodyList.forEach { result.add(it.second) }
        return result
    }

    private fun mimeOf(ext: String): String = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    /** 허용 이미지 이름인지 (thumbnail* 또는 body*) */
    private fun isAllowedImageName(lower: String): Boolean =
        lower.contains("thumbnail") || lower.contains("thumb") || lower.contains("body")

    // ---------- 유틸 ---------- (v0.1.1 유지)
    private fun baseName(path: String): String {
        val normalized = path.replace('\\', '/')
        return normalized.substringAfterLast('/')
    }

    /** (8) 파일 이름 검증: 빈 이름/제어문자 거부 */
    private fun validateName(name: String) {
        if (name.isBlank()) {
            throw PackageParseException("ZIP 안에 이름이 없는 파일이 있습니다.")
        }
        if (name.any { it.isISOControl() }) {
            throw PackageParseException("ZIP 안에 비정상적인 파일 이름이 있습니다.")
        }
    }

    /** 엔트리를 최대 maxBytes 까지만 읽는다. 초과 시 OOM 전에 예외. */
    private fun readEntryLimited(zis: ZipInputStream, maxBytes: Long, isText: Boolean): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0L
        var read: Int
        while (zis.read(chunk).also { read = it } != -1) {
            total += read
            if (total > maxBytes) {
                val what = if (isText) "텍스트 파일" else "파일"
                throw PackageParseException("ZIP 내 ${what}이 허용 크기를 초과했습니다.")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /**
     * 원본 ZIP 스트림에서 읽은 누적 바이트가 상한을 넘으면 예외를 던지는 래퍼.
     * (v1.0.0) 동시에 SHA-256 을 계산한다 — 스트림이 소비한 바이트 기준의 결정적 해시.
     */
    private class CountingInputStream(
        private val src: InputStream,
        private val maxBytes: Long
    ) : InputStream() {
        private var count = 0L
        private val digest = MessageDigest.getInstance("SHA-256")

        override fun read(): Int {
            val b = src.read()
            if (b != -1) {
                digest.update(b.toByte())
                bump(1)
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = src.read(b, off, len)
            if (n > 0) {
                digest.update(b, off, n)
                bump(n.toLong())
            }
            return n
        }

        private fun bump(n: Long) {
            count += n
            if (count > maxBytes) {
                throw PackageParseException("자료 ZIP이 허용 크기를 초과했습니다. (최대 ${maxBytes / (1024 * 1024)}MB)")
            }
        }

        fun hash(): String {
            val sb = StringBuilder()
            for (byte in digest.digest()) sb.append("%02x".format(byte))
            return sb.toString()
        }

        override fun close() = src.close()
    }
}
