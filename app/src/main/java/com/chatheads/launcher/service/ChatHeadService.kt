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
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.chatheads.launcher.R
import com.chatheads.launcher.shizuku.FreeformLauncher
import com.chatheads.launcher.ui.MainActivity
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ChatHeadService : Service() {

    private lateinit var windowManager: WindowManager
    private var packages = listOf<String>()

    // Main bubble
    private var mainBubbleView: View? = null
    private var mainBubbleParams: WindowManager.LayoutParams? = null

    // Expanded app icons
    private val expandedViews = mutableListOf<Pair<View, WindowManager.LayoutParams>>()
    private var expanded = false

    // Remove zone
    private var removeView: View? = null

    private var screenWidth = 0
    private var screenHeight = 0

    companion object {
        private const val CHANNEL_ID = "chathead_service"
        private const val NOTIFICATION_ID = 1
        private const val MAIN_BUBBLE_SIZE = 160
        private const val APP_ICON_SIZE = 130
        private const val EXPAND_RADIUS = 280
        private const val REMOVE_ZONE_SIZE = 160

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
        val newPackages = intent?.getStringArrayListExtra("packages") ?: return START_STICKY
        packages = newPackages.toList()

        if (mainBubbleView == null) {
            createMainBubble()
        }
        // Collapse if expanded so it rebuilds with new packages next tap
        if (expanded) collapseMenu()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        collapseMenu()
        mainBubbleView?.let { safeRemoveView(it) }
        removeView?.let { safeRemoveView(it) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createMainBubble() {
        val bubbleView = FrameLayout(this).apply {
            val icon = ImageView(this@ChatHeadService).apply {
                setImageResource(R.drawable.ic_bubble)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(30, 30, 30, 30)
                setColorFilter(0xFFFFFFFF.toInt())
            }
            addView(icon, FrameLayout.LayoutParams(MAIN_BUBBLE_SIZE, MAIN_BUBBLE_SIZE))
            setBackgroundResource(android.R.color.transparent)
            clipToOutline = true
            outlineProvider = CircleOutlineProvider()
            elevation = 10f
            setBackgroundColor(0xFF6750A4.toInt())
        }

        val params = WindowManager.LayoutParams(
            MAIN_BUBBLE_SIZE, MAIN_BUBBLE_SIZE,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = screenHeight / 3
        }

        setupMainBubbleTouch(bubbleView, params)
        windowManager.addView(bubbleView, params)
        mainBubbleView = bubbleView
        mainBubbleParams = params
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMainBubbleTouch(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (expanded) collapseMenu() else expandMenu()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
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
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) {
                        if (expanded) collapseMenu()
                        params.x = (initialX + dx).toInt()
                        params.y = (initialY + dy).toInt()
                        windowManager.updateViewLayout(view, params)
                        updateRemoveHighlight(params.x + MAIN_BUBBLE_SIZE / 2, params.y + MAIN_BUBBLE_SIZE / 2)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    hideRemoveZone()
                    if (moved) {
                        if (isInRemoveZone(params.x + MAIN_BUBBLE_SIZE / 2, params.y + MAIN_BUBBLE_SIZE / 2)) {
                            stopSelf()
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

    private fun expandMenu() {
        if (packages.isEmpty()) return
        val bp = mainBubbleParams ?: return
        val cx = bp.x + MAIN_BUBBLE_SIZE / 2
        val cy = bp.y + MAIN_BUBBLE_SIZE / 2

        // Determine fan direction: if bubble on left edge, fan right; otherwise fan left
        val onLeft = cx < screenWidth / 2
        val startAngle = if (onLeft) -90.0 else 90.0
        val sweep = if (onLeft) 180.0 else -180.0

        val count = packages.size
        val angleStep = if (count > 1) sweep / (count - 1) else 0.0

        packages.forEachIndexed { i, pkg ->
            val icon = getAppIcon(pkg) ?: return@forEachIndexed
            val angle = Math.toRadians(startAngle + angleStep * i)
            val ix = cx + (EXPAND_RADIUS * cos(angle)).toInt() - APP_ICON_SIZE / 2
            val iy = cy + (EXPAND_RADIUS * sin(angle)).toInt() - APP_ICON_SIZE / 2

            val iconView = FrameLayout(this).apply {
                val img = ImageView(this@ChatHeadService).apply {
                    setImageDrawable(icon)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                addView(img, FrameLayout.LayoutParams(APP_ICON_SIZE, APP_ICON_SIZE))
                clipToOutline = true
                outlineProvider = CircleOutlineProvider()
                elevation = 8f
                alpha = 0f
            }

            val iconParams = WindowManager.LayoutParams(
                APP_ICON_SIZE, APP_ICON_SIZE,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = ix
                y = iy
            }

            iconView.setOnClickListener {
                launchInFreeform(pkg)
                collapseMenu()
            }

            windowManager.addView(iconView, iconParams)
            iconView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).setStartDelay((i * 30).toLong()).start()
            expandedViews.add(iconView to iconParams)
        }
        expanded = true
    }

    private fun collapseMenu() {
        expandedViews.forEach { (view, _) ->
            view.animate().alpha(0f).setDuration(100).withEndAction {
                safeRemoveView(view)
            }.start()
        }
        expandedViews.clear()
        expanded = false
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val centerX = params.x + MAIN_BUBBLE_SIZE / 2
        val targetX = if (centerX < screenWidth / 2) 0 else screenWidth - MAIN_BUBBLE_SIZE

        val springX = SpringAnimation(view, DynamicAnimation.TRANSLATION_X, 0f).apply {
            spring = SpringForce(0f).apply {
                stiffness = SpringForce.STIFFNESS_MEDIUM
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            }
        }

        springX.addUpdateListener { _, _, _ ->
            params.x = (targetX + view.translationX).toInt()
            try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
        }
        springX.addEndListener { _, _, _, _ ->
            view.translationX = 0f
            params.x = targetX
            try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
        }

        view.translationX = (params.x - targetX).toFloat()
        params.x = targetX
        springX.start()
    }

    private fun launchInFreeform(packageName: String) {
        val w = (screenWidth * 0.7).toInt()
        val h = (screenHeight * 0.7).toInt()
        val bounds = Rect((screenWidth - w) / 2, (screenHeight - h) / 2, (screenWidth + w) / 2, (screenHeight + h) / 2)
        FreeformLauncher.launchApp(this, packageName, bounds)
    }

    // --- Remove zone ---

    private fun createRemoveView() {
        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_remove)
            setPadding(40, 40, 40, 40)
            setBackgroundResource(android.R.drawable.dialog_holo_dark_frame)
            alpha = 0f
        }
        val p = WindowManager.LayoutParams(
            REMOVE_ZONE_SIZE, REMOVE_ZONE_SIZE,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 100
        }
        windowManager.addView(img, p)
        removeView = img
    }

    private fun showRemoveZone() { removeView?.animate()?.alpha(1f)?.setDuration(200)?.start() }
    private fun hideRemoveZone() { removeView?.animate()?.alpha(0f)?.setDuration(200)?.start() }

    private fun updateRemoveHighlight(bx: Int, by: Int) {
        val close = isInRemoveZone(bx, by)
        removeView?.scaleX = if (close) 1.3f else 1f
        removeView?.scaleY = if (close) 1.3f else 1f
    }

    private fun isInRemoveZone(x: Int, y: Int): Boolean {
        val cx = screenWidth / 2
        val cy = screenHeight - 100 - REMOVE_ZONE_SIZE / 2
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy < REMOVE_ZONE_SIZE * REMOVE_ZONE_SIZE * 4
    }

    // --- Utilities ---

    private fun getAppIcon(pkg: String): Drawable? =
        try { packageManager.getApplicationIcon(pkg) } catch (_: PackageManager.NameNotFoundException) { null }

    private fun safeRemoveView(v: View) {
        try { windowManager.removeView(v) } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "ChatHead Service", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps ChatHead bubbles running"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ChatHeads")
            .setContentText("Tap the bubble to launch apps in freeform")
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private class CircleOutlineProvider : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }
}
