package cl.lpa.agenda

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Igual que "tomar foto" pero pide la camara TRASERA (la frontal de la tablet es de baja calidad).
 * La foto se guarda completa, en la resolucion que tenga configurada la app de camara del equipo.
 */
class TakeBackPicture : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent =
        super.createIntent(context, input)
            .putExtra("android.intent.extras.CAMERA_FACING", 0)
            .putExtra("android.intent.extras.LENS_FACING_BACK", 1)
            .putExtra("android.intent.extra.USE_FRONT_CAMERA", false)
}
