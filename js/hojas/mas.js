// MasBottomSheetDialog.kt y ActualizacionBottomSheetDialog.kt: versión y actualizaciones, copias, bloqueo y tema.
import { abrirHoja, dialogo } from '../paneles.js';
import { cuenta, ErrorCuenta, pendientesDeSubir, salir, sincronizar } from '../nube.js';
import { mostrarAviso } from '../avisos.js';
import { actualizaciones, bloqueo, crearLlave, disponibilidadBloqueo, elegirTema, guardarBloqueo, identificar, TEMAS, temaGuardado } from '../ajustes.js';
import { momentoRelativo } from '../fechas.js';
import { esc, numero } from '../formato.js';
import { icono } from '../iconos.js';
import { haptica, muellePulsar } from '../movimiento.js';
import { filaFormato, html, marcar, tarjetaElegible } from '../componentes.js';
import { alCambiarInstalacion, instalar, sePuedeInstalar } from '../instalacion.js';
import { alCambiarProteccion, estadoDatos, protegerDatos, sePuedeProteger } from '../almacenamiento.js';
import { MuestraTema } from '../vistas/muestra-tema.js';
import { AnimacionDescarga } from '../vistas/animacion-descarga.js';
import { abrirCopias } from './copias.js';

export function abrirMas() {
  return abrirHoja({
    tag: 'MasBottomSheet',
    construir: (panel) => {
      const s = panel.scroll;
      const cabecera = html(`
        <section class="hero panel">
          <div class="hero-interior">
            <div class="fila">
              <div class="icono-app"><img src="icons/icono-placa.png" alt=""></div>
              <div class="crece" style="margin-left:16px">
                <h2 class="nombre-app" style="margin:0">Gestión de Socios</h2>
              </div>
            </div>
            <div class="divisor-cabecera"></div>
            <div class="fila" aria-live="polite">
              <span class="placa-version" data-id="placa"></span>
              <span class="columna-textos" style="margin:0 12px">
                <span style="font-size:15px;font-weight:600;color:#fff" data-id="titulo"></span>
                <span style="margin-top:1px;font-size:12px;color:var(--header-label-neto)" data-id="detalle"></span>
              </span>
              <button class="boton-version" type="button" data-id="boton"></button>
            </div>
          </div>
        </section>`);
      s.append(cabecera);
      const botonVersion = cabecera.querySelector('[data-id="boton"]');
      muellePulsar(botonVersion);

      // --- Cuenta: con quién se ha entrado y cómo va la sincronización (ver nube.js) ---
      s.append(html('<div class="seccion">Cuenta</div>'));
      const tarjetaCuenta = html(`
        <button class="tarjeta tocable fila-formato" type="button">
          <span class="placa tono-primario" data-id="placa"></span>
          <span class="columna-textos"><span class="titulo-fila" data-id="titulo"></span><span class="descripcion-fila" data-id="detalle" aria-live="polite"></span></span>
          <span class="icono flecha" data-icono="refresh"></span>
        </button>`);
      const $cuenta = (id) => tarjetaCuenta.querySelector(`[data-id="${id}"]`);
      const pintarCuenta = () => {
        $cuenta('titulo').textContent = cuenta.sesion?.nombre ?? 'Sin sesión';
        const ultima = cuenta.ultima ? `Sincronizado ${momentoRelativo(cuenta.ultima)}` : 'Aún sin sincronizar';
        const textos = {
          SINCRONIZANDO: 'Sincronizando…',
          AL_DIA: ultima,
          SIN_CONEXION: 'Sin conexión: lo que cambies se subirá al volver',
          ERROR: cuenta.mensaje ?? 'No se ha podido sincronizar',
          INACTIVA: 'Inicia sesión para sincronizar'
        };
        $cuenta('detalle').textContent = textos[cuenta.estado] ?? ultima;
        const sincronizando = cuenta.estado === 'SINCRONIZANDO';
        $cuenta('placa').innerHTML = sincronizando
          ? '<span class="girando"></span>'
          : `<span class="icono">${icono(cuenta.estado === 'ERROR' || cuenta.estado === 'SIN_CONEXION' ? 'error' : 'person')}</span>`;
        $cuenta('placa').className = `placa ${cuenta.estado === 'ERROR' ? 'tono-rojo' : cuenta.estado === 'SIN_CONEXION' ? 'tono-ambar' : 'tono-primario'}`;
      };
      tarjetaCuenta.addEventListener('click', () => {
        haptica.click();
        sincronizar();
      });
      tarjetaCuenta.setAttribute('aria-label', 'Sincronizar ahora');
      muellePulsar(tarjetaCuenta);
      const tarjetaSalir = filaFormato({
        iconoPlaca: 'close', tono: 'rojo', titulo: 'Cerrar sesión', detalle: 'Tus datos se quedan en la cuenta y se quitan de este dispositivo', tituloRojo: true, flecha: false,
        alPulsar: () => confirmarSalir(panel)
      });
      tarjetaSalir.style.marginTop = '10px';
      pintarCuenta();
      const dejarCuenta = cuenta.suscribir(pintarCuenta);
      s.append(tarjetaCuenta, tarjetaSalir);

      // Solo en la web y solo si el navegador puede instalarla (ver instalacion.js).
      const seccionApp = html('<div class="seccion">App</div>');
      const tarjetaInstalar = filaFormato({
        iconoPlaca: 'import', tono: 'azul', titulo: 'Instalar la app', detalle: 'En la pantalla de inicio, sin la barra del navegador',
        alPulsar: async () => {
          // Directamente desde el toque: la instalación necesita que la haya pedido el usuario.
          const resultado = await instalar();
          if (resultado === 'instalada') mostrarAviso('App instalada', 'EXITO', 'Ya la tienes en la pantalla de inicio');
          else if (resultado === 'error') mostrarAviso('No se ha podido instalar', 'ERROR', 'Prueba desde el menú del navegador');
        }
      });
      const mostrarInstalar = () => {
        seccionApp.hidden = !sePuedeInstalar();
        tarjetaInstalar.hidden = !sePuedeInstalar();
      };
      mostrarInstalar();
      const dejarInstalar = alCambiarInstalacion(mostrarInstalar);
      s.append(seccionApp, tarjetaInstalar);

      s.append(html('<div class="seccion">Datos</div>'));
      const copias = html(`
        <button class="tarjeta tocable fila-formato" type="button">
          <span class="placa tono-verde"><span class="icono" data-icono="backup"></span></span>
          <span class="columna-textos"><span class="titulo-fila">Copias y exportación</span><span class="descripcion-fila">PDF, Excel o copia de seguridad</span></span>
          <span class="icono flecha" data-icono="chevron_right"></span>
        </button>`);
      copias.addEventListener('click', () => {
        panel.cerrar();
        abrirCopias();
      });
      muellePulsar(copias);
      const tarjetaBloqueo = html(`
        <button class="tarjeta tocable tarjeta-interruptor" type="button" role="switch" style="margin-top:10px">
          <span class="placa tono-azul"><span class="icono" data-icono="lock"></span></span>
          <span class="columna-textos"><span class="titulo-fila">Bloquear la app</span><span class="descripcion-fila">Pide la huella, la cara o el PIN del móvil al abrirla</span></span>
          <span class="interruptor" aria-hidden="true"></span>
        </button>`);
      muellePulsar(tarjetaBloqueo);
      s.append(copias, tarjetaBloqueo);

      // Solo en la web: si el navegador puede borrar los datos para hacer sitio (ver almacenamiento.js).
      const tarjetaDatos = html(`
        <button class="tarjeta tocable fila-formato" type="button" style="margin-top:10px" hidden>
          <span class="placa" data-id="placa"><span class="icono" data-id="icono"></span></span>
          <span class="columna-textos"><span class="titulo-fila" data-id="titulo"></span><span class="descripcion-fila" data-id="detalle"></span></span>
        </button>`);
      const $datos = (id) => tarjetaDatos.querySelector(`[data-id="${id}"]`);
      let datosProtegidos = false;
      const pintarDatos = async () => {
        if (!sePuedeProteger) return;
        const { protegidos, ocupado } = await estadoDatos();
        datosProtegidos = protegidos;
        const ocupan = ocupado != null ? ` · la app ocupa ${tamano(ocupado)}` : '';
        $datos('placa').className = `placa ${protegidos ? 'tono-verde' : 'tono-ambar'}`;
        $datos('icono').innerHTML = icono(protegidos ? 'check_circle' : 'info');
        $datos('titulo').textContent = protegidos ? 'Datos protegidos' : 'Datos sin proteger';
        $datos('detalle').textContent = protegidos
          ? `El navegador no los borrará para hacer sitio${ocupan}`
          : 'El navegador podría borrarlos si le falta espacio. Toca para protegerlos';
        tarjetaDatos.disabled = protegidos;
        tarjetaDatos.classList.toggle('tocable', !protegidos);
        tarjetaDatos.hidden = false;
      };
      tarjetaDatos.addEventListener('click', async () => {
        if (datosProtegidos) return;
        haptica.click();
        if (await protegerDatos()) mostrarAviso('Datos protegidos', 'EXITO', 'El navegador ya no los borrará para hacer sitio');
        else mostrarAviso('El navegador no lo permite todavía', 'INFO', sePuedeInstalar() ? 'Instala la app y vuelve a intentarlo' : 'Lo concede cuando usas la app a menudo');
      });
      muellePulsar(tarjetaDatos);
      pintarDatos();
      const dejarDatos = alCambiarProteccion(pintarDatos);
      s.append(tarjetaDatos);

      s.append(html('<div class="seccion">Tema</div>'));
      const temas = html('<div class="temas"></div>');
      const actual = temaGuardado();
      const opciones = TEMAS.map((t) => {
        const tarjeta = tarjetaElegible(html(`<span class="columna"><canvas aria-hidden="true"></canvas><span class="nombre-tema"><span class="icono">${icono(t.icono)}</span>${esc(t.nombre)}</span></span>`));
        tarjeta.setAttribute('aria-label', `Tema: ${t.nombre}`);
        marcar(tarjeta, t.id === actual);
        temas.append(tarjeta);
        new MuestraTema(tarjeta.querySelector('canvas'), t.id);
        tarjeta.addEventListener('click', () => {
          if (t.id === temaGuardado()) return;
          haptica.click();
          opciones.forEach(([id, el]) => marcar(el, id === t.id));
          // Un instante para que se vea la marca antes de cambiar los colores.
          setTimeout(() => elegirTema(t.id), 250);
        });
        muellePulsar(tarjeta);
        return [t.id, tarjeta];
      });
      s.append(temas);

      // --- Bloqueo ---
      const mostrarBloqueo = () => {
        tarjetaBloqueo.querySelector('.interruptor').classList.toggle('encendido', bloqueo.activo);
        tarjetaBloqueo.setAttribute('aria-checked', String(bloqueo.activo));
        tarjetaBloqueo.setAttribute('aria-label', `Bloquear la app: ${bloqueo.activo ? 'activado' : 'desactivado'}`);
      };
      const cambiarBloqueo = (activo) => {
        guardarBloqueo(activo);
        mostrarBloqueo();
        mostrarAviso(activo ? 'App bloqueada: pedirá la huella o el PIN al abrirla' : 'Ya no se pedirá la huella ni el PIN', activo ? 'EXITO' : 'INFO');
      };
      tarjetaBloqueo.addEventListener('click', async () => {
        haptica.click();
        const disponibilidad = await disponibilidadBloqueo();
        // Quitarlo siempre se puede; ponerlo, solo si hay con qué.
        if (bloqueo.activo) {
          if (disponibilidad !== 'OK' || (await identificar())) cambiarBloqueo(false);
        } else if (disponibilidad === 'OK') {
          if (await crearLlave()) cambiarBloqueo(true);
        } else if (disponibilidad === 'SIN_BLOQUEO') {
          mostrarAviso('Pon primero un bloqueo de pantalla en el móvil (PIN, patrón, huella o cara) para poder usar esto', 'INFO');
        } else {
          mostrarAviso('Este navegador no permite bloquear la app con la huella ni el PIN', 'ERROR');
        }
      });
      mostrarBloqueo();

      // --- Versión ---
      const $ = (id) => cabecera.querySelector(`[data-id="${id}"]`);
      const pintarVersion = () => {
        const e = actualizaciones.estado;
        const buscando = e.tipo === 'BUSCANDO';
        const comprobado = () => `Comprobado ${momentoRelativo(actualizaciones.ultimaComprobacion)}`;
        const [ic, titulo, detalle] = {
          SIN_COMPROBAR: ['system_update', '¿Hay una versión nueva?', actualizaciones.ultimaComprobacion > 0 ? comprobado() : 'Todavía no se ha comprobado'],
          BUSCANDO: ['system_update', 'Buscando actualizaciones…', 'Consultando el servidor'],
          AL_DIA: ['check_circle', 'Tienes la última versión', comprobado()],
          DISPONIBLE: ['new_releases', 'Actualización disponible', 'Lista para instalar desde aquí'],
          ERROR: ['error', 'No se ha podido comprobar', 'Revisa la conexión a internet']
        }[e.tipo];
        $('placa').innerHTML = buscando ? '<span class="girando"></span>' : `<span class="icono">${icono(ic)}</span>`;
        $('titulo').textContent = titulo;
        $('detalle').textContent = detalle;
        const disponible = e.tipo === 'DISPONIBLE';
        const texto = { SIN_COMPROBAR: 'Buscar', AL_DIA: 'Buscar', BUSCANDO: 'Buscando…', ERROR: 'Reintentar', DISPONIBLE: 'Actualizar' }[e.tipo];
        botonVersion.innerHTML = `<span class="icono">${icono(disponible ? 'system_update' : 'refresh')}</span>${texto}`;
        botonVersion.disabled = buscando;
        botonVersion.style.opacity = buscando ? '0.6' : '1';
        botonVersion.classList.toggle('destacado', disponible);
      };
      botonVersion.addEventListener('click', () => {
        const e = actualizaciones.estado;
        if (e.tipo === 'DISPONIBLE') {
          panel.cerrar();
          abrirActualizacion(e.publicada);
        } else {
          actualizaciones.buscar();
        }
      });
      pintarVersion();
      const dejar = actualizaciones.suscribir(pintarVersion);
      const dejarBloqueo = bloqueo.alCambiar(mostrarBloqueo);
      return () => {
        dejar();
        dejarBloqueo();
        dejarInstalar();
        dejarDatos();
        dejarCuenta();
      };
    }
  });
}

/** Cerrar sesión: antes se sube lo pendiente; si no se puede, se pregunta si salir igualmente. */
function confirmarSalir(panel) {
  dialogo({
    icono: 'close',
    titulo: '¿Cerrar sesión?',
    mensaje: 'Tus socios se quedan guardados en tu cuenta y se quitan de este dispositivo. Para volver a verlos, inicia sesión otra vez.',
    acciones: [
      { texto: 'Cancelar', segura: true },
      {
        texto: 'Cerrar sesión',
        rojo: true,
        alPulsar: async () => {
          panel.cerrar();
          try {
            await salir();
          } catch (e) {
            if (!(e instanceof ErrorCuenta) || e.codigo !== 'pendientes') throw e;
            const n = await pendientesDeSubir();
            dialogo({
              icono: 'error',
              titulo: 'Hay cambios sin subir',
              mensaje: `${n === 1 ? 'Un cambio no se ha podido' : `${n} cambios no se han podido`} subir a tu cuenta (¿sin conexión?). Si cierras sesión ahora, se perderán.`,
              acciones: [
                { texto: 'Cancelar', segura: true },
                { texto: 'Cerrar igualmente', rojo: true, alPulsar: () => salir({ forzar: true }) }
              ]
            });
          }
        }
      }
    ]
  });
}

const tamano = (bytes) => (bytes >= 1024 * 1024 ? `${numero(bytes / 1024 / 1024, 1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} kB`);

/** Descarga de una versión nueva con su animación; al terminar, la app se abre con ella. */
export function abrirActualizacion(publicada) {
  actualizaciones.descargar(publicada);
  return abrirHoja({
    tag: 'ActualizacionBottomSheet',
    alCancelar: () => actualizaciones.descarga.tipo === 'EN_CURSO' && actualizaciones.cancelar(),
    construir: (panel) => {
      const contenido = html(`
        <div class="panel-actualizacion">
          <canvas class="animacion-descarga" aria-hidden="true"></canvas>
          <h2 class="titulo-descarga" style="margin:0" aria-live="polite" data-id="titulo"></h2>
          <div class="detalle-descarga" data-id="detalle"></div>
          <div class="tarjeta novedades oculto" data-id="novedades"><div class="titulo-fila">Novedades</div><div class="descripcion-fila" data-id="textoNovedades"></div></div>
          <button class="boton relleno ancho pequeno oculto" type="button" data-id="principal" style="margin-top:16px"></button>
          <button class="boton contorno ancho pequeno oculto" type="button" data-id="recargar" style="margin-top:6px">Recargar la página</button>
          <button class="boton texto ancho" type="button" data-id="secundario" style="margin-top:4px">Cancelar</button>
        </div>`);
      panel.scroll.append(contenido);
      const $ = (id) => contenido.querySelector(`[data-id="${id}"]`);
      const animacion = new AnimacionDescarga(contenido.querySelector('canvas'));
      const total = publicada.tamano ?? 0;
      let mostrado = null;
      let instalando = false;

      $('titulo').textContent = 'Descargando la actualización';
      const novedades = publicada.novedades ?? [];
      $('novedades').classList.toggle('oculto', !novedades.length);
      $('textoNovedades').textContent = novedades.map((n) => `•  ${n}`).join('\n');

      const instalar = async () => {
        if (instalando) return;
        instalando = true;
        $('detalle').textContent = 'Abriendo la versión nueva…';
        if (!(await actualizaciones.instalar())) {
          instalando = false;
          $('detalle').textContent = 'No se ha podido abrir la versión nueva.';
        }
      };
      const botonesFinales = (texto, accion) => {
        $('principal').textContent = texto;
        $('principal').onclick = accion;
        $('principal').classList.remove('oculto');
        $('secundario').textContent = 'Cerrar';
        $('secundario').onclick = () => panel.cerrar();
      };

      animacion.alMostrarProgreso = (f) => {
        if (mostrado?.tipo === 'FALLIDA' || !total) return;
        $('detalle').textContent = `${tamano(total * f)} de ${tamano(total)}`;
      };
      animacion.alCompletar = () => {
        $('titulo').textContent = 'Actualización descargada';
        $('detalle').textContent = 'Abriendo la versión nueva…';
        botonesFinales('Instalar', instalar);
      };
      animacion.alTerminar = instalar;

      const pintar = () => {
        const d = actualizaciones.descarga;
        const anterior = mostrado;
        mostrado = d;
        if (d.tipo === 'INACTIVA') return panel.cerrar();
        if (d.tipo === 'EN_CURSO') {
          if (anterior?.tipo !== 'EN_CURSO') {
            $('principal').classList.add('oculto');
            $('recargar').classList.add('oculto');
            $('secundario').textContent = 'Cancelar';
            $('secundario').onclick = () => actualizaciones.cancelar();
            $('titulo').textContent = 'Descargando la actualización';
          }
          animacion.mostrarProgreso(d.progreso, anterior == null);
        } else if (d.tipo === 'COMPLETADA') {
          animacion.completar(anterior != null);
        } else if (d.tipo === 'FALLIDA') {
          animacion.fallar(anterior != null);
          $('titulo').textContent = 'No se ha podido descargar';
          $('detalle').textContent = d.motivo;
          botonesFinales('Reintentar', () => actualizaciones.descargar(publicada));
          $('recargar').classList.remove('oculto');
          $('recargar').onclick = () => location.reload();
        }
      };
      $('secundario').onclick = () => actualizaciones.cancelar();
      pintar();
      const dejar = actualizaciones.suscribir(pintar);
      return () => {
        dejar();
        animacion.detener();
      };
    }
  });
}
