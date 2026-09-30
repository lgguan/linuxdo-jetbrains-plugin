package com.lgguan.linuxdo.plugin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OkHttpRuntimeUnloadTest {
    @Test fun `unload terminates private okhttp workers and releases their classloader`() {
        val (loader, worker) = startAndStopPrivateRuntime()
        worker.join(3000)
        assertFalse(worker.isAlive, "OkHttp worker must terminate without its 60 second idle timeout")
        repeat(50) {
            System.gc()
            Thread.sleep(20)
        }
        assertNull(loader.get(), "OkHttp thread factory must not retain the unloaded plugin")
        // The test process's independent OkHttp runtime must remain usable.
        val client = okhttp3.OkHttpClient()
        assertFalse(client.dispatcher.executorService.isShutdown)
        client.dispatcher.executorService.shutdownNow()
    }

    private fun startAndStopPrivateRuntime(): Pair<WeakReference<ClassLoader>, Thread> {
        val parent = javaClass.classLoader
        val helper = "com.lgguan.linuxdo.plugin.net.OkHttpPluginRuntime"
        val loader = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (!name.startsWith("okhttp3.") && name != helper) return super.loadClass(name, resolve)
                synchronized(getClassLoadingLock(name)) {
                    val type = findLoadedClass(name) ?: parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.use {
                        val bytes = it.readBytes()
                        defineClass(name, bytes, 0, bytes.size)
                    }
                    if (resolve) resolveClass(type)
                    return type
                }
            }
        }
        val runnerType = loader.loadClass("okhttp3.internal.concurrent.TaskRunner")
        val runner = runnerType.getField("INSTANCE").get(null)
        val backend = runnerType.getMethod("getBackend").invoke(runner)
        val started = CountDownLatch(1)
        var worker: Thread? = null
        backend.javaClass.getMethod("execute", Runnable::class.java).invoke(backend, Runnable {
            worker = Thread.currentThread()
            started.countDown()
        })
        assertTrue(started.await(3, TimeUnit.SECONDS))
        assertTrue(worker!!.isAlive)
        val runtime = loader.loadClass(helper)
        runtime.getMethod("shutdown").invoke(runtime.getField("INSTANCE").get(null))
        return WeakReference<ClassLoader>(loader) to worker!!
    }
}
