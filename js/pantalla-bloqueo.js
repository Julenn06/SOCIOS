// PantallaBloqueo.kt: tapa toda la app (también los paneles abiertos) hasta que el usuario se identifica con la
// huella, la cara o el PIN del dispositivo.
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
  if (pantalla) return;
  pantalla = html(`
    <div class="pantalla-bloqueo" role="dialog" aria-modal="true" aria-labelledby="tituloBloqueo">
      <img class="logo" src="icons/redondo.png" alt="">
      <span class="icono candado" data-icono="lock"></span>
      <h2 id="tituloBloqueo">Socios está bloqueada</h2>
      <p>Desbloquéala con la huella, la cara o el PIN del móvil para ver tus cuentas.</p>
      <button class="boton relleno" type="button"><span class="icono" data-icono="lock"></span>Desbloquear</button>
    </div>`);
  pantalla.querySelector('button').addEventListener('click', pedir);
  // Lo de debajo (la pantalla y los paneles abiertos) no se puede tocar ni enfocar mientras tanto.
  for (const el of document.body.children) el.inert = true;
  document.body.append(pantalla);
  // El cuadro de la huella sale solo una vez por bloqueo; si se cancela, queda el botón.
  setTimeout(pedir, 300);
}

function quitar() {
  if (!pantalla) return;
  pantalla.remove();
  pantalla = null;
  for (const el of document.body.children) el.inert = false;
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
