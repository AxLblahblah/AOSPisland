package com.faroquetech.aospisland.xposed

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.faroquetech.aospisland.Demo
import com.faroquetech.aospisland.IslandConfig
import com.faroquetech.aospisland.IslandHub
import com.faroquetech.aospisland.IslandRuntime
import com.faroquetech.aospisland.IslandWindow
import com.faroquetech.aospisland.LsposedNotif
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the island inside the SystemUI process (system mode). It owns a privileged window
 * (status-bar-panel type), so no "draw over other apps" permission is needed, and it reads
 * notifications straight from SystemUI's own listener, so no notification-access permission either.
 * Progress is reported to the settings app (status box) through the settings provider.
 */
object SystemUiIsland {
    private val main = Handler(Looper.getMainLooper())
    private val posted = ConcurrentHashMap<String, StatusBarNotification>()
    @Volatile private var listenerRef: NotificationListenerService? = null
    private var runtime: IslandRuntime? = null
    private var started = false
    @Volatile private var hideCallChip = false
    @Volatile private var hideRecChip = false
    private var chipLogged = false
    private val chipNames = setOf("ongoing_call_chip", "ongoing_activity_chip", "ongoing_activity_chip_primary", "ongoing_activity_chip_secondary")
    private val chipIds = ConcurrentHashMap<Int, Boolean>()
    private var lastType = -1
    private var lastDemo: String? = null
    private val providerUri: Uri = Uri.parse("content://${IslandConfig.AUTHORITY}")
    private val configUri: Uri = Uri.parse("content://${IslandConfig.AUTHORITY}/config")

    fun start(application: Application) {
        if (started) return
        started = true
        appRef = application
        report(application, "hook loaded in SystemUI [build ${IslandConfig.BUILD}], starting in 4s")
        // wait for SystemUI + WindowManager to settle after boot
        main.postDelayed({ try { boot(application) } catch (t: Throwable) { report(application, "ERROR boot: $t") } }, 4000)
    }

    @Suppress("DEPRECATION")
    private fun report(a: Application, msg: String) {
        XposedBridge.log("AOSPIsland: $msg")
        try { a.contentResolver.call(providerUri, "hello", msg, null) } catch (ignored: Throwable) { XposedBridge.log("AOSPIsland: settings provider call failed: $ignored") }
    }

    @Suppress("DEPRECATION")
    private fun readBundle(a: Application): Bundle? =
        try { a.contentResolver.call(providerUri, "get", null, null) } catch (ignored: Throwable) { XposedBridge.log("AOSPIsland: settings provider unreachable: $ignored"); null }

    /** Diagnostics: list SystemUI classes that could be the screen-record chip / notification listener on this ROM (LSPosed log + status box). */
    private fun dumpCandidateClasses(a: Application) {
        Thread {
            try {
                val cl = a.classLoader
                val pl = findField(cl.javaClass, "pathList")?.get(cl) ?: return@Thread
                val els = findField(pl.javaClass, "dexElements")?.get(pl) as? Array<*> ?: return@Thread
                val out = ArrayList<String>()
                for (e in els) {
                    val df = findField(e!!.javaClass, "dexFile")?.get(e) as? dalvik.system.DexFile ?: continue
                    val en = df.entries()
                    while (en.hasMoreElements()) {
                        val n = en.nextElement()
                        if (n.contains('$')) continue
                        if ((n.contains(".chips.") && !n.endsWith("Kt")) || n.contains("ScreenRecord") || n.contains("OngoingActivityChip") ||
                            n.endsWith("NotificationListener") || n.contains("StatusBarRoot")) out.add(n.removePrefix("com.android.systemui."))
                    }
                }
                out.sort()
                XposedBridge.log("AOSPIsland: candidate classes (${out.size}):")
                out.chunked(25).take(24).forEach { XposedBridge.log("AOSPIsland: classes: " + it.joinToString(" ")) }
                report(a, "candidate classes: ${out.size} found, listed in the LSPosed log (tag AOSPIsland: classes)")
            } catch (t: Throwable) { XposedBridge.log("AOSPIsland: class dump failed: $t") }
        }.start()
    }

    private fun boot(a: Application) {
        dumpCandidateClasses(a)
        apply(a)
        a.contentResolver.registerContentObserver(configUri, false, object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) { try { apply(a) } catch (t: Throwable) { report(a, "ERROR apply: $t") } }
        })
    }

    private fun apply(a: Application) {
        val b = readBundle(a)
        val cfg = if (b != null) IslandConfig.fromBundle(b) else IslandConfig(enabled = true, systemMode = true)
        val want = cfg.enabled && cfg.systemMode
        hideCallChip = want && cfg.showCalls && cfg.hideNativeCallChip
        hideRecChip = want && cfg.showRecord && cfg.hideNativeRecordChip
        main.postDelayed({ try { syncBluetoothIcon() } catch (_: Throwable) { } }, 400)
        val old = runtime
        if (old != null && (!want || cfg.windowType != lastType)) { old.stop(); runtime = null }
        if (!want) { report(a, "idle: enabled=${cfg.enabled}, systemMode=${cfg.systemMode} (turn both on in the app)"); return }
        val cur = runtime
        if (cur == null) {
            lastType = cfg.windowType
            try {
                val nr = IslandRuntime(a, IslandWindow(a, cfg.windowType, true), null, cfg)
                runtime = nr
                nr.notifications.onChipState = { syncChips() }
                nr.start()
                report(a, "island window attached (layer ${cfg.windowType})")
                nr.window.view.setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_DOWN) report(a, "touch reached the island"); false }
                nr.window.view.onExpandedChanged = { open -> report(a, if (open) "island expanded" else "island collapsed") }
                try { nr.notifications.reset(listenerRef?.activeNotifications ?: posted.values.toTypedArray()) } catch (ignored: Throwable) { nr.notifications.reset(posted.values.toTypedArray()) }
                lastDemo = b?.getString("demoCmd")
            } catch (t: Throwable) {
                runtime = null
                report(a, "ERROR creating island (layer ${cfg.windowType}): $t")
            }
        } else {
            cur.applyConfig(cfg)
            syncChips()
            val cmd = b?.getString("demoCmd")
            if (cmd != null && cmd != lastDemo) {
                lastDemo = cmd
                Demo.play(cmd.substringBefore(':'), listOf(cur.controller))
            }
        }
    }

    /** SystemUI's own NotificationListenerService already receives every notification; just observe it. */
    fun hookNotificationListener(cl: ClassLoader) {
        // Install heads-up hooks independently of NotificationListener availability. Some vendor
        // SystemUI builds use a different listener class, but still use the AOSP interruption pipeline.
        hookSocialHeadsUp(cl)
        hookHeadsUpDecisionProvider(cl)
        IslandHub.canceller = { k -> try { listenerRef?.cancelNotification(k) } catch (_: Throwable) { } }
        val cls = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.NotificationListener", cl)
        if (cls == null) { XposedBridge.log("AOSPIsland: SystemUI NotificationListener not found - notification feed disabled on this ROM; heads-up hooks attempted"); return }
        XposedBridge.hookAllMethods(cls, "onNotificationPosted", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val sbn = p.args.firstOrNull() as? StatusBarNotification ?: return
                listenerRef = p.thisObject as? NotificationListenerService ?: listenerRef
                posted[sbn.key] = sbn; runtime?.notifications?.post(sbn)
                main.post { syncProgressIcons(); syncSocialIcons() }
            }
        })
        XposedBridge.hookAllMethods(cls, "onNotificationRemoved", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val sbn = p.args.firstOrNull() as? StatusBarNotification ?: return
                posted.remove(sbn.key); runtime?.notifications?.remove(sbn.key)
                main.post { syncProgressIcons(); syncSocialIcons() }
            }
        })
        XposedBridge.hookAllMethods(cls, "onListenerConnected", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) { listenerRef = p.thisObject as? NotificationListenerService }
        })
    }

    /** Suppress duplicate social heads-up UI while leaving the posted notification intact.
     * Hook the interruption decision first: returning false here prevents the heads-up entry
     * from being presented, without cancelling it or removing its actions from the shade.
     */
    /**
     * Android 16/17 take the heads-up decision in VisualInterruptionDecisionProviderImpl.makeAndLogHeadsUpDecision /
     * makeUnloggedHeadsUpDecision(NotificationEntry): DecisionImpl(String reason, boolean shouldInterrupt). Returning a
     * "do not interrupt" decision stops the native header (incoming call card, app alerts) before it is ever built;
     * the notification stays in the shade. Same policy as hookSocialHeadsUp: calls (when Calls is on) and ordinary
     * app notifications are suppressed, SystemUI / dialer-package notifications that are not calls are left alone.
     */
    private fun hookHeadsUpDecisionProvider(cl: ClassLoader) {
        var hooked = 0
        val c = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProviderImpl", cl)
        if (c == null) { XposedBridge.log("AOSPIsland: VisualInterruptionDecisionProviderImpl not found (older SystemUI); relying on the other heads-up hooks"); return }
        for (name in listOf("makeAndLogHeadsUpDecision", "makeUnloggedHeadsUpDecision")) {
            try {
                XposedBridge.hookAllMethods(c, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        try {
                            val res = p.result ?: return
                            val sbn = p.args.asSequence().mapNotNull { findSbn(it, 0) }.firstOrNull() ?: return
                            val pkg = sbn.packageName
                            if (pkg == "com.faroquetech.aospisland") return
                            val isCall = sbn.notification?.category == android.app.Notification.CATEGORY_CALL
                            if (isCall) { if (runtime?.config?.showCalls != true) return }
                            else if (pkg == "com.android.systemui" || pkg.startsWith("com.android.systemui.") ||
                                pkg.contains("incall", ignoreCase = true) || pkg.contains("dialer", ignoreCase = true)) return
                            else if (runtime?.config?.showLive != true) return
                            val ctor = res.javaClass.getDeclaredConstructor(String::class.java, java.lang.Boolean.TYPE)
                            ctor.isAccessible = true
                            p.result = ctor.newInstance("AOSPIsland: shown in island", false)
                            val k = "heads-up decision suppressed $name (${if (isCall) "call" else pkg})"
                            if (iconLog.size < 64 && iconLog.add(k)) XposedBridge.log("AOSPIsland: $k")
                        } catch (t: Throwable) { XposedBridge.log("AOSPIsland: heads-up decision hook error: $t") }
                    }
                })
                hooked++
            } catch (t: Throwable) { XposedBridge.log("AOSPIsland: cannot hook ${c.name}.$name: $t") }
        }
        XposedBridge.log("AOSPIsland: heads-up decision-provider hooks=$hooked")
    }

    private fun hookSocialHeadsUp(cl: ClassLoader) {
        var hooked = 0
        val decisionClasses = listOf(
            // AOSP classes verified in the supplied SystemUI.apk for this ROM.
            "com.android.systemui.statusbar.notification.collection.coordinator.HeadsUpCoordinator",
            "com.android.systemui.statusbar.notification.headsup.HeadsUpManager",
            "com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl",
            "com.android.systemui.statusbar.notification.interruption.HeadsUpViewBinder",
            "com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationInteractor",
            "com.android.systemui.statusbar.notification.ui.viewbinder.HeadsUpNotificationViewBinder",
            "com.android.systemui.statusbar.phone.HeadsUpAppearanceController",
            // Keep legacy/vendor candidates for other builds.
            "com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider",
            "com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl",
            "com.android.systemui.statusbar.policy.HeadsUpManager",
            "com.android.systemui.statusbar.phone.HeadsUpManagerPhone",
            "com.android.systemui.statusbar.notification.interruption.HeadsUpController",
            "com.android.systemui.statusbar.notification.interruption.HeadsUpCoordinator"
        )
        val decisionNames = setOf("shouldHeadsUp", "canAlertCommon", "shouldHeadsUpWhenAdded", "canHeadsUp", "canShowHeadsUp", "shouldHeadsUpEver", "shouldHeadsUpAgain")
        val presentationNames = setOf("showNotification", "addNotification", "showHeadsUpView", "onEntryUpdated", "bindForAsyncHeadsUp", "updateNotification", "showHeadsUp", "maybeShowHeadsUp", "bind")
        for (name in decisionClasses) {
            val c = XposedHelpers.findClassIfExists(name, cl) ?: continue
            for (m in c.declaredMethods) {
                val isDecision = m.name in decisionNames && (m.returnType == java.lang.Boolean.TYPE || m.returnType == java.lang.Boolean::class.java)
                val isPresentation = m.name in presentationNames
                if (!isDecision && !isPresentation) continue
                try {
                    XposedBridge.hookAllMethods(c, m.name, object : XC_MethodHook() {
                        override fun beforeHookedMethod(p: MethodHookParam) {
                            try {
                                val sbn = p.args.asSequence().mapNotNull { findSbn(it, 0) }.firstOrNull()
                                    ?: p.args.asSequence().mapNotNull { arg ->
                                        // Some releases pass a notification key rather than an Entry/SBN.
                                        val key = arg as? String ?: return@mapNotNull null
                                        posted.values.firstOrNull { it.key == key }
                                    }.firstOrNull()
                                    ?: return
                                val pkg = sbn.packageName
                                // Incoming-call notifications are rendered as a native CallCard inside
                                // AOSPIsland. Suppress the separate Android heads-up header only after
                                // the call has been accepted by NotificationSource and is present in
                                // the island; keep the original notification/actions in the shade.
                                val isIncomingCall = sbn.notification?.category == android.app.Notification.CATEGORY_CALL
                                // Do not wait for NotificationSource to report hasCall: its rebuild is debounced and the
                                // heads-up decision runs first, which let the native call header flash/stay on screen.
                                if (isIncomingCall && runtime?.config?.showCalls == true) {
                                    if (isDecision) {
                                        p.result = false
                                    } else if (m.returnType == Void.TYPE) {
                                        p.result = null
                                    } else return
                                    val logKey = "call heads-up routed into island ${c.simpleName}.${m.name}"
                                    if (iconLog.size < 64 && iconLog.add(logKey)) XposedBridge.log("AOSPIsland: $logKey")
                                    return
                                }
                                // Suppress duplicate heads-up for ordinary app notifications globally.
                                // Do not interfere with SystemUI's own UI or call notifications that
                                // have not yet been routed into the island.
                                if (pkg == "com.android.systemui" || pkg.startsWith("com.android.systemui.") ||
                                    pkg.contains("incall", ignoreCase = true) || pkg.contains("dialer", ignoreCase = true)) return
                                if (isDecision) {
                                    p.result = false
                                } else if (m.returnType == Void.TYPE) {
                                    p.result = null
                                } else return
                                val logKey = "heads-up suppressed ${sbn.packageName} ${c.simpleName}.${m.name}"
                                if (iconLog.size < 64 && iconLog.add(logKey)) XposedBridge.log("AOSPIsland: $logKey; notification retained")
                            } catch (t: Throwable) { XposedBridge.log("AOSPIsland: heads-up hook error: $t") }
                        }
                    })
                    hooked++
                } catch (t: Throwable) { XposedBridge.log("AOSPIsland: cannot hook ${c.name}.${m.name}: $t") }
            }
        }
        XposedBridge.log("AOSPIsland: ROM-targeted global heads-up suppression hooks=$hooked")
    }

    private fun findSbn(value: Any?, depth: Int): StatusBarNotification? {
        if (value == null || depth > 3) return null
        if (value is StatusBarNotification) return value
        val cls = value.javaClass
        try {
            val getter = cls.methods.firstOrNull { it.name in setOf("getSbn", "getStatusBarNotification", "getNotificationEntry", "getEntry") && it.parameterTypes.isEmpty() }
            if (getter != null) {
                val result = getter.invoke(value)
                if (result is StatusBarNotification) return result
                if (result != null && result !== value) findSbn(result, depth + 1)?.let { return it }
            }
        } catch (_: Throwable) { }
        for (fieldName in listOf("mSbn", "sbn", "mEntry", "entry", "mNotificationEntry")) {
            try {
                val f = findField(cls, fieldName) ?: continue
                val result = f.get(value)
                if (result is StatusBarNotification) return result
                if (result != null && result !== value) findSbn(result, depth + 1)?.let { return it }
            } catch (_: Throwable) { }
        }
        return null
    }

    private fun isSocialPackage(packageName: String): Boolean {
        val p = packageName.lowercase()
        return listOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thunderdog.challegram", "com.instagram.android", "com.facebook.katana", "com.facebook.orca", "com.facebook.mlite", "com.snapchat.android", "com.discord", "org.thoughtcrime.securesms", "com.signal.org", "com.viber.voip", "com.tencent.mm", "com.twitter.android", "com.x.android", "com.zhiliaoapp.musically", "com.threads.android", "com.linkedin.android", "com.reddit.frontpage", "com.skype.raider", "jp.naver.line.android", "com.google.android.apps.messaging", "com.google.android.youtube", "com.google.android.apps.youtube.creator", "com.microsoft.appmanager", "com.microsoft.yourphonecompanion", "com.google.android.apps.bard", "com.openai.chatgpt", "com.anthropic.claude", "org.lsposed.manager", "org.matrix.vector", "org.matrix.vector.manager").any { p == it || p.startsWith("$it.") }
    }

    // ---- native status-bar chips (call, screen record, ...) -------------------------------------------------
    private val chipViews: MutableSet<View> = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>()))
    private val wantedVis = java.util.WeakHashMap<View, Int>()
    private val chipClasses = ConcurrentHashMap<Class<*>, Boolean>()
    @Volatile private var internalSet = false

    private fun shouldHideChips(): Boolean {
        // The native screen-recording chip/countdown is rendered before Android posts the
        // recording notification. Do not gate suppression on hasRecord: that state arrives
        // too late, which is why the red countdown survived the earlier hook. When the user
        // explicitly enables native-record-chip hiding and AOSPIsland is active, suppress
        // SystemUI's native ongoing-chip row from the beginning of the Start/countdown flow.
        if (hideRecChip) return true
        if (!hideCallChip) return false
        val n = runtime?.notifications ?: return false
        return n.hasCall
    }

    private fun nameMatches(n: String): Boolean {
        if (n.contains("privacy")) return false            // never touch the camera / mic privacy chip
        return n in chipNames || n.startsWith("ongoing_activity_chip") || n.startsWith("ongoing_call_chip") ||
            (n.contains("chip") && (n.contains("ongoing") || n.contains("call") || n.contains("screen_record") || n.contains("recording")))
    }

    private fun isChip(v: View): Boolean {
        val id = v.id
        if (id != View.NO_ID) {
            val byId = chipIds.getOrPut(id) { try { nameMatches(v.resources.getResourceEntryName(id)) } catch (ignored: Throwable) { false } }
            if (byId) return true
        }
        // ROMs without a usable id: match the chip's own classes
        return chipClasses.getOrPut(v.javaClass) {
            val n = v.javaClass.name
            n.startsWith("com.android.systemui.statusbar.chips") && (n.endsWith("ChipBackgroundContainer") || n.contains("OngoingActivityChip"))
        }
    }


    private val chipPkgClasses = ConcurrentHashMap<Class<*>, Boolean>()
    /** True for a chip view or anything inside one (also covers Compose content), judged by id name or class package. */
    private fun underChip(v: View): Boolean {
        var c: View? = v
        var n = 0
        while (c != null && n < 8) {
            if (isChip(c)) return true
            val cls = c.javaClass
            if (chipPkgClasses.getOrPut(cls) { val nm = cls.name; nm.contains(".statusbar.chips.") || nm.contains("OngoingActivityChip") || nm.contains("CallChip") }) return true
            c = c.parent as? View; n++
        }
        return false
    }


    private val iconLog = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private val iconClassCache = ConcurrentHashMap<Class<*>, Boolean>()
    private val sbnMethods = ConcurrentHashMap<Class<*>, java.util.Optional<java.lang.reflect.Method>>()
    private val sbnFields = ConcurrentHashMap<Class<*>, java.util.Optional<java.lang.reflect.Field>>()
    private val iconMethods = ConcurrentHashMap<Class<*>, java.util.Optional<java.lang.reflect.Method>>()
    private val iconFields = ConcurrentHashMap<Class<*>, java.util.Optional<java.lang.reflect.Field>>()
    private val iconDecision = java.util.WeakHashMap<View, Pair<Any?, Boolean>>()

    private fun findField(c: Class<*>, name: String): java.lang.reflect.Field? {
        var k: Class<*>? = c
        while (k != null) { try { return k.getDeclaredField(name).also { it.isAccessible = true } } catch (ignored: Throwable) { }; k = k.superclass }
        return null
    }
    private fun method(c: Class<*>, name: String) = try { java.util.Optional.of(c.getMethod(name)) } catch (t: Throwable) { java.util.Optional.empty() }

    private fun sbnOf(v: View): android.service.notification.StatusBarNotification? {
        val c = v.javaClass
        sbnMethods.getOrPut(c) { method(c, "getNotification") }.orElse(null)?.let { m ->
            try { (m.invoke(v) as? android.service.notification.StatusBarNotification)?.let { return it } } catch (ignored: Throwable) { }
        }
        sbnFields.getOrPut(c) { java.util.Optional.ofNullable(findField(c, "mNotification")) }.orElse(null)?.let { f ->
            try { (f.get(v) as? android.service.notification.StatusBarNotification)?.let { return it } } catch (ignored: Throwable) { }
        }
        return null
    }

    private fun iconPkg(v: View): String? {
        val c = v.javaClass
        try {
            val ic = iconMethods.getOrPut(c) { method(c, "getStatusBarIcon") }.orElse(null)?.invoke(v)
                ?: iconFields.getOrPut(c) { java.util.Optional.ofNullable(findField(c, "mIcon")) }.orElse(null)?.get(v)
                ?: return null
            return ic.javaClass.getField("pkg").get(ic) as? String
        } catch (ignored: Throwable) { return null }
    }

    private val callPkgHints = listOf("dialer", "incallui", "telecom", "phone")

    /** True for the status-bar notification icon (the small phone next to the clock) that belongs to a call. Cheap on the draw path. */
    private fun isCallNotifIcon(v: View): Boolean {
        if (!iconClassCache.getOrPut(v.javaClass) { v.javaClass.name.endsWith("StatusBarIconView") }) return false
        try {
            val sbn = sbnOf(v)
            val token: Any? = sbn
            synchronized(iconDecision) { iconDecision[v]?.let { if (it.first === token && token != null) return it.second } }
            val pkg = sbn?.packageName ?: iconPkg(v) ?: ""
            val n = sbn?.notification
            val cat = n?.category
            val desc = v.contentDescription?.toString() ?: ""
            val call = cat == android.app.Notification.CATEGORY_CALL ||
                n?.extras?.containsKey("android.callType") == true ||
                callPkgHints.any { pkg.contains(it, true) } ||
                (pkg.isEmpty() && (desc.contains("call", true) || desc.contains("phone", true)))
            synchronized(iconDecision) { iconDecision[v] = Pair(token, call) }
            if (iconLog.size < 12 && iconLog.add("pkg=$pkg cat=${cat ?: "-"} call=$call")) XposedBridge.log("AOSPIsland: status icon pkg=$pkg cat=$cat desc=$desc call=$call")
            return call
        } catch (t: Throwable) { return false }
    }

    private val progressIconViews: MutableSet<View> = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>()))
    private val socialIconViews: MutableSet<View> = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>()))
    private val socialWantedVis = java.util.WeakHashMap<View, Int>()
    private val progressWantedVis = java.util.WeakHashMap<View, Int>()
    private val hotspotIconViews: MutableSet<View> = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>()))
    private val hotspotWantedVis = java.util.WeakHashMap<View, Int>()
    private val btIconViews: MutableSet<View> = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>()))
    private val btWantedVis = java.util.WeakHashMap<View, Int>()

    private fun btHideOn(): Boolean { val c = runtime?.config ?: return false; return c.enabled && c.systemMode && c.showBluetooth && c.hideNativeBluetoothIcon }

    /** SystemUI's own Bluetooth status icon (slot "bluetooth"); the island's Bluetooth bubble replaces it. */
    private fun isBluetoothStatusIcon(v: View): Boolean {
        if (!iconClassCache.getOrPut(v.javaClass) { v.javaClass.name.endsWith("StatusBarIconView") }) return false
        val slot = try { v.javaClass.getMethod("getSlot").invoke(v) as? String } catch (_: Throwable) { null }
        if (slot != null) return slot.equals("bluetooth", true)
        val pkg = (iconPkg(v) ?: "").lowercase()
        val sysPkg = pkg.isEmpty() || pkg.contains("systemui") || pkg == "android"
        return sysPkg && ((v.contentDescription?.toString() ?: "").contains("bluetooth", true) || idNameOf(v).contains("bluetooth", true))
    }

    private fun syncBluetoothIcon() {
        val root = barRoot?.get() ?: return
        val on = btHideOn()
        fun walk(v: View, depth: Int) {
            if (depth > 14) return
            if (v.javaClass.name.endsWith("StatusBarIconView")) {
                if (on && isBluetoothStatusIcon(v)) {
                    btIconViews.add(v)
                    synchronized(btWantedVis) { if (btWantedVis[v] == null) btWantedVis[v] = v.visibility }
                    if (v.visibility != View.GONE) setVis(v, View.GONE)
                } else if (btIconViews.contains(v)) {
                    val wanted = synchronized(btWantedVis) { btWantedVis.remove(v) }
                    btIconViews.remove(v)
                    if (v.visibility == View.GONE && wanted == View.VISIBLE) setVis(v, View.VISIBLE)
                }
            }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        try { walk(root, 0) } catch (_: Throwable) { }
    }


    /** Hide only the native hotspot/tethering status icon; leave Wi-Fi and cellular indicators alone. */
    private fun isHotspotStatusIcon(v: View): Boolean {
        if (!iconClassCache.getOrPut(v.javaClass) { v.javaClass.name.endsWith("StatusBarIconView") }) return false
        val desc = (v.contentDescription?.toString() ?: "").lowercase()
        val id = idNameOf(v).lowercase()
        val pkg = (iconPkg(v) ?: "").lowercase()
        return desc.contains("hotspot") || desc.contains("tethering") ||
            id.contains("hotspot") || id.contains("tether") ||
            (pkg.contains("systemui") && (desc.contains("ap active") || desc.contains("access point")))
    }

    private fun syncHotspotStatusIcon() {
        val root = barRoot?.get() ?: return
        fun walk(v: View, depth: Int) {
            if (depth > 14) return
            if (v.javaClass.name.endsWith("StatusBarIconView")) {
                val hide = isHotspotStatusIcon(v)
                if (hide) {
                    hotspotIconViews.add(v)
                    synchronized(hotspotWantedVis) { if (hotspotWantedVis[v] == null) hotspotWantedVis[v] = v.visibility }
                    if (v.visibility != View.GONE) setVis(v, View.GONE)
                } else if (hotspotIconViews.contains(v)) {
                    val wanted = synchronized(hotspotWantedVis) { hotspotWantedVis.remove(v) }
                    hotspotIconViews.remove(v)
                    if (v.visibility == View.GONE && wanted == View.VISIBLE) setVis(v, View.VISIBLE)
                }
            }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        try { walk(root, 0) } catch (_: Throwable) { }
    }

    /** Hide the ordinary status-bar download/progress icon only while its live progress is represented by the island. */
    private fun isProgressNotifIcon(v: View): Boolean {
        if (!iconClassCache.getOrPut(v.javaClass) { v.javaClass.name.endsWith("StatusBarIconView") }) return false
        val rt = runtime ?: return false
        if (!rt.config.showProgress || !rt.config.enabled || !rt.config.systemMode) return false
        val sbn = sbnOf(v) ?: return false
        return try {
            // Download Manager uses a persistent status icon on some ROMs even when it omits
            // standard progress extras or FLAG_ONGOING_EVENT. Match the package while progress
            // display is enabled; keep the underlying notification posted in the shade.
            val downloadManagerTransfer = sbn.packageName == "com.android.providers.downloads"
            rt.notifications.isActiveProgressNotification(sbn) || downloadManagerTransfer
        } catch (ignored: Throwable) { false }
    }

    private fun isSocialNotifIcon(v: View): Boolean {
        if (!iconClassCache.getOrPut(v.javaClass) { v.javaClass.name.endsWith("StatusBarIconView") }) return false
        val sbn = sbnOf(v) ?: return false
        val pkg = sbn.packageName.lowercase()
        if (pkg == "com.faroquetech.aospisland") return false
        val n = sbn.notification
        // LSPosed / Vector: alerts are carried by the island and the permanent "framework active" icon is not wanted
        // in the status bar either, so every status-bar icon of theirs is hidden (the notification stays in the shade)
        if (LsposedNotif.matches(sbn)) return true
        // Screen recording: the island / Now Bar carries it, and SystemUI leaves its notification icon in the status bar
        // after the recording has finished (the "saved" notice stays posted), so that icon is always hidden.
        val rc = runtime?.config
        if (rc != null && rc.enabled && rc.showRecord && (pkg == "com.android.systemui" || pkg.startsWith("com.android.systemui."))) {
            val ch = n.channelId.orEmpty().lowercase()
            val title = n.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty().lowercase()
            if (ch.contains("record") || title.contains("screen record") || title.contains("recording")) return true
        }
        // Any app, including ones installed later: no status-bar icon, the island / Now Bar carries the notification.
        // Only SystemUI and the "android" system package keep theirs (VPN, alarm, etc.).
        if (runtime?.config?.hideAllStatusIcons == true && pkg != "com.android.systemui" && pkg != "android" && !pkg.startsWith("com.android.systemui.")) return true
        if (n.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return false
        val cat = n.category
        val known = listOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thunderdog.challegram", "com.instagram.android", "com.facebook.katana", "com.facebook.orca", "com.facebook.mlite", "com.snapchat.android", "com.discord", "org.thoughtcrime.securesms", "org.signal", "com.viber.voip", "com.tencent.mm", "com.twitter.android", "com.x.android", "com.zhiliaoapp.musically", "com.threads.android", "com.linkedin.android", "com.reddit.frontpage", "com.skype.raider", "jp.naver.line.android", "com.google.android.apps.messaging", "com.google.android.youtube", "com.google.android.apps.youtube.creator", "com.microsoft.appmanager", "com.microsoft.yourphonecompanion", "com.google.android.apps.bard", "com.openai.chatgpt", "com.anthropic.claude", "org.lsposed.manager", "org.matrix.vector", "org.matrix.vector.manager")
        if (known.any { pkg == it || pkg.startsWith("$it.") }) return true
        // "All app notifications": mirror IslandSources so every alert shown in the island loses its status-bar icon
        val c = runtime?.config
        if (c != null && c.showLive && c.showAllNotifications && pkg != "com.android.systemui" && pkg != "android" &&
            n.flags and android.app.Notification.FLAG_ONGOING_EVENT == 0 && n.priority > android.app.Notification.PRIORITY_MIN) return true
        return cat == android.app.Notification.CATEGORY_MESSAGE || cat == "social"
    }

    /**
     * The screen-recording status icon (SystemUI's "screen_record" slot icon or the recorder notification's small icon).
     * Identified by the icon view's slot name and by the drawable resource it shows, so it is caught whether it is a
     * system icon or a notification icon, and also after the recording has ended and SystemUI left it behind.
     */
    private fun isRecordStatusIcon(v: View): Boolean {
        val rc = runtime?.config ?: return false
        if (!rc.enabled || !rc.showRecord) return false
        if (!iconClassCache.getOrPut(v.javaClass) { v.javaClass.name.endsWith("StatusBarIconView") }) return false
        try {
            val slot = try { v.javaClass.getMethod("getSlot").invoke(v) as? String } catch (_: Throwable) { null }
            if (slot != null && (slot.contains("screen_record", true) || slot.contains("screenrecord", true))) return true
            val desc = v.contentDescription?.toString().orEmpty().lowercase()
            if (desc.contains("screen record") || desc.contains("recording screen") || desc.contains("screen capture")) return true
            var c: Class<*>? = v.javaClass
            while (c != null && c != View::class.java) {
                for (f in c.declaredFields) {
                    if (!f.type.name.endsWith("statusbar.StatusBarIcon")) continue
                    f.isAccessible = true
                    val sbi = f.get(v) ?: continue
                    val icon = sbi.javaClass.getField("icon").get(sbi) as? android.graphics.drawable.Icon ?: continue
                    if (icon.type != android.graphics.drawable.Icon.TYPE_RESOURCE) continue
                    val name = v.resources.getResourceEntryName(icon.resId).lowercase()
                    if (name.contains("screenrecord") || name.contains("screen_record")) return true
                }
                c = c.superclass
            }
        } catch (_: Throwable) { }
        return false
    }

    private fun syncSocialIcons() {
        val root = barRoot?.get() ?: return
        fun walk(v: View, depth: Int) {
            if (depth > 14) return
            if (v.javaClass.name.endsWith("StatusBarIconView")) {
                val hide = isSocialNotifIcon(v) || isRecordStatusIcon(v)
                if (hide) {
                    socialIconViews.add(v)
                    synchronized(socialWantedVis) { if (socialWantedVis[v] == null) socialWantedVis[v] = v.visibility }
                    if (v.visibility != View.GONE) setVis(v, View.GONE)
                } else if (socialIconViews.contains(v)) {
                    val wanted = synchronized(socialWantedVis) { socialWantedVis.remove(v) }
                    socialIconViews.remove(v)
                    if (v.visibility == View.GONE && wanted == View.VISIBLE) setVis(v, View.VISIBLE)
                }
            }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        try { walk(root, 0) } catch (ignored: Throwable) { }
    }


    private fun syncProgressIcons() {
        val root = barRoot?.get() ?: return
        fun walk(v: View, depth: Int) {
            if (depth > 14) return
            if (v.javaClass.name.endsWith("StatusBarIconView")) {
                val hide = isProgressNotifIcon(v)
                if (hide) {
                    progressIconViews.add(v)
                    synchronized(progressWantedVis) { if (progressWantedVis[v] == null) progressWantedVis[v] = v.visibility }
                    if (v.visibility == View.VISIBLE) setVis(v, View.GONE)
                } else if (progressIconViews.contains(v)) {
                    val wanted = synchronized(progressWantedVis) { progressWantedVis.remove(v) }
                    progressIconViews.remove(v)
                    if (v.visibility == View.GONE && wanted == View.VISIBLE) setVis(v, View.VISIBLE)
                }
            }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        try { walk(root, 0); syncSocialIcons(); syncHotspotStatusIcon(); syncBluetoothIcon() } catch (ignored: Throwable) { }
    }

    private fun setVis(v: View, vis: Int) { internalSet = true; try { v.visibility = vis } finally { internalSet = false } }

    /** Re-applies the hide / restore decision to every chip view seen so far (called when the island's call / record state changes). */
    private var appRef: Application? = null
    private var barRoot: java.lang.ref.WeakReference<View>? = null
    private var lastDump = ""
    private var treeLogged = false

    /** Fallback: walk the whole status bar, hide everything that looks like a chip, and report what was found. */
    private fun scanBar(hide: Boolean) {
        val root = barRoot?.get() ?: return
        val dump = StringBuilder()
        fun walk(v: View, depth: Int) {
            val idName = try { if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else "" } catch (ignored: Throwable) { "" }
            val cn = v.javaClass.name
            val cand = (idName.isNotEmpty() && nameMatches(idName)) || cn.contains("Chip") || cn.contains("OngoingActivity")
            if (cand) {
                chipViews.add(v); synchronized(wantedVis) { if (wantedVis[v] == null) wantedVis[v] = v.visibility }
                if (dump.length < 700) dump.append(idName.ifEmpty { "-" }).append(':').append(cn.substringAfterLast('.')).append(if (v.visibility == View.VISIBLE) "+ " else "- ")
                if (hide && v.visibility == View.VISIBLE) setVis(v, View.GONE)
                if (hide) collapseParents(v, root, true, dump)
                return
            }
            if (v is android.view.ViewGroup && depth < 14) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        try { walk(root, 0) } catch (t: Throwable) { XposedBridge.log("AOSPIsland: chip scan failed: $t") }
        if (hide) try {
            val bg = StringBuilder()
            fun walk2(v: View, depth: Int) {
                if (v is android.view.ViewGroup) {
                    if (v.background != null && v.visibility == View.VISIBLE && v.width in 1..500 && bg.length < 500)
                        bg.append(idNameOf(v).ifEmpty { "-" }).append(':').append(v.javaClass.name.substringAfterLast('.')).append(' ').append(v.width).append('x').append(v.height).append(" | ")
                    if (depth < 14) for (i in 0 until v.childCount) walk2(v.getChildAt(i), depth + 1)
                }
            }
            walk2(root, 0)
            if (bg.isNotEmpty()) dump.append(" BG: ").append(bg)
        } catch (ignored: Throwable) { }
        if (hide && iconLog.isNotEmpty()) dump.append(" ICONS: ").append(synchronized(iconLog) { iconLog.joinToString(" | ") }.take(400))
        if (hide && dump.isEmpty() && !treeLogged) {
            treeLogged = true
            val t = StringBuilder()
            fun tree(v: View, depth: Int) {
                if (t.length > 6000) return
                t.append("  ".repeat(depth)).append(v.javaClass.name.substringAfterLast('.')).append('#').append(idNameOf(v).ifEmpty { "-" })
                    .append(' ').append(v.width).append('x').append(v.height).append(if (v.visibility == View.VISIBLE) "" else " (gone)").append('\n')
                if (v is android.view.ViewGroup && depth < 9) for (i in 0 until v.childCount) tree(v.getChildAt(i), depth + 1)
            }
            try { tree(root, 0); t.toString().lines().chunked(40).forEach { XposedBridge.log("AOSPIsland: statusbar tree:\n" + it.joinToString("\n")) } } catch (ignored: Throwable) { }
        }
        val d = if (dump.isEmpty()) "none found" else dump.toString()
        if (hide && d != lastDump) { lastDump = d; appRef?.let { report(it, "chips seen in status bar: $d") } }
    }

    private fun idNameOf(v: View) = try { if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else "" } catch (ignored: Throwable) { "" }

    /** After a chip's content is hidden, also hide containers that have nothing visible left (the empty black pill). */
    private fun collapseParents(v: View, root: View, hide: Boolean, dump: StringBuilder?) {
        var p = v.parent as? android.view.ViewGroup
        var n = 0
        while (p != null && p !== root && n < 6) {
            val name = idNameOf(p)
            if (name.contains("status_bar") || name.contains("start_side") || name.contains("clock") || name.contains("system_icons") || name.contains("notification_icon")) break
            var allGone = true
            for (i in 0 until p.childCount) if (p.getChildAt(i).visibility == View.VISIBLE) { allGone = false; break }
            if (!allGone) break
            chipViews.add(p); synchronized(wantedVis) { if (wantedVis[p] == null) wantedVis[p] = View.VISIBLE }
            dump?.append("^" + name.ifEmpty { "-" } + ":" + p.javaClass.name.substringAfterLast('.') + " ")
            if (hide && p.visibility == View.VISIBLE) setVis(p, View.GONE)
            p = p.parent as? android.view.ViewGroup; n++
        }
    }

    private val pendingChipPasses = ArrayList<Runnable>()

    private fun syncChips() {
        syncProgressIcons()
        val hide = shouldHideChips()
        scanBar(hide)
        bumpCompose()
        for (r in pendingChipPasses) main.removeCallbacks(r)
        pendingChipPasses.clear()
        for (d in longArrayOf(300, 1000, 2500)) { val r = Runnable { bumpCompose() }; pendingChipPasses.add(r); main.postDelayed(r, d) }
        if (hide) for (d in longArrayOf(300, 700, 1500, 3000)) { val r = Runnable { if (shouldHideChips()) scanBar(true) }; pendingChipPasses.add(r); main.postDelayed(r, d) }
        barRoot?.get()?.invalidate()
        val views = synchronized(chipViews) { chipViews.toList() }
        for (v in views) try {
            if (hide) { if (v.visibility == View.VISIBLE) setVis(v, View.GONE) }
            else if (v.visibility == View.GONE) { val w = synchronized(wantedVis) { wantedVis[v] }; if (w == View.VISIBLE) setVis(v, View.VISIBLE) }
            // GPU display lists are cached: dirty the chip and its parents so the "skip drawing" hooks run again at once
            v.invalidate(); var q = v.parent; var k = 0
            while (q is View && k < 10) { q.invalidate(); q = q.parent; k++ }
        } catch (ignored: Throwable) { }
    }

    /**
     * Hides SystemUI's own ongoing chips (call, screen record) while the island shows that activity, so it lives only in the island.
     * Chips are matched by view id name or class, which differs between Android versions; unknown ROMs simply keep the chip visible.
     * Views are remembered, so the chip is also hidden when it was already visible before the island picked the activity up,
     * and restored when the activity ends.
     */
    fun hookNativeCallChip() {
        try {
            XposedBridge.hookAllMethods(View::class.java, "setVisibility", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (internalSet) return
                    val v = p.thisObject as? View ?: return
                    val vis = p.args.firstOrNull() as? Int ?: return
                    if (btHideOn() && isBluetoothStatusIcon(v)) {
                        btIconViews.add(v); synchronized(btWantedVis) { btWantedVis[v] = vis }
                        if (vis == View.VISIBLE) p.args[0] = View.GONE
                        return
                    }
                    if (isSocialNotifIcon(v)) {
                        socialIconViews.add(v); synchronized(socialWantedVis) { if (socialWantedVis[v] == null) socialWantedVis[v] = vis }
                        if (vis == View.VISIBLE) p.args[0] = View.GONE
                        return
                    }
                    if (isProgressNotifIcon(v)) {
                        progressIconViews.add(v); synchronized(progressWantedVis) { progressWantedVis[v] = vis }
                        if (vis == View.VISIBLE) p.args[0] = View.GONE
                        return
                    }
                    if (!isChip(v)) return
                    chipViews.add(v); synchronized(wantedVis) { wantedVis[v] = vis }
                    if (vis == View.VISIBLE && shouldHideChips()) {
                        p.args[0] = View.GONE
                        if (!chipLogged) { chipLogged = true; XposedBridge.log("AOSPIsland: hid native chip (${v.javaClass.name})") }
                    }
                }
            })
            // Belt and braces: even if the chip stays "visible" (Compose, alpha animations, re-shown by SystemUI), skip drawing it
            XposedHelpers.findAndHookMethod(View::class.java, "draw", android.graphics.Canvas::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try {
                        val v = p.thisObject as? View ?: return
                        if (btIconViews.contains(v) && btHideOn()) { p.setResult(null); return }
                        if (!shouldHideChips()) return
                        if (underChip(v) || isCallNotifIcon(v) || isProgressNotifIcon(v)) p.setResult(null)
                    } catch (ignored: Throwable) { }
                }
            })
            // Parent-side skip: the chip is not even handed to the renderer, whatever its own view does.
            XposedBridge.hookAllMethods(android.view.ViewGroup::class.java, "drawChild", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try {
                        val child = p.args.getOrNull(1) as? View ?: return
                        if (btIconViews.contains(child) && btHideOn()) { p.setResult(false); return }
                        if (!shouldHideChips()) return
                        if (underChip(child) || isProgressNotifIcon(child)) p.setResult(false)
                    } catch (ignored: Throwable) { }
                }
            })
            XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val v = p.thisObject as? View ?: return
                    if (v.javaClass.name.endsWith("PhoneStatusBarView") || v.javaClass.name.endsWith("StatusBarRootView")) barRoot = java.lang.ref.WeakReference(v)
                    if (btHideOn() && isBluetoothStatusIcon(v)) {
                        btIconViews.add(v); synchronized(btWantedVis) { if (btWantedVis[v] == null) btWantedVis[v] = v.visibility }
                        if (v.visibility != View.GONE) setVis(v, View.GONE)
                        return
                    }
                    if (isSocialNotifIcon(v)) {
                        socialIconViews.add(v); synchronized(socialWantedVis) { if (socialWantedVis[v] == null) socialWantedVis[v] = v.visibility }
                        if (v.visibility == View.VISIBLE) setVis(v, View.GONE)
                        return
                    }
                    if (isProgressNotifIcon(v)) {
                        progressIconViews.add(v); synchronized(progressWantedVis) { if (progressWantedVis[v] == null) progressWantedVis[v] = v.visibility }
                        if (v.visibility == View.VISIBLE) setVis(v, View.GONE)
                        return
                    }
                    if (!isChip(v)) return
                    chipViews.add(v); synchronized(wantedVis) { if (wantedVis[v] == null) wantedVis[v] = v.visibility }
                    if (shouldHideChips() && v.visibility == View.VISIBLE) setVis(v, View.GONE)
                }
            })
        } catch (t: Throwable) { XposedBridge.log("AOSPIsland: native chip hook failed: $t") }
    }

    /**
     * Fallback for ROMs where the recording pill is supplied by a dedicated screen-recording
     * view-model rather than the aggregate ongoing-chip model. Only suppress boolean getters
     * whose names explicitly describe visibility/showing of a recording chip; leave recorder
     * service/state and stop actions untouched.
     */
    /** Remove a completed recording from the island when SystemUI runs its actual stop action. */
    fun hookScreenRecordStop(cl: ClassLoader) {
        try {
            val cls = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.chips.screenrecord.domain.interactor.ScreenRecordChipInteractor", cl)
                ?: run { XposedBridge.log("AOSPIsland: ScreenRecordChipInteractor not found for stop cleanup"); return }
            XposedBridge.hookAllMethods(cls, "stopRecording", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    main.post {
                        try { runtime?.notifications?.finishRecording(); report(appRef ?: return@post, "screen recording stopped; island entry cleared") }
                        catch (ignored: Throwable) { }
                    }
                }
            })
            XposedBridge.log("AOSPIsland: hooked ScreenRecordChipInteractor.stopRecording for island cleanup")
        } catch (t: Throwable) { XposedBridge.log("AOSPIsland: stop cleanup hook failed: $t") }
    }

    /**
     * Some Android builds stop recording through the service/repository flow instead of the
     * chip interactor. Observe those lifecycle methods too, and clear only the island's cached
     * recording entry after SystemUI has completed the stop call.
     */
    fun hookScreenRecordingStopLifecycle(cl: ClassLoader) {
        val targets = listOf(
            "com.android.systemui.screenrecord.ScreenRecordingService",
            "com.android.systemui.screenrecord.data.repository.ScreenRecordingServiceRepository",
            "com.android.systemui.screenrecord.data.repository.ScreenRecordRepositoryImpl",
            "com.android.systemui.screenrecord.domain.interactor.ScreenRecordingServiceInteractor",
            "com.android.systemui.statusbar.chips.screenrecord.domain.interactor.ScreenRecordChipInteractor"
        )
        var hooked = 0
        val seen = HashSet<String>()
        for (name in targets) {
            val cls = try { XposedHelpers.findClassIfExists(name, cl) } catch (_: Throwable) { null } ?: continue
            for (m in cls.declaredMethods) {
                val n = m.name.lowercase()
                if (!(n.contains("stop") || n.contains("finish") || n.contains("end") || n.contains("onrecordingstopped"))) continue
                val key = cls.name + "#" + m.name
                if (!seen.add(key)) continue
                try {
                    XposedBridge.hookAllMethods(cls, m.name, object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            XposedBridge.log("AOSPIsland: recorder lifecycle callback ${cls.simpleName}.${m.name}")
                            main.post {
                                try {
                                    runtime?.notifications?.finishRecording()
                                    syncChips()
                                    XposedBridge.log("AOSPIsland: recording island entry cleared from lifecycle callback")
                                } catch (t: Throwable) {
                                    XposedBridge.log("AOSPIsland: lifecycle cleanup failed: $t")
                                }
                            }
                        }
                    })
                    hooked++
                } catch (t: Throwable) {
                    XposedBridge.log("AOSPIsland: recorder lifecycle hook failed ${cls.simpleName}.${m.name}: $t")
                }
            }
        }
        XposedBridge.log("AOSPIsland: recorder lifecycle stop hooks=$hooked")
    }

    fun hookScreenRecordingChipViewModel(cl: ClassLoader) {
        val candidates = listOf(
            "com.android.systemui.statusbar.quickactions.screenrecord.ui.viewmodel.LargeScreenRecordingChipViewModel",
            "com.android.systemui.statusbar.chips.screenrecord.ui.viewmodel.ScreenRecordChipViewModel"
        )
        var count = 0
        for (className in candidates) {
            try {
                val cls = XposedHelpers.findClassIfExists(className, cl) ?: continue
                for (method in cls.declaredMethods) {
                    val n = method.name.lowercase()
                    val visibilityGetter = (n.contains("visible") || n.contains("show") || n.contains("shoulddisplay")) &&
                        (n.contains("chip") || n.contains("record"))
                    if (!visibilityGetter || method.returnType != java.lang.Boolean.TYPE) continue
                    try {
                        XposedBridge.hookAllMethods(cls, method.name, object : XC_MethodHook() {
                            override fun afterHookedMethod(p: MethodHookParam) {
                                if (shouldHideChips()) {
                                    p.result = false
                                    XposedBridge.log("AOSPIsland: suppressed recording-chip visibility via ${cls.simpleName}.${method.name}")
                                }
                            }
                        })
                        count++
                    } catch (t: Throwable) {
                        XposedBridge.log("AOSPIsland: recording viewmodel hook failed ${cls.simpleName}.${method.name}: $t")
                    }
                }
                XposedBridge.log("AOSPIsland: recording viewmodel class found ${cls.name}")
            } catch (t: Throwable) {
                XposedBridge.log("AOSPIsland: recording viewmodel scan failed $className: $t")
            }
        }
        XposedBridge.log("AOSPIsland: recording viewmodel visibility hooks=$count")
    }

    // A Compose state the hook reads while composing; bumping it makes Compose call the chips composable again.
    @Volatile private var tickState: Any? = null
    @Volatile private var tickGet: java.lang.reflect.Method? = null
    @Volatile private var tickSet: java.lang.reflect.Method? = null
    private var tickN = 0
    private var lastHide: Boolean? = null

    private fun initTick(cl: ClassLoader) {
        try {
            val k = Class.forName("androidx.compose.runtime.SnapshotIntStateKt", false, cl)
            val st = k.getMethod("mutableIntStateOf", Int::class.javaPrimitiveType).invoke(null, 0)
            tickGet = Class.forName("androidx.compose.runtime.IntState", false, cl).getMethod("getIntValue")
            tickSet = Class.forName("androidx.compose.runtime.MutableIntState", false, cl).getMethod("setIntValue", Int::class.javaPrimitiveType)
            tickState = st
            XposedBridge.log("AOSPIsland: compose recompose-tick ready")
        } catch (t: Throwable) { XposedBridge.log("AOSPIsland: compose tick unavailable: $t") }
    }

    private fun bumpCompose() {
        try { val st = tickState ?: return; tickSet?.invoke(st, ++tickN) } catch (t: Throwable) { XposedBridge.log("AOSPIsland: compose bump failed: $t") }
    }

    /**
     * Android 16/17 draw the status-bar chips with Compose, so there is no chip View to hide. While the island owns the
     * activity, swap the chips model passed to the chips composable for an empty one (arguments only, so the Compose
     * slot-table protocol stays intact). Signatures are logged (tag "AOSPIsland: compose") to adjust if a ROM differs.
     */
    fun hookComposeChips(cl: ClassLoader) {
        try {
            val base = "com.android.systemui.statusbar.chips.ui."
            val modelCls = XposedHelpers.findClassIfExists(base + "model.MultipleOngoingActivityChipsModel", cl)
            val kt = XposedHelpers.findClassIfExists(base + "compose.OngoingActivityChipsKt", cl)
            val singleKt = XposedHelpers.findClassIfExists(base + "compose.OngoingActivityChipKt", cl)
            if (modelCls == null || (kt == null && singleKt == null)) {
                XposedBridge.log("AOSPIsland: compose chips classes not found (model=${modelCls != null}, listKt=${kt != null}, singleKt=${singleKt != null})")
                return
            }
            modelCls.declaredConstructors.forEach { c -> XposedBridge.log("AOSPIsland: compose model ctor: " + c.parameterTypes.joinToString { it.simpleName }) }
            kt.declaredMethods.forEach { m -> XposedBridge.log("AOSPIsland: compose fn: ${m.name}(" + m.parameterTypes.joinToString { it.simpleName } + ")") }
            val empty: Any? = try {
                // On this ROM the real model constructor takes exactly three Lists.
                // Prefer it explicitly; a Kotlin synthetic no-arg constructor may not
                // produce the empty model that the composable needs.
                val listCtor = modelCls.declaredConstructors.firstOrNull { c ->
                    c.parameterTypes.size == 3 && c.parameterTypes.all { p ->
                        java.util.List::class.java.isAssignableFrom(p) ||
                            java.util.Collection::class.java.isAssignableFrom(p)
                    }
                }
                if (listCtor != null) {
                    listCtor.isAccessible = true
                    listCtor.newInstance(emptyList<Any>(), emptyList<Any>(), emptyList<Any>())
                } else {
                    val noArg = modelCls.declaredConstructors.firstOrNull { it.parameterCount == 0 }
                    if (noArg != null) { noArg.isAccessible = true; noArg.newInstance() } else null
                }
            } catch (t: Throwable) { XposedBridge.log("AOSPIsland: compose empty model failed: $t"); null }
            XposedBridge.log("AOSPIsland: compose empty model: $empty")
            initTick(cl)
            val cb = object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try {
                        // reading the state subscribes the calling composable, so bumpCompose() re-runs this hook
                        try { tickState?.let { tickGet?.invoke(it) } } catch (ignored: Throwable) { }
                        val hide = shouldHideChips()
                        if (lastHide != hide) { lastHide = hide; XposedBridge.log("AOSPIsland: compose chips " + if (hide) "hidden" else "shown") }
                        if (!hide) return
                        val e = empty ?: return
                        val a = p.args ?: return
                        var replaced = false
                        for (i in a.indices) {
                            if (modelCls.isInstance(a[i])) {
                                a[i] = e
                                replaced = true
                                XposedBridge.log("AOSPIsland: replaced native ongoing-chip model at arg=$i")
                                break
                            }
                        }
                        if (!replaced) XposedBridge.log("AOSPIsland: hide requested but composable args contained no chip model")
                    } catch (ignored: Throwable) { }
                }
            }
            var hooked = 0
            // Hook the list composable to replace the aggregate model, and the single-chip
            // composable separately. On this ROM the red recording pill can be rendered as
            // an individual OngoingActivityChip, so replacing only the aggregate model is
            // insufficient. When recording is active, skip the single-chip composable.
            kt?.declaredMethods
                ?.filter { !it.isSynthetic && !it.name.contains("$") }
                ?.map { it.name }?.distinct()?.forEach { name ->
                    try {
                        XposedBridge.hookAllMethods(kt, name, cb)
                        hooked++
                    } catch (t: Throwable) {
                        XposedBridge.log("AOSPIsland: compose list hook failed for $name: $t")
                    }
                }
            if (singleKt != null) {
                val singleChipHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        try {
                            val n = runtime?.notifications
                            // Same early suppression rule as shouldHideChips(): countdown can
                            // render before a screen-recording notification exists.
                            val hideSingleChip = hideRecChip || (hideCallChip && n?.hasCall == true)
                            if (!hideSingleChip) return
                            val method = p.method as? java.lang.reflect.Method
                            if (method != null && method.returnType == java.lang.Void.TYPE) {
                                p.setResult(null)
                                XposedBridge.log("AOSPIsland: suppressed individual native chip via ${method.name}")
                            } else {
                                // Compose functions normally return Unit/void. Don't force an
                                // incompatible result on unexpected overloads.
                                XposedBridge.log("AOSPIsland: single-chip overload not void or not a Method: ${p.method}")
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("AOSPIsland: single-chip suppression failed: $t")
                        }
                    }
                }
                singleKt.declaredMethods
                    .filter { !it.isSynthetic && !it.name.contains("$") && it.returnType == java.lang.Void.TYPE }
                    .map { it.name }.distinct().forEach { name ->
                        try {
                            XposedBridge.hookAllMethods(singleKt, name, singleChipHook)
                            hooked++
                        } catch (t: Throwable) {
                            XposedBridge.log("AOSPIsland: single-chip hook failed for $name: $t")
                        }
                    }
            }
            XposedBridge.log("AOSPIsland: Compose chip hooks installed=$hooked (list=${kt != null}, single=${singleKt != null})")
        } catch (t: Throwable) { XposedBridge.log("AOSPIsland: compose chips hook failed: $t") }
    }
}
