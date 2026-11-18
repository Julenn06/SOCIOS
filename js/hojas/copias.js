// ExportImportBottomSheetDialog.kt, DialogosCopias.kt e ImportarBottomSheetDialog.kt: exportar a PDF, CSV o JSON lo
// que se elija (compartiéndolo o guardándolo) e importar una copia después de revisar lo que trae.
import * as estado from '../estado.js';
import { abrirHoja, cabeceraPanel, dialogo, dialogoLista } from '../paneles.js';
import { mostrarAviso } from '../avisos.js';
import { escribirCsv, escribirJson, leerCsv, leerJson, leerTexto } from '../copias.js';
import { generarPdf } from '../pdf.js';
import { mesConAnio, momentoRelativo, rangoCorto, rangoSemanaTexto } from '../fechas.js';
import { cantidad, enumerar, esc, TEXTOS } from '../formato.js';
import { haptica, MEDIA_MS, activas, muellePulsar } from '../movimiento.js';
import { filaFormato, grupoOpciones, html, marcar } from '../componentes.js';

const FORMATOS = {
  PDF: { extension: 'pdf', mime: 'application/pdf' },
  CSV: { extension: 'csv', mime: 'text/csv' },
  JSON: { extension: 'json', mime: 'application/json' }
};

const ALCANCES = [
  { id: 'SEMANA', icono: 'today', nombre: 'Semana' },
  { id: 'ELEGIR', icono: 'date_range', nombre: 'Elegir' },
  { id: 'ANIO', icono: 'calendar_year', nombre: 'Un año' },
  { id: 'TODO', icono: 'history', nombre: 'Todo' }
];

const anioDe = (k) => Number(k.split('-W')[0]) || null;

function nombreArchivo(formato, alcance) {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  const sello = `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
  const parte = alcance.tipo === 'HISTORICO' ? 'historico' : alcance.tipo === 'ANIO' ? String(alcance.anio) : alcance.semanas.size === 1 ? [...alcance.semanas][0] : 'semanas';
  return `socios_${parte}_${sello}.${FORMATOS[formato].extension}`;
}

async function crearArchivo(formato, alcance, datos) {
  const nombre = nombreArchivo(formato, alcance);
  const { mime } = FORMATOS[formato];
  let blob;
  if (formato === 'PDF') blob = await generarPdf(datos, alcance);
  else if (formato === 'CSV') blob = new Blob([escribirCsv(datos)], { type: `${mime};charset=utf-8` });
  else blob = new Blob([escribirJson(datos)], { type: `${mime};charset=utf-8` });
  return new File([blob], nombre, { type: mime });
}

function descargar(archivo) {
  const url = URL.createObjectURL(archivo);
  const a = document.createElement('a');
  a.href = url;
  a.download = archivo.name;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
}

/** Pregunta si compartir el archivo con otra app o guardarlo; el archivo se va preparando mientras tanto. */
function preguntarDestino(formato, alcance, datos) {
  const archivo = crearArchivo(formato, alcance, datos);
  archivo.catch(() => {});
  const fallo = (titulo, e) => mostrarAviso(titulo, 'ERROR', estado.motivoDeError(e));
  const cuerpo = html('<div style="padding:8px 20px 0"></div>');
  let capa = null;
  const compartir = filaFormato({
    iconoPlaca: 'share', tono: 'azul', titulo: 'Compartir con otra app', detalle: 'WhatsApp, correo, Drive…',
    alPulsar: async () => {
      capa.cerrar();
      try {
        const f = await archivo;
        if (navigator.canShare?.({ files: [f] })) {
          await navigator.share({ files: [f], title: `Compartir reporte ${formato}` }).catch((e) => {
            if (e?.name !== 'AbortError') throw e;
          });
        } else {
          descargar(f);
          mostrarAviso('Archivo guardado', 'EXITO', `${formato} en la carpeta de descargas`);
        }
      } catch (e) {
        fallo('No se ha podido exportar', e);
      }
    }
  });
  const guardar = filaFormato({
    iconoPlaca: 'import', tono: 'verde', titulo: 'Guardar en el dispositivo', detalle: 'Elige la carpeta donde dejarlo',
    alPulsar: async () => {
      capa.cerrar();
      try {
        const f = await archivo;
        if (window.showSaveFilePicker) {
          let destino;
          try {
            destino = await window.showSaveFilePicker({ suggestedName: f.name, types: [{ description: formato, accept: { [f.type]: [`.${FORMATOS[formato].extension}`] } }] });
          } catch (e) {
            if (e?.name === 'AbortError') return;
            throw e;
          }
          const w = await destino.createWritable();
          await w.write(f);
          await w.close();
          mostrarAviso('Archivo guardado', 'EXITO', `${formato} en la carpeta que elegiste`);
        } else {
          descargar(f);
          mostrarAviso('Archivo guardado', 'EXITO', `${formato} en la carpeta de descargas`);
        }
      } catch (e) {
        fallo('No se ha podido guardar el archivo', e);
      }
    }
  });
  guardar.style.marginTop = '10px';
  cuerpo.append(compartir, guardar);
  capa = dialogo({ titulo: `Exportar ${formato}`, cuerpo, acciones: [{ texto: 'Cancelar' }] });
}

/** Copias y exportación: pestañas Exportar e Importar. */
export function abrirCopias() {
  return abrirHoja({
    tag: 'ExportImportDialog',
    construir: (panel) => {
      const s = panel.scroll;
      s.append(html(cabeceraPanel({ placa: 'degradado-cabecera', iconoPlaca: 'backup', titulo: 'Copias y exportación', subtitulo: 'Guarda tus datos o tráelos de otro móvil' })));
      const modo = grupoOpciones([
        { id: 'EXPORTAR', html: '<span class="icono" data-icono="export"></span>Exportar' },
        { id: 'IMPORTAR', html: '<span class="icono" data-icono="import"></span>Importar' }
      ], (id) => {
        haptica.click();
        mostrarModo(id === 'IMPORTAR', true);
      });
      modo.style.marginTop = '20px';
      modo.marcar('EXPORTAR');
      s.append(modo);

      const modos = html('<div class="modos"></div>');
      const exportar = html('<div class="modo"><div class="seccion">¿Qué quieres exportar?</div><div class="alcances"></div><div class="seccion">Formato</div></div>');
      const importar = html(`
        <div class="modo invisible">
          <div class="aviso-importar"><span class="icono" data-icono="info"></span><span>Antes de importar verás un resumen de lo que entra. Los socios que ya tengas se sustituyen por los del archivo.</span></div>
          <div class="seccion">Elige el archivo</div>
        </div>`);
      modos.append(exportar, importar);
      s.append(modos);

      let alcance = 'SEMANA';
      let semanasDisponibles = estado.semanasDisponibles();
      const visible = estado.ui().semanaKey;
      let seleccion = semanasDisponibles.length ? new Set([semanasDisponibles.includes(visible) ? visible : semanasDisponibles[0]]) : null;
      const anios = () => [...new Set(semanasDisponibles.map(anioDe).filter(Boolean))].sort((a, b) => b - a);
      let anio = semanasDisponibles.length ? (anios().includes(anioDe(visible)) ? anioDe(visible) : anios()[0]) : null;
      let dialogoAbierto = null;

      const tarjetas = new Map();
      const rejilla = exportar.querySelector('.alcances');
      for (const a of ALCANCES) {
        const t = html(`
          <button class="tarjeta elegible alcance" type="button" role="radio">
            <span class="icono marca" data-icono="check_circle"></span>
            <span class="icono" data-icono="${a.icono}"></span>
            <span class="nombre dos-lineas" style="display:block">${a.nombre}</span>
            <span class="detalle dos-lineas" style="display:block"></span>
          </button>`);
        t.addEventListener('click', () => {
          haptica.click();
          elegirAlcance(a.id);
          if (a.id === 'ELEGIR') elegirSemanas();
          if (a.id === 'ANIO') elegirAnio();
        });
        muellePulsar(t);
        tarjetas.set(a.id, t);
        rejilla.append(t);
      }
      const elegirAlcance = (id) => {
        alcance = id;
        tarjetas.forEach((t, k) => marcar(t, k === id));
      };
      const detalles = () => {
        const d = (id) => tarjetas.get(id).querySelector('.detalle');
        d('SEMANA').textContent = rangoCorto(estado.ui().semanaKey);
        d('ELEGIR').textContent = seleccion?.size ? TEXTOS.semanas(seleccion.size) : 'Toca para elegir';
        d('ANIO').textContent = anio != null ? String(anio) : 'Toca para elegir';
        d('TODO').textContent = TEXTOS.semanas(semanasDisponibles.length);
      };
      elegirAlcance('SEMANA');
      detalles();

      const elegirSemanas = () => {
        if (dialogoAbierto) return;
        if (!semanasDisponibles.length) {
          mostrarAviso('No hay semanas registradas para seleccionar', 'INFO');
          return;
        }
        if (!seleccion) seleccion = new Set();
        dialogoAbierto = dialogoLista({
          titulo: 'Seleccionar semanas a exportar',
          opciones: semanasDisponibles.map((k) => `Semana ${k} (${rangoSemanaTexto(k)})`),
          marcadas: semanasDisponibles.map((k) => seleccion.has(k)),
          multiple: true,
          aceptar: 'Aceptar',
          alCambiar: (i, marcada) => {
            if (marcada) seleccion.add(semanasDisponibles[i]);
            else seleccion.delete(semanasDisponibles[i]);
            detalles();
          },
          alCerrar: () => (dialogoAbierto = null)
        });
      };
      const elegirAnio = () => {
        if (dialogoAbierto) return;
        const lista = anios();
        if (!lista.length) {
          mostrarAviso('No hay semanas registradas para seleccionar', 'INFO');
          return;
        }
        dialogoAbierto = dialogoLista({
          titulo: '¿De qué año?',
          opciones: lista.map(String),
          marcadas: lista.map((a) => a === anio),
          multiple: false,
          alCambiar: (i) => {
            anio = lista[i];
            detalles();
          },
          alCerrar: () => (dialogoAbierto = null)
        });
      };

      const alcanceElegido = () => {
        if (alcance === 'TODO') return { tipo: 'HISTORICO' };
        if (alcance === 'ANIO') return { tipo: 'ANIO', anio };
        if (alcance === 'ELEGIR') return { tipo: 'SEMANAS', semanas: new Set(seleccion ?? []) };
        return { tipo: 'SEMANAS', semanas: new Set([estado.ui().semanaKey]) };
      };

      const exportarEn = (formato) => {
        // Sin semanas elegidas no se exporta otra cosa en su lugar.
        if (alcance === 'ELEGIR' && !seleccion?.size) {
          if (semanasDisponibles.length) mostrarAviso('Elige al menos una semana para exportar', 'INFO');
          elegirSemanas();
          return;
        }
        if (alcance === 'ANIO' && anio == null) {
          elegirAnio();
          return;
        }
        const a = alcanceElegido();
        panel.cerrar();
        estado.lanzar('No se ha podido exportar', async () => {
          const datos = estado.cargarDatos(a);
          // Una semana sin socios pero con el bonus ajustado o lo cobrado también se puede exportar.
          if (!datos.socios.length && !datos.bonosRegalo.length && !datos.cobros.length) {
            mostrarAviso('Nada que exportar', 'INFO', 'No hay datos en lo que has elegido');
            return;
          }
          preguntarDestino(formato, a, datos);
        });
      };
      const pdf = filaFormato({ iconoPlaca: 'pdf', tono: 'rojo', titulo: 'Informe PDF', detalle: 'Totales y detalle, listo para imprimir o enviar', alPulsar: () => exportarEn('PDF') });
      const csv = filaFormato({ iconoPlaca: 'table', tono: 'verde', titulo: 'Hoja de cálculo CSV', detalle: 'Para abrirla en Excel o Google Sheets', alPulsar: () => exportarEn('CSV') });
      const json = filaFormato({ iconoPlaca: 'data_object', tono: 'azul', titulo: 'Copia de seguridad JSON', detalle: 'Todos los datos, para restaurarlos en la app', alPulsar: () => exportarEn('JSON') });
      csv.style.marginTop = json.style.marginTop = '10px';
      exportar.append(pdf, csv, json);

      const importarDe = (formato) => {
        panel.cerrar();
        elegirArchivo(formato);
      };
      const iJson = filaFormato({ iconoPlaca: 'data_object', tono: 'azul', titulo: 'Copia JSON', detalle: 'Restaura una copia de seguridad de la app', alPulsar: () => importarDe('JSON') });
      const iCsv = filaFormato({ iconoPlaca: 'table', tono: 'verde', titulo: 'Hoja CSV', detalle: 'Una hoja exportada desde la app, aunque la hayas editado', alPulsar: () => importarDe('CSV') });
      iCsv.style.marginTop = '10px';
      importar.append(iJson, iCsv);

      /** Exportar e importar ocupan el mismo sitio: el que entra aparece mientras el otro se desvanece. */
      function mostrarModo(esImportar, animar) {
        const entra = esImportar ? importar : exportar;
        const sale = esImportar ? exportar : importar;
        entra.getAnimations().forEach((a) => a.cancel());
        sale.getAnimations().forEach((a) => a.cancel());
        entra.classList.remove('invisible');
        entra.style.zIndex = '1';
        sale.style.zIndex = '0';
        sale.inert = true;
        entra.inert = false;
        if (!animar || !activas()) {
          sale.classList.add('invisible');
          return;
        }
        const d = 24 * (esImportar ? 1 : -1);
        const opciones = { duration: MEDIA_MS, easing: 'cubic-bezier(0.32, 0.72, 0, 1)' };
        entra.animate([{ opacity: 0, transform: `translateX(${d}px)` }, { opacity: 1, transform: 'none' }], opciones);
        sale.animate([{ opacity: 1, transform: 'none' }, { opacity: 0, transform: `translateX(${-d}px)` }], opciones).onfinish = () => sale.classList.add('invisible');
      }
      importar.inert = true;

      const dejar = estado.suscribir(() => {
        semanasDisponibles = estado.semanasDisponibles();
        detalles();
      });
      panel.el.querySelector('[data-cerrar]').addEventListener('click', () => panel.cancelar());
      return () => {
        dejar();
        dialogoAbierto?.cerrar();
      };
    }
  });
}

/** El selector de archivos; cualquier tipo, porque muchos gestores marcan los .json y .csv como desconocidos. */
function elegirArchivo(formato) {
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = formato === 'JSON' ? '.json,application/json,*/*' : '.csv,text/csv,text/comma-separated-values,*/*';
  input.style.display = 'none';
  document.body.append(input);
  input.addEventListener('change', () => {
    const archivo = input.files?.[0];
    input.remove();
    if (!archivo) return;
    estado.lanzar('No se ha podido importar', async () => {
      const texto = await leerTexto(archivo);
      const lectura = formato === 'JSON' ? leerJson(texto) : leerCsv(texto);
      const d = lectura.datos;
      if (!d.socios.length && !d.bonosRegalo.length && !d.cobros.length) {
        mostrarAviso('El archivo no tiene datos', 'ERROR', `No hay socios ni datos válidos en este ${formato}`);
        return;
      }
      abrirRevision({ ...lectura, nombreArchivo: archivo.name || null });
    });
  });
  input.click();
}

/** Lo que hará la importación, para confirmarlo antes. */
function previsualizar(lectura) {
  const existentes = estado.idsSocios();
  const d = lectura.datos;
  const sobrescritos = d.socios.filter((s) => existentes.has(s.id)).length;
  const semanas = [...new Set([...d.socios, ...d.bonosRegalo, ...d.cobros].map((x) => x.semanaKey))].sort();
  return {
    sociosNuevos: d.socios.length - sobrescritos,
    sociosSobrescritos: sobrescritos,
    bonos: d.bonosRegalo.length,
    cobros: d.cobros.length,
    irpfIgnorados: lectura.irpfIgnorados,
    descartados: lectura.descartados,
    semanas: semanas.length,
    primera: semanas[0] ?? null,
    ultima: semanas[semanas.length - 1] ?? null,
    nombreArchivo: lectura.nombreArchivo,
    fechaCopia: lectura.fechaCopia,
    get socios() {
      return this.sociosNuevos + this.sociosSobrescritos;
    }
  };
}

function abrirRevision(lectura) {
  const p = previsualizar(lectura);
  return abrirHoja({
    tag: 'ImportarDialog',
    construir: (panel) => {
      const s = panel.scroll;
      const cab = html(cabeceraPanel({ placa: 'degradado-cabecera', iconoPlaca: 'import', titulo: 'Revisa la importación', subtitulo: p.nombreArchivo ?? 'Copia de seguridad' }));
      cab.querySelector('.subtitulo-panel').classList.add('una-linea');
      s.append(cab);
      const registros = p.bonos + p.cobros;
      const total = p.socios > 0 || registros === 0
        ? `${cantidad(p.socios)} ${p.socios === 1 ? 'socio' : 'socios'}`
        : `${cantidad(registros)} ${registros === 1 ? 'registro' : 'registros'}`;
      const desde = p.primera ? mesConAnio(p.primera) : null;
      const hasta = p.ultima ? mesConAnio(p.ultima) : desde;
      const rango = desde ? (desde === hasta ? desde : `${desde} – ${hasta}`) : null;
      s.append(html(`
        <section class="hero panel" style="margin-top:20px">
          <div class="hero-interior">
            <div class="etiqueta-neto">Vas a importar</div>
            <div style="margin-top:2px;font-size:40px;font-weight:600;letter-spacing:-0.02em;color:#fff;white-space:nowrap;line-height:1.2">${esc(total)}</div>
            <div class="pastillas">
              ${p.semanas > 0 ? `<span class="pastilla-cabecera">${esc(TEXTOS.semanas(p.semanas))}</span>` : ''}
              ${rango ? `<span class="pastilla-cabecera">${esc(rango)}</span>` : ''}
            </div>
            ${p.fechaCopia ? `<div class="fecha-copia"><span class="icono" data-icono="schedule"></span>Copia hecha ${esc(momentoRelativo(p.fechaCopia))}</div>` : ''}
          </div>
        </section>`));
      s.append(html('<div class="seccion">Qué cambia en la app</div>'));
      const lineas = [
        ['Socios nuevos', 'Se añaden a su semana', p.sociosNuevos, 'person', 'verde'],
        ['Socios que ya tienes', 'Se actualizan con los datos del archivo', p.sociosSobrescritos, 'edit', 'naranja'],
        ['Bonus ajustados a mano', 'Sustituyen al de su semana', p.bonos, 'gift', 'ambar'],
        ['Cambios de IRPF', 'No se importan: el IRPF es siempre del 21,48 %', p.irpfIgnorados, 'receipt', 'azul'],
        ['Semanas con lo cobrado', 'Sustituyen a lo apuntado en la app', p.cobros, 'cobro', 'verde']
      ].filter((l) => l[2] > 0);
      const tarjeta = html('<div class="tarjeta filas-import"></div>');
      lineas.forEach(([titulo, detalle, n, ic, tono], i) => {
        if (i > 0) tarjeta.append(html('<div class="separador-import"></div>'));
        tarjeta.append(html(`
          <div class="fila-import">
            <span class="placa p40 tono-${tono}"><span class="icono" data-icono="${ic}"></span></span>
            <span class="columna-textos" style="margin-left:12px"><span class="titulo">${esc(titulo)}</span><span class="detalle">${esc(detalle)}</span></span>
            <span class="cantidad">${cantidad(n)}</span>
          </div>`));
      });
      s.append(tarjeta);
      if (p.descartados > 0) {
        s.append(html(`
          <div class="tarjeta" style="margin-top:12px;background:var(--amber-container);border-color:var(--amber-stroke)">
            <div class="fila" style="padding:14px">
              <span class="icono" data-icono="error" style="color:var(--amber-text)"></span>
              <span class="columna-textos" style="margin:0 0 0 12px">
                <span style="font-size:14.5px;font-weight:600;color:var(--amber-text)">${esc(TEXTOS.descartados(p.descartados))}</span>
                <span style="margin-top:1px;font-size:12.5px;color:var(--text-sub)">Les faltan datos o tienen valores imposibles, así que no se importan.</span>
              </span>
            </div>
          </div>`));
      }
      s.append(html('<div class="nota-import"><span class="icono" data-icono="info"></span><span>Lo que ya tienes en la app y no viene en el archivo se queda como está.</span></div>'));
      const botones = html(`
        <div class="botones" style="margin-top:20px">
          <button class="boton tonal" type="button" data-id="cancelar">Cancelar</button>
          <button class="boton relleno" type="button" data-id="importar" style="flex-grow:1.6"><span class="icono" data-icono="import"></span>Importar</button>
        </div>`);
      s.append(botones);
      botones.querySelectorAll('.boton').forEach(muellePulsar);
      botones.querySelector('[data-id="cancelar"]').addEventListener('click', () => {
        haptica.click();
        panel.cerrar();
      });
      botones.querySelector('[data-id="importar"]').addEventListener('click', () => {
        haptica.confirmar();
        panel.cerrar();
        const datos = lectura.datos;
        estado.lanzar('No se ha podido importar', async () => {
          await estado.importar(datos);
          const partes = [
            [datos.socios.length, TEXTOS.importSocios],
            [datos.bonosRegalo.length, TEXTOS.importBonus],
            [datos.cobros.length, TEXTOS.importCobros]
          ].filter(([n]) => n > 0).map(([n, f]) => f(n));
          mostrarAviso('Copia importada', 'EXITO', enumerar(partes));
        });
      });
      panel.el.querySelector('[data-cerrar]').addEventListener('click', () => panel.cancelar());
    }
  });
}
