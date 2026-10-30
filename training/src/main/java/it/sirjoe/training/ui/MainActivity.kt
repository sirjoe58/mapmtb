package it.sirjoe.training.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import it.sirjoe.training.data.Prefs
import it.sirjoe.training.data.Storage
import it.sirjoe.training.sensors.BleSensors
import it.sirjoe.training.sensors.Sensors
import it.sirjoe.training.service.RideService
import it.sirjoe.training.service.RideState
import it.sirjoe.training.service.Snapshot

/** Unica finestra dell'app: quattro pagine (In bici, Allenamento, Uscite, Impostazioni). */
class MainActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private val tabs = ArrayList<TextView>()
    lateinit var ride: RidePage
    lateinit var plan: PlanPage
    lateinit var sessions: SessionsPage
    lateinit var settings: SettingsPage
    private lateinit var pages: List<Page>
    private var current = 0

    private var afterPermissions: (() -> Unit)? = null
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val next = afterPermissions
        afterPermissions = null
        next?.invoke()
    }

    private var onQr: ((String?) -> Unit)? = null
    private val qrLauncher = registerForActivityResult(ScanContract()) { r ->
        val cb = onQr
        onQr = null
        cb?.invoke(r.contents)
    }

    private val listener: (Snapshot) -> Unit = { s -> onSnapshot(s) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        ride = RidePage(this)
        plan = PlanPage(this)
        sessions = SessionsPage(this)
        settings = SettingsPage(this)
        pages = listOf(ride, plan, sessions, settings)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        container = FrameLayout(this)
        root.addView(container, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF08100C.toInt())
        }
        listOf("🚴\nIn bici", "📋\nAllenamento", "📂\nUscite", "⚙\nImpostazioni").forEachIndexed { i, label ->
            val t = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 12f
                setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 8))
                setOnClickListener { show(i) }
            }
            tabs += t
            bar.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        show(if (RideState.now.running) 0 else Prefs.int(this, "last_page", 0))
        if (!Prefs.bool(this, "accepted", false)) showDisclaimer(firstRun = true)
        else askBasicPermissions()
    }

    override fun onResume() {
        super.onResume()
        RideState.listen(listener)
        pages[current].onShow()
    }

    override fun onPause() {
        RideState.unlisten(listener)
        super.onPause()
    }

    override fun onStop() {
        // app in secondo piano senza uscita in corso: scollega i sensori (batteria)
        if (!RideState.now.running) Sensors.stop()
        super.onStop()
    }

    fun show(i: Int) {
        current = i
        Prefs.putInt(this, "last_page", i)
        container.removeAllViews()
        container.addView(pages[i].view)
        tabs.forEachIndexed { j, t ->
            t.setTextColor(if (j == i) Ui.ORANGE else Ui.MUTED)
            t.setBackgroundColor(if (j == i) 0xFF15241B.toInt() else 0)
        }
        pages[i].onShow()
        pages[i].onSnapshot(RideState.now)
    }

    private fun onSnapshot(s: Snapshot) {
        // schermo sempre acceso durante l'uscita (telefono sul manubrio)
        if (s.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pages[current].onSnapshot(s)
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------ permessi

    private fun missing(perms: List<String>) = perms.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    fun withPermissions(perms: List<String>, then: () -> Unit) {
        val m = missing(perms)
        if (m.isEmpty()) {
            then(); return
        }
        afterPermissions = then
        permLauncher.launch(m.toTypedArray())
    }

    fun locationPerms() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun allPerms(): List<String> {
        val l = ArrayList(locationPerms())
        l += BleSensors.permissions()
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
        return l.distinct()
    }

    private fun askBasicPermissions() {
        if (missing(allPerms()).isNotEmpty()) withPermissions(allPerms()) {}
    }

    fun hasLocation() = missing(listOf(Manifest.permission.ACCESS_FINE_LOCATION)).isEmpty()

    // ------------------------------------------------------------ avvio uscita

    /** Chiede quale programma seguire (o uscita libera) e avvia la registrazione. */
    fun startRide(preselected: String? = Prefs.str(this, "selected_workout")) {
        if (RideState.now.running) {
            show(0); return
        }
        val list = Storage.allWorkouts(this)
        val labels = arrayOf("Uscita libera (senza programma)") + list.map { w ->
            (if (w.fromPc) "💻 " else "") + w.name + "  ·  " + (w.totalSeconds / 60) + " min"
        }
        var choice = list.indexOfFirst { it.id == preselected } + 1
        AlertDialog.Builder(this)
            .setTitle("Cosa fai oggi?")
            .setSingleChoiceItems(labels, choice) { _, which -> choice = which }
            .setPositiveButton("Parti") { _, _ ->
                val id = if (choice <= 0) null else list[choice - 1].id
                if (id != null) Prefs.putStr(this, "selected_workout", id)
                withPermissions(allPerms()) {
                    if (!hasLocation()) {
                        toast("Serve il permesso di posizione per registrare l'uscita")
                        return@withPermissions
                    }
                    if (!Prefs.bool(this, "maxhr_hint", false) && Prefs.maxHr(this) == 0) {
                        Prefs.putBool(this, "maxhr_hint", true)
                        toast("Zone calcolate su una FC max stimata: impostala in Impostazioni")
                    }
                    RideService.start(this, id)
                    show(0)
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle("Fine uscita?")
            .setMessage("Vuoi salvare l'uscita? Potrai inviarla al PC dalla pagina Uscite.")
            .setPositiveButton("Salva") { _, _ ->
                RideService.stop(this)
                container.postDelayed({
                    RideService.lastSaved?.let { toast("Salvata: $it") } ?: toast("Uscita troppo breve: non salvata")
                }, 800)
            }
            .setNeutralButton("Continua", null)
            .setNegativeButton("Scarta") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("Eliminare l'uscita senza salvarla?")
                    .setPositiveButton("Elimina") { _, _ -> RideService.discard(this) }
                    .setNegativeButton("No", null)
                    .show()
            }
            .show()
    }

    // ------------------------------------------------------------ QR del PC

    fun scanQr(cb: (String?) -> Unit) {
        withPermissions(listOf(Manifest.permission.CAMERA)) {
            onQr = cb
            qrLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Inquadra il QR di SirJoe Link sul PC")
                    .setBeepEnabled(false)
                    .setOrientationLocked(true)
            )
        }
    }

    // ------------------------------------------------------------ avvertenze

    fun showDisclaimer(firstRun: Boolean = false) {
        val text = try {
            assets.open("avvertenze.txt").bufferedReader().readText()
        } catch (e: Exception) {
            ""
        }
        val tv = Ui.text(this, text, 15f).apply {
            setPadding(Ui.dp(context, 20), Ui.dp(context, 12), Ui.dp(context, 20), Ui.dp(context, 12))
        }
        val sv = ScrollView(this).apply { addView(tv) }
        val b = AlertDialog.Builder(this)
            .setTitle("Avvertenze d'uso")
            .setView(sv)
            .setCancelable(!firstRun)
        if (firstRun) {
            b.setPositiveButton("Ho letto e accetto") { _, _ ->
                Prefs.putBool(this, "accepted", true)
                askBasicPermissions()
            }
            b.setNegativeButton("Esci") { _, _ -> finish() }
        } else {
            b.setPositiveButton("Chiudi", null)
        }
        b.show()
    }
}

/** Una pagina dell'app. */
abstract class Page(val act: MainActivity) {
    abstract val view: View
    open fun onShow() {}
    open fun onSnapshot(s: Snapshot) {}
}
