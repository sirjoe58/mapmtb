package it.sirjoe.mtbcomputer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import it.sirjoe.mtbcomputer.data.Prefs
import it.sirjoe.mtbcomputer.data.RideRepository
import it.sirjoe.mtbcomputer.data.RideStats
import it.sirjoe.mtbcomputer.share.LinkClient
import it.sirjoe.mtbcomputer.share.ShareHelper
import it.sirjoe.mtbcomputer.share.Strava
import it.sirjoe.mtbcomputer.ui.DataFragment
import it.sirjoe.mtbcomputer.ui.InfoFragment
import it.sirjoe.mtbcomputer.ui.LinkUi
import it.sirjoe.mtbcomputer.ui.MapFragment
import it.sirjoe.mtbcomputer.ui.PlannerFragment
import it.sirjoe.mtbcomputer.ui.TracksFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG_MAP = "map"
        private const val TAG_PLAN = "plan"
        private const val TAG_DATA = "data"
        private const val TAG_TRACKS = "tracks"
        private const val TAG_INFO = "info"
        private val ALL_TAGS = listOf(TAG_MAP, TAG_PLAN, TAG_DATA, TAG_TRACKS, TAG_INFO)

        fun readDisclaimer(ctx: Context): String =
            ctx.assets.open("avvertenze.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private lateinit var nav: BottomNavigationView
    private var pendingSave: ByteArray? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                (supportFragmentManager.findFragmentByTag(TAG_MAP) as? MapFragment)?.startLocationUpdates()
            }
        }

    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val data = pendingSave
        pendingSave = null
        if (uri != null && data != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(data) }
                Toast.makeText(this, "File salvato", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Errore nel salvataggio: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        nav = findViewById(R.id.bottomNav)

        if (savedInstanceState == null) {
            val t = supportFragmentManager.beginTransaction()
                .add(R.id.container, MapFragment(), TAG_MAP)
                .add(R.id.container, PlannerFragment(), TAG_PLAN)
                .add(R.id.container, DataFragment(), TAG_DATA)
                .add(R.id.container, TracksFragment(), TAG_TRACKS)
                .add(R.id.container, InfoFragment(), TAG_INFO)
            t.commitNow()
            show(TAG_MAP)
        }

        nav.setOnItemSelectedListener { item ->
            show(tagFor(item.itemId))
            true
        }

        if (Prefs.disclaimerAccepted(this)) askPermissions() else showDisclaimer()

        // Schermo acceso durante la registrazione (se attivato nelle impostazioni)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RideRepository.stats.collect { s -> applyScreenMode(s) }
            }
        }

        handleIntent(intent)
    }

    /**
     * Schermo durante l'uscita:
     * - in movimento resta acceso (se attivato nelle impostazioni);
     * - in sosta si abbassa la luminosità e lo schermo può spegnersi;
     * - alla ripartenza l'app si riaccende sopra la schermata di blocco.
     */
    private var lastScreenMode: String? = null

    private fun applyScreenMode(s: RideStats) {
        val mode = when {
            !s.recording -> "off"
            s.sleeping -> "sleep"
            else -> "ride"
        }
        if (mode == lastScreenMode) return
        lastScreenMode = mode
        val overLock = s.recording && Prefs.autoSleep(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(overLock)
            setTurnScreenOn(overLock)
        } else {
            @Suppress("DEPRECATION")
            val f = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (overLock) window.addFlags(f) else window.clearFlags(f)
        }
        if (mode == "ride" && Prefs.keepScreenOn(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val lp = window.attributes
        lp.screenBrightness = if (mode == "sleep") 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // ------------------------------------------------- intent in arrivo

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val data = intent.data
        when {
            // QR di SirJoe Link letto con la fotocamera del telefono
            LinkClient.isLink(data) -> openLink(data.toString())
            // ritorno dall'autorizzazione Strava
            Strava.isAuthRedirect(data) -> {
                lifecycleScope.launch {
                    val msg = try {
                        "Strava collegato: " + Strava.finishAuth(this@MainActivity, data!!)
                    } catch (e: Exception) {
                        "Collegamento Strava non riuscito: ${e.message}"
                    }
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    (supportFragmentManager.findFragmentByTag(TAG_INFO) as? InfoFragment)?.refreshStrava()
                }
            }
            // "Apri con" da file manager, email, WhatsApp...
            intent.action == Intent.ACTION_VIEW && data != null -> importFromOtherApp(listOf(data))
            intent.action == Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { importFromOtherApp(listOf(it)) }
            }
            intent.action == Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { importFromOtherApp(it) }
            }
        }
        setIntent(Intent(this, MainActivity::class.java))
    }

    // ------------------------------------------------------- SirJoe Link

    private val scanLauncher = registerForActivityResult(ScanContract()) { r ->
        r.contents?.let { openLink(it) }
    }

    fun scanLinkQr() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Inquadra il QR di SirJoe Link sullo schermo del PC")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
                .setCameraId(0)
                // legge anche i QR chiari su sfondo scuro (schermi in modalità scura)
                .addExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN)
        )
    }

    private fun openLink(text: String) {
        val pc = LinkClient.parse(text)
        if (pc == null) {
            Toast.makeText(this, "Questo QR non è di SirJoe Link (o il PC non è nella rete di casa)", Toast.LENGTH_LONG).show()
            return
        }
        selectTab(R.id.nav_tracks)
        LinkUi.connect(this, pc)
    }

    fun onTracksChanged() {
        (supportFragmentManager.findFragmentByTag(TAG_TRACKS) as? TracksFragment)?.refresh()
    }

    private fun importFromOtherApp(uris: List<Uri>) {
        lifecycleScope.launch {
            var count = 0
            var error: String? = null
            for (u in uris) {
                try {
                    count += withContext(Dispatchers.IO) { ShareHelper.importUri(applicationContext, u).size }
                } catch (e: Exception) {
                    error = e.message
                }
            }
            val w = ShareHelper.lastImportedWaypoints
            val msg = when {
                count > 0 -> "Importate $count tracce nella pagina Percorsi" + if (w > 0) " e $w segnaposto" else ""
                w > 0 -> "Importati $w segnaposto: li trovi nella pagina Crea"
                else -> "Import non riuscito: ${error ?: "file non riconosciuto"}"
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            if (count > 0) {
                selectTab(R.id.nav_tracks)
                (supportFragmentManager.findFragmentByTag(TAG_TRACKS) as? TracksFragment)?.refresh()
            }
        }
    }

    // ----------------------------------------------------------- pagine

    private fun tagFor(id: Int): String = when (id) {
        R.id.nav_plan -> TAG_PLAN
        R.id.nav_data -> TAG_DATA
        R.id.nav_tracks -> TAG_TRACKS
        R.id.nav_info -> TAG_INFO
        else -> TAG_MAP
    }

    private fun show(tag: String) {
        val fm = supportFragmentManager
        val t = fm.beginTransaction()
        for (f in ALL_TAGS) {
            val frag: Fragment = fm.findFragmentByTag(f) ?: continue
            if (f == tag) t.show(frag) else t.hide(frag)
        }
        t.commitNow()
    }

    fun selectTab(id: Int) {
        nav.selectedItemId = id
    }

    fun openPlannerOnRoute() {
        selectTab(R.id.nav_plan)
        (supportFragmentManager.findFragmentByTag(TAG_PLAN) as? PlannerFragment)?.zoomToRoute()
    }

    fun onBaseMapChanged() {
        (supportFragmentManager.findFragmentByTag(TAG_MAP) as? MapFragment)?.applyBaseMap()
        (supportFragmentManager.findFragmentByTag(TAG_PLAN) as? PlannerFragment)?.applyBaseMap()
    }

    /** Salva dei dati in un file scelto dall'utente (Download, Drive...). */
    fun saveToFile(fileName: String, data: ByteArray) {
        pendingSave = data
        saveLauncher.launch(fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
    }

    // ------------------------------------------------------ permessi

    fun askPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun showDisclaimer() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disclaimer_title)
            .setMessage(readDisclaimer(this))
            .setCancelable(false)
            .setPositiveButton(R.string.accept) { _, _ ->
                Prefs.setDisclaimerAccepted(this)
                askPermissions()
            }
            .setNegativeButton(R.string.exit) { _, _ -> finish() }
            .show()
    }
}
