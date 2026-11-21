// SociosApp + MainActivity.onCreate: abre los datos, pinta la pantalla, el bloqueo y el aviso de versión nueva.
import * as estado from './estado.js';
import { aplicarTema, actualizaciones, bloqueo } from './ajustes.js';
import { mostrarAviso } from './avisos.js';
import { iniciarPantalla, nuevoSocio } from './pantalla.js';
import { vigilarBloqueo } from './pantalla-bloqueo.js';
import { mostrarNovedad } from './pantalla-novedad.js';
import { importarArchivo } from './hojas/copias.js';
import * as nube from './nube.js';
import { mostrarPantallaCuenta, quitarPantallaCuenta } from './pantalla-cuenta.js';

/** Donde el service worker deja la copia compartida con la app hasta que la página la recoge (ver sw.js). */
const CACHE_COMPARTIDO = 'compartido-socios';
// Al arrancar: el aviso de que se puede instalar llega pronto y hay que guardarlo para el botón de "Más".
import './instalacion.js';
import { urlScriptConfiable } from './confianza.js';

async function arrancar() {
  aplicarTema();
  try {
    await estado.iniciar();
  } catch (e) {
    console.error(e);
    document.documentElement.classList.remove('cargando');
    const aviso = document.createElement('p');
    aviso.className = 'error-arranque';
    aviso.textContent = `No se han podido abrir tus datos en este navegador (${e?.name ?? 'Error'}). Prueba a cerrar las otras pestañas de la app o a salir del modo privado.`;
    document.body.replaceChildren(aviso);
    return;
  }
  iniciarPantalla();
  vigilarBloqueo();
  vigilarCuenta();
  // Sin parpadeo: se ve cuando ya tiene los datos y las fuentes.
  await Promise.race([document.fonts?.ready, new Promise((r) => setTimeout(r, 600))]);
  document.documentElement.classList.remove('cargando');

  // Atajo "Nuevo socio" del icono de la app y copias compartidas con ella (manifest): después del bloqueo, para que
  // ningún panel se abra encima de la pantalla que tapa los datos.
  const url = new URL(location.href);
  const accion = url.searchParams.get('accion');
  if (accion) history.replaceState(history.state, '', url.pathname);
  if (accion === 'nuevo-socio') cuandoDesbloqueada(nuevoSocio);
  else if (accion === 'importar') cuandoDesbloqueada(importarCompartido);

  registrarServiceWorker();
  vigilarVersionNueva();
}

/**
 * La cuenta (nube.js): sin sesión, la pantalla de iniciar sesión o registrarse tapa la app; con ella, los datos se
 * sincronizan solos con los demás dispositivos.
 */
function vigilarCuenta() {
  nube.alRecibirCambios(async () => {
    // Puede haber llegado el tema elegido en otro dispositivo.
    aplicarTema();
    await estado.recargar();
  });
  nube.alEntrarOSalir(async (dentro) => {
    await estado.recargar();
    if (dentro) quitarPantallaCuenta();
    else mostrarPantallaCuenta();
  });
  // Si se cierra la sesión en otra pestaña, en esta también.
  addEventListener('storage', (ev) => {
    if (ev.key === 'socios.sesion' && !ev.newValue) location.reload();
  });
  if (!nube.cuenta.sesion) return mostrarPantallaCuenta();
  nube.vigilar();
  nube.sincronizar();
}

/** Ahora si no está bloqueada; si lo está, en cuanto se desbloquee. */
function cuandoDesbloqueada(hacer) {
  if (!bloqueo.bloqueada) return hacer();
  const dejar = bloqueo.alCambiar((bloqueada) => {
    if (bloqueada) return;
    dejar();
    hacer();
  });
}

/** La copia que se ha compartido con la app (Compartir → Gestión de Socios): la guarda el service worker. */
async function importarCompartido() {
  try {
    const cache = await caches.open(CACHE_COMPARTIDO);
    const respuesta = await cache.match('compartido');
    await caches.delete(CACHE_COMPARTIDO);
    if (!respuesta) return mostrarAviso('No ha llegado ningún archivo', 'ERROR', 'Vuelve a compartirlo con la app');
    const nombre = decodeURIComponent(respuesta.headers.get('X-Nombre') ?? '');
    importarArchivo(new File([await respuesta.blob()], nombre));
  } catch (e) {
    console.error(e);
    mostrarAviso('No se ha podido abrir el archivo', 'ERROR', 'Prueba desde Más → Copias → Importar');
  }
}

/**
 * Versión nueva: al abrir la app y al volver a ella (desde la pantalla de inicio o desde las apps recientes, donde la
 * página sigue viva días), una pantalla entera con las novedades (pantalla-novedad.js).
 */
function vigilarVersionNueva() {
  const mirar = async (cada) => {
    const publicada = await actualizaciones.comprobarSiToca(cada);
    if (publicada) mostrarNovedad(publicada);
  };
  mirar(0);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') mirar(30 * 60_000);
  });
}

function registrarServiceWorker() {
  if (!('serviceWorker' in navigator) || location.protocol === 'file:') return;
  navigator.serviceWorker.register(urlScriptConfiable('sw.js')).catch((e) => console.warn('Sin funcionamiento sin conexión:', e));
}

arrancar();
