package ru.begunok.analytics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.sink
import ru.begunok.BegunokApp
import java.io.FileNotFoundException

/** Streams a read-only snapshot to the system ADB shell, without creating a shared file. */
class AnalyticsUsbProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (Binder.getCallingUid() != Process.SHELL_UID) throw SecurityException("ADB shell required")
        if (mode != "r" || uri.encodedPath != "/snapshot") throw FileNotFoundException()
        val app = checkNotNull(context).applicationContext as BegunokApp
        val source = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
            ?: throw FileNotFoundException("Analytics source unavailable")
        return openPipeHelper(uri, MIME, null, source) { pipe, _, _, _, id ->
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe).sink().buffer().use { sink ->
                    runBlocking { app.container.analyticsExport.write(sink, checkNotNull(id)) }
                }
            } catch (error: Exception) {
                Log.w("MyLifeAnalytics", "USB snapshot stopped: ${error.javaClass.simpleName}")
            }
        }
    }

    override fun getType(uri: Uri): String? = MIME.takeIf { uri.encodedPath == "/snapshot" }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    private companion object {
        const val MIME = "application/x-ndjson"
    }
}
