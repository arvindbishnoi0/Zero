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
 * Background export worker for long 2K/4K renders.
 *
 * It loads the saved project JSON, runs the exact same [VibeCutExportEngine] the editor uses and
 * reports a foreground notification while Media3 encodes. Wrapped in `runCatching` so an encoder
 * failure on one device can never crash the app: the worker simply reports `failure`.
 *
 * Note: this is currently an optional path (the editor exports inline so the user sees live
 * progress). It is registered so background rendering is available without a second code path.
 */
class ExportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(KEY_PROJECT_ID) ?: return Result.failure()
        runCatching { setForeground(createForegroundInfo("Rendering your video…")) }

        val db = Room.databaseBuilder(
            applicationContext,
            VibeCutDatabase::class.java,
            DATABASE_NAME
        ).fallbackToDestructiveMigration().build()

        val entity = try {
            db.vibeCutDao().getProjectById(projectId)
        } catch (t: Throwable) {
            null
        } finally {
            db.close()
        }
        if (entity == null) return Result.failure()

        val project = runCatching { ProjectJsonCodec.decodeProject(entity.projectJson) }.getOrNull()
            ?: return Result.failure()

        val engine = VibeCutExportEngine(applicationContext)
        return when (val outcome = engine.exportProject(project, project.exportConfig)) {
            is ExportProgressState.Completed -> Result.success(
                workDataOf(
                    KEY_OUTPUT_PATH to outcome.outputFilePath,
                    KEY_FILE_SIZE to outcome.fileSizeBytes
                )
            )
            is ExportProgressState.Cancelled -> Result.failure(
                workDataOf(KEY_ERROR to "Export cancelled")
            )
            is ExportProgressState.Error -> Result.failure(workDataOf(KEY_ERROR to outcome.message))
            else -> Result.failure(workDataOf(KEY_ERROR to "Export did not finish"))
        }
    }

    private fun createForegroundInfo(progressText: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Video export",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("ZERO export")
            .setContentText(progressText)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_FILE_SIZE = "file_size"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "vibecut_export_channel"
        private const val NOTIFICATION_ID = 1042
        private const val DATABASE_NAME = "vibecut_pro_studio.db"
    }
}
