// La cuenta y la sincronización con el servidor (servidor/ en el repositorio): los mismos datos en todos los
// dispositivos donde se abre la web (la app Android no lo usa: para pasar datos de ella, las copias). Los datos siguen guardados en este dispositivo y la app funciona sin conexión; lo que cambia aquí queda
// anotado como pendiente (db.js) y se sube en cuanto se puede, y lo que cambia en los otros dispositivos se baja.
//
// Cada petición a /api/sincronizar sube lo pendiente y baja lo posterior a la última revisión que se tiene. Si un
// registro ha cambiado en los dos sitios, gana el cambio más reciente (ver servidor/sincronizacion.js).
import * as db from './db.js';
import { Centimos } from './calculos.js';

/** El servidor. En local (probando con herramientas del repositorio) va en el puerto 3000. */
export const API = location.hostname === 'localhost' ? 'http://localhost:3000' : 'https://socios-api.jfl06studio.com';

const ESPERA_TRAS_CAMBIO_MS = 800;
const CADA_MS = 30_000;
const TIEMPO_MAX_MS = 20_000;
const POR_ENVIO = 4000;

/** Lo mismo que el servidor (servidor/cuentas.js): da igual mayúsculas, minúsculas, tildes o espacios. */
export const claveNombre = (nombre) =>
  String(nombre ?? '').replace(/\s+/g, ' ').trim().normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase();

// --- Cómo viaja cada registro (los mismos nombres de campo que la app Android, importes en céntimos) ---

const TABLAS = {
  socios: {
    almacen: 'socios',
    clave: 'id',
    aServidor: (s) => ({
      id: s.id, colaboracionCentimos: Centimos.de(s.colaboracion), hecho: !!s.hecho, diaSemanaId: s.dia, semanaKey: s.semanaKey,
      nombreSocio: s.nombreSocio ?? '', notas: s.notas ?? '', timestamp: s.timestamp ?? 0
    }),
    deServidor: (r) => ({
      id: r.id, colaboracion: r.colaboracionCentimos / 100, hecho: r.hecho, dia: r.diaSemanaId, semanaKey: r.semanaKey,
      nombreSocio: r.nombreSocio, notas: r.notas, timestamp: r.timestamp
    })
  },
  bonosRegalo: {
    almacen: 'bonos',
    clave: 'semanaKey',
    aServidor: (b) => ({ semanaKey: b.semanaKey, activo: !!b.activo, esMontoFijo: !!b.esMontoFijo, montoCentimos: Centimos.de(b.monto), nota: b.nota ?? '' }),
    deServidor: (r) => ({ semanaKey: r.semanaKey, activo: r.activo, esMontoFijo: r.esMontoFijo, monto: r.montoCentimos / 100, nota: r.nota })
  },
  cobros: {
    almacen: 'cobros',
    clave: 'semanaKey',
    aServidor: (c) => ({ semanaKey: c.semanaKey, netoCobradoCentimos: Centimos.de(c.netoCobrado), nota: c.nota ?? '' }),
    deServidor: (r) => ({ semanaKey: r.semanaKey, netoCobrado: r.netoCobradoCentimos / 100, nota: r.nota })
  }
};
const TABLA_DE_ALMACEN = Object.fromEntries(Object.entries(TABLAS).map(([t, d]) => [d.almacen, t]));

// Los ajustes viajan como texto.
const AJUSTES = {
  tema: { aServidor: (v) => v ?? 'SISTEMA', deServidor: (v) => (v === 'CLARO' || v === 'OSCURO' ? v : null) },
  ultimaCuota: { aServidor: (v) => (v == null ? '' : String(v)), deServidor: (v) => (v === '' || !Number.isFinite(Number(v)) ? null : Number(v)) },
  pendientesOcultos: {
    aServidor: (v) => JSON.stringify(Array.isArray(v) ? v : []),
    deServidor: (v) => {
      try {
        const lista = JSON.parse(v);
        return Array.isArray(lista) ? lista.filter((x) => typeof x === 'string') : [];
      } catch {
        return [];
      }
    }
  }
};

// --- Estado de la cuenta, para la pantalla ---

const oyentes = new Set();
export const cuenta = {
  /** { token, nombre } o null. */
  get sesion() {
    return db.prefs.leer('sesion', null);
  },
  /** INACTIVA (sin sesión), SINCRONIZANDO, AL_DIA, SIN_CONEXION o ERROR. */
  estado: 'INACTIVA',
  mensaje: null,
  get ultima() {
    return db.prefs.leer('ultimaSincronizacion', 0);
  },
  suscribir: (f) => (oyentes.add(f), () => oyentes.delete(f))
};
const cambiarEstado = (estado, mensaje = null) => {
  cuenta.estado = estado;
  cuenta.mensaje = mensaje;
  oyentes.forEach((f) => f());
};

export class ErrorCuenta extends Error {
  constructor(mensaje, codigo = null, estado = 0) {
    super(mensaje);
    this.codigo = codigo;
    this.estado = estado;
  }
}

async function pedir(metodo, ruta, cuerpo, token = cuenta.sesion?.token) {
  let respuesta;
  try {
    respuesta = await fetch(API + ruta, {
      method: metodo,
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
      signal: AbortSignal.timeout(TIEMPO_MAX_MS),
      cache: 'no-store'
    });
  } catch {
    throw new ErrorCuenta('Sin conexión con el servidor. Comprueba internet y vuelve a probar', 'sin_conexion');
  }
  const texto = await respuesta.text();
  let datos = null;
  try {
    datos = texto ? JSON.parse(texto) : null;
  } catch {
    // Una respuesta que no es JSON (una página de error del alojamiento, por ejemplo).
  }
  if (!respuesta.ok) throw new ErrorCuenta(datos?.mensaje ?? `Error del servidor (${respuesta.status})`, datos?.error ?? null, respuesta.status);
  return datos;
}

const dispositivo = () => (matchMedia('(display-mode: standalone)').matches ? 'Web instalada' : 'Navegador');

// --- Entrar, registrarse y salir ---

/** Comprueba la contraseña de registro (el primer paso de "Registrarse"). */
export const comprobarClaveRegistro = (claveRegistro) => pedir('POST', '/api/registro/comprobar', { claveRegistro }, null);

export async function registrarse(claveRegistro, nombre, contrasena) {
  await empezarSesion(await pedir('POST', '/api/registro', { claveRegistro, nombre, contrasena, dispositivo: dispositivo() }, null));
}

export async function entrar(nombre, contrasena) {
  await empezarSesion(await pedir('POST', '/api/sesion', { nombre, contrasena, dispositivo: dispositivo() }, null));
}

let alCambiarSesion = () => {};
/** main.js: qué hacer al entrar o salir (enseñar u ocultar la pantalla de la cuenta, recargar los datos). */
export const alEntrarOSalir = (f) => (alCambiarSesion = f);

async function empezarSesion({ token, nombre }) {
  // Si en este dispositivo había datos de otra cuenta, no se mezclan con los de esta.
  const anterior = db.prefs.leer('cuentaNombre', null);
  if (anterior != null && claveNombre(anterior) !== claveNombre(nombre)) {
    await db.borrarTodo();
    db.prefs.guardar('ajustesPendientes', null);
  }
  // Lo que ya hubiera aquí sube a la cuenta; si la cuenta ya tiene lo mismo, gana lo de la cuenta.
  await db.marcarTodoPendiente(1);
  const ajustes = {};
  for (const clave of Object.keys(AJUSTES)) if (db.prefs.leer(clave, null) != null) ajustes[clave] = 1;
  db.prefs.guardar('ajustesPendientes', { ...ajustes, ...db.prefs.leer('ajustesPendientes', {}) });
  db.prefs.guardar('rev', 0);
  db.prefs.guardar('cuentaNombre', nombre);
  db.prefs.guardar('sesion', { token, nombre });
  cambiarEstado('SINCRONIZANDO');
  await alCambiarSesion(true);
  vigilar();
  await sincronizar();
}

/** Cuántos cambios de este dispositivo faltan por subir. */
export async function pendientesDeSubir() {
  return (await db.leerPendientes()).length + Object.keys(db.prefs.leer('ajustesPendientes', {})).length;
}

/**
 * Cierra la sesión y quita los datos de este dispositivo (siguen en la cuenta). Antes intenta subir lo pendiente;
 * [forzar] sale aunque quede algo sin subir.
 */
export async function salir({ forzar = false } = {}) {
  if (!forzar) {
    await sincronizar();
    if ((await pendientesDeSubir()) > 0) throw new ErrorCuenta('Hay cambios sin subir', 'pendientes');
  }
  await pedir('DELETE', '/api/sesion').catch(() => {});
  await olvidarSesion();
}

/** Borra la cuenta y todos sus datos, en el servidor y aquí. */
export async function borrarCuenta(contrasena) {
  await pedir('DELETE', '/api/cuenta', { contrasena });
  await olvidarSesion();
}

async function olvidarSesion() {
  await db.borrarTodo();
  for (const clave of ['sesion', 'rev', 'ajustesPendientes', 'ultimaSincronizacion', 'cuentaNombre', 'pendientesOcultos', 'ultimaCuota']) db.prefs.guardar(clave, null, { remoto: true });
  cambiarEstado('INACTIVA');
  await alCambiarSesion(false);
}

// --- Sincronizar ---

let alRecibir = async () => {};
/** estado.js: vuelve a leer los datos cuando llegan cambios de la cuenta. */
export const alRecibirCambios = (f) => (alRecibir = f);

let enCurso = null;
let otraVez = false;

/** Sube lo pendiente y baja lo nuevo. Si ya está en marcha, se repite al terminar. */
export function sincronizar() {
  if (!cuenta.sesion) return Promise.resolve();
  if (enCurso) {
    otraVez = true;
    return enCurso;
  }
  // Con la app abierta en varias pestañas, sincroniza una cada vez.
  const hacer = () => (navigator.locks ? navigator.locks.request('socios-sincronizar', ciclo) : ciclo());
  enCurso = hacer().finally(() => {
    enCurso = null;
    if (otraVez) {
      otraVez = false;
      sincronizar();
    }
  });
  return enCurso;
}

async function ciclo() {
  cambiarEstado('SINCRONIZANDO');
  try {
    let recibidos = 0;
    for (let vuelta = 0; vuelta < 50; vuelta++) {
      const { hayMas, aplicados } = await unaVuelta();
      recibidos += aplicados;
      if (!hayMas) break;
    }
    db.prefs.guardar('ultimaSincronizacion', Date.now());
    cambiarEstado('AL_DIA');
    if (recibidos) await alRecibir();
  } catch (e) {
    if (e instanceof ErrorCuenta && e.estado === 401) {
      // Sesión caducada o cerrada desde otro sitio: los datos y lo pendiente se quedan hasta volver a entrar.
      db.prefs.guardar('sesion', null);
      cambiarEstado('INACTIVA', e.message);
      await alCambiarSesion(false);
      return;
    }
    console.warn('Sincronización:', e);
    cambiarEstado(e?.codigo === 'sin_conexion' ? 'SIN_CONEXION' : 'ERROR', e?.message ?? String(e));
  }
}

/** Un envío: hasta POR_ENVIO cambios pendientes, y lo que devuelve el servidor. */
async function unaVuelta() {
  const pendientes = (await db.leerPendientes()).slice(0, POR_ENVIO);
  const cambios = { socios: [], bonosRegalo: [], cobros: [], ajustes: [] };
  for (const p of pendientes) {
    const tabla = TABLA_DE_ALMACEN[p.almacen];
    if (!tabla) continue;
    const registro = await db.leer(p.almacen, p.clave);
    cambios[tabla].push(registro
      ? { ...TABLAS[tabla].aServidor(registro), modificado: p.modificado }
      : { [TABLAS[tabla].clave]: p.clave, borrado: true, modificado: p.modificado });
  }
  const ajustesPendientes = db.prefs.leer('ajustesPendientes', {});
  for (const [clave, modificado] of Object.entries(ajustesPendientes)) {
    if (AJUSTES[clave]) cambios.ajustes.push({ clave, valor: AJUSTES[clave].aServidor(db.prefs.leer(clave, null)), modificado });
  }

  const respuesta = await pedir('POST', '/api/sincronizar', { desde: db.prefs.leer('rev', 0), cambios });
  if (respuesta.reiniciar) {
    // La cuenta va por detrás de este dispositivo (se ha vuelto a crear): se sube todo otra vez.
    await db.marcarTodoPendiente(1);
    db.prefs.guardar('rev', 0);
    return { hayMas: true, aplicados: 0 };
  }

  const registros = [];
  for (const [tabla, def] of Object.entries(TABLAS)) {
    for (const r of respuesta.cambios?.[tabla] ?? []) {
      registros.push({ almacen: def.almacen, clave: r[def.clave], poner: r.borrado ? null : def.deServidor(r), modificado: r.modificado });
    }
  }
  const enviados = pendientes.map((p) => ({ id: p.id, modificado: p.modificado }));
  const aplicados = await db.aplicarRemoto(registros, enviados);
  const ajustesAplicados = aplicarAjustes(respuesta.cambios?.ajustes ?? [], ajustesPendientes);
  db.prefs.guardar('rev', respuesta.rev);
  const quedan = (await db.leerPendientes()).length > 0 && pendientes.length === POR_ENVIO;
  return { hayMas: respuesta.mas || quedan, aplicados: aplicados + ajustesAplicados };
}

/** Los ajustes que llegan, y fuera de pendientes los que se acaban de subir sin volver a cambiar. */
function aplicarAjustes(recibidos, enviados) {
  const actuales = db.prefs.leer('ajustesPendientes', {});
  for (const [clave, modificado] of Object.entries(enviados)) if (actuales[clave] === modificado) delete actuales[clave];
  let aplicados = 0;
  for (const r of recibidos) {
    if (!AJUSTES[r.clave] || (actuales[r.clave] ?? 0) > r.modificado) continue;
    delete actuales[r.clave];
    db.prefs.guardar(r.clave, r.borrado ? null : AJUSTES[r.clave].deServidor(r.valor), { remoto: true });
    aplicados++;
  }
  db.prefs.guardar('ajustesPendientes', Object.keys(actuales).length ? actuales : null);
  return aplicados;
}

// --- Cuándo ---

let vigilando = false;
let temporizador = null;

/** Sincroniza al poco de cada cambio, al volver a la app, al recuperar la conexión y cada 30 s con la app a la vista. */
export function vigilar() {
  if (vigilando) return;
  vigilando = true;
  db.alCambiarLocal(() => {
    clearTimeout(temporizador);
    temporizador = setTimeout(sincronizar, ESPERA_TRAS_CAMBIO_MS);
  });
  document.addEventListener('visibilitychange', () => document.visibilityState === 'visible' && sincronizar());
  addEventListener('online', () => sincronizar());
  setInterval(() => document.visibilityState === 'visible' && sincronizar(), CADA_MS);
}
