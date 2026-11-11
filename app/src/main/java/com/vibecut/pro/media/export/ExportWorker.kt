package com.vibecut.pro.media.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vibecut.pro.data.local.VibeCutDatabase
import com.vibecut.pro.engine.serialization.ProjectJsonCodec

/**
 * WorkManager background worker allowing long 2K/4K renders to continue reliably in the background.
 */
class ExportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(KEY_PROJECT_ID) ?: return Result.failure()
        setForeground(createForegroundInfo("Rendering VibeCut Pro Video..."))

        val db = Room.databaseBuilder(
            applicationContext,
            VibeCutDatabase::class.java,
            "vibecut_pro_studio.db"
        ).build()

        val entity = db.vibeCutDao().getProjectById(projectId)
        db.close()
        if (entity == null) return Result.failure()

        val project = runCatching { ProjectJsonCodec.decodeProject(entity.projectJson) }.getOrNull()
            ?: return Result.failure()

        val engine = VibeCutExportEngine(applicationContext)
        return when (val outcome = engine.exportProject(project, project.exportConfig)) {
            is ExportProgressState.Completed -> Result.success(
                workDataOf(KEY_OUTPUT_PATH to outcome.outputFilePath)
            )
            else -> Result.failure()
        }
    }

    private fun createForegroundInfo(progressText: String): ForegroundInfo {
        val channelId = "vibecut_export_channel"
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "VibeCut Video Export",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("VibeCut Pro Export")
            .setContentText(progressText)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                1042,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            ForegroundInfo(1042, notification)
        }
    }

    companion object {
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_OUTPUT_PATH = "output_path"
    }
}
