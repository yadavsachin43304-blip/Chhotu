package com.sachin.chhotu

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/** Power ON: Chhotu background me zinda rehta hai aur har screen par floating chehra dikhata hai. */
class ChhotuService : Service(), Engine.Listener {

    private var wm: WindowManager? = null
    private var bubble: FaceView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ModelStore.cleanupOld(applicationContext)
        LocalBrain.warmUp(applicationContext)
        Engine.init(this)
        startAsForeground()
        showBubble()
        Engine.addListener(this)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TALK -> Engine.toggle()
            ACTION_STOP -> { Prefs.setPower(this, false); stopSelf() }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Chhotu", NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val talk = PendingIntent.getService(this, 1, Intent(this, ChhotuService::class.java).setAction(ACTION_TALK), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, ChhotuService::class.java).setAction(ACTION_STOP), flags)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_chhotu)
            .setContentTitle("छोटू चालू है")
            .setContentText("चेहरे पर टैप करो और बात करो")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "बात करो", talk)
            .addAction(0, "Power Off", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = (88 * resources.displayMetrics.density).toInt()
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - 16
            y = resources.displayMetrics.heightPixels / 3
        }
        val face = FaceView(this).apply { compact = true }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        var downTime = 0L
        face.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false; downTime = System.currentTimeMillis(); true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) { p.x = startX + dx.toInt(); p.y = startY + dy.toInt(); try { w.updateViewLayout(face, p) } catch (_: Exception) { } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (System.currentTimeMillis() - downTime > 600) {
                            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } else Engine.toggle()
                    }
                    true
                }
                else -> false
            }
        }
        try {
            w.addView(face, p)
            wm = w; bubble = face; params = p
        } catch (_: Exception) { }
    }

    override fun onState(state: Engine.State, status: String) {
        bubble?.mood = when (state) {
            Engine.State.IDLE -> FaceView.Mood.IDLE
            Engine.State.LISTENING -> FaceView.Mood.LISTENING
            Engine.State.THINKING -> FaceView.Mood.THINKING
            Engine.State.SPEAKING -> FaceView.Mood.SPEAKING
        }
    }

    override fun onLevel(level: Float) { bubble?.level = level }

    override fun onDestroy() {
        running = false
        Engine.removeListener(this)
        Engine.stopAll()
        try { bubble?.let { wm?.removeView(it) } } catch (_: Exception) { }
        bubble = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "chhotu_power"
        const val NOTIF_ID = 7
        const val ACTION_TALK = "com.sachin.chhotu.TALK"
        const val ACTION_STOP = "com.sachin.chhotu.STOP"
        @Volatile var running = false
            private set

        fun start(c: Context) {
            val i = Intent(c, ChhotuService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
        fun stop(c: Context) { c.stopService(Intent(c, ChhotuService::class.java)) }
    }
}
