package bg.evolution.callrecorder.data.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import bg.evolution.callrecorder.core.CallDirection
import bg.evolution.callrecorder.core.Logx

/** Resolves phone numbers to contact names and reads the system call log. */
class ContactLookup(private val context: Context) {

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /** Contact display name for [number], or null if unknown / no permission. */
    fun nameFor(number: String?): String? {
        if (number.isNullOrBlank() || !granted(Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            Logx.w("Contact lookup failed", e)
            null
        }
    }

    fun isContact(number: String?): Boolean = nameFor(number) != null

    data class CallLogEntry(val number: String?, val name: String?, val direction: CallDirection, val date: Long)

    /** Latest call-log entry that started after [sinceMillis] (with a little tolerance). */
    fun latestCallSince(sinceMillis: Long): CallLogEntry? {
        if (!granted(Manifest.permission.READ_CALL_LOG)) return null
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf((sinceMillis - 3 * 60_000).toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                if (!c.moveToFirst()) return null
                val type = c.getInt(2)
                CallLogEntry(
                    number = c.getString(0)?.takeIf { it.isNotBlank() },
                    name = c.getString(1)?.takeIf { it.isNotBlank() },
                    direction = when (type) {
                        CallLog.Calls.OUTGOING_TYPE -> CallDirection.OUTGOING
                        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> CallDirection.INCOMING
                        else -> CallDirection.UNKNOWN
                    },
                    date = c.getLong(3),
                )
            }
        } catch (e: Exception) {
            Logx.w("Call log query failed", e)
            null
        }
    }
}
