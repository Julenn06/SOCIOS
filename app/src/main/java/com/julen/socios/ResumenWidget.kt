package com.julen.socios

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DateUtils
import com.julen.socios.util.toEurosCompacto
import com.julen.socios.util.toFormattedEuros
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Widget de la pantalla de inicio con el resumen de la semana actual. Android lo refresca cada media hora
 * (así cambia solo de semana el lunes) y la app, cada vez que cambian sus datos (ver [SociosApp]).
 */
class ResumenWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Leer la base de datos lleva un momento: goAsync mantiene vivo el receptor hasta terminar.
        val pendiente = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ResumenWidget.actualizar(context, appWidgetIds)
            } finally {
                pendiente.finish()
            }
        }
    }
}

object ResumenWidget {

    /** Vuelve a dibujar todos los widgets puestos; no hace nada si no hay ninguno. */
    suspend fun actualizarTodos(context: Context) {
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, ResumenWidgetProvider::class.java))
        if (ids.isNotEmpty()) actualizar(context, ids)
    }

    suspend fun actualizar(context: Context, ids: IntArray) {
        val app = context.applicationContext as SociosApp
        val container = app.container
        val semanaKey = DateUtils.getSemanaKey(0, container.clock)
        val resumen = CalculoComisiones.calcularResumenSemana(
            socios = container.socioRepository.getSociosPorSemanaFlow(semanaKey).first(),
            bonoRegalo = container.bonoRegaloRepository.getBonoRegaloFlow(semanaKey).first(),
            porcentajeIrpf = CalculoComisiones.irpfParaSemana(semanaKey, container.irpfRepository.getVigenciasFlow().first()),
            cobro = container.cobroRepository.getCobroFlow(semanaKey).first()
        )
        val vistas = vistas(app, semanaKey, resumen)
        AppWidgetManager.getInstance(app).updateAppWidget(ids, vistas)
    }

    private fun vistas(context: Context, semanaKey: String, r: CalculoComisiones.ResumenCalculo): RemoteViews {
        val res = context.resources
        return RemoteViews(context.packageName, R.layout.widget_resumen).apply {
            setTextViewText(
                R.id.tvWidgetEtiqueta,
                context.getString(if (r.tieneCobroReal) R.string.header_neto_cobrado else R.string.header_neto_semanal)
            )
            setTextViewText(R.id.tvWidgetNeto, r.totalNeto.toFormattedEuros())
            setTextViewText(
                R.id.tvWidgetSocios,
                context.getString(
                    R.string.socio_dia_semana,
                    res.getQuantityString(R.plurals.widget_socios, r.totalSociosHechos, r.totalSociosHechos),
                    DateUtils.rangoCorto(semanaKey)
                )
            )
            // La barra cuenta desde el tramo anterior, como la de la app: así siempre se ve avanzar.
            val desde = CalculoComisiones.tramoAnterior(r.siguienteMetaSocios)
            setProgressBar(R.id.progresoWidget, r.siguienteMetaSocios - desde, r.totalSociosHechos - desde, false)
            setTextViewText(
                R.id.tvWidgetMeta,
                res.getQuantityString(
                    R.plurals.widget_meta,
                    r.sociosFaltantesParaSiguienteMeta,
                    r.sociosFaltantesParaSiguienteMeta,
                    r.siguienteMetaBonus.toEurosCompacto(prefix = "+")
                )
            )

            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { abrir ->
                setOnClickPendingIntent(
                    R.id.widgetRaiz,
                    PendingIntent.getActivity(context, PETICION_ABRIR, abrir, PendingIntent.FLAG_IMMUTABLE)
                )
            }
            // Como el atajo del icono: una tarea nueva, para que el formulario se abra aunque la app ya lo esté.
            val nuevo = Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_NUEVO_SOCIO)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            setOnClickPendingIntent(
                R.id.btnWidgetNuevo,
                PendingIntent.getActivity(context, PETICION_NUEVO, nuevo, PendingIntent.FLAG_IMMUTABLE)
            )
        }
    }

    private const val PETICION_ABRIR = 1
    private const val PETICION_NUEVO = 2
}
