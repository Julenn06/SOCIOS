// La pantalla de la cuenta: "Iniciar sesión" o "Registrarse". Tapa toda la app hasta que hay sesión (como la del
// bloqueo, es un <dialog> modal que no se puede quitar con Esc ni con atrás).
//
// Registrarse tiene dos pasos: primero la contraseña de registro (para que no pueda crear una cuenta cualquiera) y,
// si es correcta, "¿Cómo te llamas?" y la contraseña de la cuenta. Para entrar, el nombre vale escrito de cualquier
// forma: con mayúsculas, minúsculas o sin tildes.
import { comprobarClaveRegistro, cuenta, entrar, ErrorCuenta, registrarse } from './nube.js';
import { crearCampo, grupoOpciones, html } from './componentes.js';
import { haptica } from './movimiento.js';
import { mostrarAviso } from './avisos.js';
import { htmlConfiable } from './confianza.js';

let pantalla = null;

export function mostrarPantallaCuenta() {
  if (pantalla) return;
  pantalla = html(`
    <dialog class="pantalla-cuenta" closedby="none" aria-labelledby="tituloCuenta">
      <div class="cuenta-columna">
        <img class="logo" src="icons/redondo.png" alt="">
        <h1 id="tituloCuenta">Gestión de Socios</h1>
        <p class="cuenta-lema">Tus socios en el móvil y en la web, siempre al día.</p>
        <div class="cuenta-modos"></div>
        <div class="cuenta-contenido"></div>
      </div>
    </dialog>`);
  const contenido = pantalla.querySelector('.cuenta-contenido');
  const modos = grupoOpciones(
    [{ id: 'ENTRAR', html: 'Iniciar sesión' }, { id: 'REGISTRO', html: 'Registrarse' }],
    (id) => (id === 'ENTRAR' ? formularioEntrar(contenido) : formularioClaveRegistro(contenido)),
    { alto: 46, tamano: 14 }
  );
  pantalla.querySelector('.cuenta-modos').append(modos);
  modos.marcar('ENTRAR');
  formularioEntrar(contenido);
  pantalla.addEventListener('cancel', (e) => e.preventDefault());
  pantalla.addEventListener('close', () => {
    if (pantalla && !cuenta.sesion) pantalla.showModal();
  });
  // Si la sesión caducó, se dice por qué se vuelve a pedir.
  if (cuenta.mensaje) pantalla.querySelector('.cuenta-lema').textContent = cuenta.mensaje;
  document.body.append(pantalla);
  pantalla.showModal();
}

export function quitarPantallaCuenta() {
  if (!pantalla) return;
  const el = pantalla;
  pantalla = null;
  el.close();
  el.remove();
}

/** Un formulario con sus campos y su botón; [enviar] devuelve el campo con el error, si lo hay. */
function formulario(contenedor, { explicacion = null, campos, boton, enviar, extra = null }) {
  const form = html('<form class="cuenta-formulario" novalidate></form>');
  if (explicacion) form.append(html(`<p class="cuenta-explicacion">${explicacion}</p>`));
  for (const c of campos) form.append(c.el);
  const error = html('<p class="cuenta-error oculto" role="alert"></p>');
  const enviarBoton = html(`<button class="boton relleno ancho" type="submit">${boton}</button>`);
  form.append(error, enviarBoton);
  if (extra) form.append(extra);

  let ocupado = false;
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (ocupado) return;
    campos.forEach((c) => c.error(null));
    campos.forEach((c) => c.ocultar());
    error.classList.add('oculto');
    ocupado = true;
    enviarBoton.disabled = true;
    enviarBoton.innerHTML = htmlConfiable('<span class="girando" aria-hidden="true"></span>');
    enviarBoton.setAttribute('aria-label', 'Un momento');
    try {
      await enviar({
        enCampo: (campo, texto) => {
          campo.error(texto);
          campo.foco();
          throw new ErrorCuenta(texto, 'campo');
        }
      });
      haptica.confirmar();
    } catch (ex) {
      if (ex?.codigo !== 'campo') {
        error.textContent = ex?.message ?? 'No se ha podido completar';
        error.classList.remove('oculto');
      }
      haptica.rechazar();
    } finally {
      ocupado = false;
      if (enviarBoton.isConnected) {
        enviarBoton.disabled = false;
        enviarBoton.textContent = boton;
        enviarBoton.removeAttribute('aria-label');
      }
    }
  });
  contenedor.replaceChildren(form);
  requestAnimationFrame(() => campos[0]?.foco());
  return form;
}

function formularioEntrar(contenedor) {
  const nombre = crearCampo({ etiqueta: 'Tu nombre', iconoCampo: 'person', capitalizar: 'words', autocompletar: 'username', maxLength: 40, nombre: 'usuario', requerido: true });
  const contrasena = crearCampo({ etiqueta: 'Contraseña', iconoCampo: 'lock', tipo: 'contrasena', autocompletar: 'current-password', maxLength: 200, nombre: 'contrasena', requerido: true });
  formulario(contenedor, {
    campos: [nombre, contrasena],
    boton: 'Entrar',
    async enviar({ enCampo }) {
      if (!nombre.valor().trim()) enCampo(nombre, 'Escribe tu nombre');
      if (!contrasena.valor()) enCampo(contrasena, 'Escribe tu contraseña');
      const { fallos } = await entrar(nombre.valor(), contrasena.valor());
      // Alguien ha probado contraseñas con esta cuenta desde la última vez: que se sepa.
      if (fallos > 0) {
        mostrarAviso(
          fallos === 1 ? 'Ha habido un intento fallido de entrar en tu cuenta' : `Ha habido ${fallos} intentos fallidos de entrar en tu cuenta`,
          'ERROR',
          'Si no has sido tú, cambia la contraseña en Más → Cuenta'
        );
      }
    }
  });
}

function formularioClaveRegistro(contenedor) {
  const clave = crearCampo({ etiqueta: 'Contraseña de registro', iconoCampo: 'lock', tipo: 'contrasena', autocompletar: 'off', maxLength: 200, nombre: 'clave-registro', requerido: true });
  formulario(contenedor, {
    explicacion: 'Para crear una cuenta necesitas la contraseña de registro.',
    campos: [clave],
    boton: 'Continuar',
    async enviar({ enCampo }) {
      if (!clave.valor()) enCampo(clave, 'Escribe la contraseña de registro');
      try {
        await comprobarClaveRegistro(clave.valor());
      } catch (e) {
        if (e.codigo === 'clave_registro') enCampo(clave, e.message);
        throw e;
      }
      formularioRegistro(contenedor, clave.valor());
    }
  });
}

function formularioRegistro(contenedor, claveRegistro) {
  const nombre = crearCampo({ etiqueta: '¿Cómo te llamas?', iconoCampo: 'person', capitalizar: 'words', autocompletar: 'username', maxLength: 40, nombre: 'nuevo-usuario', requerido: true });
  const contrasena = crearCampo({ etiqueta: 'Contraseña', iconoCampo: 'lock', tipo: 'contrasena', autocompletar: 'new-password', maxLength: 200, ayuda: true, nombre: 'nueva-contrasena', requerido: true });
  contrasena.ayuda('Al menos 8 caracteres');
  const repetir = crearCampo({ etiqueta: 'Repite la contraseña', iconoCampo: 'lock', tipo: 'contrasena', autocompletar: 'new-password', maxLength: 200, nombre: 'repetir-contrasena', requerido: true });
  const atras = html('<button class="boton texto ancho" type="button">Atrás</button>');
  atras.addEventListener('click', () => formularioClaveRegistro(contenedor));
  formulario(contenedor, {
    campos: [nombre, contrasena, repetir],
    boton: 'Crear cuenta',
    extra: atras,
    async enviar({ enCampo }) {
      const largo = [...nombre.valor().trim()].length;
      if (largo < 2 || largo > 40) enCampo(nombre, 'Escribe tu nombre (de 2 a 40 letras)');
      if (contrasena.valor().length < 8) enCampo(contrasena, 'La contraseña tiene que tener al menos 8 caracteres');
      if (repetir.valor() !== contrasena.valor()) enCampo(repetir, 'Las contraseñas no coinciden');
      try {
        await registrarse(claveRegistro, nombre.valor(), contrasena.valor());
      } catch (e) {
        if (e.codigo === 'nombre_ocupado' || e.codigo === 'nombre') enCampo(nombre, e.message);
        if (e.codigo === 'contrasena') enCampo(contrasena, e.message);
        throw e;
      }
    }
  });
}
