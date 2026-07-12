package com.gptgongjakso.naverwriterhelper.helper

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.gptgongjakso.naverwriterhelper.image.ImageConverter
import com.gptgongjakso.naverwriterhelper.image.SavedImageEntry
import com.gptgongjakso.naverwriterhelper.model.NaverPostData
import com.gptgongjakso.naverwriterhelper.model.ParsedImage

/**
 * 파싱된 이미지를 Android 갤러리(사진)에 저장한다. v0.1.1 이식 → v1.1.0 확장.
 * 앨범명: GPT공작소  (Pictures/GPT공작소)
 *
 * Android 10(API 29)+ scoped storage / MediaStore 사용 → 별도 저장 권한 불필요.
 * 이미지 장수는 자료에 있는 만큼 모두 저장한다(가변 지원, 지시서 7).
 *
 * v1.1.0 확장(작업지시서 8):
 *  - 세션별 고유 파일명(GPTG_<postId>_<sessionId>_NN_role.ext)으로 현재 작업 이미지를
 *    다른 사진과 구분할 수 있게 한다(사진 자동 선택 오선택 방지의 기반).
 *  - WEBP 등 불안정 형식은 [ImageConverter] 로 PNG/JPEG 변환 후 저장한다.
 *  - 저장 결과를 [SavedImageEntry] manifest 로 반환해 사진 자동 선택 계획에 사용한다.
 *  - 실패한 이미지만 별도로 재시도할 수 있도록 saveOne 을 공개한다.
 */
object ImageSaveHelper {

    /** 갤러리에 보일 앨범(폴더) 이름 */
    const val ALBUM_NAME = "GPT공작소"

    data class SaveResult(
        val total: Int,
        val success: Int,
        val failed: Int,
        val savedUris: List<Uri>,
        val manifest: List<SavedImageEntry> = emptyList(),
        val failedOriginalNames: List<String> = emptyList()
    ) {
        val allSuccess: Boolean get() = failed == 0 && total > 0
    }

    /**
     * 자료의 모든 이미지를 저장한다. (IO 스레드에서 호출 권장)
     * @param sessionId 현재 자동화 세션 ID(오선택 방지용 고유 접두사)
     */
    fun saveAll(
        context: Context,
        data: NaverPostData,
        sessionId: String,
        clock: () -> Long = { System.currentTimeMillis() }
    ): SaveResult {
        var success = 0
        var failed = 0
        val uris = ArrayList<Uri>()
        val manifest = ArrayList<SavedImageEntry>()
        val failedNames = ArrayList<String>()
        val postId = data.metadata.postId?.takeIf { it.isNotBlank() } ?: "auto-${data.zipSha256.take(12)}"

        for (image in data.images) {
            val entry = runCatching { saveOne(context, image, postId, sessionId, clock()) }.getOrNull()
            if (entry != null) {
                success++
                uris.add(Uri.parse(entry.mediaStoreUri))
                manifest.add(entry)
            } else {
                failed++
                failedNames.add(image.originalName)
            }
        }
        return SaveResult(
            total = data.images.size,
            success = success,
            failed = failed,
            savedUris = uris,
            manifest = manifest,
            failedOriginalNames = failedNames
        )
    }

    /** 실패한 이미지만 다시 저장한다(작업지시서 8: 실패 이미지만 재시도). */
    fun retryFailed(
        context: Context,
        data: NaverPostData,
        sessionId: String,
        failedOriginalNames: List<String>,
        clock: () -> Long = { System.currentTimeMillis() }
    ): SaveResult {
        val targets = data.images.filter { it.originalName in failedOriginalNames }
        val retryData = data.copy(images = targets)
        return saveAll(context, retryData, sessionId, clock)
    }

    /** 이미지 1장을 (필요 시 변환하여) MediaStore에 저장하고 관리 항목을 반환한다. */
    fun saveOne(
        context: Context,
        image: ParsedImage,
        postId: String,
        sessionId: String,
        savedAtMillis: Long
    ): SavedImageEntry {
        val conversion = ImageConverter.convert(image.bytes, image.originalName)
        if (!conversion.success || conversion.bytes == null) {
            throw IllegalStateException("이미지 변환 실패: ${conversion.error ?: "알 수 없는 오류"}")
        }

        val saveName = com.gptgongjakso.naverwriterhelper.image.SavedImageManifest.buildSaveName(
            postId = postId,
            sessionId = sessionId,
            orderIndex = image.orderIndex,
            role = image.role,
            extension = conversion.extension
        )

        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, saveName)
            put(MediaStore.Images.Media.MIME_TYPE, conversion.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.DATE_TAKEN, savedAtMillis)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert 실패: $saveName")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(conversion.bytes)
                out.flush()
            } ?: throw IllegalStateException("OutputStream 열기 실패: $saveName")

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        return SavedImageEntry(
            sessionId = sessionId,
            postId = postId,
            originalName = image.originalName,
            savedName = saveName,
            mediaStoreUri = uri.toString(),
            role = image.role,
            orderIndex = image.orderIndex,
            savedAtMillis = savedAtMillis,
            sha256 = conversion.resultSha256 ?: conversion.originalSha256
        )
    }
}
