package com.gptgongjakso.naverwriterhelper.service

/**
 * "태그 1개씩" 입력 진행을 관리하는 순수 상태 머신.
 *
 * 절대 전체 태그를 한 번에 넘기지 않는다.
 * 항상 current() 로 지금 넣을 태그 1개만 알려주고, markCurrentDone() 으로 다음으로 넘어간다.
 */
class TagInputController {

    private var tags: List<String> = emptyList()

    /** 다음에 입력할 태그의 인덱스(=완료 개수) */
    var cursor: Int = 0
        private set

    val total: Int get() = tags.size
    val doneCount: Int get() = cursor.coerceAtMost(total)

    fun reset(newTags: List<String>) {
        tags = newTags
        cursor = 0
    }

    fun hasNext(): Boolean = cursor < tags.size

    /** 지금 입력해야 할 태그 1개 (없으면 null) */
    fun current(): String? = tags.getOrNull(cursor)

    /** 현재 태그를 완료 처리하고 다음으로 이동. 넘어간 태그를 반환. */
    fun markCurrentDone(): String? {
        val t = tags.getOrNull(cursor) ?: return null
        cursor++
        return t
    }

    /** "3/8" 형태의 진행 표시 */
    fun progressText(): String = "$doneCount/$total"

    fun isEmpty(): Boolean = tags.isEmpty()

    fun snapshot(): List<String> = tags
}
