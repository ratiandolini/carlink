package com.example.carlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * ფონური მონიტორინგი: აპი ჩაკეტილ ეკრანზეც ცოცხალია და ადაპტერს თავისით ეძებს.
 * მხოლოდ კითხულობს; ინტერნეტი არ სჭირდება.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val first = build("ვეძებ ადაპტერს…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, first)
        }
        val vm = (application as CarLinkApplication).vm
        vm.resumeWatching()
        scope.coroutineContext.cancelChildren()
        scope.launch {
            vm.state.collect { ui ->
                val snap = ui.snapshot
                val text = when {
                    ui.connection == Conn.Connected && snap != null && !snap.ecuAlive ->
                        "ანთება გამორთულია" + (snap.batteryV?.let { " · აკუმულატორი %.1f V".format(it) } ?: "")
                    ui.connection == Conn.Connected && snap != null ->
                        "დაკავშირებულია" +
                            (snap.batteryV?.let { " · %.1f V".format(it) } ?: "") +
                            (snap.fuelPercent?.let { " · ბენზინი %.0f%%".format(it) } ?: "")
                    ui.connection == Conn.Connecting -> "ვუკავშირდები ადაპტერს…"
                    else -> "ველოდები ადაპტერს (Bluetooth-ის რადიუსში)"
                }
                if (text != lastText) {
                    lastText = text
                    getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, build(text))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "CarLink მონიტორინგი", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("CarLink მონიტორინგი")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 100
        private const val CHANNEL = "carlink_watch"
    }
}
