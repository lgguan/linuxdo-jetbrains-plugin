package com.lgguan.linuxdo.plugin.model

internal sealed interface TagHint {
    data object Hidden : TagHint
    data object Checking : TagHint
    data class Required(val group: RequiredTagGroup) : TagHint
    data class Warning(val message: String, val detail: String? = null) : TagHint
}
