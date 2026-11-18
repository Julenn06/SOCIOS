// MainViewModel: la semana que se ve, el filtro por día, los datos y todo lo que cambia algo (con su aviso).
import * as db from './db.js';
import {
  calcularResumenHistoricoGlobal, calcularResumenSemana, CUOTA_POR_DEFECTO, redondearCentimos
} from './calculos.js';
import { diaPorId, esSemanaKeyValida, getSemanaKey, rangoCorto, semanaKeyDesplazada, semanasEntre } from './fechas.js';
import { eurosCompacto, moneda } from './formato.js';

/** El nombre del socio, o "Socio 20 €" si no tiene. */
export const nombreVisible = (s) => (s.nombreSocio && s.nombreSocio.trim() ? s.nombreSocio : `Socio ${eurosCompacto(s.colaboracion)}`);

/** Un id como los de UUID.randomUUID(). */
export const nuevoId = () => (crypto.randomUUID ? crypto.randomUUID() : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
  const r = (Math.random() * 16) | 0;
  return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
}));

const socios = new Map();
const bonos = new Map();
const cobros = new Map();

const oyentes = new Set();
const oyentesEventos = new Set();
let pendienteAvisar = false;
let cache = null;

let semanaElegida = db.sesion.leer('semanaElegida', null);
let diaFiltro = db.sesion.leer('diaFiltro', null);
let semanaActual = getSemanaKey(0);

/** Cada vez que cambia algo (datos, semana o filtro), en el siguiente microtarea y una sola vez. */
let revision = 0;
/** Cambia con cada cambio: para no repetir trabajo caro (preparar la búsqueda) si nada ha cambiado. */
export const revisionDatos = () => revision;

function avisar() {
  revision++;
  cache = null;
  if (pendienteAvisar) return;
  pendienteAvisar = true;
  queueMicrotask(() => {
    pendienteAvisar = false;
    for (const f of oyentes) f();
  });
}

export const suscribir = (f) => (oyentes.add(f), () => oyentes.delete(f));
export const alEvento = (f) => (oyentesEventos.add(f), () => oyentesEventos.delete(f));
const enviar = (evento) => oyentesEventos.forEach((f) => f(evento));
const aviso = (titulo, tipo, detalle = null) => enviar({ tipo: 'mensaje', titulo, tipoAviso: tipo, detalle });

/** Lo guardado pasa a la memoria de esta ventana. */
function poner(datos) {
  socios.clear();
  bonos.clear();
  cobros.clear();
  datos.socios.forEach((s) => socios.set(s.id, s));
  datos.bonos.forEach((b) => bonos.set(b.semanaKey, b));
  datos.cobros.forEach((c) => cobros.set(c.semanaKey, c));
}

// La app puede estar abierta en varios sitios a la vez (instalada y en una pestaña, o en dos pestañas). Cada una tiene
// los datos en memoria: cuando una guarda algo, avisa a las demás por este canal y ellas los vuelven a leer.
let canal = null;
let releyendo = null;

async function guardar(cambios) {
  await db.escribir(cambios);
  canal?.postMessage('cambio');
}

function releer() {
  // Varios avisos seguidos (una importación grande) se juntan en una sola lectura.
  releyendo ??= new Promise((r) => setTimeout(r, 50)).then(async () => {
    releyendo = null;
    poner(await db.leerTodoGuardado());
    avisar();
  });
  return releyendo;
}

export async function iniciar() {
  poner(await db.cargar());
  if ('BroadcastChannel' in self) {
    canal = new BroadcastChannel('socios-datos');
    canal.onmessage = () => releer();
  }
  // Las preferencias (pendientes ocultos, última cuota) también pueden cambiar en otra ventana.
  addEventListener('storage', (ev) => ev.key?.startsWith('socios.') && avisar());
  // La semana actual cambia sola cada lunes aunque la app siga abierta.
  setInterval(comprobarSemanaActual, 60_000);
  document.addEventListener('visibilitychange', () => document.visibilityState === 'visible' && comprobarSemanaActual());
}

function comprobarSemanaActual() {
  const k = getSemanaKey(0);
  if (k !== semanaActual) {
    semanaActual = k;
    avisar();
  }
}

// --- Lo que ve la pantalla ---

const porTimestampDesc = (a, b) => b.timestamp - a.timestamp;

export function getSemanaActual() {
  return semanaActual;
}

/** El estado de la pantalla principal (MainUiState). */
export function ui() {
  if (cache?.ui) return cache.ui;
  const semanaKey = semanaElegida ?? semanaActual;
  const todos = [...socios.values()].filter((s) => s.semanaKey === semanaKey).sort(porTimestampDesc);
  const conteo = new Map();
  for (const s of todos) conteo.set(s.dia, (conteo.get(s.dia) ?? 0) + 1);
  const bono = bonos.get(semanaKey) ?? null;
  const cobro = cobros.get(semanaKey) ?? null;
  const estado = {
    weekOffset: semanasEntre(semanaActual, semanaKey) ?? 0,
    semanaKey,
    diaFiltro,
    todosSociosSemana: todos,
    sociosFiltrados: diaFiltro == null ? todos : todos.filter((s) => s.dia === diaFiltro),
    conteoPorDia: conteo,
    bonoInfoSemana: bono,
    cobroSemana: cobro,
    resumen: calcularResumenSemana(todos, bono, undefined, cobro)
  };
  cache = { ...(cache ?? {}), ui: estado };
  return estado;
}

export function historico() {
  if (cache?.historico) return cache.historico;
  const activos = new Map([...bonos].filter(([, b]) => b.activo));
  const h = calcularResumenHistoricoGlobal([...socios.values()].sort(porTimestampDesc), activos, cobros);
  cache = { ...(cache ?? {}), historico: h };
  return h;
}

/** Semanas con socios, con un bono activo o con lo cobrado, de la más reciente a la más antigua. */
export function semanasDisponibles() {
  const claves = new Set([...socios.values()].map((s) => s.semanaKey));
  for (const [k, b] of bonos) if (b.activo) claves.add(k);
  for (const k of cobros.keys()) claves.add(k);
  return [...claves].sort().reverse();
}

export const todosLosSocios = () => [...socios.values()].sort(porTimestampDesc);
export const idsSocios = () => new Set(socios.keys());

/** Pendientes de semanas anteriores a la actual, de la más reciente a la más antigua. */
export function pendientesAnteriores() {
  return [...socios.values()]
    .filter((s) => !s.hecho && s.semanaKey < semanaActual)
    .sort((a, b) => (a.semanaKey < b.semanaKey ? 1 : a.semanaKey > b.semanaKey ? -1 : b.timestamp - a.timestamp));
}

/**
 * Los pendientes anteriores mientras haya alguno que no se haya descartado; vacío si se cerró el aviso con estos
 * mismos. El que deja de estar pendiente se olvida de los descartados.
 */
export function avisoPendientes() {
  const pendientes = pendientesAnteriores();
  const ocultos = new Set(db.prefs.leer('pendientesOcultos', []));
  const ids = new Set(pendientes.map((s) => s.id));
  if ([...ocultos].some((id) => !ids.has(id))) db.prefs.guardar('pendientesOcultos', [...ocultos].filter((id) => ids.has(id)));
  return pendientes.some((s) => !ocultos.has(s.id)) ? pendientes : [];
}

export function ocultarAvisoPendientes() {
  db.prefs.guardar('pendientesOcultos', avisoPendientes().map((s) => s.id));
  avisar();
}

export const cuotaPorDefecto = () => db.prefs.leer('ultimaCuota', null) ?? CUOTA_POR_DEFECTO;
export const bonoDe = (semanaKey) => bonos.get(semanaKey) ?? null;

// --- Navegación y filtros ---

export function cambiarSemana(delta) {
  const k = semanaKeyDesplazada(semanaElegida ?? semanaActual, delta);
  if (k) irASemana(k);
}

export const irASemanaActual = () => elegirSemana(null);

export function irASemana(semanaKey) {
  if (!esSemanaKeyValida(semanaKey)) return;
  // Volver a la actual la deja de fijar: así pasa sola a la siguiente el lunes.
  elegirSemana(semanaKey === semanaActual ? null : semanaKey);
}

function elegirSemana(k) {
  diaFiltro = null;
  semanaElegida = k;
  db.sesion.guardar('diaFiltro', null);
  db.sesion.guardar('semanaElegida', k);
  avisar();
}

export function setDiaFiltro(dia) {
  diaFiltro = dia;
  db.sesion.guardar('diaFiltro', dia);
  avisar();
}

// --- Cambios, con su aviso o su error ---

async function lanzar(error, bloque) {
  try {
    await bloque();
  } catch (e) {
    console.error(e);
    aviso(error ?? 'Algo ha fallado', 'ERROR', motivoDeError(e));
  }
}

/** Explica un error al usuario: los propios tal cual, los demás por lo que suele haber pasado. */
export function motivoDeError(e) {
  if (e?.paraUsuario && e.message) return e.message;
  if (e?.name === 'QuotaExceededError') return 'No queda espacio libre en el dispositivo';
  if (e?.name === 'NotReadableError' || e?.name === 'NotFoundError' || e?.name === 'SecurityError') {
    return 'No se puede abrir el archivo: puede que se haya movido o que no haya permiso para usarlo';
  }
  return `Error inesperado (${e?.name ?? 'Error'})`;
}

/** Un error con el mensaje ya escrito para el usuario. */
export function errorParaUsuario(mensaje) {
  const e = new Error(mensaje);
  e.paraUsuario = true;
  return e;
}

export const setSocioHecho = (id, hecho) => lanzar(null, async () => {
  const s = socios.get(id);
  if (!s) return;
  const nuevo = { ...s, hecho };
  await guardar([{ almacen: 'socios', poner: nuevo }]);
  socios.set(id, nuevo);
  avisar();
  if (hecho) aviso('Marcado como conseguido', 'EXITO');
  else aviso('Marcado como pendiente', 'PENDIENTE');
});

/** [semanaOriginal]: la del socio editado, para avisar si se ha movido de semana. */
export const guardarSocio = (socio, esNuevo, semanaOriginal = null) => lanzar(null, async () => {
  await guardar([{ almacen: 'socios', poner: socio }]);
  socios.set(socio.id, socio);
  avisar();
  if (esNuevo) {
    db.prefs.guardar('ultimaCuota', socio.colaboracion);
    aviso('Socio añadido', 'EXITO', `${nombreVisible(socio)} · ${diaPorId(socio.dia).completo}`);
  } else if (semanaOriginal && semanaOriginal !== socio.semanaKey) {
    aviso('Socio movido de semana', 'EXITO', `${nombreVisible(socio)} · ahora en la del ${rangoCorto(socio.semanaKey)}`);
  } else {
    aviso('Cambios guardados', 'EXITO', nombreVisible(socio));
  }
});

export const eliminarSocio = (socio) => lanzar(null, async () => {
  await guardar([{ almacen: 'socios', borrar: socio.id }]);
  socios.delete(socio.id);
  avisar();
  enviar({ tipo: 'socioEliminado', socio });
});

/** Vuelve a guardar un socio recién borrado, con el mismo id y fecha de alta. */
export const restaurarSocio = (socio) => lanzar(null, async () => {
  await guardar([{ almacen: 'socios', poner: socio }]);
  socios.set(socio.id, socio);
  avisar();
  aviso('Socio recuperado', 'EXITO', nombreVisible(socio));
});

export const guardarBono = (bono) => lanzar(null, async () => {
  await guardar([{ almacen: 'bonos', poner: bono }]);
  bonos.set(bono.semanaKey, bono);
  avisar();
  const importe = eurosCompacto(bono.monto);
  aviso('Bonus ajustado a mano', 'EXITO', bono.esMontoFijo ? `${importe} en lugar del automático` : `${importe} más el automático`);
});

export const eliminarBono = (semanaKey) => lanzar(null, async () => {
  await guardar([{ almacen: 'bonos', borrar: semanaKey }]);
  bonos.delete(semanaKey);
  avisar();
  aviso('Bonus automático', 'INFO', 'Vuelve a calcularse con los socios hechos');
});

/** Lo cobrado de verdad: pasa a ser el neto de la semana. */
export const guardarCobro = (semanaKey, netoCobrado, nota) => lanzar(null, async () => {
  const cobro = { semanaKey, netoCobrado: redondearCentimos(netoCobrado), nota: nota.trim() };
  await guardar([{ almacen: 'cobros', poner: cobro }]);
  cobros.set(semanaKey, cobro);
  avisar();
  aviso('Cobro apuntado', 'EXITO', `${moneda(cobro.netoCobrado)} como neto de la semana`);
});

export const eliminarCobro = (semanaKey) => lanzar(null, async () => {
  await guardar([{ almacen: 'cobros', borrar: semanaKey }]);
  cobros.delete(semanaKey);
  avisar();
  aviso('Cobro quitado', 'INFO', 'La semana vuelve al neto calculado');
});

// --- Copias de seguridad ---

/** Los datos de un alcance: { tipo: 'SEMANAS', semanas: Set } | { tipo: 'HISTORICO' } | { tipo: 'ANIO', anio }. */
export function cargarDatos(alcance) {
  const todosBonos = [...bonos.values()].filter((b) => b.activo);
  const todosCobros = [...cobros.values()];
  const lista = todosLosSocios();
  if (alcance.tipo === 'HISTORICO') return { socios: lista, bonosRegalo: todosBonos, cobros: todosCobros };
  const incluye = alcance.tipo === 'ANIO' ? (k) => k.startsWith(`${alcance.anio}-W`) : (k) => alcance.semanas.has(k);
  return {
    socios: lista.filter((s) => incluye(s.semanaKey)),
    bonosRegalo: todosBonos.filter((b) => incluye(b.semanaKey)),
    cobros: todosCobros.filter((c) => incluye(c.semanaKey))
  };
}

/** Guarda todo lo importado en una sola transacción: o entra el archivo entero o nada. */
export async function importar(datos) {
  const cambios = [
    ...datos.socios.map((s) => ({ almacen: 'socios', poner: s })),
    ...datos.bonosRegalo.map((b) => ({ almacen: 'bonos', poner: b })),
    ...datos.cobros.map((c) => ({ almacen: 'cobros', poner: c }))
  ];
  if (cambios.length) await guardar(cambios);
  datos.socios.forEach((s) => socios.set(s.id, s));
  datos.bonosRegalo.forEach((b) => bonos.set(b.semanaKey, b));
  datos.cobros.forEach((c) => cobros.set(c.semanaKey, c));
  avisar();
}

export { aviso, lanzar };
