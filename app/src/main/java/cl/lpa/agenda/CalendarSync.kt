package cl.lpa.agenda

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pasa los eventos al Google Calendar de la tablet (cuenta Google ya iniciada en Android).
 * Android lo sincroniza con la nube y Google envia la invitacion a los correos de "Involucrados".
 */
object CalendarSync {
    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Le pide a Android sincronizar el calendario YA (sin abrir la app Google Calendar). */
    fun syncNow(ctx: Context, account: String? = null) {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        runCatching {
            val accounts: List<Account> = if (account != null) listOf(Account(account, "com.google"))
            else AccountManager.get(ctx).getAccountsByType("com.google").toList()
            accounts.forEach { ContentResolver.requestSync(it, CalendarContract.AUTHORITY, extras) }
        }
    }

    /** Calendarios de Google escribibles de la tablet: (id, cuenta, es_principal). */
    private fun googleCalendars(ctx: Context): List<Triple<Long, String, Boolean>> {
        val proj = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.OWNER_ACCOUNT
        )
        val out = ArrayList<Triple<Long, String, Boolean>>()
        val cur = ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, proj,
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?", arrayOf("500"), null
        ) ?: return out
        cur.use { c ->
            while (c.moveToNext()) {
                val acc = c.getString(1) ?: ""
                val owner = c.getString(3) ?: ""
                out.add(Triple(c.getLong(0), acc, c.getInt(2) == 1 || owner == acc))
            }
        }
        return out
    }

    /** Elige el calendario de la cuenta de Ajustes; si no esta, usa el principal de la tablet y lo avisa. */
    private fun findCalendar(ctx: Context, wanted: String): Triple<Long, String, Boolean>? {
        val all = googleCalendars(ctx).filter { it.second.contains("@") }
        if (all.isEmpty()) return null
        if (wanted.isNotBlank()) {
            all.firstOrNull { it.second.equals(wanted, true) && it.third }?.let { return it }
            all.firstOrNull { it.second.equals(wanted, true) }?.let { return it }
        }
        return all.firstOrNull { it.third } ?: all.first()
    }

    /** Crea o actualiza el evento. Devuelve (id del evento en el calendario del equipo, mensaje). */
    fun push(ctx: Context, e: CalEvent): Pair<Long, String> {
        if (!hasPermission(ctx)) return Pair(e.gid, "Falta el permiso de Calendario: no se agregó a Google Calendar.")
        // La cuenta preferida es la que se escribio en Ajustes (correo de envio)
        val wanted = runCatching { MailSettings.load(ctx).sender.trim() }.getOrDefault("")
        var cal = findCalendar(ctx, wanted)
        if (cal == null) {
            // todavia no se ha creado el calendario en la tablet: se pide sincronizar y se espera un momento
            syncNow(ctx, wanted.ifBlank { null })
            for (i in 1..8) {
                Thread.sleep(2000)
                cal = findCalendar(ctx, wanted)
                if (cal != null) break
            }
        }
        if (cal == null) return Pair(
                e.gid,
                "La tablet no tiene ningún calendario de Google disponible. Abre una vez la app Google Calendar con tu cuenta, " +
                    "espera que sincronice y vuelve a guardar. El evento quedó solo en la app."
            )
        val calId = cal.first
        val account = cal.second
        val usedOther = wanted.isNotBlank() && !account.equals(wanted, ignoreCase = true)
        val d = e.localDate()
        val t = e.localTime()
        val v = ContentValues()
        v.put(CalendarContract.Events.CALENDAR_ID, calId)
        v.put(CalendarContract.Events.TITLE, e.name)
        v.put(CalendarContract.Events.EVENT_LOCATION, e.place)
        v.put(CalendarContract.Events.ORGANIZER, account)
        v.put(CalendarContract.Events.HAS_ATTENDEE_DATA, 1)
        if (t == null) {
            val start = d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            v.put(CalendarContract.Events.ALL_DAY, 1)
            v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            v.put(CalendarContract.Events.DTSTART, start)
            v.put(CalendarContract.Events.DTEND, start + 86_400_000L)
        } else {
            val zone = ZoneId.systemDefault()
            val start = d.atTime(t).atZone(zone).toInstant().toEpochMilli()
            v.put(CalendarContract.Events.ALL_DAY, 0)
            v.put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            v.put(CalendarContract.Events.DTSTART, start)
            v.put(CalendarContract.Events.DTEND, start + 3_600_000L)
        }
        return try {
            val res = ctx.contentResolver
            var id = e.gid
            var updated = false
            if (id > 0) {
                updated = res.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), v, null, null) > 0
            }
            if (!updated) {
                val uri = res.insert(CalendarContract.Events.CONTENT_URI, v)
                    ?: return Pair(e.gid, "No se pudo crear el evento en Google Calendar.")
                id = ContentUris.parseId(uri)
            } else {
                res.delete(CalendarContract.Attendees.CONTENT_URI, "${CalendarContract.Attendees.EVENT_ID}=?", arrayOf(id.toString()))
            }
            if (e.people.isNotEmpty()) {
                val org = ContentValues().apply {
                    put(CalendarContract.Attendees.EVENT_ID, id)
                    put(CalendarContract.Attendees.ATTENDEE_EMAIL, account)
                    put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ORGANIZER)
                    put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
                    put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED)
                }
                res.insert(CalendarContract.Attendees.CONTENT_URI, org)
                for (p in e.people) {
                    if (p.equals(account, ignoreCase = true)) continue
                    val a = ContentValues().apply {
                        put(CalendarContract.Attendees.EVENT_ID, id)
                        put(CalendarContract.Attendees.ATTENDEE_EMAIL, p)
                        put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE)
                        put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
                        put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_INVITED)
                    }
                    res.insert(CalendarContract.Attendees.CONTENT_URI, a)
                }
            }
            syncNow(ctx, account)   // sube el evento a Google y envia las invitaciones sin abrir otra app
            val msg = (if (usedOther) "OJO: la cuenta $wanted no está en la tablet; se usó $account. " else "") +
                "Agregado a Google Calendar ($account)" +
                (if (e.people.isEmpty()) "." else "; Google enviará la invitación a ${e.people.size} persona(s) al sincronizar.")
            Pair(id, msg)
        } catch (ex: Exception) {
            Pair(e.gid, "No se pudo agregar a Google Calendar: ${ex.message ?: ex.javaClass.simpleName}")
        }
    }

    fun remove(ctx: Context, gid: Long) {
        if (gid <= 0 || !hasPermission(ctx)) return
        runCatching {
            ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, gid), null, null)
        }
    }

    /** Hace push en segundo plano, guarda el id y avisa con un mensaje en pantalla. */
    fun pushAsync(ctx: Context, store: CalendarStore, e: CalEvent, onGid: (Long) -> Unit = {}) {
        val app = ctx.applicationContext
        Thread {
            val (gid, msg) = push(app, e)
            if (gid != e.gid) store.upsert(e.copy(gid = gid))
            Handler(Looper.getMainLooper()).post {
                onGid(gid)
                android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
