package edu.fudan.elearning.sync.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import edu.fudan.elearning.sync.MainActivity
import edu.fudan.elearning.sync.R

/** 通知工具：创建渠道并发送同步通知。 */
object Notifier {
    const val CHANNEL_ID = "fudan_sync_channel"
    private const val NOTIFY_ID = 1001

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_sync),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_sync_desc)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    /**
     * 同步完成通知（下载新文件时提示）。
     *
     * v1.1.0 起：有变更摘要时按课程列出文件名（「机器学习：lec5.pdf、hw2.pdf」），
     * 取代以前粒度只有「新增 3 个文件」的通知——用户不知道是什么文件。
     */
    fun notifySyncComplete(
        context: Context,
        downloaded: Int,
        bytes: Long,
        changes: List<edu.fudan.elearning.sync.data.FileChange> = emptyList()
    ) {
        if (downloaded <= 0) return
        val text = if (changes.isEmpty()) {
            "新增 $downloaded 个文件，共 ${formatBytes(bytes)}"
        } else {
            buildDigest(changes, bytes)
        }
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("课程同步完成")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, notification)
        } catch (_: SecurityException) {
            // Android 13+ 未授权 POST_NOTIFICATIONS 时静默忽略
        }
    }

    /** 同步失败通知。 */
    fun notifySyncError(context: Context, message: String) {
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("同步失败")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID + 1, notification)
        } catch (_: SecurityException) {
        }
    }

    /** 把本轮变更整理成「课程：文件名…」的摘要（最多 3 门课、每门最多 3 个文件名）。 */
    private fun buildDigest(
        changes: List<edu.fudan.elearning.sync.data.FileChange>,
        bytes: Long
    ): String {
        val grouped = LinkedHashMap<String, MutableList<String>>()
        for (item in changes) {
            val course = item.courseName.ifEmpty { "课程 ${item.courseId}" }
            grouped.getOrPut(course) { mutableListOf() }.add(item.filename.ifEmpty { item.fileId.toString() })
        }
        val lines = grouped.entries.take(3).map { (course, names) ->
            val shown = names.take(3).joinToString("、")
            val suffix = if (names.size > 3) " 等 ${names.size} 个" else ""
            "$course：$shown$suffix"
        }.toMutableList()
        if (grouped.size > 3) lines += "其余 ${grouped.size - 3} 门课程…"
        lines += "共 ${formatBytes(bytes)}"
        return lines.joinToString("\n")
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }
}
