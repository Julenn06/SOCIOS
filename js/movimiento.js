// util/Movimiento.kt, util/NumberAnimators.kt, util/AnimationExtensions.kt y util/HapticUtils.kt.
import { moneda } from './formato.js';

export const CORTA_MS = 180;
export const MEDIA_MS = 300;
export const LARGA_MS = 450;

/** false si el usuario ha pedido menos movimiento en el sistema. */
const reducido = matchMedia('(prefers-reduced-motion: reduce)');
export const activas = () => !reducido.matches;

/** Curva de Bézier cúbica como función de 0 a 1. */
export function bezier(x1, y1, x2, y2) {
  const cx = 3 * x1, bx = 3 * (x2 - x1) - cx, ax = 1 - cx - bx;
  const cy = 3 * y1, by = 3 * (y2 - y1) - cy, ay = 1 - cy - by;
  const x = (t) => ((ax * t + bx) * t + cx) * t;
  const y = (t) => ((ay * t + by) * t + cy) * t;
  const dx = (t) => (3 * ax * t + 2 * bx) * t + cx;
  return (p) => {
    if (p <= 0) return 0;
    if (p >= 1) return 1;
    let t = p;
    for (let i = 0; i < 8; i++) {
      const err = x(t) - p;
      if (Math.abs(err) < 1e-5) break;
      const d = dx(t);
      if (Math.abs(d) < 1e-6) break;
      t -= err / d;
    }
    t = Math.min(1, Math.max(0, t));
    return y(t);
  };
}

/** La curva de la interfaz (salida_suave): arranca con decisión y se posa despacio. */
export const curva = bezier(0.32, 0.72, 0, 1);
export const fastOutSlowIn = bezier(0.4, 0, 0.2, 1);
export const lineal = (t) => t;
export const acelerar = (t) => t * t;
export const overshoot = (tension) => (t) => {
  const u = t - 1;
  return u * u * ((tension + 1) * u + tension) + 1;
};
export const frenar = (factor) => (t) => 1 - Math.pow(1 - t, 2 * factor);

/** Lleva un número de [desde] a [hasta]; devuelve { cancelar, enMarcha }. */
export function animarValor(desde, hasta, duracion, alCambiar, interpolador = curva, alTerminar = null) {
  let id = 0;
  let vivo = true;
  const inicio = performance.now();
  const paso = (ahora) => {
    if (!vivo) return;
    const t = Math.min(1, (ahora - inicio) / duracion);
    alCambiar(desde + (hasta - desde) * interpolador(t), t);
    if (t < 1) id = requestAnimationFrame(paso);
    else {
      vivo = false;
      alTerminar?.();
    }
  };
  if (!activas() || duracion <= 0) {
    alCambiar(hasta, 1);
    vivo = false;
    alTerminar?.();
  } else {
    id = requestAnimationFrame(paso);
  }
  return {
    cancelar() {
      vivo = false;
      cancelAnimationFrame(id);
    },
    get enMarcha() {
      return vivo;
    }
  };
}

// --- Muelles (SpringAnimation): responden al dedo y se pueden interrumpir sin saltos ---

const RIGIDEZ_ALTA = 10_000;
const RIGIDEZ_MEDIA = 1_500;
const RIGIDEZ_BAJA = 200;
const RIGIDEZ_MEDIA_BAJA = 400;
const SIN_REBOTE = 1;
const muelles = new WeakMap();

/** Lleva la escala de [el] a [escala] con un muelle; si ya se movía, sigue desde donde iba y con su velocidad. */
export function escalarConMuelle(el, escala, rigidez = RIGIDEZ_MEDIA, amortiguacion = SIN_REBOTE, velocidadInicial = 0) {
  if (!el) return;
  if (!activas()) {
    el.style.transform = escala === 1 ? '' : `scale(${escala})`;
    return;
  }
  let m = muelles.get(el);
  if (!m) {
    m = { valor: 1, velocidad: 0, objetivo: 1, id: 0, ultimo: 0 };
    muelles.set(el, m);
  }
  m.objetivo = escala;
  m.rigidez = rigidez;
  m.amortiguacion = amortiguacion;
  if (velocidadInicial !== 0) m.velocidad = velocidadInicial;
  if (m.id) return;
  m.ultimo = performance.now();
  const paso = (ahora) => {
    let dt = Math.min(0.064, (ahora - m.ultimo) / 1000);
    m.ultimo = ahora;
    // Pasos pequeños para que los muelles rígidos no se vuelvan inestables.
    const c = 2 * m.amortiguacion * Math.sqrt(m.rigidez);
    while (dt > 0) {
      const h = Math.min(dt, 0.002);
      const a = -m.rigidez * (m.valor - m.objetivo) - c * m.velocidad;
      m.velocidad += a * h;
      m.valor += m.velocidad * h;
      dt -= h;
    }
    if (Math.abs(m.valor - m.objetivo) < 0.0005 && Math.abs(m.velocidad) < 0.005) {
      m.valor = m.objetivo;
      m.velocidad = 0;
      m.id = 0;
      el.style.transform = m.valor === 1 ? '' : `scale(${m.valor})`;
      return;
    }
    el.style.transform = `scale(${m.valor})`;
    m.id = requestAnimationFrame(paso);
  };
  m.id = requestAnimationFrame(paso);
}

/** Hundirse al pulsar: rápido y sin rebote. */
export const hundir = (el, escala = 0.96) => escalarConMuelle(el, escala, RIGIDEZ_ALTA, SIN_REBOTE);
/** Volver al soltar: con un rebote pequeño. */
export const soltar = (el) => escalarConMuelle(el, 1, RIGIDEZ_MEDIA_BAJA, 0.5);
/** Un latido: crece un momento y se posa con un muelle; con [hacia] negativo se encoge. */
export const latido = (el, hacia = 1) => escalarConMuelle(el, 1, RIGIDEZ_BAJA, 0.35, 3 * hacia);
export const escalarSuave = (el, escala) => escalarConMuelle(el, escala, RIGIDEZ_BAJA, 0.45);

/** Se hunde bajo el dedo y vuelve con un rebote (AnimationExtensions.setupPressScaleAnimation). */
export function muellePulsar(el) {
  el.addEventListener('pointerdown', () => hundir(el));
  for (const ev of ['pointerup', 'pointercancel', 'pointerleave']) el.addEventListener(ev, () => soltar(el));
}

/** El hundido corto de animator/pulsar (97 %), para tarjetas y botones. */
export function pulsar(el) {
  el.classList.add('pulsar');
  el.addEventListener('pointerdown', () => el.classList.add('pulsado'));
  for (const ev of ['pointerup', 'pointercancel', 'pointerleave']) el.addEventListener(ev, () => el.classList.remove('pulsado'));
}

// --- Importes que cuentan hasta su valor (NumberAnimators) ---

const contadores = new WeakMap();

export function animarMoneda(el, valor, prefijo = '', decimales = 2) {
  const estado = contadores.get(el) ?? { objetivo: null, animacion: null, mostrado: 0 };
  contadores.set(el, estado);
  const enCurso = estado.animacion?.enMarcha ? estado.animacion : null;
  if (enCurso && estado.objetivo === valor) return;
  const desde = enCurso ? estado.mostrado : (estado.objetivo ?? 0);
  enCurso?.cancelar();
  estado.objetivo = valor;
  // El cero, sin signo ni decimales: "0 €" y no "-0,00 €".
  const texto = (v) => (Math.abs(v) < 0.005 ? moneda(0, 0) : moneda(v, decimales, prefijo));
  if (Math.abs(desde - valor) < 0.001) {
    estado.mostrado = valor;
    el.textContent = texto(valor);
    return;
  }
  estado.animacion = animarValor(desde, valor, 350, (v) => {
    estado.mostrado = v;
    el.textContent = texto(v);
  }, fastOutSlowIn, () => {
    estado.mostrado = valor;
    el.textContent = texto(valor);
  });
}

/** Olvida el último importe de [el]: la próxima vez crece desde 0. */
export function reiniciarMoneda(el) {
  const estado = contadores.get(el);
  estado?.animacion?.cancelar();
  contadores.set(el, { objetivo: 0, animacion: null, mostrado: 0 });
}

// --- Mostrar y ocultar con suavidad (TransitionSet con ChangeBounds y Fade) ---

const plegados = new WeakMap();

/** Muestra u oculta [el] cambiando su alto y su opacidad a la vez. */
export function desplegar(el, visible, duracion = MEDIA_MS) {
  const estabaVisible = !el.classList.contains('oculto');
  plegados.get(el)?.cancel();
  if (estabaVisible === visible && !plegados.get(el)) return;
  if (!activas() || !el.isConnected) {
    el.classList.toggle('oculto', !visible);
    return;
  }
  const altoInicio = estabaVisible ? el.getBoundingClientRect().height : 0;
  el.classList.remove('oculto');
  const altoFin = visible ? el.scrollHeight : 0;
  el.style.overflow = 'hidden';
  const animacion = el.animate(
    [
      { height: `${altoInicio}px`, opacity: estabaVisible ? 1 : 0 },
      { height: `${altoFin}px`, opacity: visible ? 1 : 0 }
    ],
    { duration: duracion, easing: 'cubic-bezier(0.32, 0.72, 0, 1)' }
  );
  plegados.set(el, animacion);
  animacion.onfinish = () => {
    plegados.delete(el);
    el.style.overflow = '';
    if (!visible) el.classList.add('oculto');
  };
  animacion.oncancel = () => {
    plegados.delete(el);
    el.style.overflow = '';
  };
}

// --- Vibración (HapticUtils) ---

const vibrar = (patron) => {
  try {
    navigator.vibrate?.(patron);
  } catch {
    // Sin vibración.
  }
};

export const haptica = {
  click: () => vibrar(6),
  toggle: () => vibrar(10),
  confirmar: () => vibrar([8, 40, 14]),
  rechazar: () => vibrar([24, 60, 24])
};
