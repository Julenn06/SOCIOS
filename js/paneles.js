// PanelInferior.kt y los diálogos de Material: paneles que suben desde abajo ya desplegados (con el asa, se cierran
// arrastrándolos hacia abajo, tocando fuera o con el botón atrás) y diálogos centrados. Lo de detrás se desenfoca.
import { icono } from './iconos.js';
import { esc } from './formato.js';
import { activas, haptica } from './movimiento.js';

/** Paneles y diálogos abiertos, de abajo arriba. El botón atrás cierra el de arriba. */
const pila = [];
const abiertos = new Map();
let saltar = 0;
let zSiguiente = 40;

window.addEventListener('popstate', () => {
  if (saltar > 0) {
    saltar--;
    return;
  }
  const arriba = pila[pila.length - 1];
  if (arriba) arriba.cancelar(true);
});

function apilar(capa) {
  pila.push(capa);
  history.pushState({ socios: pila.length }, '');
}

function desapilar(capa, desdeAtras) {
  const i = pila.indexOf(capa);
  if (i < 0) return;
  pila.splice(i, 1);
  if (!desdeAtras) {
    saltar++;
    history.back();
  }
}

export const hayPanelAbierto = () => pila.length > 0;
export const estaAbierto = (tag) => abiertos.has(tag);

/** Cierra todo lo abierto (por ejemplo, al bloquear la app no hace falta: queda debajo). */
export function cerrarTodo() {
  [...pila].reverse().forEach((c) => c.cerrar());
}

/**
 * Abre un panel inferior, salvo que ya haya uno abierto con [tag]. [completa]: ocupa toda la altura (paneles con los
 * botones abajo o con contenido que cambia de tamaño). [construir] recibe el panel y rellena su contenido; puede
 * devolver una función que se llama al cerrarlo. [alCancelar]: al cerrarlo el usuario (fuera, atrás, arrastre o ✕).
 */
export function abrirHoja({ tag, completa = false, clase = '', construir, alCancelar = null, conBarra = false }) {
  if (tag && abiertos.has(tag)) return abiertos.get(tag);
  const z = (zSiguiente += 2);
  const velo = document.createElement('div');
  velo.className = 'velo';
  velo.style.zIndex = z;
  const hoja = document.createElement('section');
  hoja.className = `hoja ${completa ? 'completa' : ''} ${clase}`;
  hoja.style.zIndex = z + 1;
  hoja.setAttribute('role', 'dialog');
  hoja.setAttribute('aria-modal', 'true');
  hoja.innerHTML = `<div class="hoja-scroll${conBarra ? ' sin-abajo' : ''}"><div class="asa" aria-hidden="true"></div></div>`;
  const scroll = hoja.querySelector('.hoja-scroll');
  document.body.append(velo, hoja);

  let alCerrarContenido = null;
  let cerrado = false;
  const panel = {
    el: hoja,
    scroll,
    tag,
    cerrar: () => cerrar(false, false),
    cancelar: (desdeAtras = false) => cerrar(true, desdeAtras),
    /** Para los paneles con botones fijos abajo, fuera del scroll. */
    ponerBarra(barra) {
      barra.classList.add('barra-botones');
      hoja.append(barra);
    }
  };

  function cerrar(cancelado, desdeAtras) {
    if (cerrado) return;
    cerrado = true;
    abiertos.delete(tag);
    desapilar(panel, desdeAtras);
    if (cancelado) alCancelar?.();
    alCerrarContenido?.();
    hoja.classList.remove('abierta');
    velo.classList.remove('visible');
    hoja.style.transform = '';
    const quitar = () => {
      hoja.remove();
      velo.remove();
    };
    if (activas()) setTimeout(quitar, 320);
    else quitar();
  }

  tocarFuera(velo, () => panel.cancelar());
  hoja.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      e.stopPropagation();
      panel.cancelar();
    }
  });
  arrastrarParaCerrar(hoja, scroll, () => panel.cancelar());

  if (tag) abiertos.set(tag, panel);
  apilar(panel);
  alCerrarContenido = construir(panel) ?? null;

  // Pegado arriba del todo: sin las esquinas redondeadas, como el panel de Material desplegado entero.
  const ajustarEsquinas = () => hoja.classList.toggle('arriba', hoja.getBoundingClientRect().height >= innerHeight - seguroArriba() - 1);
  new ResizeObserver(ajustarEsquinas).observe(hoja);

  requestAnimationFrame(() => {
    velo.classList.add('visible');
    hoja.classList.add('abierta');
  });
  return panel;
}

/**
 * Tocar fuera cierra, pero solo si el dedo también bajó sobre el velo: el clic de un toque que empezó antes de abrirse
 * el panel (el que lo abre) no debe cerrarlo nada más aparecer.
 */
function tocarFuera(velo, alCerrar) {
  let bajoAqui = false;
  velo.addEventListener('pointerdown', () => (bajoAqui = true));
  velo.addEventListener('click', () => {
    if (bajoAqui) alCerrar();
    bajoAqui = false;
  });
}

function seguroArriba() {
  const v = getComputedStyle(document.documentElement).getPropertyValue('--seguro-arriba');
  const prueba = document.createElement('div');
  prueba.style.cssText = `position:absolute;height:${v || '0px'}`;
  document.body.append(prueba);
  const alto = prueba.offsetHeight;
  prueba.remove();
  return alto;
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
    if (dy > hoja.offsetHeight * 0.25 || v > 0.8) {
      hoja.style.transform = '';
      alCerrar();
    } else {
      hoja.style.transform = '';
    }
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
 * [acciones]: de izquierda a derecha, { texto, alPulsar, rojo }. [cuerpo]: un elemento con contenido propio.
 * Devuelve { cerrar }.
 */
export function dialogo({ icono: nombreIcono = null, titulo, mensaje = null, cuerpo = null, acciones = [], alCerrar = null }) {
  const z = (zSiguiente += 2) + 20;
  const velo = document.createElement('div');
  velo.className = 'velo';
  velo.style.zIndex = z;
  const el = document.createElement('div');
  el.className = 'dialogo';
  el.style.zIndex = z + 1;
  el.setAttribute('role', 'alertdialog');
  el.setAttribute('aria-modal', 'true');
  el.innerHTML = `
    <div class="dialogo-cabecera${nombreIcono ? ' centrado' : ''}">
      ${nombreIcono ? `<div class="dialogo-icono"><span class="icono">${icono(nombreIcono)}</span></div>` : ''}
      <h2 class="dialogo-titulo">${esc(titulo)}</h2>
    </div>
    ${mensaje ? `<div class="dialogo-mensaje">${esc(mensaje)}</div>` : ''}
    <div class="dialogo-cuerpo${cuerpo ? '' : ' oculto'}"></div>
    <div class="dialogo-acciones${acciones.length ? '' : ' oculto'}"></div>`;
  if (cuerpo) el.querySelector('.dialogo-cuerpo').append(cuerpo);
  else el.querySelector('.dialogo-cuerpo').remove();
  const barra = el.querySelector('.dialogo-acciones');
  let cerrado = false;
  const capa = {
    cerrar: () => cerrar(false),
    cancelar: (desdeAtras = false) => cerrar(desdeAtras)
  };
  function cerrar(desdeAtras) {
    if (cerrado) return;
    cerrado = true;
    desapilar(capa, desdeAtras);
    el.classList.remove('abierto');
    velo.classList.remove('visible');
    alCerrar?.();
    setTimeout(() => {
      el.remove();
      velo.remove();
    }, activas() ? 250 : 0);
  }
  for (const a of acciones) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = `boton texto${a.rojo ? ' rojo' : ''}`;
    b.textContent = a.texto;
    b.addEventListener('click', () => {
      haptica.click();
      cerrar(false);
      a.alPulsar?.();
    });
    barra.append(b);
  }
  tocarFuera(velo, () => cerrar(false));
  el.addEventListener('keydown', (e) => e.key === 'Escape' && cerrar(false));
  document.body.append(velo, el);
  apilar(capa);
  requestAnimationFrame(() => {
    velo.classList.add('visible');
    el.classList.add('abierto');
    el.querySelector('.dialogo-acciones button:last-child')?.focus({ preventScroll: true });
  });
  return capa;
}

/** Lista con casillas (varias) o con círculos (una): setMultiChoiceItems / setSingleChoiceItems. */
export function dialogoLista({ titulo, opciones, marcadas, multiple, alCambiar, aceptar = null, alCerrar = null }) {
  const lista = document.createElement('div');
  lista.className = 'lista-eleccion';
  let capa = null;
  opciones.forEach((texto, i) => {
    const item = document.createElement('button');
    item.type = 'button';
    item.className = `item${marcadas[i] ? ' marcado' : ''}`;
    item.setAttribute('role', multiple ? 'checkbox' : 'radio');
    item.setAttribute('aria-checked', String(!!marcadas[i]));
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
