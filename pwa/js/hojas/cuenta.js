// "Más → Cuenta → Nombre y contraseña" (solo en la web): cambiar el nombre con el que se entra y la contraseña. Si no
// se recuerda la contraseña actual, se puede restablecer con la contraseña de registro (la misma que para crear una
// cuenta). Al cambiarla, los demás dispositivos tienen que volver a entrar con la nueva.
import { abrirHoja, cabeceraPanel } from '../paneles.js';
import { crearCampo, html } from '../componentes.js';
import { mostrarAviso } from '../avisos.js';
import { haptica } from '../movimiento.js';
import { cambiarContrasena, cambiarNombre, cuenta } from '../nube.js';
import { htmlConfiable } from '../confianza.js';

export function abrirAjustesCuenta() {
  return abrirHoja({
    tag: 'AjustesCuenta',
    construir: (panel) => {
      const s = panel.scroll;
      s.append(html(cabeceraPanel({ placa: 'degradado-cabecera', iconoPlaca: 'person', titulo: 'Nombre y contraseña', subtitulo: 'Con ellos entras en la web desde cualquier sitio' })));
      panel.el.querySelector('[data-cerrar]')?.addEventListener('click', () => panel.cancelar());
      s.append(formularioNombre(), formularioContrasena());
    }
  });
}

/**
 * Un formulario con su botón: mientras [enviar] trabaja, el botón dice "Un momento…". Los errores que sabe colocar
 * en un campo los pone [enCampo]; los demás salen debajo.
 */
function formulario({ campos, boton, extra = [], enviar }) {
  const form = html('<form class="cuenta-formulario" novalidate></form>');
  const error = html('<p class="cuenta-error oculto" role="alert"></p>');
  const enviarBoton = html(`<button class="boton relleno ancho" type="submit">${boton}</button>`);
  form.append(...campos.map((c) => c.el), ...extra, error, enviarBoton);
  let ocupado = false;
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (ocupado) return;
    campos.forEach((c) => {
      c.error(null);
      c.ocultar?.();
    });
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
          throw Object.assign(new Error(texto), { codigo: 'campo' });
        }
      });
      haptica.confirmar();
    } catch (ex) {
      if (ex?.codigo !== 'campo') {
        error.textContent = ex?.message ?? 'No se ha podido guardar';
        error.classList.remove('oculto');
      }
      haptica.rechazar();
    } finally {
      ocupado = false;
      enviarBoton.disabled = false;
      enviarBoton.textContent = boton;
      enviarBoton.removeAttribute('aria-label');
    }
  });
  return form;
}

function formularioNombre() {
  const nombre = crearCampo({ etiqueta: 'Tu nombre', iconoCampo: 'person', capitalizar: 'words', autocompletar: 'username', maxLength: 40, nombre: 'usuario', requerido: true });
  nombre.poner(cuenta.sesion?.nombre ?? '');
  const caja = html('<section aria-labelledby="seccionNombre"><div class="seccion" id="seccionNombre">Nombre</div></section>');
  caja.append(formulario({
    campos: [nombre],
    boton: 'Guardar nombre',
    async enviar({ enCampo }) {
      const largo = [...nombre.valor().trim()].length;
      if (largo < 2 || largo > 40) enCampo(nombre, 'Escribe tu nombre (de 2 a 40 letras)');
      try {
        nombre.poner(await cambiarNombre(nombre.valor()));
      } catch (e) {
        if (e.codigo === 'nombre_ocupado' || e.codigo === 'nombre') enCampo(nombre, e.message);
        throw e;
      }
      mostrarAviso('Nombre cambiado', 'EXITO', 'Desde ahora entras con este nombre');
    }
  }));
  return caja;
}

function formularioContrasena() {
  // Con la actual o, sin recordarla, con la contraseña de registro.
  let restablecer = false;
  const actual = crearCampo({ etiqueta: 'Contraseña actual', iconoCampo: 'lock', tipo: 'contrasena', autocompletar: 'current-password', maxLength: 200, nombre: 'contrasena', requerido: true });
  const clave = crearCampo({ etiqueta: 'Contraseña de registro', iconoCampo: 'lock', tipo: 'contrasena', autocompletar: 'off', maxLength: 200, nombre: 'clave-registro' });
  const nueva = crearCampo({ etiqueta: 'Contraseña nueva', iconoCampo: 'lock', tipo: 'contrasena', autocompletar: 'new-password', maxLength: 200, ayuda: true, nombre: 'nueva-contrasena', requerido: true });
  nueva.ayuda('Al menos 8 caracteres');
  const repetir = crearCampo({ etiqueta: 'Repite la contraseña nueva', iconoCampo: 'lock', tipo: 'contrasena', autocompletar: 'new-password', maxLength: 200, nombre: 'repetir-contrasena', requerido: true });
  const explicacion = html('<p class="cuenta-explicacion oculto">Para restablecerla, escribe la contraseña de registro (la misma que para crear una cuenta) y elige una nueva.</p>');
  const alternar = html('<button class="boton texto ancho" type="button"></button>');
  const ponerModo = () => {
    actual.el.classList.toggle('oculto', restablecer);
    clave.el.classList.toggle('oculto', !restablecer);
    clave.input.required = restablecer;
    actual.input.required = !restablecer;
    explicacion.classList.toggle('oculto', !restablecer);
    alternar.textContent = restablecer ? 'Me acuerdo de la contraseña actual' : '¿No te acuerdas? Restablécela';
    actual.error(null);
    clave.error(null);
  };
  alternar.addEventListener('click', () => {
    haptica.click();
    restablecer = !restablecer;
    ponerModo();
    (restablecer ? clave : actual).foco();
  });
  ponerModo();

  const caja = html('<section aria-labelledby="seccionContrasena"><div class="seccion" id="seccionContrasena">Contraseña</div></section>');
  caja.append(formulario({
    campos: [actual, clave, nueva, repetir],
    boton: 'Cambiar contraseña',
    async enviar({ enCampo }) {
      if (restablecer && !clave.valor()) enCampo(clave, 'Escribe la contraseña de registro');
      if (!restablecer && !actual.valor()) enCampo(actual, 'Escribe tu contraseña actual');
      if (nueva.valor().length < 8) enCampo(nueva, 'La contraseña tiene que tener al menos 8 caracteres');
      if (repetir.valor() !== nueva.valor()) enCampo(repetir, 'Las contraseñas no coinciden');
      try {
        await cambiarContrasena(restablecer ? { claveRegistro: clave.valor(), nueva: nueva.valor() } : { actual: actual.valor(), nueva: nueva.valor() });
      } catch (e) {
        if (e.codigo === 'credenciales') enCampo(actual, e.message);
        if (e.codigo === 'clave_registro') enCampo(clave, e.message);
        if (e.codigo === 'contrasena') enCampo(nueva, e.message);
        throw e;
      }
      [actual, clave, nueva, repetir].forEach((c) => c.poner(''));
      restablecer = false;
      ponerModo();
      mostrarAviso('Contraseña cambiada', 'EXITO', 'En los demás dispositivos tendrás que entrar con la nueva');
    }
  }));
  const form = caja.querySelector('form');
  form.prepend(explicacion);
  form.append(alternar);
  return caja;
}
