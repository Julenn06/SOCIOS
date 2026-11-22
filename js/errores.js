// Los fallos de la web en este dispositivo llegan al servidor (servidor/errores.js), para enterarse de lo que falla en
// un móvil concreto aunque nadie lo cuente: errores de JavaScript, promesas sin atender, archivos que no cargan y lo
// que bloquea la política de seguridad (CSP y Trusted Types). Se carga lo primero (main.js) para verlo todo.
//
// No se manda nada de la persona: ni cuenta ni datos de la app; de los mensajes se quita lo que va entre comillas
// (podría ser un nombre o una nota) y de las direcciones, los parámetros. Como mucho 20 por cada vez que se abre.
import { VERSION } from './version.js';
import { API, dispositivo } from './entorno.js';

const MAX_POR_VISITA = 20;
const vistos = new Set();
const cola = [];
let temporizador = null;

const sinTextos = (v) => String(v ?? '').replace(/(["'«“`])(?:(?!\1).){0,400}\1/g, '$1…$1').slice(0, 300);
const sinParametros = (v) => String(v ?? '').split(/[?#]/)[0].slice(0, 200);

function enviar() {
  temporizador = null;
  if (!cola.length) return;
  const cuerpo = JSON.stringify({ errores: cola.splice(0, 10) });
  try {
    // Como texto: así el navegador no pregunta antes al servidor (CORS) y llega aunque se esté cerrando la página.
    const ido = navigator.sendBeacon?.(`${API}/api/errores`, new Blob([cuerpo], { type: 'text/plain' }));
    if (!ido) fetch(`${API}/api/errores`, { method: 'POST', body: cuerpo, keepalive: true, credentials: 'omit', headers: { 'Content-Type': 'text/plain' } }).catch(() => {});
  } catch {
    // Si ni esto funciona, no hay nada más que hacer.
  }
  if (cola.length) temporizador = setTimeout(enviar, 2000);
}

/** Apunta un fallo ({ tipo: 'error' | 'promesa' | 'csp' | 'sw', mensaje, fuente, linea, columna }) para mandarlo. */
export function anotarError(e) {
  const error = {
    tipo: e.tipo,
    mensaje: sinTextos(e.mensaje),
    fuente: sinParametros(e.fuente),
    linea: Number(e.linea) || 0,
    columna: Number(e.columna) || 0,
    version: VERSION,
    navegador: dispositivo()
  };
  const firma = `${error.tipo}|${error.mensaje}|${error.fuente}|${error.linea}`;
  if (vistos.has(firma) || vistos.size >= MAX_POR_VISITA) return;
  vistos.add(firma);
  cola.push(error);
  // Unos segundos de margen: si salen varios a la vez, van juntos.
  temporizador ??= setTimeout(enviar, 3000);
}

/** El archivo, la línea y la columna de la primera línea de la pila que es de la app. */
function lugar(pila) {
  const m = /(https?:\/\/[^\s)]+?):(\d+):(\d+)/.exec(String(pila ?? ''));
  return m ? { fuente: m[1], linea: m[2], columna: m[3] } : {};
}

addEventListener('error', (e) => {
  const t = e.target;
  // Un archivo que no carga (script, estilo, imagen): el evento no sube, solo se ve en la fase de captura.
  if (t && t !== window && t.tagName) {
    anotarError({ tipo: 'error', mensaje: `No se ha podido cargar ${t.tagName.toLowerCase()}`, fuente: t.src || t.href || '' });
    return;
  }
  const mensaje = e.message || e.error?.message;
  // Aviso del navegador que no rompe nada (un ResizeObserver que deja un cambio para el siguiente fotograma).
  if (/^ResizeObserver loop/.test(mensaje ?? '')) return;
  anotarError({ tipo: 'error', mensaje, fuente: e.filename, linea: e.lineno, columna: e.colno });
}, true);

addEventListener('unhandledrejection', (e) => {
  const r = e.reason;
  // Cancelar algo (cerrar el panel de compartir, por ejemplo) no es un fallo.
  if (r?.name === 'AbortError') return;
  anotarError({ tipo: 'promesa', mensaje: r?.message ?? String(r), ...lugar(r?.stack) });
});

document.addEventListener('securitypolicyviolation', (e) => {
  anotarError({
    tipo: 'csp',
    mensaje: `${e.effectiveDirective || e.violatedDirective} bloqueó ${sinParametros(e.blockedURI) || '(en línea)'}`,
    fuente: e.sourceFile,
    linea: e.lineNumber,
    columna: e.columnNumber
  });
});

addEventListener('pagehide', () => {
  clearTimeout(temporizador);
  enviar();
});
