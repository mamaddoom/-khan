package com.mozkhan.shop.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mozkhan.shop.MainActivity
import com.mozkhan.shop.R

/** Shop notifications. Tapping one opens the app on the screen named by its route ("cheques", "buy", …). */
object Notifier {
    const val EXTRA_ROUTE = "route"
    /** route a tapped notification asked for; AppRoot opens it and clears it */
    val pendingRoute = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private const val CH_REMIND = "reminders"

    const val ID_CHEQUES = 101
    const val ID_LOW = 102
    const val ID_BACKUP = 103

    fun allowed(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    private fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CH_REMIND) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_REMIND, "یادآورهای فروشگاه", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "سررسید چک، کمبود موجودی و پشتیبان‌گیری"
                },
            )
        }
    }

    fun show(ctx: Context, id: Int, title: String, text: String, lines: List<String> = emptyList(), route: String? = null) {
        if (!allowed(ctx)) return
        channel(ctx)
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (route != null) putExtra(EXTRA_ROUTE, route)
        }
        val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
        if (lines.isNotEmpty()) b.setStyle(NotificationCompat.InboxStyle().also { s -> lines.take(6).forEach { s.addLine(it) }; s.setSummaryText(text) })
        runCatching { NotificationManagerCompat.from(ctx).notify(id, b.build()) }
    }
}
