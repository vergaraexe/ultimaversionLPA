package cl.lpa.agenda

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Envio diario: el Word de UN dia por correo (sin semanas). */
object DailyReport {
    /**
     * Genera el Word del dia (con todo lo escrito hasta ese momento) y lo envia por correo.
     * Si ese dia no tiene nada escrito no se envia nada. Devuelve true si se envio un correo.
     */
    fun sendDay(ctx: Context, date: LocalDate): Boolean {
        val cfg = MailSettings.load(ctx)
        if (!MailSettings.isConfigured(cfg)) throw IllegalStateException("Faltan los datos del correo (Ajustes).")
        val cfgClean = cfg.copy(password = cfg.password.replace(" ", ""))

        // por si quedo alguna actualizacion pendiente de algun dia
        runCatching { DayStore(ctx).takeDirty().forEach { DocxExporter.sync(ctx, it) } }

        var file: File = DocxExporter.export(ctx, date) ?: return false   // null = dia sin nada escrito
        // Gmail acepta ~25 MB por correo (el adjunto crece ~33%): si pesa mucho, va una version liviana.
        // El Word original completo sigue en Documentos/Agenda.
        if (file.length() > 10L * 1024 * 1024) {
            file = runCatching { DocxExporter.export(ctx, date, force = true, backup = false, lite = true) }.getOrNull() ?: file
        }

        val f = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val dia = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale("es", "CL"))
            .replaceFirstChar { it.uppercase() }
        MailSender.send(
            cfgClean,
            "Agenda LPA - $dia ${date.format(f)}",
            "Se adjunta el Word de la agenda del $dia ${date.format(f)}.\n\nEnviado automáticamente por Agenda LPA.",
            listOf(file)
        )
        return true
    }
}
