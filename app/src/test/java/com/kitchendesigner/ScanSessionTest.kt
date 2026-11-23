package com.kitchendesigner

import com.kitchendesigner.ar.ArCoreRoomScanner
import com.kitchendesigner.ar.DimensionKind
import com.kitchendesigner.ar.DimensionObservation
import com.kitchendesigner.ar.LaserMeasurement
import com.kitchendesigner.ar.ScanCallback
import com.kitchendesigner.ar.ScanResult
import com.kitchendesigner.ar.ScanSession
import com.kitchendesigner.ar.ScanSource
import com.kitchendesigner.ar.StubBleLaserDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSessionTest {

    private fun ar(kind: DimensionKind, valueMm: Int, confidence: Float) =
        DimensionObservation(kind, valueMm, confidence, ScanSource.ARCORE_GUIDED)

    @Test fun laserOverridesArObservations() {
        val session = ScanSession()
        session.addObservation(ar(DimensionKind.WIDTH, 4_000, 0.90f))
        session.addObservation(ar(DimensionKind.WIDTH, 4_100, 0.95f))
        session.addLaser(LaserMeasurement(DimensionKind.WIDTH, 4_050))
        val est = session.estimate()
        assertEquals(4_050, est.widthMm)
        assertEquals(1.0, est.confidenceByKind.getValue(DimensionKind.WIDTH).toDouble(), 0.0001)
        assertFalse(DimensionKind.WIDTH in est.needsConfirmation)
    }

    @Test fun highestConfidenceArObservationWins() {
        val session = ScanSession()
        session.addObservation(ar(DimensionKind.WIDTH, 4_000, 0.70f))
        session.addObservation(ar(DimensionKind.WIDTH, 4_200, 0.90f))
        session.addObservation(ar(DimensionKind.WIDTH, 4_100, 0.80f))
        assertEquals(4_200, session.estimate().widthMm)
    }

    @Test fun confidenceTieGoesToLatestObservation() {
        val session = ScanSession()
        session.addObservation(ar(DimensionKind.WIDTH, 4_200, 0.90f))
        session.addObservation(ar(DimensionKind.WIDTH, 4_300, 0.90f))
        assertEquals(4_300, session.estimate().widthMm)
    }

    @Test fun lowConfidenceDimensionNeedsConfirmation() {
        val session = ScanSession() // default threshold 0.85
        session.addObservation(ar(DimensionKind.WIDTH, 4_000, 0.90f))
        session.addObservation(ar(DimensionKind.DEPTH, 3_000, 0.60f))
        val est = session.estimate()
        assertFalse(DimensionKind.WIDTH in est.needsConfirmation)
        assertTrue(DimensionKind.DEPTH in est.needsConfirmation)
        assertNotNull(est.room) // low confidence still produces a room, flagged for confirmation
    }

    @Test fun missingDepthMeansNoRoom() {
        val session = ScanSession()
        session.addObservation(ar(DimensionKind.WIDTH, 4_000, 0.95f))
        val est = session.estimate()
        assertEquals(4_000, est.widthMm)
        assertNull(est.depthMm)
        assertNull(est.room)
        assertTrue(DimensionKind.DEPTH in est.needsConfirmation)
        assertNull(session.toScanResult())
    }

    @Test fun missingHeightDefaultsTo2700AndIsFlagged() {
        val session = ScanSession()
        session.addObservation(ar(DimensionKind.WIDTH, 4_000, 0.95f))
        session.addObservation(ar(DimensionKind.DEPTH, 3_000, 0.92f))
        val est = session.estimate()
        assertNull(est.heightMm)
        assertNotNull(est.room)
        assertEquals(2_700, est.room!!.ceilingHeightMm)
        assertTrue(DimensionKind.HEIGHT in est.needsConfirmation)
    }

    @Test fun measuredHeightIsUsedInRoom() {
        val session = ScanSession()
        session.addLaser(LaserMeasurement(DimensionKind.WIDTH, 4_000))
        session.addLaser(LaserMeasurement(DimensionKind.DEPTH, 3_000))
        session.addObservation(ar(DimensionKind.HEIGHT, 2_550, 0.90f))
        val est = session.estimate()
        assertEquals(2_550, est.room!!.ceilingHeightMm)
        assertTrue(est.needsConfirmation.isEmpty())
    }

    @Test fun scanResultUsesLaserSourceAndMinWidthDepthConfidence() {
        val session = ScanSession()
        session.addLaser(LaserMeasurement(DimensionKind.WIDTH, 4_000)) // confidence 1.0
        session.addObservation(ar(DimensionKind.DEPTH, 3_000, 0.90f))
        val result = session.toScanResult()
        assertNotNull(result)
        assertEquals(ScanSource.LASER, result!!.source)
        assertEquals(0.90, result.confidence.toDouble(), 0.0001) // min(1.0, 0.90)
        assertEquals(4_000, result.room.widthMm)
        assertEquals(3_000, result.room.depthMm)
    }

    @Test fun scanResultArOnlySourceAndMinConfidence() {
        val session = ScanSession()
        session.addObservation(ar(DimensionKind.WIDTH, 4_000, 0.88f))
        session.addObservation(ar(DimensionKind.DEPTH, 3_000, 0.92f))
        val result = session.toScanResult()!!
        assertEquals(ScanSource.ARCORE_GUIDED, result.source)
        assertEquals(0.88, result.confidence.toDouble(), 0.0001) // min(0.88, 0.92)
    }

    @Test fun scanResultNotesListDimensionsNeedingConfirmation() {
        val session = ScanSession()
        session.addObservation(ar(DimensionKind.WIDTH, 4_000, 0.90f))
        session.addObservation(ar(DimensionKind.DEPTH, 3_000, 0.50f)) // below threshold
        val result = session.toScanResult()!!
        assertTrue(result.notes.any { it.contains("DEPTH") })
        assertTrue(result.notes.any { it.contains("HEIGHT") }) // missing entirely
        assertFalse(result.notes.any { it.contains("WIDTH") })
    }

    // --- ArCoreRoomScanner testable seam ---

    private class RecordingCallback : ScanCallback {
        var result: ScanResult? = null
        var errorCode: String? = null
        var errorMessage: String? = null
        override fun onResult(result: ScanResult) { this.result = result }
        override fun onError(code: String, message: String) { errorCode = code; errorMessage = message }
    }

    @Test fun arCoreScannerIsUnavailableAndStartScanErrors() {
        val scanner = ArCoreRoomScanner()
        assertFalse(scanner.isAvailable())
        assertEquals(ScanSource.ARCORE_GUIDED, scanner.source)
        val callback = RecordingCallback()
        scanner.startScan(callback)
        assertEquals("ARCORE_UNAVAILABLE", callback.errorCode)
        assertNull(callback.result)
        scanner.stopScan()
    }

    @Test fun arCoreScannerIngestAndFinishEmitsResult() {
        val scanner = ArCoreRoomScanner()
        scanner.ingestObservation(ar(DimensionKind.WIDTH, 4_000, 0.90f))
        scanner.ingestObservation(ar(DimensionKind.DEPTH, 3_000, 0.95f))
        val callback = RecordingCallback()
        scanner.finishScan(callback)
        assertNull(callback.errorCode)
        assertNotNull(callback.result)
        assertEquals(4_000, callback.result!!.room.widthMm)
        assertEquals(0.90, callback.result!!.confidence.toDouble(), 0.0001)
    }

    @Test fun arCoreScannerFinishWithoutDataErrorsInsufficientData() {
        val scanner = ArCoreRoomScanner()
        scanner.ingestObservation(ar(DimensionKind.WIDTH, 4_000, 0.90f)) // depth missing
        val callback = RecordingCallback()
        scanner.finishScan(callback)
        assertEquals("INSUFFICIENT_DATA", callback.errorCode)
        assertNull(callback.result)
    }

    // --- Stub laser device ---

    @Test fun stubLaserDeviceNeverConnectsAndMeasuresNull() {
        val device = StubBleLaserDevice()
        assertFalse(device.isConnected())
        assertFalse(device.connect())
        var measured: LaserMeasurement? = LaserMeasurement(DimensionKind.WIDTH, 1) // sentinel
        var called = false
        device.measure(DimensionKind.WIDTH) { measured = it; called = true }
        assertTrue(called)
        assertNull(measured)
        device.disconnect()
    }
}
