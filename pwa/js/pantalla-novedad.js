// Versión nueva: una pantalla entera que lo cuenta, con las novedades, para actualizar en el momento o dejarlo para
// luego. Sale al abrir la app y al volver a ella si el servidor tiene una versión más reciente (ver main.js).
//
// No interrumpe: si la app está bloqueada o hay un panel o un diálogo abierto (alguien editando un socio, por
// ejemplo), espera a que se cierre. Encima de la pantalla de iniciar sesión sí sale: la versión nueva también es
// para ella. "Más tarde" (o atrás) la quita hasta la próxima vez que se abra la app.
import { bloqueo } from './ajustes.js';
import { html } from './componentes.js';
import { esc } from './formato.js';
import { icono } from './iconos.js';
import { activas, haptica } from './movimiento.js';
import { abrirActualizacion } from './hojas/mas.js';

let pantalla = null;
/** La versión a la que se ha dicho "Más tarde" en esta visita. */
let pospuesta = null;
/** La versión que espera a que se cierre lo que hay abierto. */
let pendiente = null;

const ocupada = () =>
  bloqueo.bloqueada || [...document.querySelectorAll('dialog[open]')].some((d) => !d.classList.contains('pantalla-cuenta'));

export function mostrarNovedad(publicada) {
  if (pantalla || pospuesta === publicada.version) return;
  if (ocupada()) return esperarHueco(publicada);
  abrir(publicada);
}

/** Cuando se cierre lo que hay abierto (el evento close no sube, pero se ve en la fase de captura). */
function esperarHueco(publicada) {
  const yaEsperaba = pendiente != null;
  pendiente = publicada;
  if (yaEsperaba) return;
  const mirar = () =>
    // Después de que el bloqueo o la cuenta decidan si vuelven a abrirse, y de la animación de salida.
    setTimeout(() => {
      if (!pendiente || ocupada()) return;
      document.removeEventListener('close', mirar, true);
      const p = pendiente;
      pendiente = null;
      mostrarNovedad(p);
    }, 400);
  document.addEventListener('close', mirar, true);
}

function abrir(publicada) {
  const novedades = (publicada.novedades ?? []).filter(Boolean);
  const el = html(`
    <dialog class="pantalla-novedad" closedby="closerequest" aria-labelledby="tituloNovedad" aria-describedby="detalleNovedad">
      <div class="novedad-columna">
        <div class="novedad-placa degradado-cabecera" aria-hidden="true"><span class="icono">${icono('new_releases')}</span></div>
        <h1 id="tituloNovedad" tabindex="-1" autofocus>Hay una actualización</h1>
        <p class="novedad-lema" id="detalleNovedad">Hay una versión nueva de la app lista para instalar. Tus socios y tus ajustes se quedan como están.</p>
        ${novedades.length ? `
        <section class="tarjeta novedad-lista" aria-labelledby="tituloNovedades">
          <h2 class="titulo-fila" id="tituloNovedades">Novedades</h2>
          <ul>${novedades.map((n) => `<li>${esc(n)}</li>`).join('')}</ul>
        </section>` : ''}
        <div class="novedad-botones">
          <button class="boton relleno ancho" type="button" data-id="actualizar"><span class="icono">${icono('system_update')}</span>Actualizar ahora</button>
          <button class="boton texto ancho" type="button" data-id="luego">Más tarde</button>
        </div>
        <p class="novedad-nota">Si la dejas para luego, se pondrá sola la próxima vez que abras la app.</p>
      </div>
    </dialog>`);
  pantalla = el;
  let cerrando = false;

  const cerrar = async () => {
    if (cerrando) return;
    cerrando = true;
    el.setAttribute('data-closing', '');
    if (activas()) await new Promise((r) => setTimeout(r, 220));
    el.close();
    el.remove();
    pantalla = null;
  };
  const luego = () => {
    haptica.click();
    pospuesta = publicada.version;
    // Que el navegador vaya bajándola ya, para que "se ponga sola" al volver a abrir la app.
    navigator.serviceWorker?.getRegistration().then((r) => r?.update()).catch(() => {});
    cerrar();
  };

  el.querySelector('[data-id="actualizar"]').addEventListener('click', async () => {
    haptica.click();
    await cerrar();
    // El panel de la descarga, con su animación; al terminar, la app se abre con la versión nueva.
    abrirActualizacion(publicada);
  });
  el.querySelector('[data-id="luego"]').addEventListener('click', luego);
  el.addEventListener('cancel', (e) => {
    e.preventDefault();
    luego();
  });
  // Si el navegador la cierra por su cuenta (atrás sin poder interceptarlo), cuenta como "Más tarde".
  el.addEventListener('close', () => {
    if (cerrando) return;
    pospuesta = publicada.version;
    el.remove();
    pantalla = null;
  });
  document.body.append(el);
  el.showModal();
}
