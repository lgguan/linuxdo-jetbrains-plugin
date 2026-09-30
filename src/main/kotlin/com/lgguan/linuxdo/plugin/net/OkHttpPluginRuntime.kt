package com.lgguan.linuxdo.plugin.net

import okhttp3.internal.concurrent.TaskRunner

/** OkHttp 4.12 keeps its TaskRunner workers alive after all client pools are closed. */
internal object OkHttpPluginRuntime {
    fun shutdown() {
        // Never stop an IDE-owned/shared copy if dependency loading changes.
        check(TaskRunner::class.java.classLoader === javaClass.classLoader) {
            "Cannot shut down an OkHttp runtime owned by another classloader"
        }
        val runner = TaskRunner.INSTANCE
        synchronized(runner) { runner.cancelAll() }
        (runner.backend as TaskRunner.RealBackend).shutdown()
    }
}
