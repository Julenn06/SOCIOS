// export/PdfReportGenerator.kt: informe A4 con el aspecto de la app, con los colores del tema claro (se imprime en
// papel blanco): la cabecera verde con el neto, las cifras que lo forman, la evolución del neto, el año mes a mes
// (para la renta) y los socios de cada semana.
import { agruparPorPeriodo, calcularResumenHistoricoGlobal, escalaPara, evolucionPeriodos, evolucionSemanas, IRPF, redondearCentimos } from './calculos.js';
import { diaPorId, MESES, MESES_CORTOS, rangoCortoConAnio } from './fechas.js';
import { descontadoConSigno, eurosCompacto, moneda, porcentaje, TEXTOS } from './formato.js';
import { nombreVisible } from './estado.js';
import { urlScriptConfiable } from './confianza.js';

const ANCHO = 595, ALTO = 842, MARGEN = 32, MARGEN_PIE = 52, ALTO_SEMANA = 26, ALTO_FILA = 20;
const MAX_BARRAS_SEMANAS = 26, MAX_BARRAS_MESES = 36;
const COL_SOCIO = MARGEN + 10, COL_DIA = 210, COL_CUOTA = 290, COL_ESTADO = 345, COL_NOTAS = 420;
const COL_MES_SOCIOS = 190, COL_MES_BRUTO = 280, COL_MES_IRPF = 365, COL_MES_DESCONTADO = 455, COL_MES_NETO = ANCHO - MARGEN - 10;

const C = {
  verdeCabecera: '#155E46', verdeCabeceraOscuro: '#0A2D22', etiquetaNeto: '#9FD9C0', etiquetaClara: '#CDE8DC',
  blanco: '#FFFFFF', texto: '#111814', textoSuave: '#57625C', divisor: '#E3E7E1', grisFondo: '#F6F7F4',
  verde: '#0E7A55', verdeSuave: '#9CCCB5', verdeFondo: '#E1F1E8', verdeTexto: '#0B5A3E', azul: '#1F6470',
  azulFondo: '#E2EFEE', ambar: '#8F5D00', ambarFondo: '#FBF3DC', rojo: '#B4282C', rojoFondo: '#FBE9E8'
};

const FUENTES = { normal: 'geist_regular', media: 'geist_medium', fuerte: 'geist_semibold', negrita: 'geist_bold' };
let fuentesCargadas = null;

// El generador y las fuentes no van en la instalación (sw.js, BAJO_DEMANDA): la primera vez se descargan.
const SIN_CONEXION = 'Para el primer PDF hace falta conexión: se prepara el generador (unos 700 KB) y desde entonces funciona sin internet';

async function base64(url) {
  const respuesta = await fetch(url).catch(() => null);
  if (!respuesta?.ok) throw new Error(SIN_CONEXION);
  const bytes = new Uint8Array(await respuesta.arrayBuffer());
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

async function cargarJsPdf() {
  if (!window.jspdf) {
    await new Promise((resolver, rechazar) => {
      const s = document.createElement('script');
      // Con Trusted Types, la URL de un script tiene que pasar por la política (confianza.js).
      s.src = urlScriptConfiable(new URL('../vendor/jspdf.umd.min.js', import.meta.url).href);
      s.onload = resolver;
      s.onerror = () => {
        s.remove();
        rechazar(new Error(navigator.onLine ? 'No se ha podido cargar el generador de PDF' : SIN_CONEXION));
      };
      document.head.append(s);
    });
  }
  if (!fuentesCargadas) {
    fuentesCargadas = Object.fromEntries(await Promise.all(Object.values(FUENTES).map(async (f) => [f, await base64(new URL(`../fonts/${f}.ttf`, import.meta.url).href)])));
  }
  return window.jspdf.jsPDF;
}

/** El título del periodo: "Histórico global", "Año 2026", "12–16 oct 2026" o "3 semanas seleccionadas". */
function tituloPeriodo(alcance) {
  if (alcance.tipo === 'HISTORICO') return 'Histórico global';
  if (alcance.tipo === 'ANIO') return `Año ${alcance.anio}`;
  return alcance.semanas.size === 1 ? rangoCortoConAnio([...alcance.semanas][0]) : TEXTOS.semanasSeleccionadas(alcance.semanas.size);
}

/** Genera el informe de [datos] y lo devuelve como Blob. */
export async function generarPdf(datos, alcance, ahora = new Date()) {
  const JsPDF = await cargarJsPdf();
  const doc = new JsPDF({ unit: 'pt', format: [ANCHO, ALTO], compress: true });
  for (const [nombre, b64] of Object.entries(fuentesCargadas)) {
    doc.addFileToVFS(`${nombre}.ttf`, b64);
    doc.addFont(`${nombre}.ttf`, nombre, 'normal');
  }
  const activos = new Map(datos.bonosRegalo.filter((b) => b.activo).map((b) => [b.semanaKey, b]));
  const historico = calcularResumenHistoricoGlobal(datos.socios, activos, new Map(datos.cobros.map((c) => [c.semanaKey, c])));
  const sociosPorSemana = Map.groupBy(datos.socios, (s) => s.semanaKey);

  let numeroPagina = 1;
  let y = MARGEN;

  const preparar = (tamano, color, fuente) => {
    doc.setFont(FUENTES[fuente]);
    doc.setFontSize(tamano);
    doc.setTextColor(color);
  };
  const ancho = (t) => doc.getTextWidth(t);
  const recortar = (t, max) => {
    if (max == null || ancho(t) <= max) return t;
    let s = t;
    while (s.length && ancho(s + '…') > max) s = s.slice(0, -1);
    return s + '…';
  };
  const escribir = (texto, x, linea, tamano, color, fuente, alineacion = 'left', anchoMax = null, espaciado = 0) => {
    preparar(tamano, color, fuente);
    const visible = recortar(String(texto), anchoMax);
    const opciones = { align: alineacion, baseline: 'alphabetic' };
    if (espaciado) {
      opciones.charSpace = espaciado * tamano;
      // Con espaciado, jsPDF no lo tiene en cuenta al alinear a la derecha.
      if (alineacion === 'right') {
        opciones.align = 'left';
        x -= ancho(visible) + opciones.charSpace * (visible.length - 1);
      }
    }
    doc.text(visible, x, linea, opciones);
  };
  const relleno = (c) => doc.setFillColor(c);
  const rect = (x, yy, w, h, r, c) => {
    relleno(c);
    if (r > 0) doc.roundedRect(x, yy, w, h, r, r, 'F');
    else doc.rect(x, yy, w, h, 'F');
  };
  const linea = (x0, y0, x1, y1, c, grosor, discontinua = null) => {
    doc.setDrawColor(c);
    doc.setLineWidth(grosor);
    doc.setLineDashPattern(discontinua ?? [], 0);
    doc.line(x0, y0, x1, y1);
    doc.setLineDashPattern([], 0);
  };

  const dibujarPie = () => {
    const l = ALTO - 28;
    linea(MARGEN, l - 12, ANCHO - MARGEN, l - 12, C.divisor, 0.6);
    escribir('Generado con Gestión de Socios', MARGEN, l, 8, C.textoSuave, 'normal');
    escribir(`Página ${numeroPagina}`, ANCHO - MARGEN, l, 8, C.textoSuave, 'media', 'right');
  };
  const nuevaPagina = () => {
    dibujarPie();
    doc.addPage([ANCHO, ALTO]);
    numeroPagina++;
    y = MARGEN;
  };
  const asegurar = (alto, alEmpezarOtra = null) => {
    if (y + alto <= ALTO - MARGEN_PIE) return;
    nuevaPagina();
    alEmpezarOtra?.();
  };

  // --- Cabecera ---
  {
    const alto = 158;
    doc.addImage(fondoCabecera(ANCHO - 2 * MARGEN, alto), 'PNG', MARGEN, y, ANCHO - 2 * MARGEN, alto);
    const izq = MARGEN + 22, der = ANCHO - MARGEN - 22;
    escribir('INFORME DE SOCIOS', izq, y + 30, 8.5, C.etiquetaNeto, 'fuerte', 'left', null, 0.12);
    const generado = `${ahora.getDate()}/${ahora.getMonth() + 1}/${ahora.getFullYear()}, ${ahora.getHours()}:${String(ahora.getMinutes()).padStart(2, '0')}`;
    escribir(`Generado el ${generado}`, der, y + 30, 8.5, C.etiquetaClara, 'normal', 'right');
    escribir(tituloPeriodo(alcance), izq, y + 52, 15, C.blanco, 'fuerte', 'left', der - izq);
    escribir('Neto', izq, y + 82, 9.5, C.etiquetaNeto, 'media');
    escribir(moneda(historico.neto), izq, y + 114, 30, C.blanco, 'negrita');
    const semanas = historico.semanas.length;
    const pastillas = [`${historico.hechos} de ${historico.registrados} socios hechos`];
    if (semanas > 1) {
      pastillas.push(TEXTOS.semanas(semanas));
      pastillas.push(`Media ${moneda(redondearCentimos(historico.neto / semanas))} / semana`);
    }
    if (historico.descontado !== 0) pastillas.push(`Descontado ${descontadoConSigno(historico.descontado)}`);
    let x = izq;
    for (const p of pastillas) {
      preparar(8.5, C.blanco, 'fuerte');
      const w = ancho(p) + 18;
      doc.setGState(new doc.GState({ opacity: 0.2 }));
      rect(x, y + 128, w, 18, 9, C.blanco);
      doc.setGState(new doc.GState({ opacity: 1 }));
      doc.text(p, x + 9, y + 128 + 12.5);
      x += w + 6;
    }
    y += alto + 14;
  }

  // --- Las cifras que forman el neto ---
  {
    const manual = historico.semanas.some((s) => s.resumen.esBonoRegaloAplicado);
    const cifras = [
      ['Bruto', eurosCompacto(historico.bruto), C.grisFondo, C.texto],
      ['Base ×2', eurosCompacto(historico.base, '+'), C.azulFondo, C.azul],
      [manual ? 'Bonus (con ajustes)' : 'Bonus', eurosCompacto(historico.bonus, '+'), C.ambarFondo, C.ambar],
      ['IRPF retenido', eurosCompacto(historico.irpf, '-'), C.rojoFondo, C.rojo]
    ];
    const sep = 10;
    const w = (ANCHO - 2 * MARGEN - sep * (cifras.length - 1)) / cifras.length;
    cifras.forEach(([etiqueta, valor, fondo, col], i) => {
      const x = MARGEN + i * (w + sep);
      rect(x, y, w, 52, 12, fondo);
      escribir(etiqueta, x + 12, y + 19, 8.5, C.textoSuave, 'media', 'left', w - 24);
      escribir(valor, x + 12, y + 39, 14, col, 'negrita', 'left', w - 24);
    });
    y += 52;
    const unica = historico.semanas.length === 1 ? historico.semanas[0] : null;
    const bono = unica ? datos.bonosRegalo.find((b) => b.semanaKey === unica.semanaKey && b.activo) : null;
    const motivo = bono?.nota?.trim() && bono.nota.trim() !== 'Ajustado a mano' ? bono.nota.trim() : null;
    if (motivo) {
      escribir(`Bonus ajustado a mano: «${motivo}»`, MARGEN + 2, y + 16, 9, C.textoSuave, 'normal', 'left', ANCHO - 2 * MARGEN);
      y += 16;
    }
    y += 22;
  }

  // --- Evolución del neto ---
  if (historico.semanas.length > 1) {
    const semanas = historico.semanas;
    const hasta = semanas.reduce((m, s) => (s.semanaKey > m ? s.semanaKey : m), semanas[0].semanaKey);
    const porSemanas = evolucionSemanas(semanas, hasta, 1);
    const enSemanas = porSemanas.length <= MAX_BARRAS_SEMANAS;
    const barras = enSemanas
      ? porSemanas.map((b) => [`S${Number(b.clave.split('-W')[1])}`, b.neto])
      : evolucionPeriodos(agruparPorPeriodo(semanas, 'MES'), 'MES', hasta, 1).slice(-MAX_BARRAS_MESES)
        .map((b) => [MESES_CORTOS[Number(b.clave.split('-')[1]) - 1] ?? b.clave, b.neto]);
    const alto = 150;
    asegurar(alto + 40);
    escribir('Evolución del neto', MARGEN + 2, y + 12, 12, C.texto, 'fuerte');
    escribir(enSemanas ? 'Por semanas' : 'Por meses', ANCHO - MARGEN - 2, y + 12, 9, C.textoSuave, 'normal', 'right');
    y += 24;
    doc.setDrawColor(C.divisor);
    doc.setLineWidth(0.8);
    doc.roundedRect(MARGEN, y, ANCHO - 2 * MARGEN, alto, 14, 14, 'S');
    const escala = escalaPara(barras.map((b) => b[1]));
    const izq = MARGEN + 14;
    const derBarras = ANCHO - MARGEN - 50;
    const arriba = y + 22;
    const abajo = y + alto - 24;
    const altoUtil = abajo - arriba;
    const yDe = (v) => arriba + altoUtil * ((escala.techo - v) / escala.rango);
    for (const v of escala.guias) {
      const yg = yDe(v);
      linea(izq, yg, derBarras, yg, C.divisor, 0.8, escala.esCero(v) ? null : [3, 3]);
      escribir(eurosCompacto(v), ANCHO - MARGEN - 12, yg + 3, 7.5, C.textoSuave, 'normal', 'right');
    }
    const hueco = (derBarras - izq) / barras.length;
    const anchoBarra = Math.min(18, hueco * 0.62);
    let maximo = -1;
    barras.forEach((b, i) => {
      if (maximo < 0 || b[1] > barras[maximo][1]) maximo = i;
    });
    if (maximo >= 0 && barras[maximo][1] <= 0) maximo = -1;
    preparar(7.5, C.textoSuave, 'media');
    const anchoEtiqueta = Math.max(...barras.map((b) => ancho(b[0]))) + 6;
    const paso = Math.max(1, Math.ceil(anchoEtiqueta / hueco));
    barras.forEach(([etiqueta, neto], i) => {
      const centro = izq + hueco * (i + 0.5);
      const base = yDe(0);
      const punta = yDe(neto);
      const col = neto < 0 ? C.rojo : i === maximo || i === barras.length - 1 ? C.verde : C.verdeSuave;
      if (Math.abs(punta - base) > 0.5) {
        const top = Math.min(punta, base);
        const h = Math.abs(punta - base);
        const r = Math.min(3.5, h / 2);
        rect(centro - anchoBarra / 2, top, anchoBarra, h, r, col);
        // La base recta, pegada a la línea del cero.
        if (neto >= 0) rect(centro - anchoBarra / 2, top + h - r, anchoBarra, r, 0, col);
        else rect(centro - anchoBarra / 2, top, anchoBarra, r, 0, col);
      }
      if ((barras.length - 1 - i) % paso === 0) {
        escribir(etiqueta, centro, abajo + 14, 7.5, i === barras.length - 1 ? C.texto : C.textoSuave, 'media', 'center');
      }
      if (i === maximo) escribir(eurosCompacto(neto), centro, punta - 5, 8, C.texto, 'fuerte', 'center');
    });
    y += alto + 26;
  }

  // --- El año mes a mes, para la renta ---
  if (alcance.tipo === 'ANIO') {
    const meses = agruparPorPeriodo(historico.semanas, 'MES').sort((a, b) => a.mes - b.mes);
    if (meses.length) {
      asegurar(24 + 20 + ALTO_FILA * (meses.length + 1) + 40);
      escribir('Mes a mes', MARGEN + 2, y + 12, 12, C.texto, 'fuerte');
      y += 24;
      const cab = y + 12;
      escribir('MES', COL_SOCIO, cab, 7.5, C.textoSuave, 'fuerte', 'left', null, 0.08);
      [['SOCIOS', COL_MES_SOCIOS], ['BRUTO', COL_MES_BRUTO], ['IRPF', COL_MES_IRPF], ['DESCONTADO', COL_MES_DESCONTADO], ['NETO', COL_MES_NETO]]
        .forEach(([t, x]) => escribir(t, x, cab, 7.5, C.textoSuave, 'fuerte', 'right', null, 0.08));
      y += 20;
      const filaMes = (nombre, socios, bruto, irpf, descontado, neto, total) => {
        if (total) rect(MARGEN, y + 2, ANCHO - 2 * MARGEN, ALTO_FILA + 2, 8, C.grisFondo);
        const l = y + 14;
        const f = total ? 'negrita' : 'media';
        escribir(nombre, COL_SOCIO, l, 9, C.texto, f);
        escribir(String(socios), COL_MES_SOCIOS, l, 9, C.textoSuave, f, 'right');
        escribir(moneda(bruto), COL_MES_BRUTO, l, 9, C.texto, f, 'right');
        escribir(moneda(irpf), COL_MES_IRPF, l, 9, C.rojo, f, 'right');
        escribir(descontado !== 0 ? descontadoConSigno(descontado) : '—', COL_MES_DESCONTADO, l, 9, C.textoSuave, f, 'right');
        escribir(moneda(neto), COL_MES_NETO, l, 9, total ? C.verdeTexto : C.texto, 'negrita', 'right');
        y += ALTO_FILA + (total ? 4 : 0);
      };
      for (const m of meses) {
        const nombre = MESES[m.mes - 1][0].toUpperCase() + MESES[m.mes - 1].slice(1);
        filaMes(nombre, m.sociosHechos, m.bruto, m.irpf, m.descontado, m.neto, false);
        linea(COL_SOCIO, y, ANCHO - MARGEN - 6, y, C.divisor, 0.5);
      }
      filaMes('Total del año', historico.hechos, historico.bruto, historico.irpf, historico.descontado, historico.neto, true);
      y += 6;
      const nota = `El bruto y el IRPF son los calculados en la app (IRPF del ${porcentaje(IRPF)} sobre el bruto). Para la declaración de la renta, compáralos con el certificado de retenciones de la empresa: si te descontaron devoluciones, sus cifras pueden ser algo menores.`;
      preparar(8.5, C.textoSuave, 'normal');
      const lineas = [];
      for (const palabra of nota.split(' ')) {
        const ultima = lineas[lineas.length - 1];
        if (ultima != null && ancho(`${ultima} ${palabra}`) <= ANCHO - 2 * MARGEN - 4) lineas[lineas.length - 1] = `${ultima} ${palabra}`;
        else lineas.push(palabra);
      }
      for (const l of lineas) {
        escribir(l, MARGEN + 2, y + 11, 8.5, C.textoSuave, 'normal');
        y += 12;
      }
      y += 22;
    }
  }

  // --- Los socios de cada semana ---
  const cabeceraTabla = () => {
    const l = y + 12;
    [['SOCIO', COL_SOCIO], ['DÍA', COL_DIA], ['CUOTA', COL_CUOTA], ['ESTADO', COL_ESTADO], ['NOTAS', COL_NOTAS]]
      .forEach(([t, x]) => escribir(t, x, l, 7.5, C.textoSuave, 'fuerte', 'left', null, 0.08));
    y += 20;
  };
  const banda = (semana, continuacion = false) => {
    const r = semana.resumen;
    rect(MARGEN, y, ANCHO - 2 * MARGEN, ALTO_SEMANA - 4, 8, C.grisFondo);
    const fechas = rangoCortoConAnio(semana.semanaKey);
    escribir(continuacion ? `${fechas} (continuación)` : fechas, MARGEN + 10, y + 15, 9.5, C.texto, 'fuerte');
    escribir(`${r.totalSociosHechos} de ${r.totalSociosRegistrados} hechos  ·  Neto ${moneda(r.totalNeto)}`, ANCHO - MARGEN - 10, y + 15, 8.5, C.textoSuave, 'media', 'right');
    y += ALTO_SEMANA;
  };
  asegurar(80);
  escribir('Socios por semana', MARGEN + 2, y + 12, 12, C.texto, 'fuerte');
  y += 24;
  cabeceraTabla();
  for (const semana of historico.semanas) {
    const socios = (sociosPorSemana.get(semana.semanaKey) ?? []).toSorted((a, b) => a.dia - b.dia || a.timestamp - b.timestamp);
    asegurar(ALTO_SEMANA + ALTO_FILA, cabeceraTabla);
    banda(semana);
    if (!socios.length) {
      escribir('Sin socios: solo el bonus o lo cobrado', COL_SOCIO, y + 13, 8.5, C.textoSuave, 'normal');
      y += ALTO_FILA;
    }
    socios.forEach((socio, i) => {
      asegurar(ALTO_FILA, () => {
        cabeceraTabla();
        banda(semana, true);
      });
      const l = y + 13;
      escribir(nombreVisible(socio), COL_SOCIO, l, 9, C.texto, 'media', 'left', COL_DIA - COL_SOCIO - 8);
      escribir(diaPorId(socio.dia).completo, COL_DIA, l, 9, C.textoSuave, 'normal', 'left', COL_CUOTA - COL_DIA - 8);
      escribir(eurosCompacto(socio.colaboracion), COL_CUOTA, l, 9, C.texto, 'fuerte');
      const [estado, fondo, col] = socio.hecho ? ['Hecho', C.verdeFondo, C.verdeTexto] : ['Pendiente', C.ambarFondo, C.ambar];
      preparar(7.5, col, 'fuerte');
      rect(COL_ESTADO, y + 4, ancho(estado) + 14, 12, 6, fondo);
      escribir(estado, COL_ESTADO + 7, y + 12.6, 7.5, col, 'fuerte');
      escribir(socio.notas.replace(/\n/g, ' '), COL_NOTAS, l, 8.5, C.textoSuave, 'normal', 'left', ANCHO - MARGEN - COL_NOTAS - 6);
      if (i !== socios.length - 1) linea(COL_SOCIO, y + ALTO_FILA, ANCHO - MARGEN - 6, y + ALTO_FILA, C.divisor, 0.5);
      y += ALTO_FILA;
    });
    y += 8;
  }
  dibujarPie();
  return doc.output('blob');
}

/** El fondo de la cabecera (degradado verde con esquinas de 18), como imagen nítida. */
function fondoCabecera(w, h) {
  const escala = 4;
  const c = document.createElement('canvas');
  c.width = w * escala;
  c.height = h * escala;
  const ctx = c.getContext('2d');
  ctx.scale(escala, escala);
  const g = ctx.createLinearGradient(0, 0, w, h);
  g.addColorStop(0, C.verdeCabecera);
  g.addColorStop(1, C.verdeCabeceraOscuro);
  ctx.fillStyle = g;
  ctx.beginPath();
  if (ctx.roundRect) ctx.roundRect(0, 0, w, h, 18);
  else ctx.rect(0, 0, w, h);
  ctx.fill();
  return c.toDataURL('image/png');
}
