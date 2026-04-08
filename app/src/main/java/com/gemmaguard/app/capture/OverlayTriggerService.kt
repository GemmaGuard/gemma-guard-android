package com.gemmaguard.app.capture

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.gemmaguard.app.R
import com.gemmaguard.app.ui.widget.ScanningOrbView
import kotlin.math.abs

class OverlayTriggerService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var pulseAnimator: AnimatorSet? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> stopSelf()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    private fun showOverlay() {
        if (overlayView != null) {
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val resolvedWindowManager = getSystemService(WindowManager::class.java)
        if (resolvedWindowManager == null) {
            Log.w(TAG, "WindowManager was unavailable. Overlay cannot be shown.")
            stopSelf()
            return
        }

        windowManager = resolvedWindowManager
        val layoutParams = buildLayoutParams(resolvedWindowManager)
        overlayLayoutParams = layoutParams

        val button = buildAnimatedFab()
        button.setOnTouchListener(DragTouchListener(layoutParams))

        try {
            resolvedWindowManager.addView(button, layoutParams)
            overlayView = button
            startPulseAnimation(button)
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun buildLayoutParams(windowManager: WindowManager): WindowManager.LayoutParams {
        val overlaySize = dp(92)
        val margin = dp(20)
        val savedPosition = loadSavedPosition()
        val bounds = windowManager.maximumWindowMetrics.bounds
        val defaultX = (bounds.width() - overlaySize - margin).coerceAtLeast(0)
        val defaultY = (bounds.height() - (overlaySize * 3)).coerceAtLeast(margin)

        return WindowManager.LayoutParams(
            overlaySize,
            overlaySize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedPosition.first ?: defaultX
            y = savedPosition.second ?: defaultY
        }
    }

    private fun removeOverlay() {
        val currentOverlay = overlayView ?: return
        pulseAnimator?.cancel()
        pulseAnimator = null
        windowManager?.removeView(currentOverlay)
        overlayView = null
        overlayLayoutParams = null
        windowManager = null
    }

    private fun buildAnimatedFab(): FrameLayout {
        return FrameLayout(this).apply {
            contentDescription = getString(R.string.overlay_capture_label)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(context, R.color.panel))
                setStroke(dp(2), ContextCompat.getColor(context, R.color.accent))
            }
            elevation = dp(18).toFloat()
            alpha = 0.98f

            addView(
                ScanningOrbView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER)
                    setReadinessProgress(1f)
                },
            )
        }
    }

    private fun startPulseAnimation(button: View) {
        pulseAnimator?.cancel()
        pulseAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(button, View.SCALE_X, 1f, 1.06f, 1f).apply {
                    duration = 1280L
                    repeatCount = ObjectAnimator.INFINITE
                },
                ObjectAnimator.ofFloat(button, View.SCALE_Y, 1f, 1.06f, 1f).apply {
                    duration = 1280L
                    repeatCount = ObjectAnimator.INFINITE
                },
                ObjectAnimator.ofFloat(button, View.ALPHA, 0.92f, 1f, 0.92f).apply {
                    duration = 1280L
                    repeatCount = ObjectAnimator.INFINITE
                },
            )
            start()
        }
    }

    private fun triggerCapture() {
        ContextCompat.startForegroundService(
            this,
            ScreenCaptureService.createCaptureNowIntent(
                context = this,
                reopenAppAfterCapture = true,
            ),
        )
        stopSelf()
    }

    private fun savePosition(x: Int, y: Int) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_X, x)
            .putInt(PREF_Y, y)
            .apply()
    }

    private fun loadSavedPosition(): Pair<Int?, Int?> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasX = prefs.contains(PREF_X)
        val hasY = prefs.contains(PREF_Y)
        return Pair(
            first = if (hasX) prefs.getInt(PREF_X, 0) else null,
            second = if (hasY) prefs.getInt(PREF_Y, 0) else null,
        )
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()

    private inner class DragTouchListener(
        private val layoutParams: WindowManager.LayoutParams,
    ) : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    if (!dragging && (abs(deltaX) > DRAG_THRESHOLD_PX || abs(deltaY) > DRAG_THRESHOLD_PX)) {
                        dragging = true
                    }
                    if (dragging) {
                        layoutParams.x = (initialX + deltaX).coerceAtLeast(0)
                        layoutParams.y = (initialY + deltaY).coerceAtLeast(0)
                        windowManager?.updateViewLayout(view, layoutParams)
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        savePosition(layoutParams.x, layoutParams.y)
                    } else {
                        triggerCapture()
                    }
                    return true
                }

                else -> return false
            }
        }
    }

    companion object {
        private const val TAG = "GemmaGuardOverlay"
        private const val ACTION_SHOW = "com.gemmaguard.app.action.SHOW_CAPTURE_OVERLAY"
        private const val ACTION_HIDE = "com.gemmaguard.app.action.HIDE_CAPTURE_OVERLAY"
        private const val PREFS_NAME = "overlay_fab"
        private const val PREF_X = "fab_x"
        private const val PREF_Y = "fab_y"
        private const val DRAG_THRESHOLD_PX = 12

        fun createShowIntent(context: Context): Intent =
            Intent(context, OverlayTriggerService::class.java).apply {
                action = ACTION_SHOW
            }

        fun createHideIntent(context: Context): Intent =
            Intent(context, OverlayTriggerService::class.java).apply {
                action = ACTION_HIDE
            }
    }
}
