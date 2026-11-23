package ru.begunok.analytics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.sink
import ru.begunok.BegunokApp
import java.io.FileNotFoundException

/** Exchanges analytics snapshots and calendar summaries with the system ADB shell. */
class AnalyticsUsbProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (Binder.getCallingUid() != Process.SHELL_UID) throw SecurityException("ADB shell required")
        val type = getType(uri) ?: throw FileNotFoundException()
        if (mode != "r") throw FileNotFoundException()
        val app = checkNotNull(context).applicationContext as BegunokApp
        val snapshotSource = if (uri.encodedPath == "/snapshot") {
            Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
                ?: throw FileNotFoundException("Analytics source unavailable")
        } else null
        return openPipeHelper(uri, type, null, snapshotSource) { pipe, _, _, _, id ->
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe).sink().buffer().use { sink ->
                    runBlocking {
                        if (uri.encodedPath == "/calendar") app.container.activityCalendar.export(sink)
                        else app.container.analyticsExport.write(sink, checkNotNull(id))
                    }
                }
            } catch (error: Exception) {
                Log.w("MyLifeAnalytics", "USB snapshot stopped: ${error.javaClass.simpleName}")
            }
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != Process.SHELL_UID) throw SecurityException("ADB shell required")
        require(method == "calendar-pc")
        val app = checkNotNull(context).applicationContext as BegunokApp
        val digest = runBlocking { app.container.activityCalendar.importPc(requireNotNull(arg)) }
        return Bundle().apply { putString("ack", digest) }
    }

    override fun getType(uri: Uri): String? = when (uri.encodedPath) {
        "/snapshot" -> MIME
        "/calendar" -> "application/json"
        else -> null
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    private companion object {
        const val MIME = "application/x-ndjson"
    }
}
