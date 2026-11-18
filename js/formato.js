// util/NumberExtensions.kt y util/Textos.kt: importes, porcentajes y textos en español de España.

/** Espacio que no se parte entre una cifra y su símbolo ("50 €", "21 %"). */
export const ESPACIO_CIFRA = ' ';

/**
 * Céntimos (o la unidad que pida [decimales]) de [valor] redondeando el medio hacia arriba sobre su forma decimal,
 * como hace Java al formatear: 1,005 → 1,01 (en binario es 1,00499…).
 */
function redondear(valor, decimales) {
  const factor = 10 ** decimales;
  return Math.round(Number((Math.abs(valor) * factor).toPrecision(15))) * Math.sign(valor) / factor;
}

/** "1.250,50": separador de miles siempre (también con 4 cifras, como Java) y coma decimal. */
export function numero(valor, decimales = 2) {
  const [entera, fraccion] = Math.abs(redondear(valor, decimales)).toFixed(decimales).split('.');
  const conMiles = entera.replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  // Como Java: un negativo lleva su signo aunque redondee a cero.
  return (valor < 0 ? '-' : '') + conMiles + (fraccion ? ',' + fraccion : '');
}

/** "12,50 €", "+1.250,00 €", "-12,50 €". */
export function moneda(valor, decimales = 2, prefijo = '') {
  return `${prefijo}${numero(valor, decimales)}${ESPACIO_CIFRA}€`;
}

/** Sin decimales si el importe es entero ("20 €"); con céntimos si no ("10,50 €"). */
export const decimalesCompactos = (valor) => (valor % 1 === 0 ? 0 : 2);

export const eurosCompacto = (valor, prefijo = '') => moneda(valor, decimalesCompactos(valor), prefijo);

/** Lo descontado con su signo: "-15,31 €" si se cobró menos, "+5,00 €" si más y "0,00 €" si coincide. */
export function descontadoConSigno(valor, compacto = false) {
  const signo = valor < 0 ? '+' : valor > 0 ? '-' : '';
  return compacto ? eurosCompacto(Math.abs(valor), signo) : moneda(Math.abs(valor), 2, signo);
}

/** "21,48 %", "15 %". */
export function porcentaje(valor) {
  return String(Number(valor.toPrecision(12))).replace('.', ',') + ESPACIO_CIFRA + '%';
}

/** Para rellenar un campo de importe: "10" o "10.5". */
export const importeInput = (valor) => String(Number(valor.toPrecision(12)));

/** El importe que se escribe, con coma o con punto; null si no es un número. */
export function parseImporte(texto) {
  if (texto == null) return null;
  const limpio = String(texto).trim().replace(',', '.');
  if (limpio === '' || !/^[+-]?(\d+\.?\d*|\.\d+)(e[+-]?\d+)?$/i.test(limpio)) return null;
  const valor = Number(limpio);
  return Number.isFinite(valor) ? valor : null;
}

/** "1.250" (separador de miles). */
export const cantidad = (n) => numero(n, 0);

// --- Plurales (values/strings.xml) ---

const plural = (n, uno, otros) => (n === 1 ? uno : otros).replace('%d', n);

export const TEXTOS = {
  semanaPasada: (n) => plural(n, 'Hace %d semana', 'Hace %d semanas'),
  semanaFutura: (n) => plural(n, 'Dentro de %d semana', 'Dentro de %d semanas'),
  semanas: (n) => plural(n, '%d semana', '%d semanas'),
  pendientesAviso: (n) => plural(n, '%d pendiente de semanas anteriores', '%d pendientes de semanas anteriores'),
  resultados: (n) => plural(n, '%d socio encontrado', '%d socios encontrados'),
  sociosHechos: (n) => plural(n, '%d socio hecho', '%d socios hechos'),
  semanasSeleccionadas: (n) => plural(n, '%d semana seleccionada', '%d semanas seleccionadas'),
  importSocios: (n) => plural(n, '%d socio', '%d socios'),
  importBonus: (n) => `${n} bonus`,
  importCobros: (n) => plural(n, '%d semana con lo cobrado', '%d semanas con lo cobrado'),
  descartados: (n) => plural(n, '%d registro no válido se ignorará', '%d registros no válidos se ignorarán')
};

/** "Hace 2 semanas", "Dentro de 1 semana" o, a distancia 0, [actual]. */
export function semanaRelativa(distancia, actual) {
  if (distancia === 0) return actual;
  return distancia < 0 ? TEXTOS.semanaPasada(-distancia) : TEXTOS.semanaFutura(distancia);
}

/** Cómo se llama la diferencia entre lo cobrado y lo calculado. */
export function etiquetaDescontado(descontado) {
  if (descontado > 0) return 'Te han descontado';
  if (descontado < 0) return 'Has cobrado de más';
  return 'Cobrado justo lo calculado';
}

/** "12 socios, 2 bonus y 3 semanas con lo cobrado". */
export function enumerar(partes) {
  if (partes.length < 2) return partes[0] ?? '';
  return `${partes.slice(0, -1).join(', ')} y ${partes[partes.length - 1]}`;
}

/** El texto sin emojis ni símbolos decorativos, para los lectores de pantalla. */
export function sinSimbolos(texto) {
  return String(texto)
    .replace(/[\p{So}️‍]/gu, '')
    .replace(/\s{2,}/g, ' ')
    .replace(/\s+([)\]])/g, '$1')
    .trim();
}

/** Escapa un texto para meterlo en HTML. */
export function esc(texto) {
  return String(texto ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}
