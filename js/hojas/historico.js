// HistoricoGlobalBottomSheetDialog.kt y HistoricoAdapter.kt: lo cobrado en todas las semanas, la mejor, el gráfico
// de evolución y el desglose por semanas, meses o años.
import * as estado from '../estado.js';
import { abrirHoja, cabeceraPanel } from '../paneles.js';
import { agruparPorPeriodo, evolucionPeriodos, evolucionSemanas, mejorSemana, redondearCentimos, ultimosPeriodos } from '../calculos.js';
import { MESES_CORTOS, nombreMes, rangoCortoConAnio } from '../fechas.js';
import { descontadoConSigno, esc, eurosCompacto, moneda, sinSimbolos, TEXTOS } from '../formato.js';
import { activas, animarMoneda, haptica, LARGA_MS, muellePulsar } from '../movimiento.js';
import { grupoOpciones, html } from '../componentes.js';
import { GraficoNeto } from '../vistas/grafico-neto.js';

const etiquetaSemana = (k) => `S${Number(k.split('-W')[1]) || 0}`;

function resumenDeSemana(r) {
  const hechos = TEXTOS.sociosHechos(r.totalSociosHechos);
  return `${hechos} · ${eurosCompacto(r.bonusSemanal, '+')} bonus${r.esBonoRegaloAplicado ? ' 🎁' : ''}`;
}

const conDescontado = (detalle, descontado) => (descontado !== 0 ? `${detalle} · ${descontadoConSigno(descontado, true)} desc.` : detalle);

export function abrirHistorico() {
  return abrirHoja({
    tag: 'HistoricoGlobalDialog',
    construir: (panel) => {
      const s = panel.scroll;
      s.append(html(cabeceraPanel({ placa: 'degradado-cabecera', iconoPlaca: 'history', titulo: 'Histórico', subtitulo: 'Lo acumulado en todas tus semanas' })));
      const totales = html(`
        <section class="hero panel" style="margin-top:20px">
          <div class="hero-interior">
            <div class="etiqueta-neto">Cobrado en total</div>
            <div class="neto-total" data-id="neto">0 €</div>
            <div class="pastillas"><span class="pastilla-cabecera" data-id="semanas"></span><span class="pastilla-cabecera" data-id="media"></span></div>
            <div class="cajas" style="margin-top:18px">
              <div class="caja"><span class="caja-etiqueta" style="color:var(--header-label-base)">Base ×2</span><span class="caja-importe" data-id="base"></span></div>
              <div class="caja"><span class="caja-etiqueta" style="color:var(--header-label-bonus)">Bonus</span><span class="caja-importe" data-id="bonus" style="color:var(--header-bonus)"></span></div>
              <div class="caja"><span class="caja-etiqueta" style="color:var(--header-label-irpf)">IRPF retenido</span><span class="caja-importe" data-id="irpf" style="color:var(--header-irpf)"></span></div>
            </div>
          </div>
        </section>`);
      const mejor = html(`
        <div class="tarjeta mejor-semana oculto">
          <div class="fila-mejor" role="button" tabindex="0">
            <span class="tile-ambar degradado-ambar" style="width:48px;height:48px"><span class="icono" data-icono="trophy"></span></span>
            <span class="columna-textos">
              <span class="etiqueta">Tu mejor semana</span>
              <span class="titulo-fila" data-id="mejorRango" style="margin-top:1px"></span>
              <span class="descripcion-fila" data-id="mejorDetalle"></span>
            </span>
            <span class="neto" data-id="mejorNeto"></span>
            <span class="icono flecha" data-icono="chevron_right"></span>
          </div>
        </div>`);
      muellePulsar(mejor);
      const seccion = html('<div class="seccion">Desglose</div>');
      const agrupacion = grupoOpciones([
        { id: 'SEMANA', html: 'Semanas' }, { id: 'MES', html: 'Meses' }, { id: 'ANIO', html: 'Años' }
      ], () => {
        haptica.click();
        mostrarFilas();
      });
      agrupacion.marcar('SEMANA');
      const tarjetaGrafico = html(`
        <div class="tarjeta tarjeta-grafico">
          <div class="fila" style="padding:0 4px">
            <span class="titulo">Evolución del neto</span>
            <span class="leyenda-media oculto" data-id="media"><svg viewBox="0 0 18 8" aria-hidden="true"><line x1="0" y1="4" x2="18" y2="4" stroke="currentColor" stroke-width="1.5" stroke-dasharray="5 3"/></svg><span></span></span>
          </div>
          <div class="ayuda-grafico" data-id="ayuda">Toca una barra para ver su importe</div>
          <canvas class="grafico" role="img" tabindex="0"></canvas>
        </div>`);
      const nota = html('<div class="nota-agrupacion oculto">Las semanas que cruzan de mes o de año cuentan en el de su jueves.</div>');
      const lista = html('<div class="lista-historico"></div>');
      const bloques = [totales, mejor, seccion, agrupacion, tarjetaGrafico, nota, lista];
      s.append(...bloques);

      const canvas = tarjetaGrafico.querySelector('canvas');
      const grafico = new GraficoNeto(canvas);
      grafico.alSeleccionar = (c) => canvas.setAttribute('aria-description', `${c.etiquetaLarga} · ${moneda(c.neto)}`);
      let periodoDelGrafico;
      let graficoDibujado = false;

      // Al abrirlo, lo de debajo de la cabecera entra bloque a bloque.
      if (activas()) {
        let orden = 0;
        for (const b of bloques) {
          const retraso = b.classList.contains('oculto') ? 0 : 90 + 70 * orden++;
          b.animate([{ opacity: 0, transform: 'translateY(28px)' }, { opacity: 1, transform: 'none' }],
            { duration: LARGA_MS, delay: retraso, easing: 'cubic-bezier(0.32, 0.72, 0, 1)', fill: 'backwards' });
        }
      }

      const $ = (raiz, id) => raiz.querySelector(`[data-id="${id}"]`);

      function renderTotales(h) {
        const semanas = h.semanas.length;
        $(totales, 'semanas').textContent = TEXTOS.semanas(semanas);
        animarMoneda($(totales, 'neto'), h.neto);
        $(totales, 'media').classList.toggle('oculto', semanas === 0);
        if (semanas > 0) $(totales, 'media').textContent = `Media ${moneda(redondearCentimos(h.neto / semanas))} / semana`;
        $(totales, 'base').textContent = eurosCompacto(h.base, '+');
        $(totales, 'bonus').textContent = eurosCompacto(h.bonus, '+');
        $(totales, 'irpf').textContent = eurosCompacto(h.irpf, '-');
      }

      function renderMejor(m) {
        mejor.classList.toggle('oculto', !m);
        if (!m) return;
        $(mejor, 'mejorRango').textContent = rangoCortoConAnio(m.semanaKey);
        const detalle = resumenDeSemana(m.resumen);
        $(mejor, 'mejorDetalle').textContent = detalle;
        $(mejor, 'mejorDetalle').setAttribute('aria-label', sinSimbolos(detalle));
        $(mejor, 'mejorNeto').textContent = moneda(m.resumen.totalNeto);
        mejor.onclick = () => {
          haptica.click();
          estado.irASemana(m.semanaKey);
          panel.cerrar();
        };
      }

      function filaSemana(item, actual) {
        const r = item.resumen;
        return {
          id: item.semanaKey, etiqueta: etiquetaSemana(item.semanaKey), titulo: rangoCortoConAnio(item.semanaKey),
          detalle: conDescontado(resumenDeSemana(r), r.descontado), neto: r.totalNeto, irpf: r.retencionIrpf,
          semanaKey: item.semanaKey, destacada: item.semanaKey === actual
        };
      }

      function filaPeriodo(p, destacada) {
        return {
          id: `${p.anio}-${p.mes ?? 0}`,
          etiqueta: p.mes ? MESES_CORTOS[p.mes - 1].toUpperCase() : 'AÑO',
          titulo: p.mes ? nombreMes(p.mes, p.anio) : String(p.anio),
          detalle: conDescontado(`Bruto ${moneda(p.bruto)} · ${TEXTOS.sociosHechos(p.sociosHechos)}`, p.descontado),
          neto: p.neto, irpf: p.irpf, semanaKey: null, destacada
        };
      }

      function mostrarFilas() {
        const h = estado.historico();
        const actual = estado.getSemanaActual();
        const elegido = agrupacion.marcado();
        const periodo = elegido === 'SEMANA' ? null : elegido;
        nota.classList.toggle('oculto', !periodo);
        const periodos = periodo ? agruparPorPeriodo(h.semanas, periodo) : [];
        let filas;
        if (periodo) {
          const claveActual = ultimosPeriodos(periodos, periodo, actual, 1)[0]?.clave;
          filas = periodos.map((p) => filaPeriodo(p, p.clave === claveActual));
        } else {
          filas = h.semanas.map((i) => filaSemana(i, actual));
        }
        const mejorNeto = Math.max(0, ...filas.map((f) => f.neto));
        lista.replaceChildren(...filas.map((f) => crearFila({ ...f, proporcion: mejorNeto > 0 ? Math.min(1, Math.max(0, f.neto / mejorNeto)) : 0 })));
        mostrarGrafico(h.semanas, periodo, periodos, actual);
      }

      function crearFila(f) {
        const el = html(`
          <div class="tarjeta fila-historico${f.semanaKey ? ' abrible' : ''}"${f.semanaKey ? ' role="button" tabindex="0"' : ''}>
            <span class="placa ${f.destacada ? 'tono-verde' : 'tono-azul'}"><span class="etiqueta-placa" style="color:var(${f.destacada ? '--green-text' : '--blue-text-soft'})">${esc(f.etiqueta)}</span></span>
            <span class="columna-textos">
              <span class="fila"><span class="titulo-fila crece una-linea">${esc(f.titulo)}</span><span class="neto">${esc(moneda(f.neto, 2, f.neto > 0 ? '+' : ''))}</span></span>
              <span class="descripcion-fila" aria-label="${esc(sinSimbolos(f.detalle))}">${esc(f.detalle)}</span>
              <span class="fila" style="margin-top:8px"><span class="barra-proporcion"><div style="width:${(f.proporcion * 100).toFixed(1)}%"></div></span><span class="irpf">IRPF ${esc(moneda(f.irpf, 2, '-'))}</span></span>
            </span>
            ${f.semanaKey ? '<span class="icono flecha" data-icono="chevron_right"></span>' : ''}
          </div>`);
        if (f.semanaKey) {
          el.addEventListener('click', () => {
            estado.irASemana(f.semanaKey);
            panel.cerrar();
          });
          muellePulsar(el);
        }
        return el;
      }

      function mostrarGrafico(semanas, periodo, periodos, actual) {
        const marcaDeAnio = (barras, i) => {
          const anio = barras[i].clave.slice(0, 4);
          return i === 0 || barras[i - 1].clave.slice(0, 4) !== anio ? anio : null;
        };
        let columnas;
        if (!periodo) {
          const barras = evolucionSemanas(semanas, actual);
          columnas = barras.map((b, i) => ({ etiqueta: etiquetaSemana(b.clave), etiquetaLarga: rangoCortoConAnio(b.clave), neto: b.neto, separador: marcaDeAnio(barras, i) }));
        } else {
          const barras = evolucionPeriodos(periodos, periodo, actual);
          columnas = barras.map((b, i) => {
            const anio = b.clave.slice(0, 4);
            const mes = Number(b.clave.split('-')[1]) || null;
            return {
              etiqueta: mes ? MESES_CORTOS[mes - 1] : anio,
              etiquetaLarga: mes ? nombreMes(mes, anio) : anio,
              neto: b.neto,
              separador: periodo === 'MES' ? marcaDeAnio(barras, i) : null
            };
          });
        }
        const animar = !graficoDibujado || periodo !== periodoDelGrafico;
        grafico.mostrar(columnas, columnas.length - 1, animar);
        graficoDibujado = true;
        periodoDelGrafico = periodo;
        requestAnimationFrame(() => {
          $(tarjetaGrafico, 'ayuda').textContent = grafico.sePuedeDesplazar
            ? 'Desliza para ver todo · toca una barra para ver su importe'
            : 'Toca una barra para ver su importe';
        });
        const media = $(tarjetaGrafico, 'media');
        media.classList.toggle('oculto', grafico.media == null);
        media.querySelector('span').textContent = grafico.media != null ? `Media ${eurosCompacto(grafico.media)}` : '';
        let max = null;
        for (const c of columnas) if (!max || c.neto > max.neto) max = c;
        canvas.setAttribute('aria-label', !max || max.neto <= 0 || !columnas.length
          ? 'Todavía no hay neto registrado en estos periodos'
          : `Neto de ${columnas.length} periodos, de ${columnas[0].etiquetaLarga} a ${columnas[columnas.length - 1].etiquetaLarga}. El más alto: ${moneda(max.neto)} en ${max.etiquetaLarga}.`);
      }

      const pintar = () => {
        const h = estado.historico();
        renderTotales(h);
        renderMejor(mejorSemana(h.semanas));
        mostrarFilas();
      };
      pintar();
      const dejar = estado.suscribir(pintar);
      panel.el.querySelector('[data-cerrar]').addEventListener('click', () => panel.cancelar());
      return dejar;
    }
  });
}
