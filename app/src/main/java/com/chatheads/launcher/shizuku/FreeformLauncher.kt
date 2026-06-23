package com.chatheads.launcher.shizuku

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Rect
import android.os.IBinder
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import java.lang.reflect.Method

object FreeformLauncher {

    private const val TAG = "FreeformLauncher"
    private const val WINDOWING_MODE_FREEFORM = 5

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
        .version(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            freeformService = IFreeformService.Stub.asInterface(service)
            bound = true
            Log.d(TAG, "Freeform service connected")
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
                freeformService?.startActivityInFreeform(
                    launchIntent,
                    bounds.left,
                    bounds.top,
                    bounds.right,
                    bounds.bottom
                )
                return
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku service call failed, falling back", e)
            }
        }

        launchViaActivityOptions(context, launchIntent, bounds)
    }

    private fun launchViaActivityOptions(context: Context, intent: Intent, bounds: Rect) {
        try {
            val options = ActivityOptions.makeBasic()
            options.setLaunchBounds(bounds)
            HiddenApiBypass.invoke(
                ActivityOptions::class.java,
                options,
                "setLaunchWindowingMode",
                WINDOWING_MODE_FREEFORM
            )
            context.startActivity(intent, options.toBundle())
            Log.d(TAG, "Launched via ActivityOptions fallback")
        } catch (e: Exception) {
            Log.e(TAG, "ActivityOptions fallback failed", e)
            context.startActivity(intent)
        }
    }

    fun launchInFreeform(intent: Intent, bounds: Rect) {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService: Method = smClass.getMethod("getService", String::class.java)
            val atBinder = getService.invoke(null, "activity_task") as IBinder

            val atmStubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
            val asInterface: Method = atmStubClass.getMethod("asInterface", IBinder::class.java)
            val atm = asInterface.invoke(null, atBinder)

            val options = ActivityOptions.makeBasic()
            options.setLaunchBounds(bounds)
            HiddenApiBypass.invoke(
                ActivityOptions::class.java,
                options,
                "setLaunchWindowingMode",
                WINDOWING_MODE_FREEFORM
            )

            HiddenApiBypass.invoke(
                atm::class.java,
                atm,
                "startActivity",
                null,                          // caller
                "com.chatheads.launcher",       // callingPackage
                null,                          // callingFeatureId
                intent,                        // intent
                intent.type,                   // resolvedType
                null,                          // resultTo
                null,                          // resultWho
                0,                             // requestCode
                0,                             // startFlags
                null,                          // profilerInfo
                options.toBundle()             // bOptions
            )
            Log.d(TAG, "Launched via IActivityTaskManager")
        } catch (e: Exception) {
            Log.e(TAG, "IActivityTaskManager launch failed", e)
        }
    }
}
