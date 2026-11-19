// AddSocioBottomSheetDialog.kt, AccionesSocioBottomSheetDialog.kt y ConfirmarEliminar.kt.
import * as estado from '../estado.js';
import { abrirHoja, dialogo } from '../paneles.js';
import { calcularBaseX2, cuotaComputable, CUOTAS } from '../calculos.js';
import { DIAS, diaPorId, getSemanaKey, rangoCorto, rangoCortoConAnio, rangoSemanaTexto, semanaKeyDesplazada, semanasEntre } from '../fechas.js';
import { esc, eurosCompacto, semanaRelativa, sinSimbolos } from '../formato.js';
import { activas, animarMoneda, desplegar, haptica, latido, LARGA_MS, muellePulsar } from '../movimiento.js';
import { crearCampo, filaFormato, grupoOpciones, html, marcar, opcionesImporte, tarjetaElegible } from '../componentes.js';
import { degradadoSocio } from '../filas.js';

/** Pide confirmación antes de borrar un socio; después aún se puede deshacer desde el aviso. */
export function confirmarEliminar(socio, alConfirmar, alCerrar = null) {
  return dialogo({
    icono: 'delete',
    titulo: `¿Eliminar a ${estado.nombreVisible(socio)}?`,
    mensaje: 'Se quitará de la lista y de las cuentas de la semana. Justo después podrás deshacerlo.',
    // El foco empieza en la acción segura: un Enter o un toque de más no borra nada.
    acciones: [{ texto: 'Cancelar', segura: true }, { texto: 'Eliminar', rojo: true, alPulsar: alConfirmar }],
    alCerrar
  });
}

/** Alta ([socio] null, en [semanaKey] y [dia]) o edición de un socio. */
export function abrirFormularioSocio({ socio = null, semanaKey, dia }) {
  return abrirHoja({
    tag: 'AddSocioDialog',
    completa: true,
    conBarra: true,
    construir: (panel) => construirFormulario(panel, socio, semanaKey, dia)
  });
}

function construirFormulario(panel, socioAEditar, semanaInicial, diaInicial) {
  let semanaKey = socioAEditar?.semanaKey ?? semanaInicial;
  let anadidos = 0;
  let esHecho = socioAEditar ? socioAEditar.hecho : true;
  let detallesAbiertos = socioAEditar ? !!(socioAEditar.nombreSocio.trim() || socioAEditar.notas.trim()) : false;
  let dialogoEliminar = null;

  const s = panel.scroll;
  s.append(html(`
    <div class="fila">
      <div class="crece">
        <h2 class="titulo-panel" style="font-size:24px">${socioAEditar ? 'Editar socio' : 'Nuevo socio'}</h2>
        <div class="subtitulo-panel" style="font-size:14px" data-id="subtitulo"></div>
      </div>
      <button class="boton-icono cerrar" type="button" data-cerrar aria-label="Cerrar"><span class="icono" data-icono="close"></span></button>
    </div>`));

  // Cabecera con la ganancia: verde si está conseguido, ámbar si está pendiente.
  const hero = html(`
    <div class="hero-socio">
      <div class="fondo verde"></div><div class="fondo ambar" style="opacity:0"></div><div class="luz"></div>
      <div class="interior">
        <div class="crece">
          <div class="etiqueta">Ganancia de este socio</div>
          <div class="ganancia" data-id="ganancia">+0,00 €</div>
          <div class="detalle" data-id="detalle"></div>
        </div>
        <span class="pastilla-clara" data-id="badge" style="margin-left:8px"></span>
      </div>
    </div>`);
  s.append(hero);

  s.append(html('<div class="seccion">Cuota mensual</div>'));
  const campoCuota = crearCampo({ etiqueta: 'Cantidad personalizada (€)', iconoCampo: 'euro', tipo: 'decimal', maxLength: 9 });
  campoCuota.el.style.marginTop = '10px';
  const cuotas = opcionesImporte(CUOTAS, campoCuota, () => actualizarVistaPrevia());
  s.append(cuotas.el, campoCuota.el);
  campoCuota.input.addEventListener('input', () => {
    campoCuota.error(null);
    actualizarVistaPrevia();
  });

  // Semana: la visible al abrirlo; se puede cambiar, también para mover un socio de semana.
  s.append(html('<div class="seccion">Semana</div>'));
  const selector = html(`
    <div class="tarjeta selector-semana-form">
      <button class="boton-icono" type="button" data-id="anterior" aria-label="Semana anterior"><span class="icono" data-icono="chevron_left"></span></button>
      <div class="centro" tabindex="0" data-id="contenedorSemana">
        <span class="fecha" data-id="fecha" aria-hidden="true"></span>
        <span class="relativa" data-id="relativa" aria-hidden="true"></span>
      </div>
      <button class="boton-icono" type="button" data-id="siguiente" aria-label="Semana siguiente"><span class="icono" data-icono="chevron_right"></span></button>
    </div>`);
  s.append(selector);

  s.append(html('<div class="seccion">Día</div>'));
  const dias = grupoOpciones(DIAS.map((d) => ({ id: d.id, html: d.corto, etiqueta: d.completo })), () => haptica.click());
  s.append(dias);

  s.append(html('<div class="seccion">Estado</div>'));
  const cardHecho = tarjetaElegible(html(`
    <span class="columna">
      <span class="placa p40 circulo tono-verde"><span class="icono" data-icono="check_circle"></span></span>
      <span class="titulo-fila">Conseguido</span>
      <span class="descripcion-fila">Suma a la semana</span>
    </span>`));
  const cardPendiente = tarjetaElegible(html(`
    <span class="columna">
      <span class="placa p40 circulo tono-naranja"><span class="icono" data-icono="schedule"></span></span>
      <span class="titulo-fila">Pendiente</span>
      <span class="descripcion-fila">Aún no suma</span>
    </span>`), { pendiente: true });
  const estados = html('<div class="estados"></div>');
  estados.append(cardHecho, cardPendiente);
  s.append(estados);

  // Nombre y notas, plegados porque son opcionales.
  const tarjetaDetalles = html(`
    <div class="tarjeta" style="margin-top:20px">
      <div class="fila-detalles" role="button" tabindex="0" data-id="filaDetalles">
        <span class="placa p40 tono-azul"><span class="icono" data-icono="person"></span></span>
        <span class="columna-textos">
          <span class="titulo-fila">Nombre y notas</span>
          <span class="descripcion-fila una-linea" data-id="resumenDetalles">Opcional</span>
        </span>
        <span class="icono flecha-detalles" data-icono="expand_more"></span>
      </div>
      <div class="contenedor-detalles" data-id="contenedorDetalles"></div>
    </div>`);
  const campoNombre = crearCampo({ etiqueta: 'Nombre del socio o identificador', iconoCampo: 'person', capitalizar: 'words' });
  const campoNotas = crearCampo({ etiqueta: 'Notas o comentarios', iconoCampo: 'notes', tipo: 'area' });
  campoNotas.el.style.marginTop = '10px';
  const contenedorDetalles = tarjetaDetalles.querySelector('[data-id="contenedorDetalles"]');
  contenedorDetalles.append(campoNombre.el, campoNotas.el);
  s.append(tarjetaDetalles);

  // Botones fijos abajo: "Guardar y otro" al añadir, "Eliminar" al editar.
  const barra = html(`
    <div>
      ${socioAEditar
        ? '<button class="boton contorno rojo" type="button" data-id="eliminar"><span class="icono" data-icono="delete"></span>Eliminar</button>'
        : '<button class="boton tonal" type="button" data-id="otro"><span class="icono" data-icono="add"></span>Guardar y otro</button>'}
      <button class="boton relleno" type="button" data-id="guardar" style="flex-grow:1.6"><span class="icono" data-icono="check"></span>${socioAEditar ? 'Guardar cambios' : 'Guardar socio'}</button>
    </div>`);
  panel.ponerBarra(barra);
  barra.querySelectorAll('.boton').forEach(muellePulsar);

  const $ = (id) => panel.el.querySelector(`[data-id="${id}"]`);

  function mostrarSemana() {
    const rango = rangoCorto(semanaKey);
    $('subtitulo').textContent = anadidos > 0
      ? `Semana del ${rango} · ${anadidos} ${anadidos === 1 ? 'añadido' : 'añadidos'}`
      : `Semana del ${rango}`;
    $('fecha').textContent = rangoCortoConAnio(semanaKey);
    const distancia = semanasEntre(getSemanaKey(), semanaKey) ?? 0;
    $('relativa').textContent = semanaRelativa(distancia, 'Esta semana');
    $('contenedorSemana').setAttribute('aria-label', `${rangoSemanaTexto(semanaKey)} · ${$('relativa').textContent}`);
  }

  function mostrarEstado(animar) {
    marcar(cardHecho, esHecho);
    marcar(cardPendiente, !esHecho);
    const ambar = hero.querySelector('.fondo.ambar');
    ambar.style.transition = animar && activas() ? `opacity ${LARGA_MS}ms cubic-bezier(0.32, 0.72, 0, 1)` : 'none';
    ambar.style.opacity = esHecho ? '0' : '1';
  }

  function actualizarVistaPrevia() {
    const cuota = cuotas.elegido() ?? 0;
    animarMoneda($('ganancia'), calcularBaseX2(cuota), '+');
    // Una cuota de más de 30 € cuenta como una de 30 (CUOTA_MAXIMA en calculos.js).
    const computable = cuotaComputable(cuota);
    $('detalle').textContent = !esHecho
      ? 'No suma hasta que esté conseguido'
      : computable < cuota
        ? `Cuota de ${eurosCompacto(cuota)}, cuenta ${eurosCompacto(computable)} × 2`
        : `Cuota de ${eurosCompacto(cuota)} × 2`;
    const badge = esHecho ? '✓ Conseguido' : '⏳ Pendiente';
    $('badge').textContent = badge;
    $('badge').setAttribute('aria-label', sinSimbolos(badge));
  }

  function elegirEstado(hecho) {
    if (hecho === esHecho) return;
    haptica.toggle();
    esHecho = hecho;
    mostrarEstado(true);
    actualizarVistaPrevia();
  }

  function mostrarDetalles(animar) {
    if (animar) desplegar(contenedorDetalles, detallesAbiertos);
    else contenedorDetalles.classList.toggle('oculto', !detallesAbiertos);
    tarjetaDetalles.querySelector('.flecha-detalles').classList.toggle('abierta', detallesAbiertos);
    $('filaDetalles').setAttribute('aria-label', detallesAbiertos ? 'Nombre y notas. Toca para plegarlos' : 'Nombre y notas, opcional. Toca para escribirlos');
    $('filaDetalles').setAttribute('aria-expanded', String(detallesAbiertos));
    actualizarResumenDetalles();
  }

  /** Plegado, muestra lo escrito para que no quede escondido. */
  function actualizarResumenDetalles() {
    const escrito = [campoNombre.valor(), campoNotas.valor()].map((t) => t.trim()).filter(Boolean);
    $('resumenDetalles').textContent = detallesAbiertos || !escrito.length ? 'Opcional' : escrito.join(' · ');
  }

  function alternarDetalles() {
    haptica.click();
    detallesAbiertos = !detallesAbiertos;
    mostrarDetalles(true);
    if (detallesAbiertos) setTimeout(() => campoNombre.foco(), 60);
    else document.activeElement?.blur?.();
  }

  function moverSemana(delta) {
    const nueva = semanaKeyDesplazada(semanaKey, delta);
    if (!nueva) return;
    haptica.click();
    semanaKey = nueva;
    mostrarSemana();
  }

  function guardar(otro) {
    const cuota = cuotas.elegido();
    if (cuota == null || cuota <= 0) {
      cuotas.marcar(cuotas.otro);
      campoCuota.el.classList.remove('oculto');
      campoCuota.error('Por favor introduce un importe de colaboración válido');
      campoCuota.foco();
      return;
    }
    const dia = dias.marcado() ?? diaInicial;
    const socio = {
      id: socioAEditar?.id ?? estado.nuevoId(),
      colaboracion: cuota,
      hecho: esHecho,
      dia,
      semanaKey,
      nombreSocio: campoNombre.valor().trim(),
      notas: campoNotas.valor().trim(),
      timestamp: socioAEditar?.timestamp ?? Date.now()
    };
    estado.guardarSocio(socio, !socioAEditar, socioAEditar?.semanaKey ?? null);
    if (otro && !socioAEditar) prepararSiguiente();
    else panel.cerrar();
  }

  /** Vacía nombre y notas; la cuota, el día, el estado y la semana se mantienen. */
  function prepararSiguiente() {
    anadidos++;
    document.activeElement?.blur?.();
    campoNombre.poner('');
    campoNotas.poner('');
    detallesAbiertos = false;
    mostrarDetalles(true);
    mostrarSemana();
    panel.scroll.scrollTo({ top: 0, behavior: activas() ? 'smooth' : 'auto' });
    // La cabecera se encoge un instante y rebota, para que se note que se ha guardado.
    latido(hero, -1);
  }

  // Estado inicial.
  if (socioAEditar) {
    campoNombre.poner(socioAEditar.nombreSocio);
    campoNotas.poner(socioAEditar.notas);
    cuotas.elegir(socioAEditar.colaboracion);
    dias.marcar(socioAEditar.dia);
  } else {
    cuotas.elegir(estado.cuotaPorDefecto());
    dias.marcar(diaInicial);
  }
  cuotas.ajustarCampo();
  mostrarSemana();
  mostrarEstado(false);
  mostrarDetalles(false);
  actualizarVistaPrevia();

  cardHecho.addEventListener('click', () => elegirEstado(true));
  cardPendiente.addEventListener('click', () => elegirEstado(false));
  [cardHecho, cardPendiente].forEach(muellePulsar);
  $('filaDetalles').addEventListener('click', alternarDetalles);
  $('filaDetalles').addEventListener('keydown', (e) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), alternarDetalles()));
  campoNombre.input.addEventListener('input', actualizarResumenDetalles);
  campoNotas.input.addEventListener('input', actualizarResumenDetalles);
  $('anterior').addEventListener('click', () => moverSemana(-1));
  $('siguiente').addEventListener('click', () => moverSemana(1));
  panel.el.querySelector('[data-cerrar]').addEventListener('click', () => panel.cancelar());
  $('guardar').addEventListener('click', () => {
    haptica.confirmar();
    guardar(false);
  });
  $('otro')?.addEventListener('click', () => {
    haptica.confirmar();
    guardar(true);
  });
  $('eliminar')?.addEventListener('click', () => {
    haptica.click();
    if (dialogoEliminar) return;
    // Se borra tal como está guardado: así "Deshacer" lo deja exactamente como estaba.
    dialogoEliminar = confirmarEliminar(socioAEditar, () => {
      estado.eliminarSocio(socioAEditar);
      panel.cerrar();
    }, () => (dialogoEliminar = null));
  });
  return () => dialogoEliminar?.cerrar();
}

/** Al mantener pulsado un socio: quién es y qué se puede hacer con él. */
export function abrirAccionesSocio(socio, { alEditar, alEliminar }) {
  return abrirHoja({
    tag: 'AccionesSocio',
    construir: (panel) => {
      const base = calcularBaseX2(socio.colaboracion);
      const dia = diaPorId(socio.dia).completo;
      panel.scroll.append(html(`
        <div class="fila" style="align-items:center">
          <div class="placa-acciones" style="background:${degradadoSocio(socio.hecho)}">
            <span class="cuota cifra">${esc(eurosCompacto(socio.colaboracion))}</span>
            <span class="base">${esc(eurosCompacto(base))} base</span>
          </div>
          <div class="columna-textos">
            <h2 class="titulo-panel dos-lineas">${esc(estado.nombreVisible(socio))}</h2>
            <div class="subtitulo-panel">${esc(`${dia} · ${rangoCorto(socio.semanaKey)}`)}</div>
            <span class="pastilla" style="margin-top:8px;align-self:flex-start;padding:3px 10px;${socio.hecho ? '' : 'background:var(--orange-container);color:var(--orange-pending)'}">${socio.hecho ? `Suma ${esc(eurosCompacto(base))}` : 'Pendiente'}</span>
          </div>
        </div>`));
      if (socio.notas.trim()) panel.scroll.append(html(`<div class="nota-socio">${esc(socio.notas)}</div>`));
      panel.scroll.append(html('<div class="seccion" style="margin-top:22px">Qué quieres hacer</div>'));
      panel.scroll.append(filaFormato({
        iconoPlaca: 'edit', tono: 'verde', titulo: 'Editar socio', detalle: 'Cuota, semana, día, estado o notas',
        alPulsar: () => {
          panel.cerrar();
          alEditar(socio);
        }
      }));
      const eliminar = filaFormato({
        iconoPlaca: 'delete', tono: 'rojo', titulo: 'Eliminar socio', detalle: 'Te pedirá confirmación y podrás deshacerlo', tituloRojo: true,
        alPulsar: () => {
          panel.cerrar();
          alEliminar(socio);
        }
      });
      eliminar.style.marginTop = '10px';
      panel.scroll.append(eliminar);
    }
  });
}
