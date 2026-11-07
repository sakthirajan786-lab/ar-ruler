package com.arruler.app

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import com.google.ar.core.Session

/** Keeps the ARCore session informed about display rotation and viewport size. */
class DisplayRotationHelper(private val activity: Activity) : DisplayManager.DisplayListener {

    @Volatile private var geometryChanged = true
    @Volatile private var rotation = Surface.ROTATION_0
    private var viewportWidth = 0
    private var viewportHeight = 0

    private val displayManager: DisplayManager =
        activity.getSystemService(DisplayManager::class.java)

    fun onResume() {
        displayManager.registerDisplayListener(this, null)
        rotation = readRotation()
        geometryChanged = true
    }

    fun onPause() {
        displayManager.unregisterDisplayListener(this)
    }

    /** Called on the GL thread. */
    fun onSurfaceChanged(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        geometryChanged = true
    }

    /** Called on the GL thread before Session.update(). */
    fun updateSessionIfNeeded(session: Session) {
        if (geometryChanged && viewportWidth > 0 && viewportHeight > 0) {
            session.setDisplayGeometry(rotation, viewportWidth, viewportHeight)
            geometryChanged = false
        }
    }

    override fun onDisplayAdded(displayId: Int) {}

    override fun onDisplayRemoved(displayId: Int) {}

    override fun onDisplayChanged(displayId: Int) {
        rotation = readRotation()
        geometryChanged = true
    }

    @Suppress("DEPRECATION")
    private fun readRotation(): Int {
        val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            activity.windowManager.defaultDisplay
        }
        return display?.rotation ?: Surface.ROTATION_0
    }
}
