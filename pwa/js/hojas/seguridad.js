// "Más → Cuenta → Dispositivos y actividad" (solo en la web): dónde está abierta la cuenta, para cerrar la sesión de
// un dispositivo o de todos los demás, y lo último que ha pasado con ella (entradas, intentos fallidos, cambios de
// nombre o de contraseña). Lo cuenta el servidor (servidor/server.js: /api/sesiones y /api/actividad).
import { abrirHoja, cabeceraPanel, dialogo } from '../paneles.js';
import { html } from '../componentes.js';
import { mostrarAviso } from '../avisos.js';
import { esc } from '../formato.js';
import { momentoRelativo } from '../fechas.js';
import { haptica } from '../movimiento.js';
import { cerrarSesionDe, cerrarSesionesOtras, leerActividad, listarSesiones } from '../nube.js';

/** Cómo se cuenta cada cosa de la actividad: [icono, tono, texto]. */
const ACTIVIDAD = {
  registro: ['check_circle', 'verde', 'Cuenta creada'],
  entrada: ['person', 'verde', 'Inicio de sesión'],
  fallo: ['error', 'rojo', 'Intento fallido de entrar'],
  contrasena: ['lock', 'azul', 'Contraseña cambiada'],
  restablecida: ['lock', 'ambar', 'Contraseña restablecida'],
  nombre: ['edit', 'azul', 'Nombre cambiado'],
  cierre_remoto: ['close', 'azul', 'Sesión cerrada en otro dispositivo'],
  cierre_otras: ['close', 'azul', 'Sesiones cerradas en los demás dispositivos']
};

const esMovil = (d) => /Android|iPhone|iPad/.test(d ?? '');

export function abrirSeguridad() {
  return abrirHoja({
    tag: 'SeguridadCuenta',
    construir: (panel) => {
      const s = panel.scroll;
      s.append(html(cabeceraPanel({ placa: 'degradado-cabecera', iconoPlaca: 'escudo', titulo: 'Dispositivos y actividad', subtitulo: 'Dónde está abierta tu cuenta y qué ha pasado con ella' })));
      panel.el.querySelector('[data-cerrar]')?.addEventListener('click', () => panel.cancelar());

      const seccionSesiones = html(`
        <section aria-labelledby="tituloSesiones">
          <div class="seccion" id="tituloSesiones">Sesiones abiertas</div>
          <div class="tarjeta lista-seguridad" data-id="sesiones" aria-busy="true"></div>
          <button class="boton contorno rojo ancho oculto boton-cerrar-otras" type="button" data-id="otras">Cerrar sesión en los demás dispositivos</button>
        </section>`);
      const seccionActividad = html(`
        <section aria-labelledby="tituloActividad">
          <div class="seccion" id="tituloActividad">Actividad reciente</div>
          <div class="tarjeta lista-seguridad" data-id="actividad" aria-busy="true"></div>
        </section>`);
      s.append(seccionSesiones, seccionActividad);
      const listaSesiones = seccionSesiones.querySelector('[data-id="sesiones"]');
      const listaActividad = seccionActividad.querySelector('[data-id="actividad"]');
      const botonOtras = seccionSesiones.querySelector('[data-id="otras"]');
      let abierto = true;

      const cargando = (lista) => {
        lista.setAttribute('aria-busy', 'true');
        lista.replaceChildren(html('<div class="estado-seguridad"><span class="girando" aria-hidden="true"></span><span>Cargando…</span></div>'));
      };
      const fallo = (lista, e, reintentar) => {
        lista.setAttribute('aria-busy', 'false');
        const caja = html(`
          <div class="estado-seguridad error-seguridad" role="alert">
            <span>${esc(e?.message ?? 'No se ha podido cargar')}</span>
            <button class="boton texto pequeno" type="button">Reintentar</button>
          </div>`);
        caja.querySelector('button').addEventListener('click', () => {
          haptica.click();
          reintentar();
        });
        lista.replaceChildren(caja);
      };

      async function cargarSesiones() {
        cargando(listaSesiones);
        try {
          const sesiones = await listarSesiones();
          if (!abierto) return;
          listaSesiones.setAttribute('aria-busy', 'false');
          // Este dispositivo arriba; los demás, del usado más recientemente al que menos.
          listaSesiones.replaceChildren(...sesiones.toSorted((a, b) => b.actual - a.actual).map(filaSesion));
          botonOtras.classList.toggle('oculto', sesiones.length < 2);
        } catch (e) {
          if (abierto) fallo(listaSesiones, e, cargarSesiones);
        }
      }

      async function cargarActividad() {
        cargando(listaActividad);
        try {
          const actividad = await leerActividad();
          if (!abierto) return;
          listaActividad.setAttribute('aria-busy', 'false');
          listaActividad.replaceChildren(...(actividad.length
            ? actividad.filter((a) => ACTIVIDAD[a.tipo]).map(filaActividad)
            : [html('<div class="estado-seguridad"><span>Todavía no hay nada que contar</span></div>')]));
        } catch (e) {
          if (abierto) fallo(listaActividad, e, cargarActividad);
        }
      }

      function filaSesion(sesion) {
        const detalle = sesion.actual ? `Este dispositivo · desde ${momentoRelativo(sesion.creado)}` : `Última vez ${momentoRelativo(sesion.visto)}`;
        const fila = html(`
          <div class="fila-seguridad">
            <span class="placa tono-${sesion.actual ? 'verde' : 'azul'}"><span class="icono" data-icono="${esMovil(sesion.dispositivo) ? 'movil' : 'ordenador'}"></span></span>
            <span class="columna-textos">
              <span class="titulo-fila">${esc(sesion.dispositivo || 'Dispositivo sin nombre')}</span>
              <span class="descripcion-fila">${esc(detalle)}</span>
            </span>
            ${sesion.actual ? '' : '<button class="boton texto pequeno rojo boton-cerrar-sesion" type="button">Cerrar</button>'}
          </div>`);
        fila.querySelector('button')?.addEventListener('click', () => {
          haptica.click();
          confirmar({
            titulo: `¿Cerrar la sesión de ${sesion.dispositivo || 'ese dispositivo'}?`,
            mensaje: 'Allí tendrá que volver a entrar con tu nombre y tu contraseña. Lo que no se hubiera subido todavía desde ese dispositivo se quedará en él hasta entonces.',
            boton: 'Cerrar sesión',
            hacer: () => cerrarSesionDe(sesion.id),
            hecho: 'Sesión cerrada en ese dispositivo'
          });
        });
        return fila;
      }

      function filaActividad(a) {
        const [ic, tono, texto] = ACTIVIDAD[a.tipo];
        const detalle = [a.dispositivo, momentoRelativo(a.momento)].filter(Boolean).join(' · ');
        return html(`
          <div class="fila-seguridad${a.tipo === 'fallo' ? ' aviso-fallo' : ''}">
            <span class="placa pequena tono-${tono}"><span class="icono" data-icono="${ic}"></span></span>
            <span class="columna-textos">
              <span class="titulo-fila">${esc(texto)}</span>
              <span class="descripcion-fila">${esc(detalle)}</span>
            </span>
          </div>`);
      }

      function confirmar({ titulo, mensaje, boton, hacer, hecho }) {
        dialogo({
          icono: 'close',
          titulo,
          mensaje,
          acciones: [
            { texto: 'Cancelar', segura: true },
            {
              texto: boton,
              rojo: true,
              alPulsar: async () => {
                try {
                  await hacer();
                  haptica.confirmar();
                  mostrarAviso(hecho, 'EXITO');
                } catch (e) {
                  haptica.rechazar();
                  mostrarAviso(e?.message ?? 'No se ha podido cerrar', 'ERROR');
                }
                if (abierto) {
                  cargarSesiones();
                  cargarActividad();
                }
              }
            }
          ]
        });
      }

      botonOtras.addEventListener('click', () => {
        haptica.click();
        confirmar({
          titulo: '¿Cerrar la sesión en los demás dispositivos?',
          mensaje: 'Solo seguirás dentro en este. Si crees que alguien sabe tu contraseña, cámbiala también en "Nombre y contraseña".',
          boton: 'Cerrar en los demás',
          hacer: cerrarSesionesOtras,
          hecho: 'Sesiones cerradas en los demás dispositivos'
        });
      });

      cargarSesiones();
      cargarActividad();
      return () => {
        abierto = false;
      };
    }
  });
}
