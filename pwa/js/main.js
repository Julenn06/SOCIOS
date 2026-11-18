// SociosApp + MainActivity.onCreate: abre los datos, pinta la pantalla, el bloqueo y el aviso de versión nueva.
import * as estado from './estado.js';
import { aplicarTema, actualizaciones, bloqueo } from './ajustes.js';
import { mostrarAviso } from './avisos.js';
import { iniciarPantalla, nuevoSocio } from './pantalla.js';
import { vigilarBloqueo } from './pantalla-bloqueo.js';
import { abrirActualizacion } from './hojas/mas.js';
import { VERSION } from './version.js';

async function arrancar() {
  aplicarTema();
  try {
    await estado.iniciar();
  } catch (e) {
    console.error(e);
    document.documentElement.classList.remove('cargando');
    document.body.innerHTML = `<p style="padding:24px;font-family:Geist,system-ui">No se han podido abrir tus datos en este navegador (${e?.name ?? 'Error'}). Prueba a cerrar las otras pestañas de la app o a salir del modo privado.</p>`;
    return;
  }
  iniciarPantalla();
  vigilarBloqueo();
  // Sin parpadeo: se ve cuando ya tiene los datos y las fuentes.
  await Promise.race([document.fonts?.ready, new Promise((r) => setTimeout(r, 600))]);
  document.documentElement.classList.remove('cargando');

  // Atajo "Nuevo socio" del icono de la app (manifest): solo al abrirla con él.
  const url = new URL(location.href);
  if (url.searchParams.get('accion') === 'nuevo-socio') {
    history.replaceState(history.state, '', url.pathname);
    nuevoSocio();
  }

  registrarServiceWorker();
  avisarVersionNueva();
}

/** Versión nueva al abrir la app (como mucho una vez al día): un aviso que dura más, cuando ya no está bloqueada. */
async function avisarVersionNueva() {
  const publicada = await actualizaciones.comprobarSiToca();
  if (!publicada) return;
  const avisar = () => mostrarAviso(`Versión ${publicada.version} disponible`, 'NOVEDAD', `Ahora tienes la ${VERSION}`, 'Actualizar', () => abrirActualizacion(publicada));
  if (!bloqueo.bloqueada) return avisar();
  const dejar = bloqueo.alCambiar((bloqueada) => {
    if (bloqueada) return;
    dejar();
    avisar();
  });
}

function registrarServiceWorker() {
  if (!('serviceWorker' in navigator) || location.protocol === 'file:') return;
  navigator.serviceWorker.register('sw.js').catch((e) => console.warn('Sin funcionamiento sin conexión:', e));
}

arrancar();
