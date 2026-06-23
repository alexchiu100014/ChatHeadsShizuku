package com.chatheads.launcher.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Rect
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

object FreeformLauncher {

    private const val TAG = "FreeformLauncher"

    private var freeformService: IFreeformService? = null
    private var bound = false

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            "com.chatheads.launcher",
            FreeformUserService::class.java.name
        )
    )
        .daemon(false)
        .processNameSuffix("freeform")
        .debuggable(true)
        .version(2)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            freeformService = IFreeformService.Stub.asInterface(service)
            bound = true
            Log.d(TAG, "Freeform service connected")

            try {
                if (!freeformService!!.isFreeformEnabled) {
                    freeformService!!.enableFreeformMode()
                    Log.d(TAG, "Freeform mode enabled via Shizuku")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable freeform mode", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            freeformService = null
            bound = false
            Log.d(TAG, "Freeform service disconnected")
        }
    }

    fun bindService() {
        if (!bound) {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        }
    }

    fun unbindService() {
        if (bound) {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            bound = false
        }
    }

    val isReady: Boolean get() = bound && freeformService != null

    fun launchApp(context: Context, packageName: String, bounds: Rect) {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: run {
            Log.e(TAG, "No launch intent for $packageName")
            return
        }
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
        )

        if (bound && freeformService != null) {
            try {
                freeformService!!.startActivityInFreeform(
                    launchIntent,
                    bounds.left, bounds.top,
                    bounds.right, bounds.bottom
                )
                return
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku freeform launch failed", e)
            }
        }

        Log.w(TAG, "Shizuku service not available, cannot launch in freeform")
    }
}
