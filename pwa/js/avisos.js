// Avisos.kt y TipoAviso.kt: una tarjeta flotante con el color de su tipo, título y detalle; se quita tocándola, con
// su ✕ o deslizándola a un lado; una barra fina muestra lo que le queda, y uno nuevo sustituye al que se ve.
import { icono } from './iconos.js';
import { esc } from './formato.js';
import { activas, CORTA_MS, escalarSuave, haptica, latido, MEDIA_MS } from './movimiento.js';
import { hayPanelAbierto } from './paneles.js';

const TIPOS = {
  EXITO: { duracion: 2500, icono: 'check_circle', tono: 'verde' },
  INFO: { duracion: 3500, icono: 'info', tono: 'azul' },
  PENDIENTE: { duracion: 2500, icono: 'schedule', tono: 'pendiente' },
  BORRADO: { duracion: 5000, icono: 'delete', tono: 'rojo' },
  ERROR: { duracion: 5500, icono: 'error', tono: 'rojo' },
  NOVEDAD: { duracion: 10000, icono: 'system_update', tono: 'azul' }
};
const COLOR_TONO = { verde: '--green-text-soft', azul: '--blue-text-soft', pendiente: '--orange-pending', rojo: '--red-text' };
const TIEMPO_DETALLE_MS = 1000;
const MARGEN_SALIDA_MS = 400;

let capa = null;
let anuncio = null;
let visible = null;

/**
 * Los avisos van siempre en el mismo sitio: una región con nombre que es un popover manual. Así se ven también
 * encima de un panel abierto (los paneles son <dialog> en la capa superior, donde z-index no cuenta) y no se
 * cierran al tocar otra cosa.
 */
function crearCapa() {
  capa = document.createElement('div');
  capa.className = 'capa-avisos';
  capa.setAttribute('popover', 'manual');
  capa.setAttribute('role', 'region');
  capa.setAttribute('aria-label', 'Avisos');
  // Para los navegadores sin ariaNotify(): una región en vivo que ya existe antes de escribir en ella.
  anuncio = document.createElement('div');
  anuncio.className = 'sr';
  anuncio.setAttribute('aria-live', 'polite');
  capa.append(anuncio);
  document.body.append(capa);
}

/** Pone la capa encima de todo lo que haya en la capa superior (un panel abierto después que ella, por ejemplo). */
function traerDelante() {
  if (capa.matches(':popover-open')) capa.hidePopover();
  capa.showPopover();
}

/** Lee el aviso a quien use un lector de pantalla: solo su texto, y si tiene un botón, que hay acciones. */
function anunciar(titulo, detalle, conAccion) {
  const texto = [titulo, detalle].filter(Boolean).join('. ') + (conAccion ? '. Acciones disponibles' : '');
  if (typeof document.ariaNotify === 'function') {
    document.ariaNotify(texto);
    return;
  }
  anuncio.textContent = '';
  requestAnimationFrame(() => (anuncio.textContent = texto));
}

/** Encima del botón de "Nuevo socio" en la pantalla principal; abajo del todo si hay un panel abierto. */
function colocar(enPanel) {
  capa.style.bottom = enPanel ? '0px' : 'calc(20px + 56px + var(--seguro-abajo))';
  capa.style.paddingBottom = enPanel ? 'calc(8px + var(--seguro-abajo))' : '8px';
}

/**
 * Muestra [titulo] (y [detalle] debajo) con el aspecto de [tipo]. Con [accion] añade un botón que la ejecuta y
 * cierra el aviso.
 */
export function mostrarAviso(titulo, tipo = 'INFO', detalle = null, accion = null, alPulsarAccion = null) {
  if (!capa) crearCapa();
  const enPanel = hayPanelAbierto();
  const t = TIPOS[tipo] ?? TIPOS.INFO;
  const duracion = t.duracion + (detalle ? TIEMPO_DETALLE_MS : 0);
  const reutilizable = visible && !visible.cerrando && visible.enPanel === enPanel && performance.now() < visible.fin - MARGEN_SALIDA_MS;
  const actual = reutilizable ? visible : crear(enPanel);
  colocar(enPanel);
  traerDelante();
  actual.fin = performance.now() + duracion;
  pintar(actual, titulo, detalle, t, accion, alPulsarAccion);
  anunciar(titulo, detalle, !!(accion && alPulsarAccion));
  if (tipo === 'ERROR') haptica.rechazar();
  if (reutilizable) {
    latido(actual.placa);
    cuentaAtras(actual, duracion);
  } else {
    // El icono entra con un muelle cuando la tarjeta ya ha llegado.
    actual.placa.style.transform = 'scale(0.4)';
    setTimeout(() => {
      escalarSuave(actual.placa, 1);
      cuentaAtras(actual, duracion);
    }, activas() ? 250 : 0);
  }
  clearTimeout(actual.temporizador);
  actual.temporizador = setTimeout(() => cerrar(actual), duracion);
}

function crear(enPanel) {
  if (visible && !visible.cerrando) cerrar(visible);
  const el = document.createElement('div');
  el.className = `aviso${enPanel ? '' : ' flotante'}`;
  el.innerHTML = `
    <div class="tarjeta-aviso">
      <div class="fila-aviso">
        <div class="franja-aviso"></div>
        <div class="placa-aviso"><span class="icono"></span></div>
        <div class="columna-aviso">
          <div class="titulo-aviso dos-lineas"></div>
          <div class="detalle-aviso dos-lineas oculto"></div>
        </div>
        <button class="accion-aviso oculto" type="button"></button>
        <button class="icono-toque cerrar-aviso" type="button" aria-label="Cerrar el aviso"><span class="icono">${icono('close')}</span></button>
      </div>
      <div class="barra-tiempo"></div>
    </div>`;
  const aviso = {
    el,
    tarjeta: el.querySelector('.tarjeta-aviso'),
    placa: el.querySelector('.placa-aviso'),
    barra: el.querySelector('.barra-tiempo'),
    enPanel,
    cerrando: false,
    fin: 0,
    temporizador: 0
  };
  aviso.tarjeta.addEventListener('click', (e) => {
    if (e.target.closest('.accion-aviso')) return;
    cerrar(aviso);
  });
  permitirDeslizar(aviso);
  pausarConFoco(aviso);
  capa.append(el);
  if (activas()) el.classList.add('entrando');
  visible = aviso;
  return aviso;
}

function pintar(aviso, titulo, detalle, t, accion, alPulsarAccion) {
  const color = `var(${COLOR_TONO[t.tono]})`;
  aviso.el.querySelector('.franja-aviso').style.background = color;
  aviso.barra.style.background = color;
  aviso.placa.className = `placa-aviso tono-${t.tono}`;
  aviso.placa.querySelector('.icono').innerHTML = icono(t.icono);
  aviso.el.querySelector('.titulo-aviso').textContent = titulo;
  // Los motivos de los errores pueden venir en minúscula.
  const textoDetalle = detalle ? detalle.charAt(0).toUpperCase() + detalle.slice(1) : '';
  const elDetalle = aviso.el.querySelector('.detalle-aviso');
  elDetalle.textContent = textoDetalle;
  elDetalle.classList.toggle('oculto', !textoDetalle.trim());
  aviso.tarjeta.setAttribute('aria-label', [titulo, textoDetalle].filter(Boolean).join('. '));
  const boton = aviso.el.querySelector('.accion-aviso');
  boton.classList.toggle('oculto', !(accion && alPulsarAccion));
  boton.innerHTML = esc(accion ?? '');
  boton.onclick = accion && alPulsarAccion ? () => {
    // Primero se cierra este, así el aviso que traiga la acción sale aparte.
    cerrar(aviso);
    alPulsarAccion();
  } : null;
}

/** La barra de abajo se vacía en lo que le queda al aviso. */
function cuentaAtras(aviso, duracion) {
  aviso.animacionBarra?.cancel();
  aviso.barra.classList.toggle('oculto', !activas());
  if (!activas()) return;
  aviso.animacionBarra = aviso.barra.animate([{ transform: 'scaleX(1)' }, { transform: 'scaleX(0)' }], { duration: duracion, easing: 'linear', fill: 'forwards' });
}

function cerrar(aviso) {
  if (aviso.cerrando) return;
  aviso.cerrando = true;
  clearTimeout(aviso.temporizador);
  if (visible === aviso) visible = null;
  // Si el foco estaba en el aviso (se llegó con el teclado), vuelve a donde estaba antes.
  if (aviso.el.contains(document.activeElement)) aviso.focoPrevio?.focus?.({ preventScroll: true });
  const quitar = () => {
    if (!aviso.el.isConnected) return;
    aviso.el.remove();
    if (!capa.querySelector('.aviso') && capa.matches(':popover-open')) capa.hidePopover();
  };
  if (!activas()) return quitar();
  if (aviso.deslizada) return quitar();
  aviso.el.classList.remove('entrando');
  aviso.el.classList.add('saliendo');
  aviso.el.addEventListener('animationend', quitar, { once: true });
  setTimeout(quitar, 400);
}

/**
 * Mientras el foco está en el aviso (con el teclado o un lector de pantalla), no se va solo: su tiempo y su barra se
 * paran y siguen al salir el foco. El aviso nunca se lleva el foco al aparecer.
 */
function pausarConFoco(aviso) {
  let restante = 0;
  aviso.el.addEventListener('focusin', (e) => {
    if (aviso.cerrando || restante) return;
    if (!aviso.el.contains(e.relatedTarget)) aviso.focoPrevio = e.relatedTarget;
    restante = Math.max(0, aviso.fin - performance.now());
    clearTimeout(aviso.temporizador);
    aviso.animacionBarra?.pause();
  });
  aviso.el.addEventListener('focusout', (e) => {
    if (aviso.cerrando || aviso.el.contains(e.relatedTarget) || !restante) return;
    const queda = Math.max(restante, 1500);
    restante = 0;
    aviso.fin = performance.now() + queda;
    aviso.animacionBarra?.play();
    clearTimeout(aviso.temporizador);
    aviso.temporizador = setTimeout(() => cerrar(aviso), queda);
  });
}

/** Arrastrarla a un lado: si se suelta lejos o se lanza con fuerza, sale y se cierra; si no, vuelve. */
function permitirDeslizar(aviso) {
  const t = aviso.tarjeta;
  let x0 = 0, y0 = 0, arrastrando = false, ultimos = [];
  t.addEventListener('pointerdown', (e) => {
    x0 = e.clientX;
    y0 = e.clientY;
    arrastrando = false;
    ultimos = [{ x: e.clientX, t: e.timeStamp }];
    t.getAnimations().forEach((a) => a.cancel());
    t.setPointerCapture?.(e.pointerId);
  });
  t.addEventListener('pointermove', (e) => {
    if (!(e.buttons & 1) && e.pointerType === 'mouse') return;
    const dx = e.clientX - x0;
    if (!arrastrando && Math.abs(dx) > 8 && Math.abs(dx) > Math.abs(e.clientY - y0)) arrastrando = true;
    if (!arrastrando) return;
    ultimos.push({ x: e.clientX, t: e.timeStamp });
    if (ultimos.length > 5) ultimos.shift();
    t.style.transform = `translateX(${dx}px)`;
    t.style.opacity = String(1 - Math.min(1, Math.abs(dx) / t.offsetWidth) * 0.7);
  });
  const fin = (e) => {
    if (!arrastrando) return;
    e.stopPropagation();
    const dx = e.clientX - x0;
    const a = ultimos[0], b = ultimos[ultimos.length - 1];
    const vx = b && a && b.t > a.t ? ((b.x - a.x) / (b.t - a.t)) * 1000 : 0;
    const lanzada = Math.abs(vx) > 200 && Math.sign(vx) === Math.sign(dx);
    if (Math.abs(dx) > t.offsetWidth * 0.3 || lanzada) {
      aviso.deslizada = true;
      t.animate([{ transform: t.style.transform, opacity: t.style.opacity }, { transform: `translateX(${Math.sign(dx) * t.offsetWidth}px)`, opacity: 0 }],
        { duration: CORTA_MS, easing: 'cubic-bezier(0.32, 0.72, 0, 1)', fill: 'forwards' }).onfinish = () => cerrar(aviso);
    } else {
      t.animate([{ transform: t.style.transform, opacity: t.style.opacity }, { transform: 'none', opacity: 1 }],
        { duration: MEDIA_MS, easing: 'cubic-bezier(0.32, 0.72, 0, 1)' });
      t.style.transform = '';
      t.style.opacity = '';
    }
    // El clic que sigue a un arrastre no cierra el aviso.
    t.addEventListener('click', (ev) => ev.stopImmediatePropagation(), { capture: true, once: true });
    setTimeout(() => (arrastrando = false), 0);
  };
  t.addEventListener('pointerup', fin);
  t.addEventListener('pointercancel', fin);
  // Que deslizarlo no cambie además de semana.
  t.dataset.arrastreHorizontal = 'true';
}
