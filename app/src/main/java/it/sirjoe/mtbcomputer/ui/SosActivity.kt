package it.sirjoe.mtbcomputer.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import it.sirjoe.mtbcomputer.data.Prefs
import it.sirjoe.mtbcomputer.data.RideRepository
import it.sirjoe.mtbcomputer.data.SosLinkState
import it.sirjoe.mtbcomputer.data.SosProtocol
import it.sirjoe.mtbcomputer.service.SosBike
import it.sirjoe.mtbcomputer.service.SosPocketService
import kotlinx.coroutines.launch

/**
 * Schermata SOS a tutto schermo, anche sopra la schermata di blocco:
 * - sul manubrio: conto alla rovescia con il grande pulsante ANNULLA;
 * - in tasca: allarme, SMS inviati, pulsanti per Where ARE U e 112.
 */
class SosActivity : AppCompatActivity() {

    private lateinit var title: TextView
    private lateinit var big: TextView
    private lateinit var info: TextView
    private lateinit var cancelBtn: Button
    private lateinit var whereBtn: Button
    private lateinit var callBtn: Button
    private lateinit var mapBtn: Button

    private val pocket get() = Prefs.sosRole(this) == Prefs.SOS_ROLE_POCKET

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#B71C1C"))
            setPadding(px(20), px(40), px(20), px(20))
        }
        title = TextView(this).apply {
            textSize = 26f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        big = TextView(this).apply {
            textSize = 96f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        info = TextView(this).apply {
            textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(0, px(8), 0, px(16))
        }
        fun button(text: String, bg: String, fg: Int = Color.WHITE) = Button(this).apply {
            this.text = text
            textSize = 20f
            setTextColor(fg)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor(bg))
            isAllCaps = false
        }
        cancelBtn = button("ANNULLA: STO BENE", "#FFFFFF", Color.parseColor("#B71C1C"))
        whereBtn = button("Apri 112 Where ARE U", "#0D47A1")
        callBtn = button("Chiama il 112", "#212121")
        mapBtn = button("Mostra la posizione", "#4E342E")

        root.addView(title)
        root.addView(big)
        root.addView(info)
        val lp = { h: Int -> LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(h)).apply { topMargin = px(10) } }
        root.addView(cancelBtn, lp(96))
        root.addView(whereBtn, lp(64))
        root.addView(callBtn, lp(64))
        root.addView(mapBtn, lp(56))
        setContentView(root)

        cancelBtn.setOnClickListener {
            if (pocket) SosPocketService.cancelFromPocket(this) else SosBike.cancel(this)
            finish()
        }
        whereBtn.setOnClickListener { openWhereAreU() }
        callBtn.setOnClickListener {
            // apre il tastierino con 112 già scritto: la chiamata la fai tu
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
            } catch (_: Exception) {
                Toast.makeText(this, "Componi 112", Toast.LENGTH_LONG).show()
            }
        }
        mapBtn.setOnClickListener {
            val s = RideRepository.sos.value
            val la = s.lat ?: RideRepository.location.value?.latitude
            val lo = s.lon ?: RideRepository.location.value?.longitude
            if (la == null || lo == null) Toast.makeText(this, "Posizione non disponibile", Toast.LENGTH_SHORT).show()
            else try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SosProtocol.mapLink(la, lo))))
            } catch (_: Exception) {
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RideRepository.sos.collect { render(it) }
            }
        }
    }

    private fun openWhereAreU() {
        val i = packageManager.getLaunchIntentForPackage(SosPocketService.WHERE_ARE_U)
        if (i != null) {
            startActivity(i)
            return
        }
        Toast.makeText(this, "Where ARE U non è installata su questo telefono: chiama il 112", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + SosPocketService.WHERE_ARE_U)))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun render(s: SosLinkState) {
        val cd = s.countdown
        when {
            cd != null -> {
                title.text = if (pocket) "Possibile caduta!" else "Possibile caduta!\nStai bene?"
                big.text = cd.toString()
                info.text = if (pocket) {
                    "Il telefono sul manubrio ha rilevato un urto.\nSe non annulli, tra $cd secondi partono gli SMS ai tuoi contatti."
                } else {
                    "Se non annulli, tra $cd secondi il telefono in tasca manda gli SMS di aiuto con la tua posizione." +
                        if (!s.connected) "\n\n⚠ Telefono in tasca NON collegato" else ""
                }
                whereBtn.visibility = if (pocket) android.view.View.VISIBLE else android.view.View.GONE
                callBtn.visibility = whereBtn.visibility
            }
            s.sosSent -> {
                title.text = "SOS inviato"
                big.text = "🆘"
                info.text = (s.message ?: "") + "\n\nSe puoi, apri Where ARE U o chiama il 112.\nPremi ANNULLA solo se stai bene: i contatti riceveranno un SMS di falso allarme."
                // Where ARE U è sul telefono in tasca; il 112 si chiama anche senza SIM
                whereBtn.visibility = if (pocket) android.view.View.VISIBLE else android.view.View.GONE
                callBtn.visibility = android.view.View.VISIBLE
            }
            else -> {
                // allarme finito o annullato altrove
                finish()
            }
        }
    }
}
