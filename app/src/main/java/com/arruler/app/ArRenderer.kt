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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
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
    /** Surface normal recorded for each anchor (null if unknown). Same indices as [anchors]. */
    private val normals = ArrayList<FloatArray?>()
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
    private val invViewProj = FloatArray(16)
    private val ndcIn = FloatArray(4)
    private val rayNear = FloatArray(4)
    private val rayFar = FloatArray(4)
    private val rayOrigin = FloatArray(3)
    private val rayDir = FloatArray(3)
    private val samplePx = 32f * context.resources.displayMetrics.density

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
        if (anchors.isNotEmpty()) {
            anchors.removeAt(anchors.size - 1).detach()
            normals.removeAt(normals.size - 1)
        }
    }

    /** Must run on the GL thread (use GLSurfaceView.queueEvent). */
    fun clearOnGlThread() {
        for (a in anchors) a.detach()
        anchors.clear()
        normals.clear()
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
        val haveInverse = Matrix.invertM(invViewProj, 0, viewProj, 0)

        if (!planesSeen) {
            planesSeen = s.getAllTrackables(Plane::class.java)
                .any { it.trackingState == TrackingState.TRACKING }
        }

        val hit = findTarget(frame, camera, haveInverse)
        if (hit != null) updateSmoothed(hit.x, hit.y, hit.z) else hasSmoothed = false

        if (addRequested.getAndSet(false)) {
            if (hit != null) addAnchor(s, hit) else noSurfaceFlashUntil = System.currentTimeMillis() + 1800
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

        val reticleMode = when {
            hit == null -> OverlayState.RETICLE_NONE
            hit.estimated -> OverlayState.RETICLE_ESTIMATED
            else -> OverlayState.RETICLE_LOCKED
        }
        overlay.publish(OverlayState(points, pointCount, lines, reticleMode))

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

    /** A 3D target under the reticle. [hit] is null when the position was estimated. */
    private class Target(
        val x: Float,
        val y: Float,
        val z: Float,
        val hit: HitResult?,
        val normal: FloatArray?,
        val estimated: Boolean
    )

    /**
     * Finds what the centre of the screen is pointing at, trying in order:
     *  1. A direct ARCore hit: detected plane (floor, table, wall), depth point, or any
     *     tracked feature point — so textured objects and walls work without a plane.
     *  2. The surface the current measurement started on, extended (lets you finish a
     *     measurement across a plain, featureless wall).
     *  3. Feature points just around the centre, projected onto the centre ray.
     */
    private fun findTarget(frame: Frame, camera: Camera, haveInverse: Boolean): Target? {
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f

        for (hit in frame.hitTest(cx, cy)) {
            if (!isUsable(hit, camera)) continue
            val p = hit.hitPose
            return Target(p.tx(), p.ty(), p.tz(), hit, normalOf(hit), estimated = false)
        }

        if (!haveInverse || !screenRay(cx, cy)) return null

        // 2. Extend the start point's surface.
        if (anchors.size % 2 == 1) {
            val start = anchors[anchors.size - 1]
            val n = normals[normals.size - 1]
            if (n != null && start.trackingState == TrackingState.TRACKING) {
                val sp = start.pose
                val denom = n[0] * rayDir[0] + n[1] * rayDir[1] + n[2] * rayDir[2]
                if (abs(denom) > 0.1f) {
                    val t = (n[0] * (sp.tx() - rayOrigin[0]) +
                        n[1] * (sp.ty() - rayOrigin[1]) +
                        n[2] * (sp.tz() - rayOrigin[2])) / denom
                    if (t > NEAR && t < MAX_HIT_DISTANCE) {
                        return Target(
                            rayOrigin[0] + rayDir[0] * t,
                            rayOrigin[1] + rayDir[1] * t,
                            rayOrigin[2] + rayDir[2] * t,
                            null, n, estimated = true
                        )
                    }
                }
            }
        }

        // 3. Nearby feature points, depth taken along the centre ray.
        for (k in 0 until SAMPLE_DIRECTIONS) {
            val angle = (2.0 * Math.PI * k / SAMPLE_DIRECTIONS).toFloat()
            val sx = cx + cos(angle) * samplePx
            val sy = cy + sin(angle) * samplePx
            for (hit in frame.hitTest(sx, sy)) {
                if (!isUsable(hit, camera)) continue
                val p = hit.hitPose
                val depth = (p.tx() - rayOrigin[0]) * rayDir[0] +
                    (p.ty() - rayOrigin[1]) * rayDir[1] +
                    (p.tz() - rayOrigin[2]) * rayDir[2]
                if (depth > NEAR && depth < MAX_HIT_DISTANCE) {
                    return Target(
                        rayOrigin[0] + rayDir[0] * depth,
                        rayOrigin[1] + rayDir[1] * depth,
                        rayOrigin[2] + rayDir[2] * depth,
                        null, normalOf(hit), estimated = true
                    )
                }
                break
            }
        }
        return null
    }

    private fun isUsable(hit: HitResult, camera: Camera): Boolean {
        if (hit.distance > MAX_HIT_DISTANCE) return false
        return when (val trackable = hit.trackable) {
            is Plane -> trackable.trackingState == TrackingState.TRACKING &&
                trackable.isPoseInPolygon(hit.hitPose) &&
                distanceToPlane(hit.hitPose, camera.pose) > 0f
            is DepthPoint -> true
            // Any tracked feature point is a real spot on a real surface; orientation
            // doesn't matter for measuring distance.
            is Point -> trackable.trackingState == TrackingState.TRACKING
            else -> false
        }
    }

    /** Surface normal of the hit if ARCore knows it, else null. */
    private fun normalOf(hit: HitResult): FloatArray? {
        val trackable = hit.trackable
        val known = trackable is Plane || trackable is DepthPoint ||
            (trackable is Point && trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)
        if (!known) return null
        val n = FloatArray(3)
        hit.hitPose.getTransformedAxis(1, 1.0f, n, 0)
        return n
    }

    /** Fills rayOrigin / rayDir (world space) for a screen pixel. */
    private fun screenRay(px: Float, py: Float): Boolean {
        val nx = px / viewWidth * 2f - 1f
        val ny = 1f - py / viewHeight * 2f
        ndcIn[0] = nx; ndcIn[1] = ny; ndcIn[2] = -1f; ndcIn[3] = 1f
        Matrix.multiplyMV(rayNear, 0, invViewProj, 0, ndcIn, 0)
        ndcIn[2] = 1f
        Matrix.multiplyMV(rayFar, 0, invViewProj, 0, ndcIn, 0)
        if (abs(rayNear[3]) < 1e-7f || abs(rayFar[3]) < 1e-7f) return false
        for (k in 0..2) {
            rayNear[k] /= rayNear[3]
            rayFar[k] /= rayFar[3]
        }
        val dx = rayFar[0] - rayNear[0]
        val dy = rayFar[1] - rayNear[1]
        val dz = rayFar[2] - rayNear[2]
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-6f) return false
        rayOrigin[0] = rayNear[0]; rayOrigin[1] = rayNear[1]; rayOrigin[2] = rayNear[2]
        rayDir[0] = dx / len; rayDir[1] = dy / len; rayDir[2] = dz / len
        return true
    }

    private fun distanceToPlane(planePose: Pose, cameraPose: Pose): Float {
        planePose.getTransformedAxis(1, 1.0f, normal, 0)
        return (cameraPose.tx() - planePose.tx()) * normal[0] +
            (cameraPose.ty() - planePose.ty()) * normal[1] +
            (cameraPose.tz() - planePose.tz()) * normal[2]
    }

    private fun addAnchor(s: Session, target: Target) {
        try {
            // Keep memory and tracking cost bounded: drop the oldest measurement.
            if (anchors.size >= MAX_POINTS && anchors.size % 2 == 0) {
                anchors.removeAt(0).detach(); normals.removeAt(0)
                anchors.removeAt(0).detach(); normals.removeAt(0)
            }
            val anchor = target.hit?.createAnchor()
                ?: s.createAnchor(Pose.makeTranslation(target.x, target.y, target.z))
            anchors.add(anchor)
            normals.add(target.normal)
        } catch (e: Exception) {
            Log.w(TAG, "Could not create anchor", e)
        }
    }

    /** Low-pass filter on the reticle position so the live reading doesn't jitter. */
    private fun updateSmoothed(x: Float, y: Float, z: Float) {
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
        const val SAMPLE_DIRECTIONS = 8
    }
}
