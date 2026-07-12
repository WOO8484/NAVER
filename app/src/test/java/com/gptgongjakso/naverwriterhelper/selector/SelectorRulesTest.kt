package com.gptgongjakso.naverwriterhelper.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 선택자 JSON 파싱/스키마 검증 단위 테스트 (작업지시서 14.1-13, 14.1-14). */
class SelectorRulesTest {

    private val validJson = """
        {
          "schema_version": "1.1",
          "rules_version": "test-1",
          "target_app": { "package": "com.nhn.android.blog" },
          "screens": {
            "WRITE_EDITOR": {
              "signals": [ { "hint_contains": "제목" } ],
              "title_field": [ { "view_id_contains": "title" }, { "hint_contains": "제목" } ]
            },
            "PHOTO_PICKER": {
              "package_names": ["com.sec.android.gallery3d"],
              "safe_done_button": [ { "text_exact": "완료", "screen_role": "PHOTO_PICKER" } ]
            }
          }
        }
    """.trimIndent()

    // 13. 선택자 JSON 정상 파싱
    @Test
    fun `valid json parses into RuleSet`() {
        val result = SelectorRules.validate(validJson, SelectorRules.Source.ACTIVE)
        assertTrue(result is SelectorRules.ValidationResult.Valid)
        val ruleSet = (result as SelectorRules.ValidationResult.Valid).ruleSet
        assertEquals("1.1", ruleSet.schemaVersion)
        assertEquals("test-1", ruleSet.rulesVersion)
        assertEquals("com.nhn.android.blog", ruleSet.targetPackage)
        assertEquals(2, ruleSet.screen("WRITE_EDITOR").titleField.size)
        assertEquals(listOf("com.sec.android.gallery3d"), ruleSet.screen("PHOTO_PICKER").packageNames)
    }

    // 14. 선택자 스키마 오류 차단
    @Test
    fun `wrong schema version is rejected`() {
        val json = validJson.replace("\"1.1\"", "\"9.9\"")
        val result = SelectorRules.validate(json, SelectorRules.Source.ACTIVE)
        assertTrue(result is SelectorRules.ValidationResult.Invalid)
    }

    @Test
    fun `missing schema version is rejected`() {
        val json = """{"rules_version":"x","target_app":{"package":"com.nhn.android.blog"},"screens":{}}"""
        val result = SelectorRules.validate(json, SelectorRules.Source.ACTIVE)
        assertTrue(result is SelectorRules.ValidationResult.Invalid)
    }

    @Test
    fun `broken json is rejected`() {
        val result = SelectorRules.validate("{not valid json", SelectorRules.Source.ACTIVE)
        assertTrue(result is SelectorRules.ValidationResult.Invalid)
    }

    @Test
    fun `missing target package is rejected`() {
        val json = """{"schema_version":"1.1","screens":{}}"""
        val result = SelectorRules.validate(json, SelectorRules.Source.ACTIVE)
        assertTrue(result is SelectorRules.ValidationResult.Invalid)
    }

    // 17. 위험 버튼 차단(선택자에 screen_role 이 실려 안전 클릭 정책에 쓰일 수 있는지 파싱 확인)
    @Test
    fun `safe confirm candidate keeps screenRole for context-aware click`() {
        val result = SelectorRules.validate(validJson, SelectorRules.Source.ACTIVE) as SelectorRules.ValidationResult.Valid
        val safeDone = result.ruleSet.screen("PHOTO_PICKER").safeDoneButton.first()
        assertEquals("PHOTO_PICKER", safeDone.screenRole)
        assertEquals("완료", safeDone.textExact)
    }
}
