// MainActivity.kt con CabeceraResumen, SelectorSemana, FiltroDias, ListaSemana y DeslizarEntreSemanas: la pantalla
// principal y lo que abre.
import * as estado from './estado.js';
import { mostrarAviso } from './avisos.js';
import { hayPanelAbierto } from './paneles.js';
import { siguienteMetaBonus } from './calculos.js';
import { DIAS, diaPorId, diaSemanaActual, getSemanaKey, indiceDeHoy, rangoCorto, rangoCortoConAnio } from './fechas.js';
import { decimalesCompactos, etiquetaDescontado, eurosCompacto, moneda, porcentaje, semanaRelativa, TEXTOS } from './formato.js';
import {
  activas, animarMoneda, CORTA_MS, haptica, latido, MEDIA_MS, muellePulsar, overshoot, reiniciarMoneda
} from './movimiento.js';
import { autoajustar, grupoOpciones, pintarIconos } from './componentes.js';
import { agrupadasPorDia, ListaSocios, sinAgrupar } from './filas.js';
import { ProgresoBonus } from './vistas/progreso-bonus.js';
import { FranjaSemana } from './vistas/franja-semana.js';
import { abrirAccionesSocio, abrirFormularioSocio, confirmarEliminar } from './hojas/socio.js';
import { abrirBonus, abrirCobro } from './hojas/ajustes-semana.js';
import { abrirListaSocios } from './hojas/lista-socios.js';
import { abrirHistorico } from './hojas/historico.js';
import { abrirMas } from './hojas/mas.js';

const $ = (id) => document.getElementById(id);
const curvaCss = 'cubic-bezier(0.32, 0.72, 0, 1)';

let franja, progreso, lista, filtro;
let dialogoEliminar = null;

export function iniciarPantalla() {
  pintarIconos(document.body);
  franja = new FranjaSemana($('franjaSemana'));
  progreso = new ProgresoBonus($('progresoBonus'));
  autoajustar($('cifraNeto'), 44, 20);
  ['importeBase', 'importeBonus', 'importeIrpf'].forEach((id) => autoajustar($(id), 16, 10));
  autoajustar($('etiquetaIrpf'), 11.5, 8);
  autoajustar($('rangoSemana'), 16, 11);

  lista = new ListaSocios($('listaSocios'), {
    alCambiarHecho: (s, hecho) => estado.setSocioHecho(s.id, hecho),
    alEditar: editar,
    alEliminar: eliminar,
    alAcciones: (s) => abrirAccionesSocio(s, { alEditar: editar, alEliminar: eliminar })
  });

  filtro = grupoOpciones(
    [{ id: 0, peso: 1.25, etiqueta: 'Todos los días' }, ...DIAS.map((d) => ({ id: d.id, etiqueta: d.completo }))].map((o) => ({ ...o, html: '' })),
    (id, boton) => {
      haptica.click();
      latido(boton, 0.6);
      estado.setDiaFiltro(id === 0 ? null : id);
    },
    { alto: 58, tamano: 13 }
  );
  filtro.querySelectorAll('.opcion').forEach((b) => {
    b.style.padding = '0';
    b.style.lineHeight = 'calc(1.2em + 1px)';
  });
  $('filtroDias').replaceWith(filtro);
  filtro.id = 'filtroDias';

  configurarSelector();
  configurarCabecera();
  configurarScroll();
  configurarDeslizar();
  configurarTitulo();

  $('fab').addEventListener('click', () => nuevoSocio());
  $('vacio').addEventListener('click', () => nuevoSocio());
  $('btnBuscar').addEventListener('click', () => abrirListaSocios('BUSCAR'));
  $('btnHistorico').addEventListener('click', () => abrirHistorico());
  $('btnMas').addEventListener('click', () => abrirMas());
  [$('fab'), $('cajaBonus'), $('filaCobro'), $('avisoPendientes')].forEach(muellePulsar);

  estado.alEvento((e) => {
    if (e.tipo === 'mensaje') mostrarAviso(e.titulo, e.tipoAviso, e.detalle);
    else if (e.tipo === 'socioEliminado') {
      mostrarAviso('Socio eliminado', 'BORRADO', estado.nombreVisible(e.socio), 'Deshacer', () => estado.restaurarSocio(e.socio));
    }
  });
  estado.suscribir(render);
  render();
}

export function nuevoSocio() {
  const ui = estado.ui();
  abrirFormularioSocio({ semanaKey: ui.semanaKey, dia: ui.diaFiltro ?? diaSemanaActual() });
}

const editar = (socio) => abrirFormularioSocio({ socio });

/** Pide confirmación antes de borrar; después aún se puede deshacer desde el aviso. */
function eliminar(socio) {
  if (dialogoEliminar) return;
  dialogoEliminar = confirmarEliminar(socio, () => estado.eliminarSocio(socio), () => (dialogoEliminar = null));
}

function render() {
  const ui = estado.ui();
  mostrarSelector(ui);
  mostrarCabecera(ui);
  mostrarFiltro(ui);
  mostrarLista(ui);
  const pendientes = estado.avisoPendientes();
  $('avisoPendientes').classList.toggle('oculto', pendientes.length === 0);
  $('textoPendientes').textContent = TEXTOS.pendientesAviso(pendientes.length);
}

// --- Selector de semana ---

let anteriorSelector = null;

function configurarSelector() {
  $('btnSemanaAnterior').addEventListener('click', () => estado.cambiarSemana(-1));
  $('btnSemanaSiguiente').addEventListener('click', () => estado.cambiarSemana(1));
  for (const id of ['hoyIzq', 'hoyDer']) {
    const b = $(id).firstElementChild;
    b.addEventListener('click', () => estado.irASemanaActual());
    muellePulsar(b);
    b.setAttribute('aria-hidden', 'true');
  }
  [$('btnSemanaAnterior'), $('btnSemanaSiguiente')].forEach(muellePulsar);
}

function mostrarSelector(ui) {
  const distancia = ui.weekOffset;
  const previa = anteriorSelector;
  anteriorSelector = [ui.semanaKey, distancia];
  const momento = distancia < 0 ? 'PASADA' : distancia > 0 ? 'FUTURA' : 'ACTUAL';
  // Los días se comprueban siempre: hoy puede cambiar con la app abierta.
  franja.mostrar(momento, indiceDeHoy(), !!previa);
  if (previa && previa[0] === ui.semanaKey && previa[1] === distancia) return;
  const direccion = previa ? Math.sign(distancia - previa[1]) : 0;
  // El año solo si no es el de ahora: "19–23 oct" cabe hasta con "Hoy" al lado.
  const delAnioActual = ui.semanaKey.slice(0, 4) === getSemanaKey(0).slice(0, 4);
  $('rangoSemana').textContent = delAnioActual ? rangoCorto(ui.semanaKey) : rangoCortoConAnio(ui.semanaKey);
  $('textoSubtituloSemana').textContent = semanaRelativa(distancia, 'Semana actual');
  const [punto, texto] = distancia === 0 ? ['--primary', '--primary'] : distancia < 0 ? ['--stroke', '--text-sub'] : ['--blue-text', '--blue-text'];
  $('puntoSemana').style.background = `var(${punto})`;
  $('textoSubtituloSemana').style.color = `var(${texto})`;
  mostrarHoy(distancia, !!previa);
  if (direccion !== 0 && activas()) {
    [$('rangoSemana'), $('subtituloSemana')].forEach((el, i) => {
      el.animate([{ transform: `translateX(${18 * direccion}px)`, opacity: 0 }, { transform: 'none', opacity: 1 }],
        { duration: 260, delay: i * 40, easing: curvaCss, fill: 'backwards' });
    });
  }
}

/** "Hoy" aparece del lado de la semana actual; la tarjeta se estrecha o se ensancha con suavidad. */
function mostrarHoy(distancia, animar) {
  poner($('hoyIzq'), distancia > 0, animar);
  poner($('hoyDer'), distancia < 0, animar);
}

function poner(envoltura, visible, animar) {
  const boton = envoltura.firstElementChild;
  if ((envoltura.dataset.visible === 'true') === visible) return;
  envoltura.dataset.visible = String(visible);
  boton.tabIndex = visible ? 0 : -1;
  boton.setAttribute('aria-hidden', String(!visible));
  const destino = visible ? boton.offsetWidth + 8 : 0;
  envoltura.getAnimations().forEach((a) => a.cancel());
  boton.getAnimations().forEach((a) => a.cancel());
  const desde = envoltura.getBoundingClientRect().width;
  envoltura.style.width = `${destino}px`;
  boton.style.opacity = visible ? '1' : '0';
  if (!animar || !activas()) return;
  envoltura.animate([{ width: `${desde}px` }, { width: `${destino}px` }], { duration: 260, easing: curvaCss });
  // Al aparecer, "Hoy" espera a que la tarjeta le haya dejado sitio.
  boton.animate([{ opacity: visible ? 0 : 1 }, { opacity: visible ? 1 : 0 }],
    { duration: visible ? CORTA_MS : CORTA_MS / 2, delay: visible ? 70 : 0, easing: curvaCss, fill: 'backwards' });
}

// --- Cabecera verde y tarjeta del bonus ---

let bonusAnterior = null;
let sociosContados = null;

function configurarCabecera() {
  $('cajaBonus').addEventListener('click', () => {
    const ui = estado.ui();
    abrirBonus(ui.semanaKey, ui.bonoInfoSemana);
  });
  $('filaCobro').addEventListener('click', () => {
    haptica.click();
    mostrarCobro();
  });
  // Doble toque en el neto: ajustarlo a lo cobrado. Un toque solo no hace nada, para no abrirlo sin querer.
  // Con el clic y no con pointerup: si el panel se abriera al levantar el dedo, el clic de ese mismo toque caería
  // en su velo y lo cerraría al momento.
  const neto = $('netoCabecera');
  let ultimo = 0, x = 0, y = 0;
  neto.addEventListener('click', (e) => {
    const ahora = e.timeStamp;
    if (ahora - ultimo < 300 && Math.hypot(e.clientX - x, e.clientY - y) < 40) {
      ultimo = 0;
      haptica.confirmar();
      mostrarCobro();
      return;
    }
    ultimo = ahora;
    x = e.clientX;
    y = e.clientY;
  });
  neto.addEventListener('keydown', (e) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), mostrarCobro()));
  $('avisoPendientes').addEventListener('click', (e) => {
    if (e.target.closest('#btnOcultarPendientes')) return;
    haptica.click();
    abrirListaSocios('PENDIENTES');
  });
  $('btnOcultarPendientes').addEventListener('click', () => {
    haptica.click();
    estado.ocultarAvisoPendientes();
  });
  for (const id of ['cajaBonus', 'filaCobro', 'avisoPendientes']) {
    $(id).addEventListener('keydown', (e) => (e.key === 'Enter' || e.key === ' ') && e.target === $(id) && (e.preventDefault(), $(id).click()));
  }
}

function mostrarCobro() {
  const ui = estado.ui();
  if (ui.resumen) abrirCobro(ui);
}

function mostrarCabecera(ui) {
  const r = ui.resumen;
  animarMoneda($('cifraNeto'), r.totalNeto, '');
  const etiquetaNeto = r.tieneCobroReal ? 'Neto cobrado' : 'Neto de la semana';
  $('etiquetaNeto').textContent = etiquetaNeto;
  $('netoCabecera').setAttribute('aria-label', `${etiquetaNeto}: ${moneda(r.totalNeto)}. Doble toque: ajustar a lo cobrado`);
  mostrarFilaCobro(r, ui.cobroSemana);
  animarMoneda($('importeBase'), r.gananciasBaseX2, '+', decimalesCompactos(r.gananciasBaseX2));
  animarMoneda($('importeBonus'), r.bonusSemanal, '+', decimalesCompactos(r.bonusSemanal));
  animarMoneda($('importeIrpf'), r.retencionIrpf, '-');
  $('etiquetaBonus').textContent = r.esBonoRegaloAplicado ? 'Bonus manual' : 'Bonus';
  $('etiquetaIrpf').textContent = `IRPF ${porcentaje(r.porcentajeIrpf)}`;

  const hechos = r.totalSociosHechos;
  $('tituloBonus').textContent = `Bonus de la semana: ${eurosCompacto(r.bonusSemanal, '+')}`;
  $('pastillaBonus').setAttribute('aria-label', `${hechos} de ${r.siguienteMetaSocios} socios para el siguiente tramo`);

  // La barra y su contador avanzan (o retroceden) hasta los socios de la semana; solo la primera vez sin animar.
  const anterior = bonusAnterior;
  const mismaSemana = anterior && anterior[0] === ui.semanaKey;
  progreso.mostrar(hechos, !!anterior, !mismaSemana);
  mostrarContador(hechos, !!anterior);
  // Un latido al subir de tramo dentro de la misma semana.
  if (anterior && mismaSemana && anterior[1] < r.bonusSemanal) {
    latido($('tituloBonus'));
    latido($('cajaBonus'));
  }
  bonusAnterior = [ui.semanaKey, r.bonusSemanal];
}

/** El contador "3 / 5" cambia como un cuentakilómetros: el número sale hacia arriba y el nuevo entra desde abajo. */
function mostrarContador(hechos, animar) {
  const vista = $('contadorBonus');
  const texto = `${hechos} / ${siguienteMetaBonus(hechos)[0]}`;
  const anterior = sociosContados;
  sociosContados = hechos;
  if (vista.textContent === texto) return;
  vista.getAnimations().forEach((a) => a.cancel());
  if (!animar || anterior == null || anterior === hechos || !activas()) {
    vista.textContent = texto;
    return;
  }
  const salto = 14 * (hechos > anterior ? 1 : -1);
  const sale = vista.animate([{ transform: 'none', opacity: 1 }, { transform: `translateY(${-salto}px)`, opacity: 0 }],
    { duration: CORTA_MS / 2, easing: 'cubic-bezier(0.4, 0, 1, 1)', fill: 'forwards' });
  sale.onfinish = () => {
    vista.textContent = texto;
    sale.cancel();
    const rebote = overshoot(1.6);
    // El rebote de OvershootInterpolator, en fotogramas clave.
    const pasos = Array.from({ length: 13 }, (_, i) => {
      const t = i / 12;
      return { transform: `translateY(${salto * (1 - rebote(t))}px)`, opacity: Math.min(1, t * 1.6) };
    });
    vista.animate(pasos, { duration: MEDIA_MS, easing: 'linear' });
  };
}

/** Solo con el neto ajustado a lo cobrado: cuánto se ha descontado de lo calculado (y por qué, si había nota). */
function mostrarFilaCobro(r, cobro) {
  const valor = $('valorCobro');
  $('filaCobro').classList.toggle('oculto', !r.tieneCobroReal);
  if (!r.tieneCobroReal) {
    // Oculta: la próxima vez crece desde 0 en lugar de desde el de otra semana.
    reiniciarMoneda(valor);
    return;
  }
  const d = r.descontado;
  const etiqueta = etiquetaDescontado(d);
  animarMoneda(valor, Math.abs(d), d < 0 ? '+' : '-');
  const motivo = cobro?.nota?.trim();
  $('etiquetaCobro').textContent = motivo ? `${etiqueta} · ${motivo}` : etiqueta;
  $('filaCobro').setAttribute('aria-label', `${etiqueta}: ${moneda(Math.abs(d))}. Calculado: ${moneda(r.netoCalculado)}. Toca para cambiar lo cobrado`);
}

// --- Filtro por día ---

function mostrarFiltro(ui) {
  const boton = (id, nombre, leido, n) => {
    const b = filtro.boton(id);
    b.firstElementChild.innerHTML = `<span style="display:flex;flex-direction:column;align-items:center">${nombre}<span style="font-size:0.9em">${n}</span></span>`;
    b.setAttribute('aria-label', `${leido}, ${n} ${n === 1 ? 'socio' : 'socios'}`);
  };
  boton(0, 'Todos', 'Todos los días', ui.todosSociosSemana.length);
  for (const d of DIAS) boton(d.id, d.corto, d.completo, ui.conteoPorDia.get(d.id) ?? 0);
  filtro.marcar(ui.diaFiltro ?? 0);
}

// --- Lista ---

let vistaAnterior = null;

function mostrarLista(ui) {
  // Al cambiar de día, los socios entran uno tras otro. Al cambiar de semana no se anima la página: solo los
  // importes, que cuentan hasta su valor, y las filas nuevas, que aparecen.
  const anterior = vistaAnterior;
  vistaAnterior = [ui.semanaKey, ui.diaFiltro];
  let modo = 'normal';
  if (!anterior) modo = 'nada';
  else if (anterior[0] === ui.semanaKey && anterior[1] !== ui.diaFiltro) modo = 'cascada';
  const filas = ui.diaFiltro == null
    ? agrupadasPorDia(ui.sociosFiltrados)
    : sinAgrupar(ui.sociosFiltrados.slice().sort((a, b) => a.timestamp - b.timestamp));
  lista.mostrar(filas, activas() ? modo : 'nada');
  const vacia = ui.sociosFiltrados.length === 0;
  $('vacio').classList.toggle('oculto', !vacia);
  $('listaSocios').classList.toggle('oculto', vacia);
  $('tituloLista').textContent = ui.diaFiltro == null ? 'Socios de la semana' : `Socios del ${diaPorId(ui.diaFiltro).completo}`;
  $('resumenLista').textContent = `${ui.sociosFiltrados.filter((s) => s.hecho).length} de ${ui.sociosFiltrados.length} hechos`;
}

// --- Barra de arriba, botón flotante y título ---

function configurarScroll() {
  const barra = $('barra');
  const contenido = $('contenido');
  new ResizeObserver(() => (contenido.style.paddingTop = `${barra.offsetHeight + 4}px`)).observe(barra);
  const fab = $('fab');
  let anchoExtendido = 0;
  const medir = () => {
    if (fab.classList.contains('encogido')) return;
    fab.style.width = '';
    anchoExtendido = fab.offsetWidth;
    fab.style.width = `${anchoExtendido}px`;
  };
  document.fonts?.ready.then(medir);
  medir();
  let ultimoY = scrollY;
  addEventListener('scroll', () => {
    const y = scrollY;
    // El borde de la barra aparece a medida que el contenido pasa por debajo.
    barra.style.setProperty('--borde-barra', String(Math.min(1, y / 24)));
    const dy = y - ultimoY;
    ultimoY = y;
    if (dy > 12 && !fab.classList.contains('encogido')) {
      fab.classList.add('encogido');
      fab.style.width = '56px';
    } else if ((dy < -12 || y <= 0) && fab.classList.contains('encogido')) {
      fab.classList.remove('encogido');
      fab.style.width = `${anchoExtendido}px`;
    }
  }, { passive: true });
}

/** Con poco ancho, "Gestión de Socios" no cabe junto a los iconos: se queda en "Socios". */
function configurarTitulo() {
  const h1 = $('tituloBarra');
  const ajustar = () => {
    h1.textContent = 'Gestión de Socios';
    if (h1.scrollWidth > h1.clientWidth + 1) h1.textContent = 'Socios';
  };
  new ResizeObserver(ajustar).observe(h1.parentElement);
  document.fonts?.ready.then(ajustar);
}

// --- Cambiar de semana deslizando en horizontal por cualquier parte ---

function configurarDeslizar() {
  const app = $('app');
  app.style.touchAction = 'pan-y';
  let inicio = null;
  app.addEventListener('pointerdown', (e) => {
    if (!e.isPrimary || hayPanelAbierto()) return;
    // Lo que ya se arrastra a los lados (interruptores, avisos) se queda su gesto.
    if (e.target.closest('.interruptor, .contenedor-interruptor, [data-arrastre-horizontal], input, textarea')) {
      inicio = null;
      return;
    }
    inicio = { x: e.clientX, y: e.clientY, t: e.timeStamp, id: e.pointerId };
  }, true);
  app.addEventListener('pointerup', (e) => {
    const i = inicio;
    inicio = null;
    if (!i || e.pointerId !== i.id || hayPanelAbierto()) return;
    const dx = e.clientX - i.x;
    const dy = e.clientY - i.y;
    const vx = Math.abs(dx) / Math.max(1, e.timeStamp - i.t) * 1000;
    if (Math.abs(dx) > Math.abs(dy) * 1.3 && Math.abs(dx) > 30 && vx > 30 && e.timeStamp - i.t < 600) {
      const delta = dx < 0 ? 1 : -1;
      haptica.click();
      estado.cambiarSemana(delta);
      // El gesto ha cambiado de semana: la tarjeta de debajo no debe tomarlo también por un toque.
      const comer = (ev) => {
        ev.stopPropagation();
        ev.preventDefault();
      };
      addEventListener('click', comer, { capture: true, once: true });
      // Si no llega ese clic (con el dedo no suele), que no se coma el siguiente de verdad.
      setTimeout(() => removeEventListener('click', comer, { capture: true }), 80);
    }
  }, true);
  app.addEventListener('pointercancel', () => (inicio = null), true);
}
