package com.arruler.app

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/**
 * Runs on the GL thread. Owns all ARCore frame processing and the measurement anchors.
 * Anchors are only touched on this thread, so no locking is needed.
 */
class ArRenderer(
    private val context: Context,
    private val rotationHelper: DisplayRotationHelper,
    private val overlay: MeasureOverlayView,
    private val statusListener: (readout: String, hint: String) -> Unit
) : GLSurfaceView.Renderer {

    @Volatile var session: Session? = null
    @Volatile var unit: MeasureUnit = MeasureUnit.CM

    private val background = BackgroundRenderer()
    private var textureBoundTo: Session? = null

    /** Measurements are stored as consecutive pairs: (0,1), (2,3), ... */
    private val anchors = ArrayList<Anchor>()
    private val addRequested = AtomicBoolean(false)
    private var noSurfaceFlashUntil = 0L

    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val worldA = FloatArray(4)
    private val worldB = FloatArray(4)
    private val clipA = FloatArray(4)
    private val clipB = FloatArray(4)
    private val screen = FloatArray(4)
    private val normal = FloatArray(3)

    private val smoothed = FloatArray(3)
    private var hasSmoothed = false
    private var planesSeen = false

    private var viewWidth = 1
    private var viewHeight = 1

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastReadout = ""
    private var lastHint = ""

    // ---- Commands from the UI thread ----

    fun requestAddPoint() = addRequested.set(true)

    /** Must run on the GL thread (use GLSurfaceView.queueEvent). */
    fun undoOnGlThread() {
        if (anchors.isNotEmpty()) anchors.removeAt(anchors.size - 1).detach()
    }

    /** Must run on the GL thread (use GLSurfaceView.queueEvent). */
    fun clearOnGlThread() {
        for (a in anchors) a.detach()
        anchors.clear()
    }

    // ---- GLSurfaceView.Renderer ----

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        try {
            background.createOnGlThread()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create GL resources", e)
        }
        textureBoundTo = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height
        rotationHelper.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (background.textureId < 0) return

        try {
            if (textureBoundTo !== s) {
                s.setCameraTextureName(background.textureId)
                textureBoundTo = s
            }
            rotationHelper.updateSessionIfNeeded(s)
            val frame = s.update()
            background.draw(frame)
            processFrame(s, frame)
        } catch (t: Throwable) {
            // Never crash the render loop; ARCore can throw transiently (e.g. during pause).
            Log.e(TAG, "Exception on the GL thread", t)
        }
    }

    // ---- Frame processing ----

    private fun processFrame(s: Session, frame: Frame) {
        val camera = frame.camera

        if (camera.trackingState != TrackingState.TRACKING) {
            hasSmoothed = false
            addRequested.set(false)
            overlay.publish(OverlayState.EMPTY)
            postStatus(currentReadout(null), trackingHint(camera.trackingFailureReason))
            return
        }

        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projMatrix, 0, NEAR, FAR)
        Matrix.multiplyMM(viewProj, 0, projMatrix, 0, viewMatrix, 0)

        if (!planesSeen) {
            planesSeen = s.getAllTrackables(Plane::class.java)
                .any { it.trackingState == TrackingState.TRACKING }
        }

        val hit = findSurfaceHit(frame, camera)
        if (hit != null) updateSmoothed(hit.hitPose) else hasSmoothed = false

        if (addRequested.getAndSet(false)) {
            if (hit != null) addAnchor(hit) else noSurfaceFlashUntil = System.currentTimeMillis() + 1800
        }

        // Build overlay snapshot.
        val points = FloatArray(anchors.size * 2)
        var pointCount = 0
        val lines = ArrayList<OverlayLine>(anchors.size / 2 + 1)
        var lastCompleteMeters: Float? = null

        var i = 0
        while (i < anchors.size) {
            val a = anchors[i]
            val b = if (i + 1 < anchors.size) anchors[i + 1] else null
            val aTracking = a.trackingState == TrackingState.TRACKING
            if (aTracking && projectPoint(a.pose)) {
                points[pointCount * 2] = screen[0]
                points[pointCount * 2 + 1] = screen[1]
                pointCount++
            }
            if (b != null) {
                val bTracking = b.trackingState == TrackingState.TRACKING
                if (bTracking && projectPoint(b.pose)) {
                    points[pointCount * 2] = screen[0]
                    points[pointCount * 2 + 1] = screen[1]
                    pointCount++
                }
                if (aTracking && bTracking) {
                    val meters = distance(a.pose, b.pose)
                    lastCompleteMeters = meters
                    setWorld(worldA, a.pose)
                    setWorld(worldB, b.pose)
                    if (projectSegment()) {
                        lines.add(OverlayLine(screen[0], screen[1], screen[2], screen[3], meters, false))
                    }
                }
            }
            i += 2
        }

        // Live "rubber band" segment from a pending start point to the reticle.
        var liveMeters: Float? = null
        val pending = anchors.size % 2 == 1
        if (pending && hasSmoothed) {
            val start = anchors[anchors.size - 1]
            if (start.trackingState == TrackingState.TRACKING) {
                setWorld(worldA, start.pose)
                worldB[0] = smoothed[0]; worldB[1] = smoothed[1]; worldB[2] = smoothed[2]; worldB[3] = 1f
                val meters = distance3(worldA, worldB)
                liveMeters = meters
                if (projectSegment()) {
                    lines.add(OverlayLine(screen[0], screen[1], screen[2], screen[3], meters, true))
                }
            }
        }

        overlay.publish(OverlayState(points, pointCount, lines, hit != null))

        val readout = currentReadout(liveMeters ?: lastCompleteMeters)
        val hint = when {
            System.currentTimeMillis() < noSurfaceFlashUntil -> context.getString(R.string.hint_no_surface_for_point)
            !planesSeen && hit == null -> context.getString(R.string.hint_scan)
            hit == null -> context.getString(R.string.hint_aim)
            pending -> context.getString(R.string.hint_end)
            anchors.isEmpty() -> context.getString(R.string.hint_start)
            else -> context.getString(R.string.hint_done)
        }
        postStatus(readout, hint)
    }

    /** Finds the closest reliable surface under the centre of the screen. */
    private fun findSurfaceHit(frame: Frame, camera: Camera): HitResult? {
        val hits = frame.hitTest(viewWidth / 2f, viewHeight / 2f)
        for (hit in hits) {
            val ok = when (val trackable = hit.trackable) {
                is Plane -> trackable.isPoseInPolygon(hit.hitPose) &&
                    distanceToPlane(hit.hitPose, camera.pose) > 0f
                is DepthPoint -> true
                is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                else -> false
            }
            if (ok && hit.distance <= MAX_HIT_DISTANCE) return hit
        }
        return null
    }

    private fun distanceToPlane(planePose: Pose, cameraPose: Pose): Float {
        planePose.getTransformedAxis(1, 1.0f, normal, 0)
        return (cameraPose.tx() - planePose.tx()) * normal[0] +
            (cameraPose.ty() - planePose.ty()) * normal[1] +
            (cameraPose.tz() - planePose.tz()) * normal[2]
    }

    private fun addAnchor(hit: HitResult) {
        try {
            // Keep memory and tracking cost bounded: drop the oldest measurement.
            if (anchors.size >= MAX_POINTS && anchors.size % 2 == 0) {
                anchors.removeAt(0).detach()
                anchors.removeAt(0).detach()
            }
            anchors.add(hit.createAnchor())
        } catch (e: Exception) {
            Log.w(TAG, "Could not create anchor", e)
        }
    }

    /** Low-pass filter on the reticle position so the live reading doesn't jitter. */
    private fun updateSmoothed(pose: Pose) {
        val x = pose.tx(); val y = pose.ty(); val z = pose.tz()
        if (!hasSmoothed) {
            smoothed[0] = x; smoothed[1] = y; smoothed[2] = z
            hasSmoothed = true
            return
        }
        val dx = x - smoothed[0]; val dy = y - smoothed[1]; val dz = z - smoothed[2]
        if (dx * dx + dy * dy + dz * dz > JUMP_RESET_SQ) {
            smoothed[0] = x; smoothed[1] = y; smoothed[2] = z
        } else {
            smoothed[0] += dx * SMOOTHING
            smoothed[1] += dy * SMOOTHING
            smoothed[2] += dz * SMOOTHING
        }
    }

    // ---- Projection helpers ----

    private fun setWorld(out: FloatArray, pose: Pose) {
        out[0] = pose.tx(); out[1] = pose.ty(); out[2] = pose.tz(); out[3] = 1f
    }

    /** Projects a single pose into screen[0..1]. Returns false if it's behind the camera. */
    private fun projectPoint(pose: Pose): Boolean {
        setWorld(worldA, pose)
        Matrix.multiplyMV(clipA, 0, viewProj, 0, worldA, 0)
        if (clipA[3] <= NEAR) return false
        toScreen(clipA, 0)
        return true
    }

    /**
     * Projects worldA→worldB into screen[0..3], clipping against the near plane so
     * segments that pass behind the camera are still drawn correctly.
     */
    private fun projectSegment(): Boolean {
        Matrix.multiplyMV(clipA, 0, viewProj, 0, worldA, 0)
        Matrix.multiplyMV(clipB, 0, viewProj, 0, worldB, 0)
        // Signed distance to the near plane in clip space (z >= -w is visible).
        val da = clipA[2] + clipA[3]
        val db = clipB[2] + clipB[3]
        if (da < 0f && db < 0f) return false
        if (da < 0f) {
            clipToNear(clipA, clipB, da, db)
        } else if (db < 0f) {
            clipToNear(clipB, clipA, db, da)
        }
        if (clipA[3] <= 1e-5f || clipB[3] <= 1e-5f) return false
        toScreen(clipA, 0)
        toScreen(clipB, 2)
        return true
    }

    private fun clipToNear(behind: FloatArray, front: FloatArray, dBehind: Float, dFront: Float) {
        val t = dBehind / (dBehind - dFront)
        for (k in 0..3) behind[k] += t * (front[k] - behind[k])
    }

    private fun toScreen(clip: FloatArray, offset: Int) {
        val ndcX = clip[0] / clip[3]
        val ndcY = clip[1] / clip[3]
        screen[offset] = (ndcX + 1f) * 0.5f * viewWidth
        screen[offset + 1] = (1f - ndcY) * 0.5f * viewHeight
    }

    private fun distance(a: Pose, b: Pose): Float {
        val dx = a.tx() - b.tx(); val dy = a.ty() - b.ty(); val dz = a.tz() - b.tz()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun distance3(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // ---- Status text ----

    private fun currentReadout(meters: Float?): String =
        meters?.let { unit.format(it) } ?: context.getString(R.string.readout_empty)

    private fun trackingHint(reason: TrackingFailureReason): String = context.getString(
        when (reason) {
            TrackingFailureReason.INSUFFICIENT_LIGHT -> R.string.hint_too_dark
            TrackingFailureReason.EXCESSIVE_MOTION -> R.string.hint_too_fast
            TrackingFailureReason.INSUFFICIENT_FEATURES -> R.string.hint_no_features
            TrackingFailureReason.CAMERA_UNAVAILABLE -> R.string.hint_camera_unavailable
            TrackingFailureReason.BAD_STATE -> R.string.hint_bad_state
            else -> R.string.hint_initializing
        }
    )

    /** Only posts to the UI thread when the text actually changes. */
    private fun postStatus(readout: String, hint: String) {
        if (readout == lastReadout && hint == lastHint) return
        lastReadout = readout
        lastHint = hint
        mainHandler.post { statusListener(readout, hint) }
    }

    private companion object {
        const val TAG = "ArRenderer"
        const val NEAR = 0.05f
        const val FAR = 100f
        const val MAX_POINTS = 40
        const val MAX_HIT_DISTANCE = 10f
        const val SMOOTHING = 0.35f
        const val JUMP_RESET_SQ = 0.15f * 0.15f
    }
}
