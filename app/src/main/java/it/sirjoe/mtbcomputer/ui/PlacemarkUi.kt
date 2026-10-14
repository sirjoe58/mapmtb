package it.sirjoe.mtbcomputer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import it.sirjoe.mtbcomputer.MainActivity
import it.sirjoe.mtbcomputer.R
import it.sirjoe.mtbcomputer.data.BRouterClient
import it.sirjoe.mtbcomputer.data.PlaceType
import it.sirjoe.mtbcomputer.data.Placemark
import it.sirjoe.mtbcomputer.data.Prefs
import it.sirjoe.mtbcomputer.data.RideRepository
import it.sirjoe.mtbcomputer.data.RouteSummary
import it.sirjoe.mtbcomputer.data.Track
import it.sirjoe.mtbcomputer.data.TrackPoint
import it.sirjoe.mtbcomputer.util.Format
import it.sirjoe.mtbcomputer.util.Geo
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/** Disegno e finestre comuni dei segnaposto (pagine Crea e Mappa). */
object PlacemarkUi {

    private val icons = HashMap<PlaceType, BitmapDrawable>()

    /** Spillo colorato in base al tipo. */
    fun icon(ctx: Context, type: PlaceType): BitmapDrawable = icons.getOrPut(type) {
        val d = ctx.resources.displayMetrics.density
        val w = (34 * d).toInt()
        val h = (44 * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(d, d)
        fun drop(r: Float, tipY: Float) = Path().apply {
            addCircle(17f, 17f, r, Path.Direction.CW)
            moveTo(17f - r * 0.75f, 17f + r * 0.66f)
            lineTo(17f, tipY)
            lineTo(17f + r * 0.75f, 17f + r * 0.66f)
            close()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        c.drawPath(drop(15.5f, 43f), p)
        p.color = type.color
        c.drawPath(drop(13f, 39.5f), p)
        p.color = Color.WHITE
        c.drawCircle(17f, 17f, 5.5f, p)
        BitmapDrawable(ctx.resources, bmp)
    }

    fun marker(map: MapView, ctx: Context, p: Placemark): Marker = Marker(map).apply {
        position = GeoPoint(p.lat, p.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        icon = icon(ctx, p.type)
        title = p.name
        relatedObject = p.id
    }

    /** Finestra per creare o modificare un segnaposto: nome, nota, tipo. */
    fun edit(ctx: Context, p: Placemark, isNew: Boolean, onSave: (Placemark) -> Unit) {
        val dp = ctx.resources.displayMetrics.density
        var type = p.type
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
        }
        val name = EditText(ctx).apply {
            hint = "Nome"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(p.name)
            selectAll()
        }
        val note = EditText(ctx).apply {
            hint = "Nota (facoltativa)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(p.note)
        }
        val typeBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        fun showType() {
            typeBtn.text = "Tipo: " + type.label
        }
        showType()
        typeBtn.setOnClickListener {
            val types = PlaceType.values()
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Tipo di segnaposto")
                .setSingleChoiceItems(types.map { it.label }.toTypedArray(), types.indexOf(type)) { d, which ->
                    type = types[which]
                    showType()
                    d.dismiss()
                }
                .show()
        }
        box.addView(name)
        box.addView(note)
        box.addView(typeBtn)
        box.addView(TextView(ctx).apply {
            text = String.format(java.util.Locale.ITALY, "%.5f, %.5f", p.lat, p.lon)
            textSize = 12f
            setPadding(0, (6 * dp).toInt(), 0, 0)
        })
        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (isNew) "Nuovo segnaposto" else "Modifica segnaposto")
            .setView(box)
            .setPositiveButton("Salva") { _, _ ->
                onSave(
                    p.copy(
                        name = name.text.toString().trim().ifBlank { p.name },
                        note = note.text.toString().trim(),
                        type = type
                    )
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Scelta del tipo di percorso: corto, veloce, lungo, fuoristrada, equilibrato. */
    fun chooseRouteType(ctx: Context, onChosen: (BRouterClient.Profile) -> Unit) {
        val types = BRouterClient.Profile.values()
        val sel = types.indexOf(Prefs.plannerProfile(ctx)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Tipo di percorso")
            .setSingleChoiceItems(types.map { it.label }.toTypedArray(), sel) { d, which ->
                Prefs.setPlannerProfile(ctx, types[which])
                d.dismiss()
                onChosen(types[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun distanceText(p: Placemark): String {
        val l = RideRepository.location.value ?: return ""
        val d = Geo.distance(l.latitude, l.longitude, p.lat, p.lon)
        return if (d < 1000) " · ${d.toInt()} m" else " · ${Format.distanceKm(d)} km"
    }

    /** Calcola il percorso dalla posizione attuale al segnaposto e lo segue sulla mappa. */
    fun navigateTo(act: MainActivity, p: Placemark) {
        val here = RideRepository.location.value
        if (here == null) {
            Toast.makeText(act, "Posizione GPS non ancora disponibile", Toast.LENGTH_LONG).show()
            return
        }
        chooseRouteType(act) { profile ->
            Toast.makeText(act, "Calcolo del percorso verso ${p.name}…", Toast.LENGTH_SHORT).show()
            act.lifecycleScope.launch {
                try {
                    val pts = BRouterClient.route(TrackPoint(here.latitude, here.longitude), p.toPoint(), profile)
                    val track = Track("Verso " + p.name, pts)
                    RideRepository.setRoute(track)
                    act.selectTab(R.id.nav_map)
                    val s = RouteSummary.of(pts)
                    Toast.makeText(
                        act,
                        "${p.name}: ${Format.distanceKm(s.distanceM)} km" +
                            (s.ascentM?.let { ", ↗ ${Format.meters(it)} m" } ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(act, "Percorso non calcolato: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
