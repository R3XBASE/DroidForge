package com.droidforge.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.flutter.plugin.common.MethodChannel

/**
 * VncManager — handles VNC/X11 session and Termux-X11 activity launching.
 */
class VncManager(private val context: Context) {

    /** Launch the Termux-X11 activity to display the desktop */
    fun launchVncActivity() {
        try {
            val intent = Intent("com.termux.x11.ACTION_START")
            intent.setPackage("com.termux.x11")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback: try launching Termux-X11 by package name
            try {
                val launchIntent = context.packageManager
                    .getLaunchIntentForPackage("com.termux.x11")
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                }
            } catch (_: Exception) {}
        }
    }

    /** Kill VNC server processes */
    fun killVnc() {
        try {
            Runtime.getRuntime().exec(
                arrayOf("/data/data/com.termux/files/usr/bin/bash", "-c", "pkill -f vncserver || true")
            )
        } catch (_: Exception) {}
    }

    /** Get list of running VNC sessions */
    fun getVncSessions(): List<String> {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("/data/data/com.termux/files/usr/bin/bash", "-c", "vncserver -list 2>/dev/null || true")
            )
            val reader = java.io.BufferedReader(
                java.io.InputStreamReader(process.inputStream)
            )
            val sessions = mutableListOf<String>()
            var line = reader.readLine()
            while (line != null) {
                if (line.contains(":")) {
                    sessions.add(line.trim())
                }
                line = reader.readLine()
            }
            process.waitFor()
            sessions
        } catch (_: Exception) {
            emptyList()
        }
    }
}
