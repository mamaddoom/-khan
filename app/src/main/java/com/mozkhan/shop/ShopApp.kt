package com.mozkhan.shop

import android.app.Application
import android.content.Context
import com.mozkhan.shop.data.AppDb
import com.mozkhan.shop.data.Repo
import com.mozkhan.shop.sync.CloudSync
import com.mozkhan.shop.util.CrashLog
import com.mozkhan.shop.util.Feedback
import com.mozkhan.shop.util.Prefs
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class ShopApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> CrashLog.record(this, e, "BACKGROUND") })
    lateinit var prefs: Prefs
    lateinit var repo: Repo
    lateinit var sync: CloudSync
    lateinit var feedback: Feedback
    lateinit var updater: com.mozkhan.shop.util.Updater

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        prefs = Prefs(this)
        preMigrationCopy()
        val db = AppDb.create(this)
        sync = CloudSync(this, db, appScope, prefs)
        repo = Repo(db, prefs, sync)
        feedback = Feedback(this, prefs)
        updater = com.mozkhan.shop.util.Updater(this, appScope)
        updater.check(silent = true)
        com.mozkhan.shop.ml.ImageFingerprint.level = prefs.visionLevel.value
        com.mozkhan.shop.ml.ImageFingerprint.init(this) // loads the embedding model on a background thread
        // chosen DNS first, so Firebase resolves its servers through it (needs the one-time VPN permission from Settings)
        com.mozkhan.shop.net.InAppDns.install { prefs.dnsServers() } // no VPN: Firestore asks the chosen DNS itself
        if (prefs.dnsVpn.value && prefs.dnsServers().isNotEmpty()) com.mozkhan.shop.net.DnsControl.start(this)
        // Runtime Firebase configuration (Settings -> Google) when there is no google-services.json
        if (prefs.fbApiKey.isNotBlank()) sync.configureFromPrefs()
        if (prefs.cloudSync.value) runCatching { sync.start() }.onFailure { CrashLog.record(this, it, "SYNC") }
        appScope.launch {
            // forces the migration now (not at the first screen) and repairs codes of older data
            db.openHelper.writableDatabase
            prefs.dbSeenVersion = AppDb.VERSION
            repo.ensureCodes()
        }
    }

    /**
     * Before the first start on a newer database version the current database files are copied once to
     * files/premigrate/v<old>/ so a failed upgrade can never cost the shop its data.
     */
    private fun preMigrationCopy() {
        runCatching {
            val db = getDatabasePath("shop.db")
            if (!db.exists() || prefs.dbSeenVersion >= AppDb.VERSION) return
            val dir = File(filesDir, "premigrate/v${prefs.dbSeenVersion.coerceAtLeast(1)}").apply { mkdirs() }
            for (suffix in listOf("", "-wal", "-shm")) {
                val src = File(db.path + suffix)
                if (src.exists()) src.copyTo(File(dir, src.name), overwrite = true)
            }
        }.onFailure { CrashLog.record(this, it, "PRE-MIGRATION COPY") }
    }
}
