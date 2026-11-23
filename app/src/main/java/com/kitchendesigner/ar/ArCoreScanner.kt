package com.kitchendesigner.ar

/**
 * Guided ARCore room scanner feeding a [ScanSession].
 *
 * HONEST STATUS: there is no ARCore runtime in this build, so [isAvailable]
 * returns false and [startScan] fails fast with `ARCORE_UNAVAILABLE`. The
 * testable seam is [ingestObservation] + [finishScan]: plane-extent estimates
 * produced by the real ARCore pipeline (below) will be fed through exactly
 * those methods, fused by [ScanSession], and emitted as a [ScanResult].
 *
 * TODO-ARCORE — the real implementation seams, naming the actual APIs:
 *  - Availability: `com.google.ar.core.ArCoreApk.getInstance().checkAvailability(context)`
 *    (returns SUPPORTED_INSTALLED / SUPPORTED_APK_TOO_OLD / SUPPORTED_NOT_INSTALLED /
 *    UNSUPPORTED_DEVICE_NOT_CAPABLE / UNKNOWN_CHECKING). It is currently reached
 *    via reflection / a TODO seam so this module compiles without the ARCore
 *    SDK on the classpath; [isAvailable] hard-codes false until that lands.
 *  - Session: create `com.google.ar.core.Session`, then `session.configure(config)`
 *    with a `com.google.ar.core.Config` whose depth mode is
 *    `Config.DepthMode.AUTOMATIC` (depth-from-motion; best 0.5–5 m, no LiDAR
 *    on Android in practice), plane finding enabled, light estimation off.
 *  - Per frame: `session.update()` returns a `com.google.ar.core.Frame`;
 *    depth comes from `frame.acquireDepthImage16Bits()` (millimetre depth
 *    image) and the colour camera from `frame.acquireCameraImage()`.
 *  - Geometry: track `com.google.ar.core.Plane`s via
 *    `session.getAllTrackables(Plane::class.java)`, keeping only planes whose
 *    `trackingState == TrackingState.TRACKING` and whose type is
 *    `Plane.Type.HORIZONTAL_UPWARD_FACING` (floor) or vertical (walls);
 *    `plane.extentX / extentZ` and `plane.centerPose` give wall lengths.
 *    `frame.hitTest(...)` yields `com.google.ar.core.HitResult`s for tap-to-
 *    measure points; pin stable geometry with `plane.createAnchor(pose)` /
 *    `session.createAnchor(pose)` (`com.google.ar.core.Anchor`).
 *  - Fusion: snap detected wall planes to right angles, convert extents to
 *    [DimensionObservation]s (confidence derived from tracking duration,
 *    depth agreement and plane polygon area), feed them via
 *    [ingestObservation], and let the user confirm/correct lengths — ARCore
 *    guided scan is an estimate, never survey-grade.
 */
class ArCoreRoomScanner(private val session: ScanSession = ScanSession()) : RoomScanner {

    override val source: ScanSource = ScanSource.ARCORE_GUIDED

    private var pendingCallback: ScanCallback? = null
    private var scanning: Boolean = false

    /**
     * False in this build: no ARCore runtime is bundled.
     * TODO-ARCORE: replace with the real check —
     * `ArCoreApk.getInstance().checkAvailability(context)` is
     * SUPPORTED_INSTALLED (see class KDoc for the reflection/TODO seam).
     */
    override fun isAvailable(): Boolean = false

    /**
     * Begins a guided scan session and stores [callback] for [finishScan].
     * Because [isAvailable] is false in this build, it immediately reports
     * `ARCORE_UNAVAILABLE` and directs the user to the manual / laser paths.
     * Observations may still be pre-ingested via [ingestObservation] and
     * emitted through [finishScan] — that is the testable path.
     */
    override fun startScan(callback: ScanCallback) {
        pendingCallback = callback
        scanning = true
        if (!isAvailable()) {
            callback.onError(
                "ARCORE_UNAVAILABLE",
                "ARCore guided scan is not available on this device/build — " +
                    "enter dimensions manually or connect a Bluetooth laser measure instead",
            )
        }
        // TODO-ARCORE: when available, create the com.google.ar.core.Session here,
        // configure DepthMode.AUTOMATIC, and stream plane extents into ingestObservation().
    }

    /** Feeds one fused dimension estimate into the session (the ARCore plane-extent seam). */
    fun ingestObservation(observation: DimensionObservation) {
        session.addObservation(observation)
    }

    /** Feeds one laser measurement into the session (laser overrides AR for that dimension). */
    fun ingestLaser(measurement: LaserMeasurement) {
        session.addLaser(measurement)
    }

    /**
     * Emits the fused session result to [callback]: [ScanSession.toScanResult]
     * on success, or `INSUFFICIENT_DATA` when width/depth are not both known.
     */
    fun finishScan(callback: ScanCallback) {
        val result = session.toScanResult()
        if (result == null) {
            callback.onError(
                "INSUFFICIENT_DATA",
                "Not enough scan data: both room width and depth are required before a room can be built",
            )
        } else {
            callback.onResult(result)
        }
        scanning = false
        if (pendingCallback === callback) pendingCallback = null
    }

    /** Stops the scan and clears any stored callback/state. */
    override fun stopScan() {
        pendingCallback = null
        scanning = false
        // TODO-ARCORE: session.close() on the real com.google.ar.core.Session here.
    }
}
