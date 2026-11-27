package cl.lpa.agenda

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Envio diario: a las 00:00 se genera y se envia el Word del dia que termino.
 *  - "Tarea de medianoche": una tarea programada para la proxima 00:00; al correr deja programada la siguiente.
 *  - "Red de seguridad": una revision diaria (~08:00) que envia los dias que quedaron pendientes
 *    (tablet apagada o sin internet). Nunca se envia dos veces el mismo dia. Los dias sin nada escrito no se envian.
 * Android puede atrasar unos minutos las tareas programadas para ahorrar bateria.
 */
class WeeklyMailWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        // la tarea de medianoche siempre deja programada la siguiente, pase lo que pase
        if (inputData.getBoolean("chain", false)) WeeklyScheduler.schedule(ctx)

        if (!MailSettings.isConfigured(MailSettings.load(ctx))) return Result.success()

        val target = LocalDate.now().minusDays(1)          // el dia que ya termino
        val last = runCatching { LocalDate.parse(MailSettings.lastSent(ctx)) }.getOrNull()
        if (last == null) {
            // instalacion nueva: no se envian dias antiguos; se parte con el dia de hoy (a las 00:00 de manana)
            MailSettings.setLastSent(ctx, target.toString())
            return Result.success()
        }
        if (!last.isBefore(target)) return Result.success()

        // dias pendientes (maximo los ultimos 7), del mas antiguo al mas reciente
        var d = maxOf(last.plusDays(1), target.minusDays(6))
        return try {
            while (!d.isAfter(target)) {
                DailyReport.sendDay(ctx, d)
                MailSettings.setLastSent(ctx, d.toString())
                d = d.plusDays(1)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

object WeeklyScheduler {
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val now = LocalDateTime.now()

        // 1) tarea de la proxima medianoche (00:00)
        val next = now.toLocalDate().plusDays(1).atStartOfDay()
        val midnight = OneTimeWorkRequestBuilder<WeeklyMailWorker>()
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("chain" to true))
            .setConstraints(online)
            .build()
        wm.enqueueUniqueWork("daily_mail_${next.toLocalDate()}", ExistingWorkPolicy.KEEP, midnight)

        // 2) red de seguridad diaria (~08:00)
        var morning = now.toLocalDate().atTime(8, 0)
        if (!morning.isAfter(now)) morning = morning.plusDays(1)
        val catchUp = PeriodicWorkRequestBuilder<WeeklyMailWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, morning).toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(online)
            .build()
        wm.enqueueUniquePeriodicWork("weekly_mail_catchup", ExistingPeriodicWorkPolicy.KEEP, catchUp)

        wm.cancelUniqueWork("weekly_mail")
    }
}
