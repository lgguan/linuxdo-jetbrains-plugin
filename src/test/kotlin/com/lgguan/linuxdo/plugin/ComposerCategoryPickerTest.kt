package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.model.Category
import com.lgguan.linuxdo.plugin.ui.dialog.ComposerCategories
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ComposerCategoryPickerTest {
    private fun category(id: Int, name: String, parent: Int? = null, permission: Int? = 1) =
        Category(id, name, "0088cc", "category-$id", parentCategoryId = parent, permission = permission)

    @Test fun `nested categories deduplicate and retain parent path and colors`() {
        val child = category(2, "工具")
        val parent = category(1, "开发", permission = 2).copy(subcategoryList = listOf(child))
        val flat = ComposerCategories.flatten(listOf(parent, child))
        assertEquals(listOf(1, 2), flat.map { it.id })
        assertEquals(1, flat.last().parentCategoryId)
        val option = ComposerCategories.options(flat).single()
        assertEquals(2, option.id)
        assertEquals("开发 › 工具", option.path)
        assertEquals(parent.color, option.parentColor)
    }

    @Test fun `search matches Chinese ancestors slug and sanitized description with all words`() {
        val parent = category(1, "技术讨论", permission = null)
        val child = category(2, "工具", 1).copy(slug = "developer-tools", description = "<p>共享 <b>编辑器</b> 经验</p>")
        val option = ComposerCategories.options(listOf(parent, child)).single()
        assertTrue(option.matches("技术 编辑器"))
        assertTrue(option.matches("DEVELOPER"))
        assertFalse(option.matches("技术 不存在"))
        assertFalse(option.description.contains("<"))
    }

    @Test fun `only explicit writable permissions are offered and parent cycles terminate`() {
        val options = ComposerCategories.options(listOf(category(1, "甲", 2), category(2, "乙", 1),
            category(3, "不可发布", permission = 2), category(4, "未知权限", permission = null)))
        assertEquals(listOf(1, 2), options.map { it.id })
        assertEquals("乙 › 甲", options.first().path)
    }
}
