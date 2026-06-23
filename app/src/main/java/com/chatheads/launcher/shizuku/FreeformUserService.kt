package com.chatheads.launcher.shizuku

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.system.exitProcess

class FreeformUserService : IFreeformService.Stub() {

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
        FreeformLauncher.launchInFreeform(intent, bounds)
    }

    override fun destroy() {
        exitProcess(0)
    }
}
