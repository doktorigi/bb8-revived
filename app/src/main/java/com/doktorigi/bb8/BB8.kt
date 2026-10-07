package com.doktorigi.bb8

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Sphero v1 API packets, as spoken by the original BB-8 (R001). Pure Kotlin so it unit-tests on the JVM.
 * Layouts match what Sphero Edu actually sends (captured from an HCI snoop log), which differ from the old docs.
 */
object Protocol {
    private var seq = 0

    /** FF FF DID CID SEQ DLEN DATA.. CHK. CHK = inverted byte sum of DID..DATA. Replies are requested like spherov2 does. */
    @Synchronized fun packet(did: Int, cid: Int, vararg data: Int): ByteArray {
        seq = (seq + 1) and 0xff
        val body = intArrayOf(did, cid, seq, data.size + 1) + data
        val chk = body.sum().inv() and 0xff
        return (intArrayOf(0xff, 0xff) + body + chk).map { it.toByte() }.toByteArray()
    }

    const val STOP = 0
    const val DRIVE = 1
    const val ROTATE = 2 // turn in place to the heading (Edu uses it while aiming)

    fun roll(speed: Int, heading: Int, state: Int = if (speed > 0) DRIVE else ROTATE): ByteArray {
        val h = Math.floorMod(heading, 360)
        return packet(0x02, 0x30, speed.coerceIn(0, 255), h shr 8, h and 0xff, state, 0)
    }
    fun color(rgb: Int) = packet(0x02, 0x20, rgb shr 16 and 0xff, rgb shr 8 and 0xff, rgb and 0xff)
    fun jumpToMain() = packet(0x01, 0x04)
    /** Edu sets these on every connect (meaning undocumented); BB-8 acked our commands but misbehaved without them. */
    fun eduOptionFlags() = packet(0x02, 0x35, 0x00, 0x00, 0x01, 0x11)
    fun tail(level: Int) = packet(0x02, 0x21, level.coerceIn(0, 255))
    fun setHeading(deg: Int) = packet(0x02, 0x01, deg shr 8, deg and 0xff)
}

private const val TAG = "BB8"
/** Min gap between joystick rolls. Edu drives at ~37 ms; calibration knob if it jitters. */
private const val DRIVE_INTERVAL_MS = 40L

// BLE layout from orbotix/sphero.js lib/adaptors/ble.js
private fun uuid(s: String) = UUID.fromString("22bb746f-$s-7554-2d6f-726568705327")
private val RADIO = uuid("2bb0")
private val ANTI_DOS = uuid("2bbd")
private val TX_POWER = uuid("2bb2")
private val WAKE = uuid("2bbf")
private val ROBOT = uuid("2ba0")
private val COMMANDS = uuid("2ba1")
private val RESPONSES = uuid("2ba6")
private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** BLE link to BB-8. MainActivity grants BLUETOOTH_SCAN/CONNECT before connect(). */
@SuppressLint("MissingPermission")
class BB8(private val ctx: Context, private val say: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    @Volatile private var cmd: BluetoothGattCharacteristic? = null
    private val writeDone = Semaphore(0)
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val latestDrive = AtomicReference<ByteArray?>()

    /** Heading BB-8 was last told to face; moves turn relative to it. 0 = forward as set by aiming. */
    @Volatile var heading = 0
        private set
    @Volatile var baseColor = 0x0044ff
        private set
    val connected get() = cmd != null

    fun connect() {
        if (gatt != null || scanning) return
        val scanner = ctx.getSystemService(BluetoothManager::class.java).adapter
            ?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return say("Turn Bluetooth on first")
        scanning = true
        say("Scanning for BB-8…")
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: return
                if (!scanning || !name.startsWith("BB-")) return
                scanning = false
                scanner.stopScan(this)
                say("Connecting to $name…")
                gatt = r.device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
            override fun onScanFailed(errorCode: Int) { scanning = false; say("Scan failed ($errorCode)") }
        }
        scanner.startScan(cb)
        main.postDelayed({
            if (scanning) {
                scanning = false
                scanner.stopScan(cb)
                say("No BB-8 found. Wake it on its charger and close Sphero Edu.")
            }
        }, 15_000)
    }

    fun disconnect() {
        cmd = null
        gatt?.run { disconnect(); close() }
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) return run { g.discoverServices() }
            cmd = null
            g.close()
            if (gatt === g) gatt = null
            say("Disconnected")
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { thread { unlock(g) } }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { writeDone.release() }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { writeDone.release() }
        // Replies: FF FF MRSP SEQ ... ; MRSP 00 = OK, anything else is BB-8 rejecting the command
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            Log.d(TAG, "rx " + value.joinToString(" ") { "%02x".format(it) })
        }
    }

    private fun unlock(g: BluetoothGatt) {
        val radio = g.getService(RADIO)
        val robot = g.getService(ROBOT)
        if (radio == null || robot == null) return say("That isn't an original BB-8 (no Sphero v1 services)")
        val ok = write(g, radio.getCharacteristic(ANTI_DOS), "011i3".toByteArray()) &&
            write(g, radio.getCharacteristic(TX_POWER), byteArrayOf(7)) &&
            write(g, radio.getCharacteristic(WAKE), byteArrayOf(1))
        val c = robot.getCharacteristic(COMMANDS)
        if (!ok || c == null) { say("BB-8 didn't accept the unlock. Try again."); disconnect(); return }
        // Both sphero.js and spherov2 subscribe to replies before commanding
        val rx = robot.getCharacteristic(RESPONSES)
        if (rx == null || !g.setCharacteristicNotification(rx, true) || !writeCccd(g, rx)) Log.w(TAG, "couldn't subscribe to replies")
        // Same start-up as Sphero Edu: leave the bootloader, give it 2 s to boot, then set option flags
        say("Waking BB-8…")
        write(g, c, Protocol.jumpToMain())
        Thread.sleep(2000)
        cmd = c
        thread(name = "bb8-writer") { writer(g, c) }
        send(Protocol.eduOptionFlags())
        tail(0)
        color(baseColor)
        say("Connected")
    }

    /** One GATT op at a time: wait for onCharacteristicWrite before the next. */
    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic?, bytes: ByteArray,
                      type: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT): Boolean {
        c ?: return false
        writeDone.drainPermits()
        repeat(20) { // the stack answers BUSY while a previous write is still in flight
            if (g.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS) return writeDone.tryAcquire(2, TimeUnit.SECONDS)
            Thread.sleep(10)
        }
        return false
    }

    private fun writeCccd(g: BluetoothGatt, rx: BluetoothGattCharacteristic): Boolean {
        val d = rx.getDescriptor(CCCD) ?: return false
        writeDone.drainPermits()
        return g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS &&
            writeDone.tryAcquire(2, TimeUnit.SECONDS)
    }

    private fun writer(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        // Edu uses acknowledged writes; BB-8 drops unacknowledged ones under load
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        var lastDrive = 0L
        while (cmd === c) {
            var p = queue.poll()
            // Sphero firmware stutters when flooded with rolls; the old SDKs drove at ~10 Hz
            if (p == null && SystemClock.uptimeMillis() - lastDrive >= DRIVE_INTERVAL_MS)
                p = latestDrive.getAndSet(null)?.also { lastDrive = SystemClock.uptimeMillis() }
            p = p ?: queue.poll(20, TimeUnit.MILLISECONDS) ?: continue
            Log.d(TAG, "tx " + p.joinToString(" ") { "%02x".format(it) })
            if (!write(g, c, p, type)) Log.w(TAG, "command write failed or timed out")
        }
    }

    fun send(p: ByteArray) { if (connected) queue.add(p) }

    /** Joystick input: only the newest position matters, so it overwrites instead of queueing. */
    fun drive(speed: Int, h: Int) {
        // Round off finger wobble so BB-8 isn't re-steering on every touch event
        val s = (speed + 2) / 5 * 5
        val newHeading = (Math.floorMod(h, 360) + 2) / 5 * 5 % 360
        if (s == lastDriveSpeed && newHeading == heading) return
        lastDriveSpeed = s
        heading = newHeading
        if (connected) latestDrive.set(Protocol.roll(s, heading, if (s > 0) Protocol.DRIVE else Protocol.STOP))
    }
    private var lastDriveSpeed = -1
    fun roll(speed: Int, h: Int) { heading = Math.floorMod(h, 360); lastDriveSpeed = -1; send(Protocol.roll(speed, heading)) }
    fun color(rgb: Int) = send(Protocol.color(rgb))
    fun setBaseColor(rgb: Int) { baseColor = rgb; color(rgb) }
    fun tail(level: Int) = send(Protocol.tail(level))
    /** Make the current direction "forward" (after aiming with the tail light). */
    fun setForward() { heading = 0; send(Protocol.setHeading(0)) }
    fun stop() { queue.clear(); latestDrive.set(null); send(Protocol.roll(0, heading, Protocol.STOP)) }
}
