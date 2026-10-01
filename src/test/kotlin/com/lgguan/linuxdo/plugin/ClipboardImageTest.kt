package com.lgguan.linuxdo.plugin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.ImageIcon

class ClipboardImageTest {

    @Test
    fun testBufferedImageConversion() {
        val testBi = BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB)
        val icon = ImageIcon(testBi)
        assertEquals(100, icon.iconWidth)
        assertEquals(80, icon.iconHeight)

        val outBi = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        val g = outBi.createGraphics()
        g.drawImage(testBi, 0, 0, null)
        g.dispose()

        val baos = ByteArrayOutputStream()
        ImageIO.write(outBi, "png", baos)
        assertTrue(baos.toByteArray().isNotEmpty())
    }

    @Test
    fun testJEditorPaneLocalImageRendering() {
        val tempFile = File.createTempFile("test-img-", ".png")
        tempFile.deleteOnExit()
        val img = BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB)
        ImageIO.write(img, "png", tempFile)

        val fileUri = tempFile.toURI().toURL().toExternalForm()
        val html = "<html><body><img src='$fileUri' width='50' height='50'/></body></html>"

        val pane = javax.swing.JEditorPane()
        pane.contentType = "text/html"
        pane.text = html
        assertNotNull(pane.document)
    }

    @Test
    fun testJBTextAreaPasteBehavior() {
        val textArea = com.intellij.ui.components.JBTextArea()
        assertNotNull(textArea.transferHandler)
        assertNotNull(textArea.actionMap.get(javax.swing.text.DefaultEditorKit.pasteAction))

        var importDataCalled = false
        val customHandler = object : javax.swing.TransferHandler() {
            override fun importData(support: TransferSupport): Boolean {
                importDataCalled = true
                return true
            }
            override fun canImport(support: TransferSupport): Boolean = true
        }
        textArea.transferHandler = customHandler
        val dummyTransferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = flavor == DataFlavor.stringFlavor
            override fun getTransferData(flavor: DataFlavor?): Any = "hello"
        }
        val support = javax.swing.TransferHandler.TransferSupport(textArea, dummyTransferable)
        textArea.transferHandler.importData(support)
        assertTrue(importDataCalled)
    }

    @Test
    fun testSystemClipboardImageRetrieval() {
        org.junit.jupiter.api.Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "Requires a desktop clipboard")

        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val testImage = BufferedImage(200, 150, BufferedImage.TYPE_INT_RGB)
        val g = testImage.createGraphics()
        g.color = java.awt.Color.RED
        g.fillRect(0, 0, 200, 150)
        g.dispose()

        val imageTransferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor?): Any = testImage
        }

        val previous = clipboard.getContents(null)
        try {
            clipboard.setContents(imageTransferable, null)

            assertTrue(clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor))
            val retrieved = clipboard.getData(DataFlavor.imageFlavor)
            assertNotNull(retrieved)
            println("Retrieved image class: ${retrieved.javaClass.name}")

            val icon = ImageIcon(retrieved as Image)
            println("Icon dimensions: ${icon.iconWidth}x${icon.iconHeight}")
            assertEquals(200, icon.iconWidth)
            assertEquals(150, icon.iconHeight)
        } finally {
            clipboard.setContents(previous ?: java.awt.datatransfer.StringSelection(""), null)
        }
    }

    @Test
    fun testPasteActionCallsTransferHandlerWithFileList() {
        org.junit.jupiter.api.Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "Requires a desktop clipboard")
        val textArea = com.intellij.ui.components.JBTextArea()
        var importedFileList = false

        textArea.transferHandler = object : javax.swing.TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean = true
            override fun importData(support: TransferSupport): Boolean {
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    importedFileList = true
                    return true
                }
                return false
            }
        }

        val tempFile = File.createTempFile("test-copy-", ".png")
        tempFile.deleteOnExit()

        val fileListTransferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = flavor == DataFlavor.javaFileListFlavor
            override fun getTransferData(flavor: DataFlavor?): Any = listOf(tempFile)
        }

        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val previous = clipboard.getContents(null)
        try {
            clipboard.setContents(fileListTransferable, null)

            val pasteAction = textArea.actionMap.get(javax.swing.text.DefaultEditorKit.pasteAction)
            assertNotNull(pasteAction)
            pasteAction.actionPerformed(java.awt.event.ActionEvent(textArea, java.awt.event.ActionEvent.ACTION_PERFORMED, "paste"))

            println("Imported file list on pasteAction: $importedFileList")
            assertTrue(importedFileList)
        } finally {
            clipboard.setContents(previous ?: java.awt.datatransfer.StringSelection(""), null)
        }
    }

    @Test
    fun testLinuxDoImageCacheMapping() {
        val cache = com.lgguan.linuxdo.plugin.common.LinuxDoImageCache
        val bytes = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        val localUrl = cache.cacheImageBytes(bytes, "sample-test.png")
        val uploadUrl = "upload://i5ay5G77tE200zAjE4HgBQw3Gqg.png"
        val serverUrl = "/uploads/default/original/2X/i/i5ay5G77tE200zAjE4HgBQw3Gqg.png"

        cache.put(uploadUrl, localUrl)
        cache.put(serverUrl, localUrl)

        assertEquals(localUrl, cache.resolve(uploadUrl))
        assertEquals(localUrl, cache.resolve("i5ay5G77tE200zAjE4HgBQw3Gqg.png"))
        assertEquals(localUrl, cache.resolve("/uploads/short-url/i5ay5G77tE200zAjE4HgBQw3Gqg.png"))
        assertEquals(localUrl, cache.resolve(serverUrl))

        val unvalidated = File.createTempFile("unvalidated-test-", ".png")
        try {
                unvalidated.writeBytes(ByteArray(10) { 1 })
                cache.put("unsafe", unvalidated.toURI().toString())
                assertNull(cache.resolve("unsafe"))
            } finally { unvalidated.delete() }
        }

        @Test
        fun testLinuxDoImageCacheByteCaching() {
            val cache = com.lgguan.linuxdo.plugin.common.LinuxDoImageCache
            val img = BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB)
            val baos = ByteArrayOutputStream()
            ImageIO.write(img, "png", baos)
            val bytes = baos.toByteArray()

            val cachedUri = cache.cacheImageBytes(bytes, "pasted-test.png")
            assertTrue(cachedUri.startsWith("file:"))

            val dimAttr = cache.getImageDimensionAttr(cachedUri, maxWidth = 450)
            assertTrue(dimAttr.contains("width='450'"))
            assertTrue(dimAttr.contains("height='337'"))
        }
    }
