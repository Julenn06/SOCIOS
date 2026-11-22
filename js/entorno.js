// Dónde corre la app: el servidor de la cuenta y cómo se llama este dispositivo. Sin dependencias, para que errores.js
// pueda cargarse antes que todo lo demás.

/** El servidor. En local (probando con herramientas del repositorio) va en el puerto 3000. */
export const API = location.hostname === 'localhost' ? 'http://localhost:3000' : 'https://socios-api.jfl06studio.com';

/** Cómo se ve este dispositivo en la lista de sesiones abiertas: "Chrome en Android (instalada)". */
export function dispositivo() {
  const ua = navigator.userAgent;
  const navegador = /Edg(e|A|iOS)?\//.test(ua) ? 'Edge'
    : /SamsungBrowser/.test(ua) ? 'Samsung Internet'
      : /OPR\/|Opera/.test(ua) ? 'Opera'
        : /Firefox|FxiOS/.test(ua) ? 'Firefox'
          : /Chrome|CriOS/.test(ua) ? 'Chrome'
            : /Safari/.test(ua) ? 'Safari' : 'Navegador';
  const sistema = /Android/.test(ua) ? 'Android'
    : /iPhone/.test(ua) ? 'iPhone'
      : /iPad/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1) ? 'iPad'
        : /Windows/.test(ua) ? 'Windows'
          : /CrOS/.test(ua) ? 'ChromeOS'
            : /Macintosh|Mac OS X/.test(ua) ? 'Mac'
              : /Linux/.test(ua) ? 'Linux' : '';
  const instalada = matchMedia('(display-mode: standalone)').matches;
  return `${navegador}${sistema ? ` en ${sistema}` : ''}${instalada ? ' (instalada)' : ''}`;
}
