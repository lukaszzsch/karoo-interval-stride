package com.daviddokakis.karoointerval

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.UUID

/** ERG settings: on/off and the chosen trainer (Bluetooth FTMS). */
class ErgSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("erg", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled

    private val _trainer = MutableStateFlow(prefs.getString(KEY_ADDRESS, null)?.let { it to (prefs.getString(KEY_NAME, null) ?: it) })
    /** Address and name of the trainer, or null when none is chosen. */
    val trainer: StateFlow<Pair<String, String>?> = _trainer

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
    }

    fun setTrainer(address: String, name: String) {
        prefs.edit().putString(KEY_ADDRESS, address).putString(KEY_NAME, name).apply()
        _trainer.value = address to name
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"

        @Volatile
        private var instance: ErgSettings? = null

        fun get(context: Context): ErgSettings =
            instance ?: synchronized(this) {
                instance ?: ErgSettings(context.applicationContext).also { instance = it }
            }

        /** Bluetooth permissions the trainer connection needs (asked on the Trainer page). */
        val PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun hasPermissions(context: Context): Boolean =
            PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}

/**
 * One trainer driven over Bluetooth FTMS (Fitness Machine Service), e.g. a Wahoo KICKR: take
 * control, then set the target power (ERG). The Karoo keeps reading the trainer's power over
 * ANT+ (its trainer set to Read-Only), so the trainer's Bluetooth is free for this.
 *
 * Every call suspends until the trainer answers (or throws on timeout / disconnect).
 */
@SuppressLint("MissingPermission") // checked with ErgSettings.hasPermissions before use
class FtmsTrainer(private val context: Context, private val address: String) {

    private var gatt: BluetoothGatt? = null
    private var controlPoint: BluetoothGattCharacteristic? = null
    private val ops = Mutex()

    @Volatile private var connected: CompletableDeferred<Boolean>? = null
    @Volatile private var servicesFound: CompletableDeferred<Boolean>? = null
    @Volatile private var descriptorWritten: CompletableDeferred<Boolean>? = null
    @Volatile private var response: CompletableDeferred<ByteArray>? = null

    /** False once the link dropped; the controller then makes a new [FtmsTrainer]. */
    @Volatile var isUp = false
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Timber.i("FTMS connection state $newState (status $status)")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected?.complete(true)
            } else {
                isUp = false
                val error = IllegalStateException("trainer disconnected")
                connected?.complete(false)
                servicesFound?.complete(false)
                descriptorWritten?.complete(false)
                response?.completeExceptionally(error)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesFound?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorWritten?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onIndication(c.uuid, c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onIndication(c.uuid, value)
        }
    }

    private fun onIndication(uuid: UUID, value: ByteArray) {
        // Control point answers: 0x80, the request's opcode, a result code.
        if (uuid == CONTROL_POINT && value.size >= 3 && value[0] == RESPONSE) response?.complete(value)
    }

    /** Connects, finds FTMS, turns on control point indications and takes control. */
    suspend fun open() = ops.withLock {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val device: BluetoothDevice = manager.adapter?.getRemoteDevice(address) ?: error("no Bluetooth")
        connected = CompletableDeferred()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        check(withTimeout(TIMEOUT_MS * 2) { connected!!.await() }) { "could not connect" }

        servicesFound = CompletableDeferred()
        gatt!!.discoverServices()
        check(withTimeout(TIMEOUT_MS * 2) { servicesFound!!.await() }) { "no services" }
        val cp = gatt!!.getService(FTMS_SERVICE)?.getCharacteristic(CONTROL_POINT) ?: error("trainer has no FTMS control point")
        controlPoint = cp

        gatt!!.setCharacteristicNotification(cp, true)
        val cccd = cp.getDescriptor(CCCD) ?: error("no indication descriptor")
        descriptorWritten = CompletableDeferred()
        writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        check(withTimeout(TIMEOUT_MS) { descriptorWritten!!.await() }) { "could not enable indications" }
        isUp = true

        command(byteArrayOf(OP_REQUEST_CONTROL))
        command(byteArrayOf(OP_START))
    }

    /** ERG: hold [watts]. */
    suspend fun setTargetPower(watts: Int) = ops.withLock {
        val w = watts.coerceIn(0, 2000)
        command(byteArrayOf(OP_SET_TARGET_POWER, (w and 0xFF).toByte(), (w shr 8 and 0xFF).toByte()))
    }

    /** Lets go of the resistance (pause / no target), keeping the connection. */
    suspend fun pause() = ops.withLock {
        command(byteArrayOf(OP_STOP_PAUSE, PARAM_PAUSE))
    }

    /** Sends start/resume after a pause. */
    suspend fun resume() = ops.withLock {
        command(byteArrayOf(OP_START))
    }

    fun close() {
        isUp = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    /** Writes a control point command and waits for its answer; throws unless it succeeded. */
    private suspend fun command(bytes: ByteArray) {
        val cp = controlPoint ?: error("not connected")
        val answer = CompletableDeferred<ByteArray>()
        response = answer
        writeCharacteristic(cp, bytes)
        val r = withTimeout(TIMEOUT_MS) { answer.await() }
        check(r[1] == bytes[0]) { "unexpected answer" }
        check(r[2] == RESULT_SUCCESS) { "trainer refused command 0x%02x (result %d)".format(bytes[0], r[2]) }
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(c: BluetoothGattCharacteristic, value: ByteArray) {
        val g = gatt ?: error("not connected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            c.value = value
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(c)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(d: BluetoothGattDescriptor, value: ByteArray) {
        val g = gatt ?: error("not connected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value)
        } else {
            d.value = value
            g.writeDescriptor(d)
        }
    }

    companion object {
        val FTMS_SERVICE: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
        private val CONTROL_POINT: UUID = UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val OP_REQUEST_CONTROL: Byte = 0x00
        private const val OP_SET_TARGET_POWER: Byte = 0x05
        private const val OP_START: Byte = 0x07
        private const val OP_STOP_PAUSE: Byte = 0x08
        private const val PARAM_PAUSE: Byte = 0x02
        private const val RESPONSE: Byte = 0x80.toByte()
        private const val RESULT_SUCCESS: Byte = 0x01
        private const val TIMEOUT_MS = 5_000L
    }
}
