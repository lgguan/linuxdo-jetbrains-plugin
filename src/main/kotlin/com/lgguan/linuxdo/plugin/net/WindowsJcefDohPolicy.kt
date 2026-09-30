package com.lgguan.linuxdo.plugin.net

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/** Loaded only by the Windows policy branch; other hosts never initialize Win32/JNA. */
internal object WindowsJcefDohPolicy {
    fun write(key: String, values: Map<String, String>) {
        Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, key)
        values.forEach { (name, value) ->
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, name, value)
        }
    }
}
