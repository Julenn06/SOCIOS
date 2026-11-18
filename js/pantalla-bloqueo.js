// PantallaBloqueo.kt: tapa toda la app (también los paneles abiertos) hasta que el usuario se identifica con la
// huella, la cara o el PIN del dispositivo.
//
// Es un <dialog> modal: queda encima de cualquier panel (la capa superior se apila por orden de apertura) y deja el
// resto de la página inerte. closedby="none" y el cancel interceptado impiden quitarlo con Esc o con atrás; como
// el navegador puede cerrar un modal igualmente tras varios intentos sin interacción, si se cierra estando aún
// bloqueada, se vuelve a abrir.
import { bloqueo, disponibilidadBloqueo, identificar } from './ajustes.js';
import { html } from './componentes.js';

let pantalla = null;
let pidiendo = false;

/** La pone encima de todo si hace falta, y la quita al desbloquear. */
export function vigilarBloqueo() {
  const comprobar = () => (bloqueo.bloqueada ? mostrar() : quitar());
  bloqueo.alCambiar(comprobar);
  comprobar();
}

function mostrar() {
  if (pantalla) {
    // Ya estaba: que siga siendo lo de más arriba aunque se haya abierto algo después.
    if (pantalla.open) pantalla.close();
    return;
  }
  pantalla = html(`
    <dialog class="pantalla-bloqueo" closedby="none" aria-labelledby="tituloBloqueo" aria-describedby="detalleBloqueo">
      <img class="logo" src="icons/redondo.png" alt="">
      <span class="icono candado" data-icono="lock"></span>
      <h2 id="tituloBloqueo">Socios está bloqueada</h2>
      <p id="detalleBloqueo">Desbloquéala con la huella, la cara o el PIN del móvil para ver tus cuentas.</p>
      <button class="boton relleno" type="button" autofocus><span class="icono" data-icono="lock"></span>Desbloquear</button>
    </dialog>`);
  pantalla.querySelector('button').addEventListener('click', pedir);
  pantalla.addEventListener('cancel', (e) => e.preventDefault());
  pantalla.addEventListener('close', () => {
    if (pantalla && bloqueo.bloqueada) pantalla.showModal();
  });
  document.body.append(pantalla);
  pantalla.showModal();
  // El cuadro de la huella sale solo una vez por bloqueo; si se cancela, queda el botón.
  setTimeout(pedir, 300);
}

function quitar() {
  if (!pantalla) return;
  const el = pantalla;
  pantalla = null;
  el.close();
  el.remove();
}

async function pedir() {
  if (pidiendo) return;
  pidiendo = true;
  try {
    // Si el dispositivo ya no tiene con qué identificarse, dejarla bloqueada impediría entrar para siempre.
    if ((await disponibilidadBloqueo()) !== 'OK' || (await identificar())) bloqueo.desbloquear();
  } finally {
    pidiendo = false;
  }
}
