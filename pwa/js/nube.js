// La cuenta y la sincronización con el servidor (servidor/ en el repositorio): los mismos datos en todos los
// dispositivos donde se abre la web (la app Android no lo usa: para pasar datos de ella, las copias). Los datos siguen guardados en este dispositivo y la app funciona sin conexión; lo que cambia aquí queda
// anotado como pendiente (db.js) y se sube en cuanto se puede, y lo que cambia en los otros dispositivos se baja.
//
// Cada petición a /api/sincronizar sube lo pendiente y baja lo posterior a la última revisión que se tiene. Si un
// registro ha cambiado en los dos sitios, se juntan campo a campo: de cada campo gana el cambio más reciente (ver
// servidor/sincronizacion.js y db.juntar).
import * as db from './db.js';
import { Centimos } from './calculos.js';
import { API, dispositivo } from './entorno.js';

export { API, dispositivo };

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
    }),
    // Los campos que se llaman distinto aquí (para la hora de cada campo); los demás, igual.
    nombres: { colaboracion: 'colaboracionCentimos', dia: 'diaSemanaId' }
  },
  bonosRegalo: {
    almacen: 'bonos',
    clave: 'semanaKey',
    aServidor: (b) => ({ semanaKey: b.semanaKey, activo: !!b.activo, esMontoFijo: !!b.esMontoFijo, montoCentimos: Centimos.de(b.monto), nota: b.nota ?? '' }),
    deServidor: (r) => ({ semanaKey: r.semanaKey, activo: r.activo, esMontoFijo: r.esMontoFijo, monto: r.montoCentimos / 100, nota: r.nota }),
    nombres: { monto: 'montoCentimos' }
  },
  cobros: {
    almacen: 'cobros',
    clave: 'semanaKey',
    aServidor: (c) => ({ semanaKey: c.semanaKey, netoCobradoCentimos: Centimos.de(c.netoCobrado), nota: c.nota ?? '' }),
    deServidor: (r) => ({ semanaKey: r.semanaKey, netoCobrado: r.netoCobradoCentimos / 100, nota: r.nota }),
    nombres: { netoCobrado: 'netoCobradoCentimos' }
  }
};
const TABLA_DE_ALMACEN = Object.fromEntries(Object.entries(TABLAS).map(([t, d]) => [d.almacen, t]));

/** La hora de cada campo con los nombres del servidor ([haciaServidor]) o con los de aquí. */
function traducirCampos(def, campos, haciaServidor) {
  if (!campos) return undefined;
  const mapa = haciaServidor ? def.nombres : Object.fromEntries(Object.entries(def.nombres).map(([a, b]) => [b, a]));
  return Object.fromEntries(Object.entries(campos).map(([c, t]) => [mapa[c] ?? c, t]));
}

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
  /**
   * { nombre, cookie: true } o null. La sesión en sí va en una cookie HttpOnly del servidor, que este código no puede
   * leer (si se colara un script, no podría llevársela). Las sesiones abiertas antes de la 1.0.16 traen aún { token },
   * que pasa a la cookie en la primera sincronización (pasarACookie).
   */
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
      cache: 'no-store',
      // Con la cookie de la sesión (el servidor es del mismo sitio: SameSite=Strict la deja pasar).
      credentials: 'include'
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

// --- Entrar, registrarse y salir ---

/** Comprueba la contraseña de registro (el primer paso de "Registrarse"). */
export const comprobarClaveRegistro = (claveRegistro) => pedir('POST', '/api/registro/comprobar', { claveRegistro }, null);

export async function registrarse(claveRegistro, nombre, contrasena) {
  await empezarSesion(await pedir('POST', '/api/registro', { claveRegistro, nombre, contrasena, dispositivo: dispositivo(), modo: 'cookie' }, null));
}

/** Devuelve cuántas veces se ha fallado la contraseña de la cuenta desde la última vez que se entró. */
export async function entrar(nombre, contrasena) {
  const respuesta = await pedir('POST', '/api/sesion', { nombre, contrasena, dispositivo: dispositivo(), modo: 'cookie' }, null);
  await empezarSesion(respuesta);
  return { fallos: respuesta.fallos ?? 0 };
}

/** Una sesión de antes de la 1.0.16 (con el token aquí): el servidor la pasa a la cookie y aquí se olvida el token. */
async function pasarACookie() {
  const sesion = cuenta.sesion;
  if (!sesion?.token) return;
  await pedir('POST', '/api/sesion/cookie', undefined, sesion.token);
  db.prefs.guardar('sesion', { nombre: sesion.nombre, cookie: true });
}

// --- Dispositivos y actividad (Más → Cuenta → Dispositivos y actividad) ---

/** [{ id, dispositivo, creado, visto, actual }], el más reciente primero. */
export const listarSesiones = async () => (await pedir('GET', '/api/sesiones')).sesiones;
export const cerrarSesionDe = (id) => pedir('POST', '/api/sesiones/cerrar', { id });
export const cerrarSesionesOtras = () => pedir('POST', '/api/sesiones/cerrar', { otras: true });
/** [{ tipo, momento, dispositivo }], lo más reciente primero. */
export const leerActividad = async () => (await pedir('GET', '/api/actividad')).actividad;

// --- Llaves de acceso (passkeys): entrar con la huella, la cara o el PIN del dispositivo, ver servidor/passkeys.js ---

const aB64u = (buf) => {
  const bytes = new Uint8Array(buf instanceof ArrayBuffer ? buf : buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength));
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};
const deB64u = (texto) => Uint8Array.from(atob(texto.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));

/** Si este navegador sabe usar llaves de acceso (y dar la clave pública, que es lo que guarda el servidor). */
export const llavesDisponibles = () =>
  typeof window.PublicKeyCredential === 'function' && typeof window.AuthenticatorAttestationResponse?.prototype?.getPublicKey === 'function';

/** Lo que dice el navegador al cancelar o fallar, en palabras de la app. */
function errorDeLlave(e) {
  if (e instanceof ErrorCuenta) return e;
  if (e?.name === 'NotAllowedError' || e?.name === 'AbortError') return new ErrorCuenta('Cancelado', 'cancelado');
  if (e?.name === 'InvalidStateError') return new ErrorCuenta('Este dispositivo ya tiene una llave de acceso de tu cuenta', 'llave_repetida');
  if (e?.name === 'SecurityError') return new ErrorCuenta('Las llaves de acceso solo funcionan en la web de Gestión de Socios', 'llave');
  return new ErrorCuenta('No se ha podido usar la llave de acceso en este dispositivo', 'llave');
}

/** [{ id, nombre, creada, usada }], la más reciente primero. */
export const listarLlaves = async () => (await pedir('GET', '/api/llaves')).llaves;
export const borrarLlave = (id) => pedir('POST', '/api/llaves/borrar', { id });

/** Crea una llave de acceso en este dispositivo (o en el móvil, con el QR que enseña el navegador) para esta cuenta. */
export async function crearLlave() {
  const op = await pedir('POST', '/api/llaves/opciones', {});
  let credencial;
  try {
    credencial = await navigator.credentials.create({
      publicKey: {
        challenge: deB64u(op.reto),
        rp: { name: op.rp },
        user: { id: deB64u(op.usuario), name: op.nombre, displayName: op.nombre },
        pubKeyCredParams: op.algoritmos.map((alg) => ({ type: 'public-key', alg })),
        // Guardada en el dispositivo (así no hace falta escribir el nombre al entrar) y siempre con huella, cara o PIN.
        authenticatorSelection: { residentKey: 'required', requireResidentKey: true, userVerification: 'required' },
        excludeCredentials: op.excluir.map((l) => ({ type: 'public-key', id: deB64u(l.id), transports: l.transportes })),
        attestation: 'none',
        timeout: 120_000
      }
    });
  } catch (e) {
    throw errorDeLlave(e);
  }
  const r = credencial.response;
  return pedir('POST', '/api/llaves', {
    id: credencial.id,
    clientDataJSON: aB64u(r.clientDataJSON),
    authenticatorData: aB64u(r.getAuthenticatorData()),
    publicKey: aB64u(r.getPublicKey()),
    alg: r.getPublicKeyAlgorithm(),
    transports: r.getTransports?.() ?? [],
    dispositivo: dispositivo()
  });
}

/** Entra con una llave de acceso (sin nombre ni contraseña). Devuelve { fallos }, como entrar(). */
export async function entrarConLlave() {
  const { reto } = await pedir('POST', '/api/sesion/llave/opciones', {}, null);
  let credencial;
  try {
    credencial = await navigator.credentials.get({ publicKey: { challenge: deB64u(reto), userVerification: 'required', timeout: 120_000 } });
  } catch (e) {
    throw errorDeLlave(e);
  }
  const r = credencial.response;
  const respuesta = await pedir('POST', '/api/sesion/llave', {
    id: credencial.id,
    clientDataJSON: aB64u(r.clientDataJSON),
    authenticatorData: aB64u(r.authenticatorData),
    signature: aB64u(r.signature),
    userHandle: r.userHandle ? aB64u(r.userHandle) : null,
    dispositivo: dispositivo(),
    modo: 'cookie'
  }, null);
  await empezarSesion(respuesta);
  return { fallos: respuesta.fallos ?? 0 };
}

let alCambiarSesion = () => {};
/** main.js: qué hacer al entrar o salir (enseñar u ocultar la pantalla de la cuenta, recargar los datos). */
export const alEntrarOSalir = (f) => (alCambiarSesion = f);

async function empezarSesion({ nombre }) {
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
  db.prefs.guardar('sesion', { nombre, cookie: true });
  cambiarEstado('SINCRONIZANDO');
  vigilar();
  // Si este navegador ya tiene datos, se ven al momento y se sincroniza detrás. Si no, primero se bajan y después se
  // quita la pantalla de entrar: la app aparece ya con todo, sin pasar por vacía. (sincronizar() no lanza errores:
  // sin conexión se entra igual y se sincroniza al volver la red.)
  if ((await db.leerPendientes()).length > 0) {
    await alCambiarSesion(true);
    await sincronizar();
  } else {
    await sincronizar();
    await alCambiarSesion(true);
  }
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

/** Cambia el nombre de la cuenta (con el que se entra). Devuelve cómo ha quedado. */
export async function cambiarNombre(nombre) {
  const { nombre: nuevo } = await pedir('POST', '/api/cuenta/nombre', { nombre });
  db.prefs.guardar('sesion', { ...cuenta.sesion, nombre: nuevo });
  db.prefs.guardar('cuentaNombre', nuevo);
  cambiarEstado(cuenta.estado, cuenta.mensaje);
  return nuevo;
}

/**
 * Cambia la contraseña con la actual o, si no se recuerda, con la contraseña de registro (restablecerla). Las
 * sesiones de los demás dispositivos se cierran: tendrán que entrar con la nueva.
 */
export async function cambiarContrasena({ actual, claveRegistro, nueva }) {
  await pedir('POST', '/api/cuenta/contrasena', claveRegistro !== undefined ? { claveRegistro, nueva } : { actual, nueva });
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
    await pasarACookie();
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
  // Con muchos pendientes (al entrar con datos ya guardados), todo de una vez: leerlos uno a uno serían cientos de
  // transacciones seguidas.
  const leer = pendientes.length > 20 ? lectorDeTodo(await db.leerTodoGuardado()) : (almacen, clave) => db.leer(almacen, clave);
  for (const p of pendientes) {
    const tabla = TABLA_DE_ALMACEN[p.almacen];
    if (!tabla) continue;
    const registro = await leer(p.almacen, p.clave);
    // Sin campos (pendientes de antes de la 1.0.17), el servidor lo cuenta todo como cambiado en `modificado`.
    const campos = traducirCampos(TABLAS[tabla], p.campos, true);
    cambios[tabla].push(registro
      ? { ...TABLAS[tabla].aServidor(registro), modificado: p.modificado, ...(campos && { campos }) }
      : { [TABLAS[tabla].clave]: p.clave, borrado: true, modificado: p.modificado, ...(campos && { campos }) });
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
      registros.push({
        almacen: def.almacen, clave: r[def.clave], poner: r.borrado ? null : def.deServidor(r), modificado: r.modificado,
        campos: traducirCampos(def, r.campos, false)
      });
    }
  }
  const enviados = pendientes.map((p) => ({ id: p.id, modificado: p.modificado }));
  const aplicados = await db.aplicarRemoto(registros, enviados);
  const ajustesAplicados = aplicarAjustes(respuesta.cambios?.ajustes ?? [], ajustesPendientes);
  db.prefs.guardar('rev', respuesta.rev);
  const quedan = (await db.leerPendientes()).length > 0 && pendientes.length === POR_ENVIO;
  return { hayMas: respuesta.mas || quedan, aplicados: aplicados + ajustesAplicados };
}

/** Busca en lo leído de una vez ({ socios, bonos, cobros }) por almacén y clave. */
function lectorDeTodo(todo) {
  const mapas = Object.fromEntries(Object.entries(db.CLAVES).map(([almacen, clave]) => [almacen, new Map(todo[almacen].map((r) => [r[clave], r]))]));
  return async (almacen, clave) => mapas[almacen].get(clave);
}

/** Los ajustes que llegan, y fuera de pendientes los que se acaban de subir sin volver a cambiar. */
function aplicarAjustes(recibidos, enviados) {
  const actuales = db.prefs.leer('ajustesPendientes', {});
  for (const [clave, modificado] of Object.entries(enviados)) if (actuales[clave] === modificado) delete actuales[clave];
  let aplicados = 0;
  for (const r of recibidos) {
    // Lo que se acaba de subir vuelve igual: ni se escribe ni cuenta como cambio.
    if (enviados[r.clave] === r.modificado) continue;
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
