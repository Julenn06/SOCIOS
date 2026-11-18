// BonoRegaloBottomSheetDialog.kt y CobroBottomSheetDialog.kt: el bonus ajustado a mano y lo cobrado de verdad.
import * as estado from '../estado.js';
import { abrirHoja, cabeceraPanel } from '../paneles.js';
import { mostrarAviso } from '../avisos.js';
import { BONUS, IMPORTE_MAXIMO, redondearCentimos } from '../calculos.js';
import { rangoCortoConAnio } from '../fechas.js';
import { descontadoConSigno, etiquetaDescontado, importeInput, moneda, parseImporte } from '../formato.js';
import { haptica, muellePulsar } from '../movimiento.js';
import { crearCampo, html, marcar, opcionesImporte, tarjetaElegible, tarjetaInterruptor } from '../componentes.js';

/** Ajuste manual del bonus de [semanaKey]: sustituye al automático o se suma a él. */
export function abrirBonus(semanaKey, bonoActual) {
  return abrirHoja({
    tag: 'BonoRegaloDialog',
    construir: (panel) => {
      const tieneBono = bonoActual?.activo === true;
      let montoFijo = bonoActual?.esMontoFijo ?? true;
      const s = panel.scroll;
      s.append(html(cabeceraPanel({ placa: 'degradado-ambar', iconoPlaca: 'gift', titulo: 'Bonus de la semana', subtitulo: rangoCortoConAnio(semanaKey) })));

      const config = html('<div></div>');
      const interruptor = tarjetaInterruptor({
        iconoPlaca: 'edit', tono: 'naranja', titulo: 'Ajustar a mano', detalle: 'Cambia el bonus automático de esta semana',
        encendido: tieneBono, opciones: config
      });
      interruptor.el.style.marginTop = '20px';
      s.append(interruptor.el, config);

      config.append(html('<div class="seccion">¿Cómo se aplica?</div>'));
      const fijo = tarjetaElegible(html('<span class="columna"><span class="titulo-fila">Sustituir</span><span class="descripcion-fila">En lugar del automático</span></span>'));
      const sumar = tarjetaElegible(html('<span class="columna"><span class="titulo-fila">Sumar</span><span class="descripcion-fila">Además del automático</span></span>'));
      const modos = html('<div class="estados"></div>');
      modos.append(fijo, sumar);
      config.append(modos);
      const mostrarModo = () => {
        marcar(fijo, montoFijo);
        marcar(sumar, !montoFijo);
      };
      const elegirModo = (f) => {
        if (f === montoFijo) return;
        haptica.click();
        montoFijo = f;
        mostrarModo();
      };
      fijo.addEventListener('click', () => elegirModo(true));
      sumar.addEventListener('click', () => elegirModo(false));
      [fijo, sumar].forEach(muellePulsar);

      config.append(html('<div class="seccion">Importe</div>'));
      const campoMonto = crearCampo({ etiqueta: 'Importe del bonus (€)', iconoCampo: 'euro', tipo: 'decimal', maxLength: 9 });
      campoMonto.el.style.marginTop = '10px';
      const montos = opcionesImporte(BONUS, campoMonto);
      config.append(montos.el, campoMonto.el);
      campoMonto.input.addEventListener('input', () => campoMonto.error(null));
      const campoNota = crearCampo({ etiqueta: 'Motivo del ajuste (opcional)', iconoCampo: 'notes' });
      campoNota.el.style.marginTop = '12px';
      config.append(campoNota.el);

      const botones = html(`
        <div class="botones">
          <button class="boton contorno${tieneBono ? '' : ' oculto'}" type="button" data-id="restablecer">Restablecer</button>
          <button class="boton relleno" type="button" data-id="guardar" style="flex-grow:1.4"><span class="icono" data-icono="check"></span>Guardar</button>
        </div>`);
      s.append(botones);
      botones.querySelectorAll('.boton').forEach(muellePulsar);

      // También un bonus de 0 €, que es válido: va a "Otro" con su importe.
      if (tieneBono) montos.elegir(bonoActual.monto);
      const motivo = bonoActual?.nota?.trim();
      campoNota.poner(motivo && motivo !== 'Ajustado a mano' ? motivo : '');
      montos.ajustarCampo();
      mostrarModo();

      panel.el.querySelector('[data-cerrar]').addEventListener('click', () => panel.cancelar());
      botones.querySelector('[data-id="restablecer"]').addEventListener('click', () => {
        haptica.click();
        estado.eliminarBono(semanaKey);
        panel.cerrar();
      });
      botones.querySelector('[data-id="guardar"]').addEventListener('click', () => {
        haptica.confirmar();
        if (!interruptor.encendido()) {
          estado.eliminarBono(semanaKey);
          panel.cerrar();
          return;
        }
        const monto = montos.elegido();
        if (monto == null || monto < 0) {
          if (montos.esOtro()) campoMonto.error('Elige o escribe el importe del bonus');
          else mostrarAviso('Elige o escribe el importe del bonus', 'ERROR');
          return;
        }
        estado.guardarBono({ semanaKey, activo: true, esMontoFijo: montoFijo, monto, nota: campoNota.valor().trim() });
        panel.cerrar();
      });
    }
  });
}

/** Ajusta el neto de la semana visible a lo cobrado de verdad (se abre con un doble toque en el neto). */
export function abrirCobro(ui) {
  const { semanaKey, cobroSemana: actual } = ui;
  return abrirHoja({
    tag: 'CobroDialog',
    construir: (panel) => {
      const tieneCobro = actual != null;
      const s = panel.scroll;
      s.append(html(cabeceraPanel({ placa: 'degradado-verde', iconoPlaca: 'cobro', titulo: 'Lo que has cobrado', subtitulo: rangoCortoConAnio(semanaKey) })));

      const config = html('<div></div>');
      const interruptor = tarjetaInterruptor({
        iconoPlaca: 'edit', tono: 'verde', titulo: 'Ajustar a lo cobrado', detalle: 'Usa lo que te ha llegado de verdad en lugar del neto calculado',
        encendido: tieneCobro, opciones: config, alCambiar: () => campo.error(null)
      });
      interruptor.el.style.marginTop = '20px';
      s.append(interruptor.el, config);

      config.append(html('<div class="seccion">Lo que te ha llegado</div>'));
      const campo = crearCampo({ etiqueta: 'Neto cobrado (€)', iconoCampo: 'euro', tipo: 'decimal', maxLength: 9, ayuda: true, grande: true });
      config.append(campo.el);
      const vista = html(`
        <div class="tarjeta" style="margin-top:14px;padding:16px">
          <div class="fila"><span class="punto-calculo" style="background:var(--text-sub)"></span><span class="etiqueta-calculo">Neto calculado</span><span class="importe-calculo" data-id="calculado"></span></div>
          <div class="fila" style="margin-top:12px"><span class="punto-calculo" style="background:var(--primary)"></span><span class="etiqueta-calculo">Has cobrado</span><span class="importe-calculo" data-id="cobrado">–</span></div>
          <div class="placa-diferencia oculto" data-id="diferencia"><span class="etiqueta" data-id="etiquetaDiferencia"></span><span class="valor cifra" data-id="valorDiferencia"></span></div>
        </div>`);
      config.append(vista);
      const guardar = html('<button class="boton relleno ancho" type="button" style="margin-top:22px"><span class="icono" data-icono="check"></span>Guardar</button>');
      s.append(guardar);
      muellePulsar(guardar);

      if (tieneCobro) campo.poner(importeInput(actual.netoCobrado));

      /** Lo escrito, redondeado a céntimos; null si no es un importe válido o pasa del tope. */
      const netoEscrito = () => {
        const v = parseImporte(campo.valor());
        return v != null && v >= 0 && v <= IMPORTE_MAXIMO ? redondearCentimos(v) : null;
      };
      const $ = (id) => vista.querySelector(`[data-id="${id}"]`);
      const actualizar = () => {
        const ui = estado.ui();
        if (ui.semanaKey !== semanaKey) return;
        const calculado = ui.resumen.netoCalculado;
        const cobrado = netoEscrito();
        campo.ayuda(`Calculado: ${moneda(calculado)}`);
        $('calculado').textContent = moneda(calculado);
        $('cobrado').textContent = cobrado != null ? moneda(cobrado) : '–';
        const descontado = cobrado != null ? redondearCentimos(calculado - cobrado) : null;
        const rojo = (descontado ?? 0) > 0;
        const placa = $('diferencia');
        placa.classList.toggle('oculto', descontado == null);
        placa.style.background = rojo ? 'var(--red-container)' : 'var(--green-container)';
        placa.style.color = rojo ? 'var(--red-text)' : 'var(--green-text)';
        $('etiquetaDiferencia').textContent = etiquetaDescontado(descontado ?? 0);
        $('valorDiferencia').textContent = descontado != null ? descontadoConSigno(descontado) : '';
      };
      campo.input.addEventListener('input', () => {
        campo.error(null);
        actualizar();
      });
      campo.input.addEventListener('keydown', (e) => e.key === 'Enter' && campo.input.blur());
      actualizar();
      const dejar = estado.suscribir(actualizar);

      panel.el.querySelector('[data-cerrar]').addEventListener('click', () => panel.cancelar());
      guardar.addEventListener('click', () => {
        haptica.confirmar();
        if (!interruptor.encendido()) {
          if (tieneCobro) estado.eliminarCobro(semanaKey);
          panel.cerrar();
          return;
        }
        const cobrado = netoEscrito();
        if (cobrado == null) {
          campo.error('Escribe lo que has cobrado, por ejemplo 284,69');
          return;
        }
        // La nota ya no se escribe aquí, pero se conserva la que tuviera.
        estado.guardarCobro(semanaKey, cobrado, actual?.nota ?? '');
        panel.cerrar();
      });
      return dejar;
    }
  });
}
