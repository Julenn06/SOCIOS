// Ayudas comunes de las vistas dibujadas a mano: colores del tema, lienzos nítidos y texto con Geist.

// Las variables del tema valen light-dark(claro, oscuro): su texto no es un color. Se resuelven pintándolas en un
// elemento de prueba, una vez por variable y tema (las vistas animadas piden colores en cada fotograma).
let prueba = null;
const resueltos = new Map();
document.addEventListener('socios:tema', () => resueltos.clear());
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => resueltos.clear());

/** El color de una variable CSS del tema como [r, g, b, a]. */
export function color(nombre) {
  let c = resueltos.get(nombre);
  if (!c) {
    if (!prueba) {
      prueba = document.createElement('span');
      prueba.hidden = true;
      document.body.append(prueba);
    }
    prueba.style.color = `var(${nombre})`;
    c = parsear(getComputedStyle(prueba).color);
    resueltos.set(nombre, c);
  }
  return c;
}

export function parsear(v) {
  if (v.startsWith('#')) {
    const h = v.slice(1);
    const n = h.length === 3 ? h.split('').map((c) => c + c).join('') : h;
    return [parseInt(n.slice(0, 2), 16), parseInt(n.slice(2, 4), 16), parseInt(n.slice(4, 6), 16), n.length === 8 ? parseInt(n.slice(6, 8), 16) / 255 : 1];
  }
  const m = v.match(/[\d.]+/g)?.map(Number) ?? [0, 0, 0];
  return [m[0], m[1], m[2], m[3] ?? 1];
}

/** ColorUtils.blendARGB. */
export function mezclar(a, b, f) {
  return [0, 1, 2, 3].map((i) => a[i] + (b[i] - a[i]) * f);
}

/** El color con [alfa] de 0 a 255, como ColorUtils.setAlphaComponent. */
export const conAlfa = (c, alfa) => [c[0], c[1], c[2], alfa / 255];

export const css = (c) => `rgba(${Math.round(c[0])},${Math.round(c[1])},${Math.round(c[2])},${Math.max(0, Math.min(1, c[3])).toFixed(3)})`;

/** Ajusta el lienzo a su tamaño en pantalla y a la densidad; devuelve el contexto en px de CSS. */
export function preparar(canvas, ancho, alto) {
  const dpr = window.devicePixelRatio || 1;
  const w = Math.max(1, Math.round(ancho * dpr));
  const h = Math.max(1, Math.round(alto * dpr));
  if (canvas.width !== w) canvas.width = w;
  if (canvas.height !== h) canvas.height = h;
  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, ancho, alto);
  return ctx;
}

/** Fuente de Geist: [peso] 400–700 y [tamano] en px. */
export const fuente = (peso, tamano) => `${peso} ${tamano}px Geist, system-ui, sans-serif`;

/** Ascenso y descenso de la fuente (los de Paint.ascent/descent, en positivo). */
export function metricas(ctx) {
  const m = ctx.measureText('0');
  const asc = m.fontBoundingBoxAscent ?? m.actualBoundingBoxAscent * 1.25;
  const desc = m.fontBoundingBoxDescent ?? m.actualBoundingBoxAscent * 0.3;
  return { asc, desc };
}

/** Rectángulo con esquinas de radios distintos [arriba-izq, arriba-der, abajo-der, abajo-izq]. */
export function rectRedondo(ctx, x, y, w, h, radios) {
  const r = Array.isArray(radios) ? radios : [radios, radios, radios, radios];
  ctx.beginPath();
  if (ctx.roundRect) {
    ctx.roundRect(x, y, w, h, r);
  } else {
    const [a, b, c, d] = r.map((v) => Math.min(v, w / 2, h / 2));
    ctx.moveTo(x + a, y);
    ctx.lineTo(x + w - b, y);
    ctx.arcTo(x + w, y, x + w, y + b, b);
    ctx.lineTo(x + w, y + h - c);
    ctx.arcTo(x + w, y + h, x + w - c, y + h, c);
    ctx.lineTo(x + d, y + h);
    ctx.arcTo(x, y + h, x, y + h - d, d);
    ctx.lineTo(x, y + a);
    ctx.arcTo(x, y, x + a, y, a);
    ctx.closePath();
  }
}

/** Vuelve a dibujar [vista] cuando cambia su tamaño o el tema. */
/**
 * El tamaño en pantalla de un lienzo, en px de CSS, sin leer clientWidth en cada dibujo: esa lectura obliga al
 * navegador a recalcular el diseño si ha cambiado algo (un importe que cuenta, por ejemplo) y, en una animación, eso
 * pasa en cada fotograma. Lo mantiene al día un único ResizeObserver; la primera vez se lee una sola vez.
 */
const medidas = new WeakMap();
const tamanoDe = (e) => {
  const caja = e.contentBoxSize?.[0];
  // Redondeado como clientWidth: con decimales, el lienzo no casa con los píxeles de la pantalla.
  return caja ? { ancho: Math.round(caja.inlineSize), alto: Math.round(caja.blockSize) } : { ancho: Math.round(e.contentRect.width), alto: Math.round(e.contentRect.height) };
};
const vigilante = new ResizeObserver((entradas) => {
  for (const e of entradas) medidas.set(e.target, tamanoDe(e));
});
export function medida(canvas) {
  let m = medidas.get(canvas);
  if (!m) {
    m = { ancho: canvas.clientWidth, alto: canvas.clientHeight };
    medidas.set(canvas, m);
    vigilante.observe(canvas);
  }
  return m;
}

export function alCambiarTamano(el, dibujar) {
  const ro = new ResizeObserver(() => dibujar());
  ro.observe(el);
  document.addEventListener('socios:tema', dibujar);
  return () => {
    ro.disconnect();
    document.removeEventListener('socios:tema', dibujar);
  };
}
