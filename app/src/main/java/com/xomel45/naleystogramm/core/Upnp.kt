package com.xomel45.naleystogramm.core

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL

// Mirrors: src/core/upnp.h — SSDP discovery + SOAP AddPortMapping.
// Requires CHANGE_WIFI_MULTICAST_STATE permission in AndroidManifest.

class Upnp(private val context: Context) {
    companion object {
        private const val TAG         = "Upnp"
        private const val SSDP_ADDR   = "239.255.255.250"
        private const val SSDP_PORT   = 1900
        private const val TIMEOUT_MS  = 5000
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY = 2000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mappingPort = 0
    private var retryCount  = 0
    private var _localIp    = ""

    fun localIp() = _localIp

    var onMapped: ((Boolean) -> Unit)? = null

    fun mapPort(port: Int) {
        mappingPort = port
        retryCount  = 0
        scope.launch { discover() }
    }

    private suspend fun discover() {
        val wm   = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wm.createMulticastLock("naleystogramm_upnp").also { it.acquire() }
        var location = ""
        try {
            val request = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n"

            DatagramSocket().use { sock ->
                sock.soTimeout = TIMEOUT_MS
                _localIp = sock.localAddress.hostAddress ?: ""
                val bytes = request.toByteArray()
                sock.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(SSDP_ADDR), SSDP_PORT))
                val buf = ByteArray(4096)
                val pkt = DatagramPacket(buf, buf.size)
                runCatching { sock.receive(pkt) }
                location = String(buf, 0, pkt.length).lines()
                    .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim() ?: ""
            }
        } catch (e: Exception) {
            Logger.e(TAG, "SSDP error: ${e.message}")
        } finally {
            lock.release()
        }

        if (location.isNotEmpty()) { fetchControlUrl(location); return }

        if (retryCount < MAX_RETRIES) {
            retryCount++
            Logger.d(TAG, "Retry $retryCount/$MAX_RETRIES")
            delay(RETRY_DELAY)
            discover()
        } else {
            Logger.w(TAG, "No IGD found after $MAX_RETRIES retries")
            withContext(Dispatchers.Main) { onMapped?.invoke(false) }
        }
    }

    private suspend fun fetchControlUrl(location: String) {
        runCatching {
            val xml  = URL(location).readText()
            val path = Regex(
                "<serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>" +
                ".*?<controlURL>(.*?)</controlURL>",
                RegexOption.DOT_MATCHES_ALL
            ).find(xml)?.groupValues?.get(1) ?: run {
                withContext(Dispatchers.Main) { onMapped?.invoke(false) }
                return
            }
            val base = location.let { l ->
                val afterScheme = l.substringAfter("://")
                "${l.substringBefore("://")}://${afterScheme.substringBefore("/")}"
            }
            addPortMapping(if (path.startsWith("http")) path else "$base$path")
        }.onFailure { e ->
            Logger.e(TAG, "fetchControlUrl: ${e.message}")
            withContext(Dispatchers.Main) { onMapped?.invoke(false) }
        }
    }

    private suspend fun addPortMapping(controlUrl: String) {
        val soap = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:AddPortMapping xmlns:u="urn:schemas-upnp-org:service:WANIPConnection:1">
      <NewRemoteHost></NewRemoteHost>
      <NewExternalPort>$mappingPort</NewExternalPort>
      <NewProtocol>TCP</NewProtocol>
      <NewInternalPort>$mappingPort</NewInternalPort>
      <NewInternalClient>${_localIp}</NewInternalClient>
      <NewEnabled>1</NewEnabled>
      <NewPortMappingDescription>Naleystogramm</NewPortMappingDescription>
      <NewLeaseDuration>86400</NewLeaseDuration>
    </u:AddPortMapping>
  </s:Body>
</s:Envelope>"""
        runCatching {
            val conn = (URL(controlUrl).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                setRequestProperty("SOAPAction",
                    "\"urn:schemas-upnp-org:service:WANIPConnection:1#AddPortMapping\"")
                doOutput = true
                outputStream.bufferedWriter().use { it.write(soap) }
            }
            val code = conn.responseCode
            Logger.i(TAG, "AddPortMapping HTTP $code for port $mappingPort")
            withContext(Dispatchers.Main) { onMapped?.invoke(code in 200..299) }
        }.onFailure { e ->
            Logger.e(TAG, "addPortMapping: ${e.message}")
            withContext(Dispatchers.Main) { onMapped?.invoke(false) }
        }
    }

    fun destroy() { scope.cancel() }
}
