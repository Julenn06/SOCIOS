package com.julen.socios

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.julen.socios.ActualizacionesViewModel.Descarga
import com.julen.socios.actualizaciones.VersionPublicada
import com.julen.socios.databinding.BottomSheetActualizacionBinding
import com.julen.socios.util.abrirEnlace
import kotlinx.coroutines.launch

/**
 * Descarga de una versión nueva con su animación. Al terminar abre el instalador de Android, que pide
 * confirmación (y, la primera vez, permiso para instalar aplicaciones desde socios).
 */
class ActualizacionBottomSheetDialog : BottomSheetDialogFragment() {

    private val viewModel: ActualizacionesViewModel by activityViewModels { ActualizacionesViewModel.Factory }

    private var _binding: BottomSheetActualizacionBinding? = null
    private val binding get() = _binding!!

    /** Para no volver a abrir el instalador solo al girar la pantalla. */
    private var instaladorAbierto = false

    /** El último estado mostrado; null hasta mostrar el primero, que se muestra sin animar. */
    private var mostrado: Descarga? = null
    private var tamanoTotal = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instaladorAbierto = savedInstanceState?.getBoolean(STATE_INSTALADOR_ABIERTO) == true
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetActualizacionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.animacionDescarga.alMostrarProgreso = ::mostrarTamano
        binding.animacionDescarga.alCompletar = ::mostrarCompletada
        binding.animacionDescarga.alTerminar = { abrirInstalador(soloLaPrimeraVez = true) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.descarga.collect(::render)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_INSTALADOR_ABIERTO, instaladorAbierto)
    }

    override fun onResume() {
        super.onResume()
        // Puede que acabe de dar el permiso desde el instalador.
        if (binding.tvAvisoPermiso.isVisible) {
            binding.tvAvisoPermiso.isVisible = !requireContext().packageManager.canRequestPackageInstalls()
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        if (viewModel.descarga.value is Descarga.EnCurso) viewModel.cancelarDescarga()
    }

    override fun onDestroyView() {
        binding.animacionDescarga.alMostrarProgreso = null
        binding.animacionDescarga.alCompletar = null
        binding.animacionDescarga.alTerminar = null
        mostrado = null
        _binding = null
        super.onDestroyView()
    }

    private fun render(estado: Descarga) {
        val anterior = mostrado
        mostrado = estado
        val animacion = binding.animacionDescarga
        when (estado) {
            // Cancelada, o la app se cerró a medias y no queda descarga que mostrar.
            Descarga.Inactiva -> dismiss()

            is Descarga.EnCurso -> {
                if (anterior !is Descarga.EnCurso) mostrarDescargando(estado.publicada)
                animacion.mostrarProgreso(estado.progreso.fraccion, inmediato = anterior == null)
            }

            // Los textos cambian cuando el anillo llega al final (alCompletar) y el instalador se abre al
            // terminar la animación (alTerminar).
            is Descarga.Completada -> {
                if (anterior == null) mostrarDescargando(estado.publicada)
                animacion.completar(animar = anterior != null)
            }

            is Descarga.Fallida -> {
                if (anterior == null) mostrarDescargando(estado.publicada)
                animacion.fallar(animar = anterior != null)
                mostrarFallo(estado)
            }
        }
    }

    private fun mostrarDescargando(publicada: VersionPublicada) {
        tamanoTotal = publicada.apk?.tamano ?: 0L
        val version = publicada.version.toString()
        binding.tvTituloDescarga.text = getString(R.string.descarga_titulo, version)
        binding.tvVersionesDescarga.text = getString(R.string.descarga_versiones, viewModel.versionInstalada, version)
        binding.tvVersionesDescarga.contentDescription =
            getString(R.string.descarga_versiones_a11y, viewModel.versionInstalada, version)
        binding.tvDetalleDescarga.text = null
        // Si Android aún no deja instalar desde socios, avisa de que lo pedirá.
        binding.tvAvisoPermiso.isVisible = !requireContext().packageManager.canRequestPackageInstalls()
        binding.btnPrincipalDescarga.isVisible = false
        binding.btnGitHubDescarga.isVisible = false
        binding.btnSecundarioDescarga.setText(R.string.action_cancel)
        binding.btnSecundarioDescarga.setOnClickListener { viewModel.cancelarDescarga() }
    }

    private fun mostrarTamano(fraccion: Float) {
        val b = _binding ?: return
        if (mostrado is Descarga.Fallida) return
        b.tvDetalleDescarga.text =
            getString(R.string.descarga_progreso, tamano((tamanoTotal * fraccion).toLong()), tamano(tamanoTotal))
    }

    private fun mostrarCompletada() {
        val b = _binding ?: return
        val completada = mostrado as? Descarga.Completada ?: return
        b.tvTituloDescarga.text = getString(R.string.descarga_titulo_completada, completada.publicada.version.toString())
        b.tvDetalleDescarga.setText(
            if (instaladorAbierto) R.string.descarga_confirmar_instalacion else R.string.descarga_abriendo_instalador
        )
        b.btnPrincipalDescarga.setText(R.string.action_instalar)
        b.btnPrincipalDescarga.setOnClickListener { abrirInstalador(soloLaPrimeraVez = false) }
        b.btnPrincipalDescarga.isVisible = true
        b.btnSecundarioDescarga.setText(R.string.action_close)
        b.btnSecundarioDescarga.setOnClickListener { dismiss() }
    }

    private fun mostrarFallo(fallida: Descarga.Fallida) {
        binding.tvTituloDescarga.setText(R.string.descarga_titulo_fallida)
        binding.tvDetalleDescarga.text = fallida.motivo.resolver(requireContext())
        binding.tvAvisoPermiso.isVisible = false
        binding.btnPrincipalDescarga.setText(R.string.action_reintentar)
        binding.btnPrincipalDescarga.setOnClickListener { viewModel.descargar(fallida.publicada) }
        binding.btnPrincipalDescarga.isVisible = true
        binding.btnGitHubDescarga.setOnClickListener {
            if (requireContext().abrirEnlace(fallida.publicada.url)) {
                dismiss()
            } else {
                binding.tvDetalleDescarga.setText(R.string.msg_sin_navegador)
            }
        }
        binding.btnGitHubDescarga.isVisible = true
        binding.btnSecundarioDescarga.setText(R.string.action_close)
        binding.btnSecundarioDescarga.setOnClickListener { dismiss() }
    }

    private fun abrirInstalador(soloLaPrimeraVez: Boolean) {
        val b = _binding ?: return
        val completada = mostrado as? Descarga.Completada ?: return
        if (soloLaPrimeraVez && instaladorAbierto) return
        if (!completada.apk.exists()) {
            // Android puede vaciar la caché: se vuelve a descargar.
            viewModel.descargar(completada.publicada)
            return
        }
        val contexto = requireContext()
        val uri = FileProvider.getUriForFile(contexto, "${contexto.packageName}.fileprovider", completada.apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, MIME_APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
            instaladorAbierto = true
            b.tvDetalleDescarga.setText(R.string.descarga_confirmar_instalacion)
        } catch (e: ActivityNotFoundException) {
            b.tvDetalleDescarga.setText(R.string.descarga_sin_instalador)
        }
    }

    private fun tamano(bytes: Long): String = Formatter.formatShortFileSize(requireContext(), bytes)

    companion object {
        const val TAG = "ActualizacionBottomSheet"
        private const val STATE_INSTALADOR_ABIERTO = "instaladorAbierto"
        private const val MIME_APK = "application/vnd.android.package-archive"
    }
}
