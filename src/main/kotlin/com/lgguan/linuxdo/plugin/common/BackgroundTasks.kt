package com.lgguan.linuxdo.plugin.common

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

/** Own background work with the UI that requested it, including queued tasks. */
internal class BackgroundTasks : Disposable {
    @Volatile private var disposed = false
    private val tasks = ConcurrentHashMap.newKeySet<Future<*>>()
    @Synchronized fun submit(action: () -> Unit): Future<*> {
        lateinit var task: FutureTask<Unit>
        task = FutureTask<Unit> {
            try { if (!disposed) action() } finally { tasks.remove(task) }
        }
        if (disposed) task.cancel(false) else {
            tasks.add(task)
            ApplicationManager.getApplication().executeOnPooledThread(task)
        }
        return task
    }
    @Synchronized override fun dispose() {
        disposed = true
        tasks.forEach { it.cancel(true) }
        tasks.clear()
    }
}
