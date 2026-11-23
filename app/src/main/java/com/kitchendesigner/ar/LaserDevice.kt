package com.kitchendesigner.ar

/**
 * A Bluetooth Low Energy laser distance meter (Bosch GLM / Leica DISTO class
 * devices). The laser is the accuracy path for room dimensions: a laser
 * reading is treated as confidence 1.0 by [ScanSession] and overrides AR
 * estimates for the same dimension.
 */
interface LaserDevice {
    val name: String
    fun isConnected(): Boolean

    /** Attempts to connect; returns true on success. */
    fun connect(): Boolean
    fun disconnect()

    /**
     * Takes one measurement for [kind] and delivers it asynchronously.
     * The callback receives null when no measurement could be taken
     * (not connected, timeout, user cancelled on the device, ...).
     */
    fun measure(kind: DimensionKind, callback: (LaserMeasurement?) -> Unit)
}

/**
 * Placeholder BLE UUIDs for laser meters. These are NOT authoritative:
 * TODO-BLE — verify against vendor documentation before shipping.
 *
 * Bosch GLM and Leica DISTO meters expose proprietary GATT services whose
 * UUIDs are only published in each vendor's developer documentation / SDK
 * terms (Leica DISTO SDK, Bosch MeasureOn / GLM partner docs). The values
 * below are deliberately obvious placeholders so no one mistakes them for
 * verified constants.
 */
object BleLaserUuids {
    // TODO-BLE: placeholder — verify against vendor documentation.
    const val SERVICE_UUID_PLACEHOLDER = "00000000-0000-0000-0000-00000000LASE"

    // TODO-BLE: placeholder — verify against vendor documentation.
    const val MEASUREMENT_CHARACTERISTIC_UUID_PLACEHOLDER = "00000000-0000-0000-0000-00000000MEAS"

    // The one standard UUID here: BLE Client Characteristic Configuration
    // descriptor (0x2902), used to enable notifications on a characteristic.
    const val CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"
}

/**
 * Stub [LaserDevice]: never connected, [connect] always fails and [measure]
 * always calls back null. It exists so the UI and [ScanSession] wiring can be
 * built and tested before the real BLE implementation lands.
 *
 * The real implementation (TODO-BLE) follows the standard Android BLE GATT
 * flow for Bosch GLM / Leica DISTO type meters:
 *
 * 1. SCAN — obtain a `BluetoothLeScanner` from the system `BluetoothAdapter`
 *    (requires BLUETOOTH_SCAN / location runtime permissions depending on API
 *    level) and scan filtered by the meter's measurement service UUID (see
 *    [BleLaserUuids]; verify the UUID against vendor documentation). Match on
 *    advertised service UUID rather than device name, which users can rename.
 * 2. CONNECT — call `device.connectGatt(context, autoConnect = false, callback)`
 *    and wait for `BluetoothGattCallback.onConnectionStateChange` reporting
 *    `BluetoothProfile.STATE_CONNECTED`.
 * 3. DISCOVER — call `gatt.discoverServices()`, then locate the measurement
 *    service and its measurement characteristic in
 *    `onServicesDiscovered`.
 * 4. SUBSCRIBE — `gatt.setCharacteristicNotification(characteristic, true)` and
 *    write `BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE` to the
 *    characteristic's Client Characteristic Configuration descriptor (0x2902)
 *    so the meter pushes a notification per trigger pull.
 * 5. MEASURE / PARSE — each `onCharacteristicChanged` notification carries one
 *    distance payload. Vendor payloads differ (verify against vendor
 *    documentation): commonly a little-endian IEEE-754 float of metres, or a
 *    fixed-point integer of millimetres. Convert metres -> mm with
 *    `(metres * 1000).roundToInt()`, sanity-check the range (typical meters:
 *    ~0.05 m to 50–120 m depending on model), and deliver a [LaserMeasurement]
 *    for the [DimensionKind] the user was prompted to measure.
 * 6. TEARDOWN — `gatt.disconnect()` then `gatt.close()`; never reuse a closed
 *    `BluetoothGatt`. Handle `onConnectionStateChange` DISCONNECTED by
 *    clearing state and reporting not-connected to the UI.
 *
 * Error handling: GATT operations must be serialised (one outstanding
 * operation at a time) and every step needs a timeout, because meters drop
 * the link when they sleep. A failed or timed-out measurement calls back null.
 */
class StubBleLaserDevice : LaserDevice {
    override val name: String = "Stub BLE laser (not implemented)"

    override fun isConnected(): Boolean = false

    override fun connect(): Boolean = false // STUB: no BLE stack wired up yet — see class KDoc.

    override fun disconnect() = Unit

    override fun measure(kind: DimensionKind, callback: (LaserMeasurement?) -> Unit) {
        callback(null) // STUB: never connected, so no measurement is possible.
    }
}
