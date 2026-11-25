package com.sansui.remote2026

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.sansui.remote2026.core.Discovery
import java.net.Inet4Address

/** Finds Android / Google TVs on the Wi-Fi via mDNS (_androidtvremote2._tcp). */
class NsdDiscovery(context: Context) : Discovery {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var callback: ((String, String) -> Unit)? = null

    @Synchronized
    override fun start(onFound: (String, String) -> Unit) {
        stop()
        callback = onFound
        lock = wifi.createMulticastLock("sansui-remote").apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onStartDiscoveryFailed(t: String?, e: Int) { stop() }
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            override fun onServiceLost(s: NsdServiceInfo?) {}
            override fun onServiceFound(s: NsdServiceInfo) = enqueue(s)
        }
        listener = l
        try { nsd.discoverServices("_androidtvremote2._tcp", NsdManager.PROTOCOL_DNS_SD, l) }
        catch (_: Exception) { stop() }
    }

    @Synchronized
    override fun stop() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listener = null; callback = null; queue.clear(); resolving = false
        lock?.let { if (it.isHeld) it.release() }; lock = null
    }

    @Synchronized private fun enqueue(s: NsdServiceInfo) { queue.addLast(s); next() }

    // NsdManager can only resolve one service at a time.
    @Synchronized private fun next() {
        if (resolving) return
        val s = queue.removeFirstOrNull() ?: return
        resolving = true
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo?, e: Int) { done() }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val host = ipv4(i)
                    if (host != null) callback?.invoke(i.serviceName, host)
                    done()
                }
            })
        } catch (_: Exception) { done() }
    }

    @Synchronized private fun done() { resolving = false; next() }

    @Suppress("DEPRECATION")
    private fun ipv4(i: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= 34) {
            i.hostAddresses.firstOrNull { it is Inet4Address }?.let { return it.hostAddress }
        }
        return (i.host as? Inet4Address)?.hostAddress      // skip IPv6 link-local: not connectable
    }
}
