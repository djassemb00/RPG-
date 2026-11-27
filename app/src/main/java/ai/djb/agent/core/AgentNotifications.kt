package ai.djb.agent.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import ai.djb.agent.R

/** Small helper so long-running tasks leave a visible trace in the shade. */
object AgentNotifications {

    const val CHANNEL_ID = "agent_progress"
    private const val NOTIFICATION_ID = 4242

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "تقدم الوكيل",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "حالة المهام التي ينفّذها المساعد" }
            )
        }
    }

    fun progress(context: Context, text: String): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("djb ai يعمل")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun show(context: Context, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, progress(context, text))
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
