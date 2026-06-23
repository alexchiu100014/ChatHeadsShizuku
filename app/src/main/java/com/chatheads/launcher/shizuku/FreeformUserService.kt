package com.chatheads.launcher.shizuku

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

class FreeformUserService : IFreeformService.Stub() {

    companion object {
        private const val TAG = "FreeformUserService"
        private const val WINDOWING_MODE_FREEFORM = 5
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
    }

    override fun startActivityInFreeform(
        intent: Intent,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val bounds = Rect(left, top, right, bottom)

        if (launchViaActivityTaskManager(intent, bounds)) return
        if (launchViaAmCommand(intent, bounds)) return

        Log.e(TAG, "All freeform launch methods failed")
    }

    override fun enableFreeformMode() {
        runShellCommand("settings put global enable_freeform_support 1")
        runShellCommand("settings put global force_resizable_activities 1")
        Log.d(TAG, "Freeform mode enabled")
    }

    override fun isFreeformEnabled(): Boolean {
        val result = runShellCommand("settings get global enable_freeform_support")
        return result.trim() == "1"
    }

    override fun destroy() {
        exitProcess(0)
    }

    private fun launchViaActivityTaskManager(intent: Intent, bounds: Rect): Boolean {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val atBinder = getService.invoke(null, "activity_task") as IBinder

            val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val atm = asInterface.invoke(null, atBinder)!!

            val options = ActivityOptions.makeBasic()
            options.setLaunchBounds(bounds)
            HiddenApiBypass.invoke(
                options::class.java, options,
                "setLaunchWindowingMode", WINDOWING_MODE_FREEFORM
            )

            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
            )

            val methods = HiddenApiBypass.getDeclaredMethods(atm::class.java)
            val startActivity = methods.filterIsInstance<java.lang.reflect.Method>()
                .filter { it.name == "startActivity" }
                .maxByOrNull { it.parameterCount }
                ?: throw NoSuchMethodException("startActivity not found on IActivityTaskManager")

            val paramCount = startActivity.parameterCount
            Log.d(TAG, "Found startActivity with $paramCount params")

            val args = buildStartActivityArgs(paramCount, intent, options)
            val result = startActivity.invoke(atm, *args)
            Log.d(TAG, "IActivityTaskManager.startActivity returned: $result")
            true
        } catch (e: Exception) {
            Log.e(TAG, "IActivityTaskManager launch failed", e)
            false
        }
    }

    private fun buildStartActivityArgs(
        paramCount: Int,
        intent: Intent,
        options: ActivityOptions
    ): Array<Any?> {
        // Android 12-15 IActivityTaskManager.startActivity signature:
        // (IApplicationThread, String callingPackage, String callingFeatureId,
        //  Intent, String resolvedType, IBinder resultTo, String resultWho,
        //  int requestCode, int startFlags, ProfilerInfo, Bundle options)
        // = 11 params
        //
        // Some versions add/remove params. We match by count.
        return when {
            paramCount >= 11 -> arrayOf(
                null,                           // caller (IApplicationThread)
                "com.chatheads.launcher",        // callingPackage
                null,                           // callingFeatureId
                intent,                         // intent
                intent.type,                    // resolvedType
                null,                           // resultTo (IBinder)
                null,                           // resultWho
                0,                              // requestCode
                0,                              // startFlags
                null,                           // profilerInfo
                options.toBundle()              // bOptions
            )
            paramCount >= 10 -> arrayOf(
                null,                           // caller
                "com.chatheads.launcher",        // callingPackage
                intent,                         // intent
                intent.type,                    // resolvedType
                null,                           // resultTo
                null,                           // resultWho
                0,                              // requestCode
                0,                              // startFlags
                null,                           // profilerInfo
                options.toBundle()              // bOptions
            )
            else -> throw IllegalArgumentException("Unexpected param count: $paramCount")
        }
    }

    private fun launchViaAmCommand(intent: Intent, bounds: Rect): Boolean {
        return try {
            val component = intent.component
            val target = if (component != null) {
                "-n ${component.flattenToShortString()}"
            } else {
                val pkg = intent.`package` ?: return false
                pkg
            }

            // am start supports --windowingMode directly on Android 12+
            val cmd = "am start" +
                " $target" +
                " --windowingMode $WINDOWING_MODE_FREEFORM" +
                " -f ${intent.flags}" +
                " --user 0"

            val result = runShellCommand(cmd)
            Log.d(TAG, "am start result: $result")
            !result.contains("Error") && !result.contains("Exception")
        } catch (e: Exception) {
            Log.e(TAG, "am start launch failed", e)
            false
        }
    }

    private fun runShellCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
            process.waitFor()
            if (error.isNotEmpty()) Log.w(TAG, "Shell stderr: $error")
            output
        } catch (e: Exception) {
            Log.e(TAG, "Shell command failed: $command", e)
            "Error: ${e.message}"
        }
    }
}
