package com.lgguan.linuxdo.plugin.net

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.lgguan.linuxdo.plugin.common.Constants

/** Blocking OS keyring access, used only by cookie persistence workers. */
internal interface CookieCredentialStorage {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

internal object PasswordSafeCookieStorage : CookieCredentialStorage {
    private fun attributes(key: String) =
        CredentialAttributes(generateServiceName(Constants.PASSWORD_SAFE_SERVICE_NAME, key))

    override fun read(key: String): String? = if (ApplicationManager.getApplication() == null) null
        else PasswordSafe.instance.getPassword(attributes(key))

    override fun write(key: String, value: String?) {
        if (ApplicationManager.getApplication() != null) PasswordSafe.instance.setPassword(attributes(key), value)
    }
}
