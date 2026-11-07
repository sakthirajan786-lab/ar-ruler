package com.arruler.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException

class MainActivity : Activity() {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var overlay: MeasureOverlayView
    private lateinit var readout: TextView
    private lateinit var hint: TextView
    private lateinit var unitButton: TextView
    private lateinit var renderer: ArRenderer
    private lateinit var rotationHelper: DisplayRotationHelper

    private var session: Session? = null
    private var installRequested = false
    private var waitingOnPermissionDialog = false
    private var fatalDialogShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surface_view)
        overlay = findViewById(R.id.overlay)
        readout = findViewById(R.id.readout)
        hint = findViewById(R.id.hint)
        unitButton = findViewById(R.id.btn_unit)

        rotationHelper = DisplayRotationHelper(this)
        renderer = ArRenderer(this, rotationHelper, overlay) { r, h ->
            if (readout.text.toString() != r) readout.text = r
            if (hint.text.toString() != h) hint.text = h
        }

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(renderer)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        val savedUnit = MeasureUnit.fromOrdinal(
            getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_UNIT, 0)
        )
        applyUnit(savedUnit)

        findViewById<View>(R.id.btn_add).setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            renderer.requestAddPoint()
        }
        findViewById<View>(R.id.btn_undo).setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            surfaceView.queueEvent { renderer.undoOnGlThread() }
        }
        findViewById<View>(R.id.btn_clear).setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            surfaceView.queueEvent { renderer.clearOnGlThread() }
        }
        unitButton.setOnClickListener {
            val next = renderer.unit.next()
            applyUnit(next)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_UNIT, next.ordinal).apply()
        }

        applyWindowInsets()
    }

    private fun applyUnit(unit: MeasureUnit) {
        renderer.unit = unit
        overlay.unit = unit
        unitButton.text = unit.label
    }

    /** Keep controls clear of the status bar, cutouts and navigation bar (edge-to-edge on Android 15+). */
    @Suppress("DEPRECATION")
    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.root)
        val topBar = findViewById<View>(R.id.top_bar)
        val bottomBar = findViewById<View>(R.id.bottom_bar)
        val baseTopMargin = (topBar.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        val baseBottomPadding = bottomBar.paddingBottom

        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                top = bars.top
                bottom = bars.bottom
            } else {
                top = insets.systemWindowInsetTop
                bottom = insets.systemWindowInsetBottom
            }
            val lp = topBar.layoutParams as ViewGroup.MarginLayoutParams
            lp.topMargin = baseTopMargin + top
            topBar.layoutParams = lp
            bottomBar.setPadding(
                bottomBar.paddingLeft, bottomBar.paddingTop,
                bottomBar.paddingRight, baseBottomPadding + bottom
            )
            insets
        }
        root.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        if (fatalDialogShown || waitingOnPermissionDialog) return

        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    else -> Unit
                }

                if (!hasCameraPermission()) {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
                    return
                }

                val newSession = Session(this)
                configureSession(newSession)
                session = newSession
            } catch (e: UnavailableUserDeclinedInstallationException) {
                showFatal(getString(R.string.err_declined)); return
            } catch (e: UnavailableArcoreNotInstalledException) {
                showFatal(getString(R.string.err_declined)); return
            } catch (e: UnavailableDeviceNotCompatibleException) {
                showFatal(getString(R.string.err_not_compatible)); return
            } catch (e: UnavailableApkTooOldException) {
                showFatal(getString(R.string.err_update_arcore)); return
            } catch (e: UnavailableSdkTooOldException) {
                showFatal(getString(R.string.err_update_app)); return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AR session", e)
                showFatal(getString(R.string.err_generic, e.localizedMessage ?: e.javaClass.simpleName))
                return
            }
        }

        val s = session ?: return
        try {
            s.resume()
        } catch (e: CameraNotAvailableException) {
            s.close()
            session = null
            renderer.session = null
            showFatal(getString(R.string.err_camera))
            return
        }
        renderer.session = s
        surfaceView.onResume()
        rotationHelper.onResume()
    }

    override fun onPause() {
        super.onPause()
        val s = session ?: return
        rotationHelper.onPause()
        surfaceView.onPause() // blocks until the GL thread has paused
        s.pause()
    }

    override fun onDestroy() {
        renderer.session = null
        session?.close()
        session = null
        super.onDestroy()
    }

    private fun configureSession(s: Session) {
        val config = Config(s)
        config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL)
        config.setFocusMode(Config.FocusMode.AUTO)
        config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
        config.setLightEstimationMode(Config.LightEstimationMode.DISABLED)
        config.setDepthMode(
            if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC
            else Config.DepthMode.DISABLED
        )
        s.configure(config)
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) return

        waitingOnPermissionDialog = true
        val permanentlyDenied = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_title)
            .setMessage(R.string.perm_message)
            .setCancelable(false)
            .setPositiveButton(if (permanentlyDenied) R.string.open_settings else R.string.ok) { _, _ ->
                waitingOnPermissionDialog = false
                if (permanentlyDenied) {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", packageName, null))
                    )
                } else {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
                }
            }
            .setNegativeButton(R.string.exit) { _, _ -> finish() }
            .show()
    }

    private fun showFatal(message: String) {
        if (fatalDialogShown || isFinishing) return
        fatalDialogShown = true
        AlertDialog.Builder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.ok) { _, _ -> finish() }
            .show()
    }

    private companion object {
        const val TAG = "ARRuler"
        const val REQ_CAMERA = 1001
        const val PREFS = "settings"
        const val KEY_UNIT = "unit"
    }
}
