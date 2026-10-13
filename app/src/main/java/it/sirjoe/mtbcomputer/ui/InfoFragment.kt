package it.sirjoe.mtbcomputer.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import it.sirjoe.mtbcomputer.BuildConfig
import it.sirjoe.mtbcomputer.MainActivity
import it.sirjoe.mtbcomputer.R
import it.sirjoe.mtbcomputer.data.Prefs

/** Pagina "Info": logo, impostazioni, avvertenze d'uso, licenze. */
class InfoFragment : Fragment(R.layout.fragment_info) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        view.findViewById<TextView>(R.id.version).text =
            getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME
        view.findViewById<TextView>(R.id.disclaimer).text = MainActivity.readDisclaimer(ctx)

        fun bind(id: Int, value: Boolean, save: (Boolean) -> Unit) {
            val sw = view.findViewById<SwitchCompat>(id)
            sw.isChecked = value
            sw.setOnCheckedChangeListener { _, checked -> save(checked) }
        }
        bind(R.id.swAutoPause, Prefs.autoPause(ctx)) { Prefs.setAutoPause(ctx, it) }
        bind(R.id.swHeading, Prefs.headingUp(ctx)) { Prefs.setHeadingUp(ctx, it) }
        bind(R.id.swScreen, Prefs.keepScreenOn(ctx)) { Prefs.setKeepScreenOn(ctx, it) }
        bind(R.id.swVoice, Prefs.voice(ctx)) { Prefs.setVoice(ctx, it) }
        bind(R.id.swMtb, Prefs.mtbOverlay(ctx)) {
            Prefs.setMtbOverlay(ctx, it)
            (activity as? MainActivity)?.onBaseMapChanged()
        }
    }
}
