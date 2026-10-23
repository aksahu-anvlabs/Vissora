package org.anvlabs.vissora

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.*
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.win32.StdCallLibrary

interface Dwmapi : StdCallLibrary {
    fun DwmSetWindowAttribute(hwnd: HWND, dwAttribute: Int, pvAttribute: IntArray, cbAttribute: Int): Int

    companion object {
        val INSTANCE: Dwmapi = Native.load("dwmapi", Dwmapi::class.java)
        const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    }
}

fun main() = application {
    Window(icon = painterResource("drawable/logo.svg"), onCloseRequest = ::exitApplication, state = rememberWindowState(placement = WindowPlacement.Maximized), undecorated = true, resizable = false, title = "Vissora Desktop") {
        LaunchedEffect(Unit) {
            if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
                val hwnd = HWND(Native.getWindowPointer(window))
                val darkMode = intArrayOf(1)
                Dwmapi.INSTANCE.DwmSetWindowAttribute(
                    hwnd,
                    Dwmapi.DWMWA_USE_IMMERSIVE_DARK_MODE,
                    darkMode,
                    4
                )
            }
        }
        App(onExitApp = ::exitApplication)
    }
}