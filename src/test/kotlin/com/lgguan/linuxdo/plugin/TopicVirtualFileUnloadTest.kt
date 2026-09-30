package com.lgguan.linuxdo.plugin

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.LightVirtualFile
import com.lgguan.linuxdo.plugin.editor.LinuxDoTopicVirtualFile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.ref.Reference
import java.lang.ref.WeakReference

class TopicVirtualFileUnloadTest {
    @Test fun `topic tabs use a platform file and keep navigation metadata`() {
        val file = LinuxDoTopicVirtualFile.create(847468, "Topic / title", 6386)
        assertEquals(LightVirtualFile::class.java, file.javaClass)
        assertSame(PlainTextFileType.INSTANCE, file.assignedFileType)
        assertFalse(file.isWritable)
        assertEquals("[847468] Topic _ title.doc", file.name)
        assertTrue(LinuxDoTopicVirtualFile.accepts(file))
        assertFalse(LinuxDoTopicVirtualFile.accepts(LightVirtualFile("ordinary.doc")))
        assertEquals(847468L, LinuxDoTopicVirtualFile.topicId(file))
        assertEquals("Topic / title", LinuxDoTopicVirtualFile.topicTitle(file))
        assertEquals(6386, LinuxDoTopicVirtualFile.targetPostNumber(file))
        LinuxDoTopicVirtualFile.setTargetPostNumber(file, 25)
        assertEquals(25, LinuxDoTopicVirtualFile.targetPostNumber(file))
        LinuxDoTopicVirtualFile.setTargetPostNumber(file, null)
        assertNull(LinuxDoTopicVirtualFile.targetPostNumber(file))
    }

    @Test fun `retained closed tab does not keep its factory classloader alive`() {
        // The IDE has already initialized its file infrastructure before loading plugins.
        // On JDK 17, first-time platform threads otherwise inherit the test loader's
        // AccessControlContext and test a different retention path than closed tabs.
        LinuxDoTopicVirtualFile.create(1, "Platform warmup", 1)
        val (file, loader) = createWithIsolatedFactory()
        // Model EditorWindow.removedTabs retaining the file even after the plugin is unloaded.
        val removedTabs = arrayOf(file)
        repeat(50) {
            System.gc()
            Thread.sleep(20)
        }
        assertNull(loader.get(), "Closed-tab history must not retain the plugin factory loader")
        assertEquals(LightVirtualFile::class.java, removedTabs.single().javaClass)
        assertFalse(removedTabs.single().isWritable)
        Reference.reachabilityFence(removedTabs)
    }

    private fun createWithIsolatedFactory(): Pair<LightVirtualFile, WeakReference<ClassLoader>> {
        val factoryName = LinuxDoTopicVirtualFile::class.java.name
        val parent = LinuxDoTopicVirtualFile::class.java.classLoader
        val loader = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name != factoryName) return super.loadClass(name, resolve)
                synchronized(getClassLoadingLock(name)) {
                    val found = findLoadedClass(name) ?: parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.use {
                        val bytes = it.readBytes()
                        defineClass(name, bytes, 0, bytes.size)
                    }
                    if (resolve) resolveClass(found)
                    return found
                }
            }
        }
        val factory = loader.loadClass(factoryName)
        assertSame(loader, factory.classLoader)
        val file = factory.getMethod("create", Long::class.javaPrimitiveType, String::class.java, Int::class.javaObjectType)
            .invoke(factory.getField("INSTANCE").get(null), 42L, "Retained tab", 7) as LightVirtualFile
        return file to WeakReference(loader)
    }
}
