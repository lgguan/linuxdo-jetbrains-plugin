package com.lgguan.linuxdo.plugin.net

import com.intellij.openapi.Disposable

/** Plugin services are disposed on plugin unload, unlike the IDE Application root. */
class LinuxDoPluginLifetime : Disposable {
    override fun dispose() {
        try {
            LinuxDoJcefBridge.dispose()
        } finally {
            try {
                IsolatedCefRuntime.currentOrNull()?.dispose()
            } finally {
                try {
                    LinuxDoHttpClient.dispose()
                } finally {
                    try {
                        OkHttpPluginRuntime.shutdown()
                    } finally {
                        com.lgguan.linuxdo.plugin.common.LinuxDoLog.dispose()
                    }
                }
            }
        }
    }
}
