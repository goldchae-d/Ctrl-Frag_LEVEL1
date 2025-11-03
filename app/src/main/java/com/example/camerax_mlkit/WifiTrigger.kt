package com.example.camerax_mlkit

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

object WifiTrigger {
    private var cm: ConnectivityManager? = null
    private var registered = false
    private var appCtx: Context? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Wi-Fi 연결됨 → 신뢰 Wi-Fi 여부에 따라 허용 ON
            appCtx?.let { ctx ->
                TriggerGate.setTrustedWifi(true, ctx)
            }
        }

        override fun onLost(network: Network) {
            // Wi-Fi 연결 해제 → 허용 OFF
            appCtx?.let { ctx ->
                TriggerGate.setTrustedWifi(false, ctx)
            }
        }
    }

    /** 앱 시작 시 한 번 호출 (예: MainActivity.onCreate) */
    fun start(ctx: Context) {
        if (registered) return
        appCtx = ctx.applicationContext
        cm = appCtx!!.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        cm?.registerNetworkCallback(req, callback)
        registered = true
    }

    /** 앱 종료/로그아웃 등에서 필요 시 호출 */
    fun stop() {
        if (!registered) return
        cm?.unregisterNetworkCallback(callback)
        registered = false
        appCtx = null
    }
}
