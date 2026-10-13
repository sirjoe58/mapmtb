package it.sirjoe.mtbcomputer

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import it.sirjoe.mtbcomputer.data.Prefs
import it.sirjoe.mtbcomputer.data.RideRepository
import it.sirjoe.mtbcomputer.ui.DataFragment
import it.sirjoe.mtbcomputer.ui.InfoFragment
import it.sirjoe.mtbcomputer.ui.MapFragment
import it.sirjoe.mtbcomputer.ui.TracksFragment
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG_MAP = "map"
        private const val TAG_DATA = "data"
        private const val TAG_TRACKS = "tracks"
        private const val TAG_INFO = "info"

        fun readDisclaimer(ctx: Context): String =
            ctx.assets.open("avvertenze.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private lateinit var nav: BottomNavigationView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                (supportFragmentManager.findFragmentByTag(TAG_MAP) as? MapFragment)?.startLocationUpdates()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        nav = findViewById(R.id.bottomNav)

        if (savedInstanceState == null) {
            val map = MapFragment()
            val data = DataFragment()
            val tracks = TracksFragment()
            val info = InfoFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.container, map, TAG_MAP)
                .add(R.id.container, data, TAG_DATA)
                .add(R.id.container, tracks, TAG_TRACKS)
                .add(R.id.container, info, TAG_INFO)
                .hide(data)
                .hide(tracks)
                .hide(info)
                .commitNow()
        }

        nav.setOnItemSelectedListener { item ->
            show(tagFor(item.itemId))
            true
        }

        if (Prefs.disclaimerAccepted(this)) askPermissions() else showDisclaimer()

        // Schermo acceso durante la registrazione (se attivato nelle impostazioni)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RideRepository.stats.collect { s ->
                    if (s.recording && Prefs.keepScreenOn(this@MainActivity)) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
    }

    private fun tagFor(id: Int): String = when (id) {
        R.id.nav_data -> TAG_DATA
        R.id.nav_tracks -> TAG_TRACKS
        R.id.nav_info -> TAG_INFO
        else -> TAG_MAP
    }

    private fun show(tag: String) {
        val fm = supportFragmentManager
        val t = fm.beginTransaction()
        for (f in listOf(TAG_MAP, TAG_DATA, TAG_TRACKS, TAG_INFO)) {
            val frag: Fragment = fm.findFragmentByTag(f) ?: continue
            if (f == tag) t.show(frag) else t.hide(frag)
        }
        t.commit()
    }

    fun selectTab(id: Int) {
        nav.selectedItemId = id
    }

    fun onBaseMapChanged() {
        (supportFragmentManager.findFragmentByTag(TAG_MAP) as? MapFragment)?.applyBaseMap()
    }

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
