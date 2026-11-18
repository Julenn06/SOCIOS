// PanelInferior.kt y los diálogos de Material: paneles que suben desde abajo ya desplegados (con el asa, se cierran
// arrastrándolos hacia abajo, tocando fuera, con Esc o con el gesto de atrás) y diálogos centrados.
//
// Los dos son <dialog> nativos abiertos con showModal(): el navegador pone el resto de la página inerte, contiene el
// foco (y lo devuelve al cerrar), los pinta en la capa superior y los cierra con Esc y con el gesto o botón de atrás
// del móvil. closedby="any" añade cerrarlos tocando fuera. Lo de detrás se desenfoca en su ::backdrop.
import { icono } from './iconos.js';
import { esc } from './formato.js';
import { activas, haptica } from './movimiento.js';

/** Paneles y diálogos abiertos, de abajo arriba. */
const pila = [];
const abiertos = new Map();
let siguienteId = 0;

export const hayPanelAbierto = () => pila.length > 0;
export const estaAbierto = (tag) => abiertos.has(tag);

/** Cierra todo lo abierto, de arriba abajo. */
export function cerrarTodo() {
  pila.toReversed().forEach((c) => c.cerrar());
}

// Sin closedby (Safari), tocar fuera tiene que cerrarlo a mano. El dedo tiene que bajar y subir fuera: así el toque
// que abre un panel nunca lo cierra nada más aparecer.
const sinClosedBy = !('closedBy' in HTMLDialogElement.prototype);
const fuera = (e, el) => {
  const r = el.getBoundingClientRect();
  return e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom;
};

/**
 * Prepara un <dialog> ya creado: lo abre como modal, y lo cierra animado desde el código, con Esc, con el gesto de
 * atrás o tocando fuera. Devuelve { cerrar, cancelar } y llama a [alCerrar] (con true si lo cerró el usuario).
 */
function abrirModal(el, alCerrar) {
  let cerrado = false;
  let cancelado = false;
  const capa = {
    el,
    cerrar: () => cerrar(false),
    cancelar: () => cerrar(true)
  };

  // La salida se anima siempre igual: data-closing lleva el panel a su estado cerrado (CSS) y, cuando termina la
  // transición, se cierra de verdad. Así también se anima donde aún no se puede transicionar display y overlay.
  async function cerrar(porUsuario) {
    if (cerrado || el.hasAttribute('data-closing')) return;
    cancelado = porUsuario;
    el.style.transform = '';
    el.setAttribute('data-closing', '');
    if (activas()) {
      const animaciones = el.getAnimations({ subtree: false });
      await Promise.race([
        Promise.allSettled(animaciones.map((a) => a.finished)),
        new Promise((r) => setTimeout(r, 400))
      ]);
    }
    el.close();
  }

  el.addEventListener('cancel', (e) => {
    // Esc, atrás o tocar fuera: si el navegador deja interceptarlo, se cierra animado; si no, se cierra al momento.
    if (e.cancelable) {
      e.preventDefault();
      cerrar(true);
    } else {
      cancelado = true;
    }
  });
  el.addEventListener('close', () => {
    if (cerrado) return;
    cerrado = true;
    const i = pila.indexOf(capa);
    if (i >= 0) pila.splice(i, 1);
    alCerrar?.(cancelado);
    el.removeAttribute('data-closing');
    el.remove();
  });
  if (sinClosedBy) {
    let pulsoFuera = false;
    el.addEventListener('pointerdown', (e) => (pulsoFuera = fuera(e, el)));
    el.addEventListener('pointerup', (e) => {
      if (pulsoFuera && fuera(e, el)) cerrar(true);
      pulsoFuera = false;
    });
  }

  document.body.append(el);
  pila.push(capa);
  el.showModal();
  return capa;
}

/** El nombre accesible del panel: su primer título. */
function nombrar(el) {
  const titulo = el.querySelector('h2');
  if (!titulo) return;
  titulo.id ||= `titulo-${++siguienteId}`;
  el.setAttribute('aria-labelledby', titulo.id);
}

/**
 * Abre un panel inferior, salvo que ya haya uno abierto con [tag]. [completa]: ocupa toda la altura (paneles con los
 * botones abajo o con contenido que cambia de tamaño). [construir] recibe el panel y rellena su contenido; puede
 * devolver una función que se llama al cerrarlo. [alCancelar]: al cerrarlo el usuario (fuera, atrás, Esc, arrastre o ✕).
 */
export function abrirHoja({ tag, completa = false, clase = '', construir, alCancelar = null, conBarra = false }) {
  if (tag && abiertos.has(tag)) return abiertos.get(tag);
  const hoja = document.createElement('dialog');
  hoja.className = `hoja ${completa ? 'completa' : ''} ${clase}`;
  hoja.setAttribute('closedby', 'any');
  hoja.innerHTML = `<div class="hoja-scroll${conBarra ? ' sin-abajo' : ''}"><div class="asa" aria-hidden="true"></div></div>`;
  const scroll = hoja.querySelector('.hoja-scroll');

  let alCerrarContenido = null;
  const panel = {
    el: hoja,
    scroll,
    tag,
    cerrar: () => modal.cerrar(),
    cancelar: () => modal.cancelar(),
    /** Para los paneles con botones fijos abajo, fuera del scroll. */
    ponerBarra(barra) {
      barra.classList.add('barra-botones');
      hoja.append(barra);
    }
  };

  arrastrarParaCerrar(hoja, scroll, () => panel.cancelar());
  if (tag) abiertos.set(tag, panel);
  alCerrarContenido = construir(panel) ?? null;
  nombrar(hoja);
  const modal = abrirModal(hoja, (cancelado) => {
    abiertos.delete(tag);
    if (cancelado) alCancelar?.();
    alCerrarContenido?.();
  });

  // Pegado arriba del todo: sin las esquinas redondeadas, como el panel de Material desplegado entero.
  const ajustarEsquinas = () => hoja.classList.toggle('arriba', hoja.getBoundingClientRect().top <= seguroArriba() + 1);
  new ResizeObserver(ajustarEsquinas).observe(hoja);
  return panel;
}

let altoSeguroArriba = null;
function seguroArriba() {
  if (altoSeguroArriba == null) {
    const prueba = document.createElement('div');
    prueba.style.cssText = 'position:absolute;visibility:hidden;height:var(--seguro-arriba)';
    document.body.append(prueba);
    altoSeguroArriba = prueba.offsetHeight;
    prueba.remove();
  }
  return altoSeguroArriba;
}

/** Arrastrar el panel hacia abajo (cuando su contenido está arriba del todo) lo cierra si se suelta lejos o rápido. */
function arrastrarParaCerrar(hoja, scroll, alCerrar) {
  let y0 = 0, x0 = 0, dy = 0, activo = false, posible = false, ultimo = { y: 0, t: 0 }, previo = { y: 0, t: 0 };
  hoja.addEventListener('pointerdown', (e) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    // No desde un campo de texto, un gráfico o un control que se arrastra a los lados.
    if (e.target.closest('input, textarea, canvas.grafico, .no-arrastrar')) return;
    posible = scroll.scrollTop <= 0;
    activo = false;
    y0 = e.clientY;
    x0 = e.clientX;
    dy = 0;
    ultimo = previo = { y: e.clientY, t: e.timeStamp };
  });
  hoja.addEventListener('pointermove', (e) => {
    if (!posible) return;
    const d = e.clientY - y0;
    if (!activo) {
      if (d > 8 && d > Math.abs(e.clientX - x0) && scroll.scrollTop <= 0) {
        activo = true;
        hoja.classList.add('arrastrando');
        hoja.setPointerCapture?.(e.pointerId);
      } else if (d < -4 || Math.abs(e.clientX - x0) > 12) {
        posible = false;
        return;
      } else {
        return;
      }
    }
    e.preventDefault();
    dy = Math.max(0, d);
    previo = ultimo;
    ultimo = { y: e.clientY, t: e.timeStamp };
    hoja.style.transform = `translateY(${dy}px)`;
  });
  const soltar = () => {
    if (!activo) {
      posible = false;
      return;
    }
    activo = false;
    posible = false;
    hoja.classList.remove('arrastrando');
    const v = ultimo.t > previo.t ? (ultimo.y - previo.y) / (ultimo.t - previo.t) : 0;
    if (dy > hoja.offsetHeight * 0.25 || v > 0.8) alCerrar();
    else hoja.style.transform = '';
  };
  hoja.addEventListener('pointerup', soltar);
  hoja.addEventListener('pointercancel', soltar);
  // Que el desplazamiento del navegador no se adelante al arrastre hacia abajo arriba del todo.
  hoja.addEventListener('touchmove', (e) => {
    if (activo) e.preventDefault();
  }, { passive: false });
}

// --- Cabecera común de los paneles: placa con icono, título, subtítulo y ✕ ---

export function cabeceraPanel({ placa, iconoPlaca, titulo, subtitulo = '', idSubtitulo = '', cerrar = true }) {
  return `
    <div class="cabecera-panel">
      <div class="placa-panel ${placa}"><span class="icono">${icono(iconoPlaca)}</span></div>
      <div class="columna-textos">
        <h2 class="titulo-panel">${esc(titulo)}</h2>
        <div class="subtitulo-panel"${idSubtitulo ? ` data-id="${idSubtitulo}"` : ''}>${esc(subtitulo)}</div>
      </div>
      ${cerrar ? `<button class="boton-icono cerrar" type="button" data-cerrar aria-label="Cerrar"><span class="icono">${icono('close')}</span></button>` : ''}
    </div>`;
}

// --- Diálogos (MaterialAlertDialogBuilder.mostrarConVidrio) ---

/**
 * [acciones]: de izquierda a derecha, { texto, alPulsar, rojo, segura }. La [segura] (Cancelar en un borrado) recibe
 * el foco al abrir. [cuerpo]: un elemento con contenido propio. Devuelve { cerrar }.
 */
export function dialogo({ icono: nombreIcono = null, titulo, mensaje = null, cuerpo = null, acciones = [], alCerrar = null }) {
  const el = document.createElement('dialog');
  el.className = 'dialogo';
  el.setAttribute('closedby', 'any');
  const idTitulo = `titulo-${++siguienteId}`;
  el.setAttribute('aria-labelledby', idTitulo);
  el.innerHTML = `
    <div class="dialogo-cabecera${nombreIcono ? ' centrado' : ''}">
      ${nombreIcono ? `<div class="dialogo-icono"><span class="icono">${icono(nombreIcono)}</span></div>` : ''}
      <h2 class="dialogo-titulo" id="${idTitulo}">${esc(titulo)}</h2>
    </div>
    ${mensaje ? `<div class="dialogo-mensaje" id="${idTitulo}-mensaje">${esc(mensaje)}</div>` : ''}
    <div class="dialogo-cuerpo"></div>
    <div class="dialogo-acciones${acciones.length ? '' : ' oculto'}"></div>`;
  if (mensaje) el.setAttribute('aria-describedby', `${idTitulo}-mensaje`);
  if (cuerpo) el.querySelector('.dialogo-cuerpo').append(cuerpo);
  else el.querySelector('.dialogo-cuerpo').remove();
  const barra = el.querySelector('.dialogo-acciones');
  let modal = null;
  for (const a of acciones) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = `boton texto${a.rojo ? ' rojo' : ''}`;
    b.textContent = a.texto;
    if (a.segura) b.autofocus = true;
    b.addEventListener('click', () => {
      haptica.click();
      modal.cerrar();
      a.alPulsar?.();
    });
    barra.append(b);
  }
  modal = abrirModal(el, () => alCerrar?.());
  return modal;
}

/** Lista con casillas (varias) o con círculos (una): setMultiChoiceItems / setSingleChoiceItems. */
export function dialogoLista({ titulo, opciones, marcadas, multiple, alCambiar, aceptar = null, alCerrar = null }) {
  const lista = document.createElement('div');
  lista.className = 'lista-eleccion';
  lista.setAttribute('role', multiple ? 'group' : 'radiogroup');
  let capa = null;
  opciones.forEach((texto, i) => {
    const item = document.createElement('button');
    item.type = 'button';
    item.className = `item${marcadas[i] ? ' marcado' : ''}`;
    item.setAttribute('role', multiple ? 'checkbox' : 'radio');
    item.setAttribute('aria-checked', String(!!marcadas[i]));
    if (marcadas[i] && !lista.querySelector('[autofocus]')) item.autofocus = true;
    item.innerHTML = `<span class="${multiple ? 'casilla' : 'radio'}"></span><span>${esc(texto)}</span>`;
    item.addEventListener('click', () => {
      if (multiple) {
        marcadas[i] = !marcadas[i];
        item.classList.toggle('marcado', marcadas[i]);
        item.setAttribute('aria-checked', String(marcadas[i]));
        alCambiar(i, marcadas[i]);
      } else {
        alCambiar(i);
        capa.cerrar();
      }
    });
    lista.append(item);
  });
  capa = dialogo({ titulo, cuerpo: lista, acciones: aceptar ? [{ texto: aceptar }] : [], alCerrar });
  return capa;
}
