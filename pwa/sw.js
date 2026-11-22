// Service worker: guarda la app entera para que funcione sin conexión. Una versión nueva se descarga entera antes de
// usarse (contando lo que lleva, para la animación de "Actualizar") y solo se activa cuando la app lo pide.
const VERSION = '1.0.18';
const CACHE = `socios-${VERSION}`;
const ARCHIVOS = [
  './',
  'index.html',
  'manifest.webmanifest',
  'css/app.css',
  'js/main.js',
  'js/version.js',
  'js/estado.js',
  'js/db.js',
  'js/calculos.js',
  'js/fechas.js',
  'js/formato.js',
  'js/movimiento.js',
  'js/avisos.js',
  'js/paneles.js',
  'js/componentes.js',
  'js/filas.js',
  'js/iconos.js',
  'js/iconos-web.js',
  'js/lienzo.js',
  'js/ajustes.js',
  'js/instalacion.js',
  'js/nube.js',
  'js/pantalla-cuenta.js',
  'js/copias.js',
  'js/pdf.js',
  'js/pantalla.js',
  'js/pantalla-bloqueo.js',
  'js/pantalla-novedad.js',
  'js/hojas/socio.js',
  'js/hojas/ajustes-semana.js',
  'js/hojas/lista-socios.js',
  'js/hojas/historico.js',
  'js/hojas/mas.js',
  'js/hojas/copias.js',
  'js/hojas/cuenta.js',
  'js/hojas/seguridad.js',
  'js/confianza.js',
  'js/entorno.js',
  'js/errores.js',
  'js/vistas/progreso-bonus.js',
  'js/vistas/franja-semana.js',
  'js/vistas/grafico-neto.js',
  'js/vistas/muestra-tema.js',
  'js/vistas/animacion-descarga.js',
  'fonts/geist_regular.woff2',
  'fonts/geist_medium.woff2',
  'fonts/geist_semibold.woff2',
  'fonts/geist_bold.woff2',
  'img/grano.png',
  'icons/icono-192.png',
  'icons/icono-512.png',
  'icons/maskable-192.png',
  'icons/maskable-512.png',
  'icons/apple-touch-icon.png',
  'icons/favicon-64.png',
  'icons/icono-placa.png',
  'icons/redondo.png',
  'icons/atajo-nuevo-socio.png'
];
// Lo que solo usa el informe PDF (el generador y las fuentes TTF, que jsPDF no lee WOFF2): unos 700 KB, casi la mitad
// de la app. No va en la instalación: se guarda aparte la primera vez que se pide (al exportar un PDF, o en segundo plano
// un rato después de abrir la app, ver main.js) y desde entonces funciona sin conexión.
const BAJO_DEMANDA = [
  'vendor/jspdf.umd.min.js',
  'fonts/geist_regular.ttf',
  'fonts/geist_medium.ttf',
  'fonts/geist_semibold.ttf',
  'fonts/geist_bold.ttf'
];
// No empieza por "socios-": actualizar la app no la borra.
const CACHE_PDF = 'pdf-socios';

async function avisar(mensaje) {
  const clientes = await self.clients.matchAll({ includeUncontrolled: true, type: 'window' });
  clientes.forEach((c) => c.postMessage(mensaje));
}

self.addEventListener('install', (evento) => {
  evento.waitUntil((async () => {
    const cache = await caches.open(CACHE);
    // Las versiones anteriores a la 1.0.17 los guardaban con todo lo demás: pasan a su sitio antes de que se borren.
    const pdf = await caches.open(CACHE_PDF);
    for (const archivo of BAJO_DEMANDA) {
      if (await pdf.match(archivo)) continue;
      const vieja = await caches.match(archivo);
      if (vieja) await pdf.put(archivo, vieja);
    }
    let hechos = 0;
    let bytes = 0;
    // Uno a uno, para que la animación vea cómo avanza. Con no-cache, cada archivo se le pregunta al servidor: si no ha
    // cambiado desde la versión anterior, contesta "304, sin cambios" y no se vuelve a descargar.
    for (const archivo of ARCHIVOS) {
      const respuesta = await fetch(new Request(archivo, { cache: 'no-cache' }));
      if (!respuesta.ok) throw new Error(`${archivo}: ${respuesta.status}`);
      const cuerpo = await respuesta.clone().arrayBuffer();
      bytes += cuerpo.byteLength;
      await cache.put(archivo, respuesta);
      hechos++;
      await avisar({ tipo: 'progreso', hechos, total: ARCHIVOS.length, bytes });
    }
    // La primera vez no hay nada que sustituir: se activa enseguida.
    if (!self.registration.active) await self.skipWaiting();
  })());
});

self.addEventListener('activate', (evento) => {
  evento.waitUntil((async () => {
    for (const nombre of await caches.keys()) if (nombre.startsWith('socios-') && nombre !== CACHE) await caches.delete(nombre);
    await self.clients.claim();
  })());
});

self.addEventListener('message', (evento) => {
  if (evento.data?.tipo === 'activar') self.skipWaiting();
});

// Una copia compartida con la app (share_target del manifest): Android la envía aquí como formulario. Se guarda
// aparte (el nombre no empieza por "socios-", así que actualizar no la borra) y la app la recoge al abrirse.
async function recibirCompartido(peticion) {
  const destino = new URL('./?accion=importar', self.registration.scope).href;
  try {
    const archivo = (await peticion.formData()).get('copia');
    if (archivo instanceof File) {
      const cache = await caches.open('compartido-socios');
      await cache.put('compartido', new Response(archivo, {
        headers: { 'Content-Type': archivo.type || 'application/octet-stream', 'X-Nombre': encodeURIComponent(archivo.name) }
      }));
    }
  } catch (e) {
    console.error('No se ha podido recibir el archivo compartido:', e);
  }
  return Response.redirect(destino, 303);
}

/** Lo del PDF: lo guardado al momento (y se mira en segundo plano si ha cambiado) o, la primera vez, del servidor. */
async function bajoDemanda(evento, url) {
  const cache = await caches.open(CACHE_PDF);
  const clave = url.origin + url.pathname;
  const guardada = await cache.match(clave);
  const nueva = fetch(new Request(clave, { cache: 'no-cache' })).then(async (r) => {
    if (r.ok) await cache.put(clave, r.clone());
    return r;
  });
  if (!guardada) return nueva;
  evento.waitUntil(nueva.catch(() => {}));
  return guardada;
}

self.addEventListener('fetch', (evento) => {
  const peticion = evento.request;
  const url = new URL(peticion.url);
  if (peticion.method === 'POST' && url.origin === location.origin && url.pathname.endsWith('/compartir')) {
    evento.respondWith(recibirCompartido(peticion));
    return;
  }
  if (peticion.method !== 'GET') return;
  if (url.origin !== location.origin) return;
  // La versión publicada siempre se pregunta al servidor.
  if (url.pathname.endsWith('/version.json')) return;
  if (BAJO_DEMANDA.includes(url.href.slice(self.registration.scope.length).split('?')[0])) {
    evento.respondWith(bajoDemanda(evento, url));
    return;
  }
  evento.respondWith((async () => {
    const cache = await caches.open(CACHE);
    if (peticion.mode === 'navigate') {
      return (await cache.match('index.html')) ?? fetch(peticion);
    }
    const guardada = await cache.match(peticion, { ignoreSearch: true });
    return guardada ?? fetch(peticion);
  })());
});
