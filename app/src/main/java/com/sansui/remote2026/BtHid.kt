package com.sansui.remote2026

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.sansui.remote2026.core.BtTransport
import com.sansui.remote2026.core.HidMap
import com.sansui.remote2026.core.TvController
import java.util.concurrent.Executors

/**
 * Backup link: the phone presents itself to the TV as a Bluetooth keyboard + media remote.
 * Used only when Wi-Fi control is unavailable. Support depends on the phone's Bluetooth stack.
 */
@SuppressLint("MissingPermission")
class BtHid(
    private val ctx: Context,
    private val ctl: TvController,
    private val emit: (String) -> Unit
) : BtTransport {
    private val adapter: BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var registered = false
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var supported = adapter != null
    @Volatile private var pendingConnect: String? = null

    fun hasPermission() = Build.VERSION.SDK_INT < 31 ||
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun init() {
        if (adapter == null || !hasPermission()) { push(); return }
        if (hid != null) { push(); return }
        val ok = adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HID_DEVICE) { hid = proxy as BluetoothHidDevice; register() }
            }
            override fun onServiceDisconnected(profile: Int) { hid = null; registered = false; push() }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) { supported = false; push() }
    }

    private fun register() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Sansui Remote", "Phone remote", "Sansui", BluetoothHidDevice.SUBCLASS1_COMBO, HidMap.DESCRIPTOR
        )
        val cb = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
                registered = isRegistered
                if (isRegistered) pendingConnect?.let { pendingConnect = null; connect(it) }
                push()
            }
            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        host = device
                        ctl.setBt(device.address, device.name ?: "TV"); ctl.btLinked(true)
                    }
                    BluetoothProfile.STATE_DISCONNECTED ->
                        if (host?.address == device.address) { host = null; ctl.btLinked(false) }
                }
                push()
            }
        }
        val started = try { hid?.registerApp(sdp, null, null, exec, cb) ?: false } catch (_: Exception) { false }
        if (!started) { supported = false; push() }
    }

    override fun connect(addr: String) {
        if (adapter == null || !hasPermission()) return
        if (hid == null || !registered) { pendingConnect = addr; init(); return }
        try { hid?.connect(adapter.getRemoteDevice(addr)) } catch (_: Exception) {}
    }

    fun forget() {
        host?.let { try { hid?.disconnect(it) } catch (_: Exception) {} }
        host = null; ctl.forgetBt(); push()
    }

    private fun press(r: HidMap.Report): Boolean {
        val h = hid ?: return false; val d = host ?: return false
        if (!h.sendReport(d, r.id, r.data)) return false
        Thread.sleep(18)
        h.sendReport(d, r.id, HidMap.release(r).data)
        return true
    }

    override fun send(code: Int): Boolean = HidMap.forKey(code)?.let { press(it) } ?: false

    override fun type(text: String): Boolean {
        var any = false
        for (ch in text) HidMap.forChar(ch)?.let { if (press(it)) any = true; Thread.sleep(12) }
        return any
    }

    fun push() {
        val devices = if (adapter != null && hasPermission())
            adapter.bondedDevices.joinToString(",") { """{"name":${TvController.q(it.name ?: it.address)},"addr":${TvController.q(it.address)}}""" }
        else ""
        emit("""{"t":"bt","supported":$supported,"perm":${hasPermission()},"on":${adapter?.isEnabled == true},"registered":$registered,"connected":${host != null},"devices":[$devices]}""")
    }
}
