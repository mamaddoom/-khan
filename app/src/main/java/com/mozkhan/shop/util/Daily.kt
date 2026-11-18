package com.mozkhan.shop.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mozkhan.shop.ShopApp
import com.mozkhan.shop.data.Cheque
import com.mozkhan.shop.data.ProductWithStock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * What runs once a day without the user: cheque reminders, low-stock alert and the automatic backup.
 * Each kind runs at most once per day (remembered in Prefs), so starting the app or a second worker run never repeats it.
 */
object Daily {
    private const val WORK = "mozkhan-daily"
    private const val DAY = 86_400_000L

    /** Every few hours: the work itself decides whether today's reminders / backup are still due. */
    fun schedule(ctx: Context) {
        runCatching {
            val req = PeriodicWorkRequestBuilder<DailyWorker>(4, TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    suspend fun run(app: ShopApp) {
        runCatching { remind(app) }.onFailure { CrashLog.record(app, it, "REMINDERS") }
        runCatching { if (app.prefs.autoBackup.value && app.prefs.lastBackup.value < Jalali.startOfDay(System.currentTimeMillis())) backup(app) }
            .onFailure { CrashLog.record(app, it, "AUTO-BACKUP") }
    }

    // ---------------------------------------------------------------- reminders
    /** pending cheques due within [days] days (or already overdue), soonest first */
    fun dueCheques(list: List<Cheque>, days: Int, now: Long = System.currentTimeMillis()): List<Pair<Cheque, Int>> =
        list.filter { !it.deleted && it.status == "PENDING" }
            .map { it to Math.round((Jalali.startOfDay(it.dueDate) - Jalali.startOfDay(now)) / DAY.toDouble()).toInt() }
            .filter { it.second <= days }
            .sortedBy { it.second }

    fun whenText(days: Int): String = when {
        days < 0 -> Fmt.digits("${-days} روز گذشته")
        days == 0 -> "امروز"
        days == 1 -> "فردا"
        else -> Fmt.digits("$days روز دیگر")
    }

    fun lowList(products: List<ProductWithStock>): List<ProductWithStock> =
        products.filter { it.trackStock && it.kind != com.mozkhan.shop.data.KIND_INGREDIENT && (it.low || (it.stock <= 0 && it.minStock > 0)) }
            .sortedBy { it.stock }

    private suspend fun remind(app: ShopApp) {
        val p = app.prefs
        val today = Jalali.startOfDay(System.currentTimeMillis())
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour < 8 || hour >= 22) return // never at night
        val cur = p.currency.value
        if (p.remindCheques.value && p.remindedOn("cheques") < today) {
            val due = dueCheques(app.repo.cheques.first(), p.chequeDays.value)
            if (due.isNotEmpty()) {
                val sum = due.sumOf { it.first.amount }
                Notifier.show(
                    app, Notifier.ID_CHEQUES,
                    Fmt.digits("${due.size} چک نزدیک سررسید"),
                    "جمع " + Fmt.money(sum, cur),
                    due.map { (c, d) -> "چک " + Fmt.digits(c.number) + " • " + Fmt.money(c.amount, cur) + " • " + whenText(d) + (if (c.payee.isNotBlank()) " • " + c.payee else "") },
                    route = "cheques",
                )
            }
            p.setRemindedOn("cheques", today)
        }
        if (p.lowAlert.value && p.remindedOn("low") < today) {
            val low = lowList(app.repo.products.first())
            if (low.isNotEmpty()) lowNotice(app, low)
            p.setRemindedOn("low", today)
        }
    }

    fun lowNotice(ctx: Context, low: List<ProductWithStock>) {
        Notifier.show(
            ctx, Notifier.ID_LOW,
            Fmt.digits("${low.size} کالا رو به اتمام است"),
            low.take(4).joinToString("، ") { it.name },
            low.map { it.name + " • موجودی " + Fmt.qtyOf(it.stock, it.unit) },
            route = "buy",
        )
    }

    // ---------------------------------------------------------------- automatic backup
    private const val FOLDER = "MozKhan"
    private const val KEEP = 7

    /**
     * Writes today's backup file. Android 10+: Download/MozKhan (visible in the Files app, survives uninstalling the app);
     * older phones: the app's own folder on the storage (Android/data/com.mozkhan.shop/files/backups).
     * Only the newest [KEEP] automatic backups are kept. Returns where it was written.
     */
    suspend fun backup(app: ShopApp): String = withContext(Dispatchers.IO) {
        val json = app.repo.exportJson()
        val name = "mozkhan-auto-" + Jalali.date(System.currentTimeMillis()).replace('/', '-') + ".json"
        val where = if (Build.VERSION.SDK_INT >= 29) {
            val r = app.contentResolver
            val col = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val rel = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/"
            // the same day again (button pressed twice): replace that file
            r.query(col, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(rel, name), null)
                ?.use { c -> while (c.moveToNext()) runCatching { r.delete(android.content.ContentUris.withAppendedId(col, c.getLong(0)), null, null) } }
            val uri = r.insert(col, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            }) ?: error("ساخت فایل پشتیبان ممکن نشد")
            r.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: error("نوشتن فایل پشتیبان ممکن نشد")
            // keep only the newest automatic backups
            r.query(
                col, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'mozkhan-auto-%'",
                arrayOf(rel), "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                var i = 0
                while (c.moveToNext()) { if (i++ >= KEEP) runCatching { r.delete(android.content.ContentUris.withAppendedId(col, c.getLong(0)), null, null) } }
            }
            "Download/$FOLDER/$name"
        } else {
            val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "backups").apply { mkdirs() }
            File(dir, name).writeText(json)
            dir.listFiles { f -> f.name.startsWith("mozkhan-auto-") }?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
            File(dir, name).absolutePath
        }
        app.prefs.setLastBackup(System.currentTimeMillis())
        app.prefs.lastBackupWhere = where
        where
    }
}

class DailyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? ShopApp ?: return Result.success()
        Daily.run(app)
        return Result.success()
    }
}
