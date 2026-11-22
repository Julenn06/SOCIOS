import { createHash } from 'node:crypto'

/**
 * Errores de la web en los dispositivos: los manda la propia web (js/errores.js, fallos de JavaScript y violaciones de
 * la política de seguridad) y, si ni siquiera llega a arrancar, el navegador (report-uri de la CSP del .htaccess).
 * Sirven para enterarse de lo que falla en un móvil concreto (Safari en iPhone, por ejemplo).
 *
 * No se guarda nada de la persona ni de sus datos: ni cuenta, ni IP, ni direcciones con parámetros; los textos entre
 * comillas de los mensajes (pueden traer un nombre o una nota) se quitan. Los vigila .github/workflows/vigilancia.yml,
 * que los pide con GET /api/errores.
 */

const TIPOS = new Set(['error', 'promesa', 'csp', 'sw'])
const MAX_POR_ENVIO = 10

const corto = (v, max) => String(v ?? '').replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, max)
/** Sin lo que va entre comillas (puede ser un dato de la app). */
const sinTextos = (v) => String(v ?? '').replace(/(["'«“`])(?:(?!\1).){0,400}\1/g, '$1…$1').replace(/[«“][^»”]{0,400}[»”]/g, '«…»')
/** Una dirección: solo el origen y la ruta (sin parámetros ni #), y las de la web, solo la ruta. */
function direccion(v) {
  const texto = String(v ?? '')
  if (!texto || /^(inline|eval|self|data|blob|wasm-eval|trusted-types-sink|trusted-types-policy)$/.test(texto)) return texto
  try {
    const u = new URL(texto)
    return u.protocol === 'data:' || u.protocol === 'blob:' ? u.protocol : `${u.origin}${u.pathname}`
  } catch {
    return texto.split(/[?#]/)[0]
  }
}

function uno(e) {
  if (!e || typeof e !== 'object') return null
  const tipo = String(e.tipo ?? '')
  if (!TIPOS.has(tipo)) return null
  const version = /^\d{1,4}\.\d{1,4}\.\d{1,4}$/.test(e.version) ? e.version : ''
  return {
    tipo,
    mensaje: corto(sinTextos(e.mensaje), 300) || '(sin mensaje)',
    fuente: corto(direccion(e.fuente).replace(/^https:\/\/socios\.jfl06studio\.com/, '') + (e.linea ? `:${Number(e.linea) | 0}:${Number(e.columna) | 0}` : ''), 200),
    version,
    navegador: corto(e.navegador, 80),
  }
}

/** Una violación de la CSP que manda el navegador (report-uri: { "csp-report": {…} }; Reporting API: [{ type, body }]). */
function deCsp(r) {
  if (!r || typeof r !== 'object') return null
  const directiva = r['effective-directive'] ?? r.effectiveDirective ?? r['violated-directive'] ?? r.violatedDirective
  const bloqueado = r['blocked-uri'] ?? r.blockedURL ?? r.blockedUri
  return uno({
    tipo: 'csp',
    mensaje: `${corto(directiva, 60)} bloqueó ${direccion(bloqueado) || '(en línea)'}`,
    fuente: r['source-file'] ?? r.sourceFile,
    linea: r['line-number'] ?? r.lineNumber,
    columna: r['column-number'] ?? r.columnNumber,
    navegador: 'informe del navegador',
  })
}

/** Lo que trae un envío, ya limpio (como mucho MAX_POR_ENVIO). */
export function limpiarErrores(cuerpo) {
  let lista
  if (Array.isArray(cuerpo)) lista = cuerpo.filter((r) => r?.type === 'csp-violation').map((r) => deCsp(r.body))
  else if (cuerpo?.['csp-report']) lista = [deCsp(cuerpo['csp-report'])]
  else lista = Array.isArray(cuerpo?.errores) ? cuerpo.errores.map(uno) : []
  return lista.filter(Boolean).slice(0, MAX_POR_ENVIO)
}

/** Los errores iguales (mismo tipo, mensaje, lugar, versión y navegador) van en una sola fila, contados. */
export const firmaError = (e) =>
  createHash('sha256').update([e.tipo, e.mensaje, e.fuente, e.version, e.navegador].join('\u0001')).digest('hex').slice(0, 32)
