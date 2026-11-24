package com.faroquetech.aospisland

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.net.Uri
import android.content.IntentFilter
import android.app.KeyguardManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import kotlin.math.max

/** Where the camera cutout is and how big the island should be around it (all px, current rotation). */
data class Geometry(val centerX: Int, val top: Int, val holeW: Int, val pillH: Int, val screenW: Int, val screenH: Int) {
    val landscape: Boolean get() = screenW > screenH

    companion object {
        @Suppress("DEPRECATION")
        fun compute(ctx: Context, cfg: IslandConfig): Geometry {
            val d = ctx.resources.displayMetrics.density
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
            val size = Point(); display.getRealSize(size)
            var rect: Rect? = null
            if (!cfg.manualCutout && Build.VERSION.SDK_INT >= 29) {
                val cutout = display.cutout
                rect = cutout?.boundingRects?.filter { it.top <= 1 && !it.isEmpty && it.width() < size.x / 2 }
                    ?.minByOrNull { kotlin.math.abs(it.centerX() - size.x / 2) }
            }
            if (rect == null) {
                val w = (cfg.cutoutWdp * d).toInt(); val h = (cfg.cutoutHdp * d).toInt(); val t = (cfg.cutoutTopDp * d).toInt()
                rect = Rect(size.x / 2 - w / 2, t, size.x / 2 + w / 2, t + h)
            }
            val margin = (3 * d).toInt()
            val top = max(0, rect.top - margin) + (cfg.yOffsetDp * d).toInt()
            val bottom = rect.bottom + margin + (cfg.yOffsetDp * d).toInt()
            val pillH = max(bottom - top + (cfg.heightTrimDp * d).toInt(), (12 * d).toInt())
            return Geometry(rect.centerX(), top, rect.width() + margin, pillH, size.x, size.y)
        }
    }
}

/**
 * Hosts an [IslandView] in two windows:
 *  - the VISUAL window: full screen width, anchored at the top, NOT touchable. Its x and width never change after attach
 *    (only its height follows the expanded card), so the window manager can never move the pill sideways. The pill is
 *    drawn at the cutout's x inside it.
 *  - the INPUT proxy: a small invisible window that follows the pill's bounds and forwards every touch to the view.
 *    It is the only window that moves / resizes, and nothing is drawn in it, so any lag there is invisible. Touches
 *    everywhere else go straight to the app underneath.
 */
class IslandWindow(private val ctx: Context, private val windowType: Int, private val systemHost: Boolean) {
    val view = IslandView(ctx)
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var lp: WindowManager.LayoutParams? = null
    private var geo: Geometry? = null
    private var config = IslandConfig()
    var attached = false; private set

    private var proxy: InputProxy? = null
    private var proxyLp: WindowManager.LayoutParams? = null
    private var pendingBounds: Rect? = null
    @Volatile private var locked = false
    private var lockReceiverRegistered = false
    private val keyguard by lazy { ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshLockState() }
    }

    private inner class InputProxy(c: Context) : View(c) {
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val a = lp ?: return false; val b = proxyLp ?: return false
            val e = MotionEvent.obtain(ev)
            e.offsetLocation((b.x - a.x).toFloat(), (b.y - a.y).toFloat())
            val handled = try { view.dispatchTouchEvent(e) } finally { e.recycle() }
            return handled
        }
    }

    private fun baseFlags(): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (systemHost) f = f or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        return f
    }

    private fun cutoutMode(p: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= 30) { p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS; p.fitInsetsTypes = 0 }
        else if (Build.VERSION.SDK_INT >= 28) p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    @Suppress("DEPRECATION")
    fun attach(cfg: IslandConfig) {
        if (attached) { update(cfg); return }
        config = cfg
        val d = ctx.resources.displayMetrics.density
        val g = Geometry.compute(ctx, cfg); geo = g
        val params = WindowManager.LayoutParams(
            g.screenW, g.pillH + (18 * d).toInt(), windowType,
            baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            title = "AOSPIsland"
            x = 0; y = g.top
            cutoutMode(this)
        }
        lp = params
        view.config = cfg
        view.onSizeNeeded = { _, h -> resize(h) }
        view.onTouchBounds = { r -> updateProxy(r) }
        view.onInputMode = { on -> setInputMode(on) }
        view.setGeometry(g.holeW, g.pillH, g.screenW, g.centerX, 0, g.top)
        view.suppressed = cfg.hideInLandscape && g.landscape
        wm.addView(view, params)
        attached = true
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(lockReceiver, filter, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(lockReceiver, filter)
            lockReceiverRegistered = true
        } catch (_: Throwable) { }
        refreshLockState()

        val pv = InputProxy(ctx)
        val pp = WindowManager.LayoutParams(
            1, 1, windowType,
            baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            title = "AOSPIslandInput"
            x = 0; y = g.top
            cutoutMode(this)
        }
        proxy = pv; proxyLp = pp
        try { wm.addView(pv, pp) } catch (ignored: Throwable) { proxy = null; proxyLp = null }
        if (locked) updateProxy(null) else updateProxy(view.touchBounds())
    }

    private fun refreshLockState() {
        val isLocked = try { keyguard.isKeyguardLocked } catch (_: Throwable) { false }
        if (locked == isLocked) return
        locked = isLocked
        view.lockHidden = locked
        view.visibility = if (locked) View.INVISIBLE else View.VISIBLE
        if (locked) updateProxy(null) else updateProxy(view.touchBounds())
    }

    fun update(cfg: IslandConfig) {
        config = cfg
        if (!attached) return
        val g = Geometry.compute(ctx, cfg); geo = g
        view.config = cfg
        view.setGeometry(g.holeW, g.pillH, g.screenW, g.centerX, 0, g.top)
        view.suppressed = cfg.hideInLandscape && g.landscape
        lp?.let { it.x = 0; it.y = g.top; it.width = g.screenW; safeUpdate(it) }
        if (locked) updateProxy(null) else updateProxy(view.touchBounds())
        refreshLockState()
    }

    /** Call on rotation / display changes. */
    fun onConfigurationChanged(@Suppress("UNUSED_PARAMETER") c: Configuration?) = update(config)

    /** Only the height can change: x and width of the visual window are fixed. */
    private fun resize(h: Int) {
        val p = lp ?: return; val g = geo ?: return
        if (p.height == h && p.y == g.top && p.x == 0 && p.width == g.screenW) return
        p.height = h; p.x = 0; p.y = g.top; p.width = g.screenW
        safeUpdate(p)
    }

    private fun updateProxy(r: Rect?) {
        pendingBounds = r
        if (!attached) return
        val p = proxyLp ?: return; val pv = proxy ?: return; val g = geo ?: return
        val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (r == null || r.width() <= 0 || r.height() <= 0) {
            if (p.flags and notTouchable == 0) {
                p.flags = p.flags or notTouchable; p.width = 1; p.height = 1; p.x = 0; p.y = g.top
                try { wm.updateViewLayout(pv, p) } catch (ignored: Throwable) { }
            }
            return
        }
        val nx = r.left; val ny = g.top + r.top; val nw = r.width(); val nh = r.height()
        if (p.flags and notTouchable == 0 && p.x == nx && p.y == ny && p.width == nw && p.height == nh) return
        p.flags = p.flags and notTouchable.inv()
        p.x = nx; p.y = ny; p.width = nw; p.height = nh
        try { wm.updateViewLayout(pv, p) } catch (ignored: Throwable) { }
    }

    /** Reply typing: the (otherwise never focusable) island window takes focus so the keyboard can attach, then gives it back. */
    private fun setInputMode(on: Boolean) {
        val p = lp ?: return
        val nf = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val focusable = p.flags and nf == 0
        if (focusable == on) return
        p.flags = if (on) p.flags and nf.inv() else p.flags or nf
        p.softInputMode = if (on) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED
        safeUpdate(p)
    }

    private fun safeUpdate(p: WindowManager.LayoutParams) { try { if (attached) wm.updateViewLayout(view, p) } catch (ignored: Throwable) { } }

    fun detach() {
        if (!attached) return
        attached = false
        proxy?.let { try { wm.removeViewImmediate(it) } catch (ignored: Throwable) { } }
        proxy = null; proxyLp = null
        try { wm.removeViewImmediate(view) } catch (ignored: Throwable) { }
        if (lockReceiverRegistered) { try { ctx.unregisterReceiver(lockReceiver) } catch (_: Throwable) { }; lockReceiverRegistered = false }
    }
}
