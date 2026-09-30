package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.common.ClipboardImageFile
import com.lgguan.linuxdo.plugin.common.ImageClipboardWriter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO

class ClipboardImageFileTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `file clipboard preserves both GIF frames and original encoded bytes`() {
        val encoded = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        ImageIO.createImageOutputStream(encoded).use { output ->
            writer.output = output
            writer.prepareWriteSequence(null)
            for (color in listOf(0xff0000, 0x0000ff)) {
                val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
                image.setRGB(0, 0, color)
                writer.writeToSequence(IIOImage(image, null, null), null)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
        val bytes = encoded.toByteArray()
        val file = ClipboardImageFile.save(bytes, temporary.toFile())
        assertEquals("gif", file.extension)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(file, ClipboardImageFile.save(bytes, temporary.toFile()))
        val clipboard = Clipboard("test only")
        ImageClipboardWriter.writeContents(ClipboardImageFile(file), { clipboard }, {})
        assertEquals(listOf(file), clipboard.getData(DataFlavor.javaFileListFlavor))
        assertFalse(clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor))
        ImageIO.createImageInputStream(file).use { input ->
            val reader = ImageIO.getImageReaders(input).next()
            reader.input = input
            assertEquals(2, reader.getNumImages(true))
            assertNotEquals(reader.read(0).getRGB(0, 0), reader.read(1).getRGB(0, 0))
            reader.dispose()
        }
    }

    @Test
    fun `HTML error pages are not copied as image files`() {
        assertThrows(IllegalStateException::class.java) {
            ClipboardImageFile.save("<html>access denied</html>".toByteArray(), temporary.toFile())
        }
    }
}
