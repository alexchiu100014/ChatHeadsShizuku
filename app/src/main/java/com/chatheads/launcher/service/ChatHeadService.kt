package com.chatheads.launcher.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.IBinder
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.chatheads.launcher.R
import com.chatheads.launcher.shizuku.FreeformLauncher
import com.chatheads.launcher.ui.MainActivity

class ChatHeadService : Service() {

    private lateinit var windowManager: WindowManager
    private val bubbleViews = mutableListOf<BubbleEntry>()
    private var removeView: View? = null
    private var screenWidth = 0
    private var screenHeight = 0

    private data class BubbleEntry(
        val packageName: String,
        val view: View,
        val params: WindowManager.LayoutParams
    )

    companion object {
        private const val CHANNEL_ID = "chathead_service"
        private const val NOTIFICATION_ID = 1
        private const val BUBBLE_SIZE = 160
        private const val REMOVE_ZONE_SIZE = 160
        private const val STACK_OFFSET = 30

        fun start(context: Context, packages: Set<String>) {
            val intent = Intent(context, ChatHeadService::class.java)
            intent.putStringArrayListExtra("packages", ArrayList(packages))
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChatHeadService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val bounds = windowManager.currentWindowMetrics.bounds
        screenWidth = bounds.width()
        screenHeight = bounds.height()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        createRemoveView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val packages = intent?.getStringArrayListExtra("packages") ?: return START_STICKY

        val currentPackages = bubbleViews.map { it.packageName }.toSet()
        val newPackages = packages.toSet()

        val toRemove = currentPackages - newPackages
        val toAdd = newPackages - currentPackages

        toRemove.forEach { pkg -> removeBubble(pkg) }
        toAdd.forEachIndexed { index, pkg ->
            addBubble(pkg, bubbleViews.size + index)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleViews.forEach { entry ->
            try {
                windowManager.removeView(entry.view)
            } catch (_: Exception) {}
        }
        bubbleViews.clear()
        removeView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble(packageName: String, stackIndex: Int) {
        val icon = getAppIcon(packageName) ?: return

        val bubbleContainer = FrameLayout(this).apply {
            val imageView = ImageView(this@ChatHeadService).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            addView(imageView, FrameLayout.LayoutParams(BUBBLE_SIZE, BUBBLE_SIZE))
            clipToOutline = true
            outlineProvider = CircleOutlineProvider()
            elevation = 8f
        }

        val params = WindowManager.LayoutParams(
            BUBBLE_SIZE,
            BUBBLE_SIZE,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200 + stackIndex * (BUBBLE_SIZE + STACK_OFFSET)
        }

        setupTouchListener(bubbleContainer, params, packageName)
        windowManager.addView(bubbleContainer, params)
        bubbleViews.add(BubbleEntry(packageName, bubbleContainer, params))
    }

    private fun removeBubble(packageName: String) {
        val entry = bubbleViews.find { it.packageName == packageName } ?: return
        try {
            windowManager.removeView(entry.view)
        } catch (_: Exception) {}
        bubbleViews.remove(entry)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener(
        view: View,
        params: WindowManager.LayoutParams,
        packageName: String
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                launchInFreeform(packageName)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                isDragging = true
                showRemoveZone()
            }
        })

        view.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = (initialX + dx).toInt()
                        params.y = (initialY + dy).toInt()
                        windowManager.updateViewLayout(view, params)
                        updateRemoveZoneHighlight(params.x + BUBBLE_SIZE / 2, params.y + BUBBLE_SIZE / 2)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    hideRemoveZone()
                    if (isDragging) {
                        if (isInRemoveZone(params.x + BUBBLE_SIZE / 2, params.y + BUBBLE_SIZE / 2)) {
                            removeBubble(packageName)
                        } else {
                            snapToEdge(view, params)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val centerX = params.x + BUBBLE_SIZE / 2
        val targetX = if (centerX < screenWidth / 2) 0 else screenWidth - BUBBLE_SIZE

        val springX = SpringAnimation(view, DynamicAnimation.TRANSLATION_X, 0f).apply {
            spring = SpringForce(0f).apply {
                stiffness = SpringForce.STIFFNESS_MEDIUM
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            }
        }

        springX.addUpdateListener { _, _, _ ->
            params.x = (targetX + view.translationX).toInt()
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {}
        }

        springX.addEndListener { _, _, _, _ ->
            view.translationX = 0f
            params.x = targetX
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {}
        }

        val dx = (targetX - params.x).toFloat()
        view.translationX = -dx
        params.x = targetX
        springX.start()
    }

    private fun createRemoveView() {
        val removeImage = ImageView(this).apply {
            setImageResource(R.drawable.ic_remove)
            setPadding(40, 40, 40, 40)
            setBackgroundResource(android.R.drawable.dialog_holo_dark_frame)
            alpha = 0f
        }

        val rParams = WindowManager.LayoutParams(
            REMOVE_ZONE_SIZE,
            REMOVE_ZONE_SIZE,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        windowManager.addView(removeImage, rParams)
        removeView = removeImage
    }

    private fun showRemoveZone() {
        removeView?.animate()?.alpha(1f)?.setDuration(200)?.start()
    }

    private fun hideRemoveZone() {
        removeView?.animate()?.alpha(0f)?.setDuration(200)?.start()
    }

    private fun updateRemoveZoneHighlight(bubbleX: Int, bubbleY: Int) {
        val isClose = isInRemoveZone(bubbleX, bubbleY)
        removeView?.scaleX = if (isClose) 1.3f else 1f
        removeView?.scaleY = if (isClose) 1.3f else 1f
    }

    private fun isInRemoveZone(x: Int, y: Int): Boolean {
        val removeCenterX = screenWidth / 2
        val removeCenterY = screenHeight - 100 - REMOVE_ZONE_SIZE / 2
        val dx = x - removeCenterX
        val dy = y - removeCenterY
        return dx * dx + dy * dy < (REMOVE_ZONE_SIZE * 2) * (REMOVE_ZONE_SIZE * 2)
    }

    private fun launchInFreeform(packageName: String) {
        val windowWidth = (screenWidth * 0.7).toInt()
        val windowHeight = (screenHeight * 0.7).toInt()
        val left = (screenWidth - windowWidth) / 2
        val top = (screenHeight - windowHeight) / 2
        val bounds = Rect(left, top, left + windowWidth, top + windowHeight)
        FreeformLauncher.launchApp(this, packageName, bounds)
    }

    private fun getAppIcon(packageName: String): Drawable? {
        return try {
            packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ChatHead Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps ChatHead bubbles running"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ChatHeads")
            .setContentText("Floating bubbles active")
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private class CircleOutlineProvider : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }
}
