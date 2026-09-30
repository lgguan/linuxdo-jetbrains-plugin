package com.lgguan.linuxdo.plugin.common

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState

/** Login UI callbacks must run while the modal authentication dialog is open.
 * Only use for plugin UI/session state, never for PSI/VFS/project model writes. */
internal fun invokeLoginUiLater(action: () -> Unit) {
    val app = ApplicationManager.getApplication()
    if (app == null) action() else app.invokeLater({ action() }, ModalityState.any())
}
