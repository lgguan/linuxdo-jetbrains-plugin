package com.lgguan.linuxdo.plugin.common

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.Transferable

internal object ImageClipboardWriter {
    /** Called on a pooled thread. Windows can briefly lock the clipboard while another app reads it. */
    fun write(
        image: Image,
        clipboard: () -> Clipboard = { Toolkit.getDefaultToolkit().systemClipboard },
        pause: (Long) -> Unit = { Thread.sleep(it) }
    ) = writeContents(ClipboardImage(image), clipboard, pause)

    fun writeContents(
        content: Transferable,
        clipboard: () -> Clipboard = { Toolkit.getDefaultToolkit().systemClipboard },
        pause: (Long) -> Unit = { Thread.sleep(it) }
    ) {
        repeat(5) { attempt ->
            try {
                clipboard().setContents(content, null)
                return
            } catch (busy: IllegalStateException) {
                if (attempt == 4) throw busy
                pause(100L * (attempt + 1))
            }
        }
    }
}
