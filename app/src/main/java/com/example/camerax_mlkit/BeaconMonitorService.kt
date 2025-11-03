package com.example.camerax_mlkit.geofence

import android.app.Service
import android.content.Intent
import android.os.IBinder

class BeaconMonitorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}