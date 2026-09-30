package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.common.ImageClipboardWriter
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ImageClipboardWriterTest {
    private val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB)

    @Test
    fun `temporary clipboard contention is retried then writes real image data`() {
        var attempts = 0
        val clipboard = object : Clipboard("test only") {
            override fun setContents(contents: Transferable, owner: ClipboardOwner?) {
                if (++attempts < 3) throw IllegalStateException("clipboard busy")
                super.setContents(contents, owner)
            }
        }
        val waits = mutableListOf<Long>()
        ImageClipboardWriter.write(image, { clipboard }, waits::add)
        assertEquals(3, attempts)
        assertEquals(listOf(100L, 200L), waits)
        assertSame(image, clipboard.getData(DataFlavor.imageFlavor))
    }

    @Test
    fun `permanently unavailable clipboard reports failure after bounded retries`() {
        var attempts = 0
        assertThrows(IllegalStateException::class.java) {
            ImageClipboardWriter.write(image, {
                attempts++
                throw IllegalStateException("unavailable")
            }, {})
        }
        assertEquals(5, attempts)
    }
}
