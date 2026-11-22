import { createServer } from 'node:http'
import {
  claveNombre,
  cifrarContrasena,
  comprobarContrasena,
  ErrorPeticion,
  esClaveRegistro,
  esToken,
  hashToken,
  nuevoId,
  nuevoToken,
  SESION_MS,
  validarContrasena,
  validarNombre,
} from './cuentas.js'
import { sincronizar } from './sincronizacion.js'
import { ALGORITMOS, comprobarCreacion, comprobarEntrada, idDeLlave, nuevoReto, RP_NOMBRE, usuarioDeLlave } from './passkeys.js'
import { firmaError, limpiarErrores } from './errores.js'
import { crearAlmacenMemoria } from './almacen-memoria.js'
import { crearAlmacenMysql } from './almacen-mysql.js'

/**
 * Servidor de Gestión de Socios (https://socios-api.jfl06studio.com): cuentas y sincronización de los datos de la web
 * (pwa/) entre los dispositivos donde se abre. La app Android no lo usa. Mismo planteamiento que el servidor de
 * Marea: Node sin frameworks, MySQL de Hostinger.
 *
 * API (JSON; las rutas con sesión piden "Authorization: Bearer <token>"):
 *   GET    /api/salud
 *   POST   /api/registro/comprobar   { claveRegistro }                          → 204 si es la correcta
 *   POST   /api/registro             { claveRegistro, nombre, contrasena, dispositivo } → { token, nombre }
 *   POST   /api/sesion               { nombre, contrasena, dispositivo }        → { token, nombre }
 *   DELETE /api/sesion                                                          (cerrar sesión)
 *   GET    /api/cuenta                                                          → { nombre, creado }
 *   DELETE /api/cuenta               { contrasena }                             (borra la cuenta y sus datos)
 *   POST   /api/cuenta/nombre        { nombre }                                 → { nombre }
 *   POST   /api/cuenta/contrasena    { actual | claveRegistro, nueva }          → 204 (cierra las demás sesiones)
 *   POST   /api/sincronizar          { desde, cambios }                         → { rev, mas, cambios } (ver sincronizacion.js)
 *   POST   /api/sesion/cookie                                                   (pasa una sesión con token a la cookie)
 *   GET    /api/sesiones                                                        → { sesiones: [{ id, dispositivo, creado, visto, actual }] }
 *   POST   /api/sesiones/cerrar      { id } | { otras: true }                   → 204
 *   GET    /api/actividad                                                       → { actividad: [{ tipo, momento, dispositivo }] }
 *   POST   /api/llaves/opciones                                                 → { reto, usuario, nombre, rp, excluir, algoritmos }
 *   POST   /api/llaves               { id, clientDataJSON, authenticatorData, publicKey, alg, transports, dispositivo } → { id, nombre, creada }
 *   GET    /api/llaves                                                          → { llaves: [{ id, nombre, creada, usada }] }
 *   POST   /api/llaves/borrar        { id }                                     → 204
 *   POST   /api/sesion/llave/opciones                                           → { reto } (sin sesión)
 *   POST   /api/sesion/llave         { id, clientDataJSON, authenticatorData, signature, userHandle, dispositivo } → { nombre }
 *   POST   /api/errores              { errores: [{ tipo, mensaje, fuente, version, navegador }] } o informe CSP → 204
 *   GET    /api/errores?desde=ms     (Authorization: Clave <CLAVE_ERRORES o CLAVE_REGISTRO>) → { errores: [...] }
 *
 * La sesión: la web la lleva en una cookie HttpOnly (__Host-socios_sesion, SameSite=Strict), que su código no puede
 * leer; para eso pide { modo: 'cookie' } al entrar o registrarse. Sin ese modo (tests, otros clientes) se devuelve un
 * token para "Authorization: Bearer". Toda petición que cambia algo y trae Origin tiene que venir de la web.
 *
 * Variables de entorno:
 *   CLAVE_REGISTRO     contraseña que hay que saber para crear una cuenta (sin ella, el registro está cerrado)
 *   DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD   los pone Hostinger al crear la base de datos del sitio
 *                      (también valen MYSQL_*; sin ninguna, todo va en memoria: solo para probar)
 *   CLAVE_ERRORES      la que pide GET /api/errores (si no está, la de registro). Ver errores.js
 *   ALLOWED_ORIGIN     orígenes web permitidos, separados por comas (por defecto https://socios.jfl06studio.com)
 *   PORT               lo pone Hostinger
 *
 * No se registran cuerpos de petición, contraseñas ni tokens.
 */

const env = process.env
const PORT = Number(env.PORT || 3000)
const ORIGENES = (env.ALLOWED_ORIGIN || 'https://socios.jfl06studio.com').split(',').map((o) => o.trim()).filter(Boolean)
const MAX_CUERPO = 4 * 1024 * 1024
const MAX_CUERPO_CUENTA = 8 * 1024
const RENOVAR_SESION_MS = 3600_000

// La cookie de la sesión. __Host-: solo por HTTPS, solo para este dominio exacto y para todo él. HttpOnly: el código
// de la web no la ve (aunque se colara un script, no podría llevársela). SameSite=Strict: no viaja en peticiones que
// salgan de otro sitio.
const COOKIE = '__Host-socios_sesion'
const ATRIBUTOS_COOKIE = 'Path=/; HttpOnly; Secure; SameSite=Strict'
const ponerCookie = (token) => `${COOKIE}=${token}; Max-Age=${Math.floor(SESION_MS / 1000)}; ${ATRIBUTOS_COOKIE}`
const QUITAR_COOKIE = `${COOKIE}=; Max-Age=0; ${ATRIBUTOS_COOKIE}`

// Las cabeceras de seguridad de todas las respuestas: solo HTTPS, nada de cargarlo dentro de otra página y, al ser
// JSON, ningún contenido activo.
const CABECERAS_SEGURIDAD = {
  'Strict-Transport-Security': 'max-age=31536000; includeSubDomains',
  'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
  'X-Frame-Options': 'DENY',
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
  'Cross-Origin-Resource-Policy': 'same-origin',
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=(), usb=()',
}

// Hostinger carga este archivo con require(): nada de await de nivel superior. El almacén se abre al arrancar y cada
// petición lo espera.
export const almacenListo = env.DB_NAME || env.MYSQL_DATABASE ? crearAlmacenMysql(env) : Promise.resolve(crearAlmacenMemoria())
almacenListo.catch((e) => console.error('No se ha podido abrir la base de datos:', e.message))

// --- límites --------------------------------------------------------------

/** Ventana deslizante sencilla: como mucho `max` sucesos por `ms` para cada clave. */
function limitador(max, ms) {
  const mapa = new Map()
  setInterval(() => {
    const corte = Date.now() - ms
    for (const [k, v] of mapa) if (v.t < corte) mapa.delete(k)
  }, ms).unref()
  return {
    pasado(clave) {
      const ahora = Date.now()
      const v = mapa.get(clave)
      return Boolean(v && ahora - v.t <= ms && v.n >= max)
    },
    contar(clave) {
      const ahora = Date.now()
      const v = mapa.get(clave)
      if (!v || ahora - v.t > ms) mapa.set(clave, { n: 1, t: ahora })
      else v.n++
    },
    olvidar: (clave) => mapa.delete(clave),
  }
}

const porIp = limitador(240, 60_000)
// Intentos de entrar o de registrarse: por IP y, aparte, fallos por cuenta (aunque cambie la IP).
const accesoPorIp = limitador(20, 15 * 60_000)
const fallosPorCuenta = limitador(10, 15 * 60_000)
// Informes de errores de la web: los que pasen de aquí se descartan sin avisar.
const erroresPorIp = limitador(30, 10 * 60_000)
const MAX_LLAVES = 20

const demasiados = () => new ErrorPeticion(429, 'demasiados_intentos', 'Demasiados intentos. Espera unos minutos y vuelve a probar')

// --- utilidades -----------------------------------------------------------

function cabecerasCors(req) {
  const origen = req.headers.origin
  return origen && ORIGENES.includes(origen)
    ? { 'Access-Control-Allow-Origin': origen, 'Access-Control-Allow-Credentials': 'true', Vary: 'Origin' }
    : { Vary: 'Origin' }
}

function enviar(req, res, estado, cuerpo, extra = {}) {
  res.writeHead(estado, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    ...CABECERAS_SEGURIDAD,
    ...cabecerasCors(req),
    ...extra,
  })
  res.end(cuerpo === undefined ? '' : JSON.stringify(cuerpo))
}

function leerJson(req, limite) {
  return new Promise((resolver, rechazar) => {
    let tamano = 0
    const trozos = []
    req.on('data', (c) => {
      tamano += c.length
      if (tamano > limite) {
        rechazar(new ErrorPeticion(413, 'demasiado_grande', 'La petición es demasiado grande'))
        req.destroy()
        return
      }
      trozos.push(c)
    })
    req.on('end', () => {
      try {
        resolver(trozos.length ? JSON.parse(Buffer.concat(trozos).toString('utf8')) : {})
      } catch {
        rechazar(new ErrorPeticion(400, 'json', 'JSON no válido'))
      }
    })
    req.on('error', rechazar)
  })
}

const dispositivo = (d) => String(d ?? '').replace(/[\u0000-\u001f]/g, '').slice(0, 80)

/** El valor de una cookie de la petición. */
function leerCookie(req, nombre) {
  for (const parte of String(req.headers.cookie ?? '').split(';')) {
    const i = parte.indexOf('=')
    if (i > 0 && parte.slice(0, i).trim() === nombre) return parte.slice(i + 1).trim()
  }
  return null
}

/** Intentos fallidos de entrar desde la última vez que se entró (lo más reciente primero). */
function fallosDesdeUltimaEntrada(actividad) {
  let fallos = 0
  for (const a of actividad) {
    if (a.tipo === 'entrada' || a.tipo === 'llave' || a.tipo === 'registro') break
    if (a.tipo === 'fallo') fallos++
  }
  return fallos
}

/**
 * Abre una sesión nueva. Con modo 'cookie' (la web), el token va solo en la cookie; si no, en la respuesta. [fallos]:
 * cuántas veces se ha fallado la contraseña de esta cuenta desde la última vez que se entró, para avisar.
 */
async function abrirSesion({ almacen, ctx }, usuario, cuerpo, tipo) {
  const token = nuevoToken()
  const ahora = Date.now()
  const d = dispositivo(cuerpo.dispositivo)
  const fallos = tipo === 'entrada' || tipo === 'llave' ? fallosDesdeUltimaEntrada(await almacen.actividad(usuario.id, 100)) : 0
  await almacen.crearSesion({ tokenHash: hashToken(token), usuarioId: usuario.id, dispositivo: d, creado: ahora, visto: ahora, caduca: ahora + SESION_MS })
  await almacen.tocarAcceso(usuario.id, ahora)
  await almacen.anotar(usuario.id, tipo, d, ahora)
  if (cuerpo.modo === 'cookie') {
    ctx.cabeceras['Set-Cookie'] = ponerCookie(token)
    return { nombre: usuario.nombre, fallos }
  }
  return { token, nombre: usuario.nombre, fallos }
}

/**
 * La sesión de la petición: la de la cookie o, si no hay, la del token. La renueva (otros 180 días) como mucho una
 * vez por hora, y con ella la cookie.
 */
async function sesionDe({ almacen, req, ctx }, { soloToken = false } = {}) {
  const cookie = soloToken ? null : leerCookie(req, COOKIE)
  const token = cookie ?? /^Bearer (.+)$/.exec(req.headers.authorization ?? '')?.[1]
  if (!esToken(token)) throw new ErrorPeticion(401, 'sin_sesion', 'Inicia sesión')
  const hash = hashToken(token)
  const s = await almacen.sesion(hash)
  const ahora = Date.now()
  if (!s || Number(s.caduca) < ahora) {
    if (cookie) ctx.cabeceras['Set-Cookie'] = QUITAR_COOKIE
    throw new ErrorPeticion(401, 'sin_sesion', 'La sesión ha caducado. Vuelve a iniciar sesión')
  }
  if (ahora - Number(s.visto) > RENOVAR_SESION_MS) {
    await almacen.tocarSesion(hash, ahora, ahora + SESION_MS)
    if (cookie) ctx.cabeceras['Set-Cookie'] = ponerCookie(token)
  }
  return { ...s, hash, token }
}

/** El identificador público de una sesión (en la lista de dispositivos): un trozo de su SHA-256, no el token. */
const idSesion = (hash) => hash.slice(0, 16)

function comprobarClaveRegistro(cuerpo, ip) {
  if (!env.CLAVE_REGISTRO) throw new ErrorPeticion(503, 'registro_cerrado', 'El registro no está abierto')
  if (accesoPorIp.pasado(ip)) throw demasiados()
  if (!esClaveRegistro(cuerpo.claveRegistro, env.CLAVE_REGISTRO)) {
    accesoPorIp.contar(ip)
    throw new ErrorPeticion(403, 'clave_registro', 'La contraseña de registro no es correcta')
  }
}

// --- rutas ----------------------------------------------------------------

const rutas = {
  'GET /api/salud': async () => ({ ok: true }),

  'POST /api/registro/comprobar': async ({ req, ip }) => {
    comprobarClaveRegistro(await leerJson(req, MAX_CUERPO_CUENTA), ip)
    return [204]
  },

  'POST /api/registro': async (c) => {
    const cuerpo = await leerJson(c.req, MAX_CUERPO_CUENTA)
    comprobarClaveRegistro(cuerpo, c.ip)
    const nombre = validarNombre(cuerpo.nombre)
    const contrasena = validarContrasena(cuerpo.contrasena)
    accesoPorIp.contar(c.ip)
    const ahora = Date.now()
    const usuario = { id: nuevoId(), nombre, nombreClave: claveNombre(nombre), contrasena: await cifrarContrasena(contrasena), creado: ahora, ultimoAcceso: ahora }
    if (!(await c.almacen.crearUsuario(usuario))) throw new ErrorPeticion(409, 'nombre_ocupado', 'Ya hay una cuenta con ese nombre. Inicia sesión o elige otro')
    return [201, await abrirSesion(c, usuario, cuerpo, 'registro')]
  },

  'POST /api/sesion': async (c) => {
    const cuerpo = await leerJson(c.req, MAX_CUERPO_CUENTA)
    const clave = claveNombre(cuerpo.nombre)
    if (accesoPorIp.pasado(c.ip) || fallosPorCuenta.pasado(clave)) throw demasiados()
    const usuario = clave ? await c.almacen.usuarioPorClave(clave) : null
    if (!(await comprobarContrasena(String(cuerpo.contrasena ?? ''), usuario?.contrasena))) {
      accesoPorIp.contar(c.ip)
      fallosPorCuenta.contar(clave)
      if (usuario) await c.almacen.anotar(usuario.id, 'fallo', dispositivo(cuerpo.dispositivo), Date.now())
      throw new ErrorPeticion(401, 'credenciales', 'El nombre o la contraseña no son correctos')
    }
    fallosPorCuenta.olvidar(clave)
    return abrirSesion(c, usuario, cuerpo, 'entrada')
  },

  // Una sesión de antes (con el token guardado en la web) pasa a la cookie, y la web olvida el token.
  'POST /api/sesion/cookie': async (c) => {
    const s = await sesionDe(c, { soloToken: true })
    c.ctx.cabeceras['Set-Cookie'] = ponerCookie(s.token)
    return [204]
  },

  'DELETE /api/sesion': async (c) => {
    c.ctx.cabeceras['Set-Cookie'] = QUITAR_COOKIE
    const s = await sesionDe(c)
    await c.almacen.borrarSesion(s.hash)
    return [204]
  },

  'GET /api/cuenta': async (c) => {
    const s = await sesionDe(c)
    const u = await c.almacen.usuario(s.usuarioId)
    if (!u) throw new ErrorPeticion(401, 'sin_sesion', 'Inicia sesión')
    return { nombre: u.nombre, creado: Number(u.creado) }
  },

  'DELETE /api/cuenta': async (c) => {
    const s = await sesionDe(c)
    const cuerpo = await leerJson(c.req, MAX_CUERPO_CUENTA)
    if (accesoPorIp.pasado(c.ip)) throw demasiados()
    const u = await c.almacen.usuario(s.usuarioId)
    if (!u || !(await comprobarContrasena(String(cuerpo.contrasena ?? ''), u.contrasena))) {
      accesoPorIp.contar(c.ip)
      throw new ErrorPeticion(403, 'credenciales', 'La contraseña no es correcta')
    }
    await c.almacen.borrarUsuario(u.id)
    c.ctx.cabeceras['Set-Cookie'] = QUITAR_COOKIE
    return [204]
  },

  'POST /api/cuenta/nombre': async (c) => {
    const s = await sesionDe(c)
    const nombre = validarNombre((await leerJson(c.req, MAX_CUERPO_CUENTA)).nombre)
    if (!(await c.almacen.cambiarNombre(s.usuarioId, nombre, claveNombre(nombre)))) {
      throw new ErrorPeticion(409, 'nombre_ocupado', 'Ya hay una cuenta con ese nombre. Elige otro')
    }
    await c.almacen.anotar(s.usuarioId, 'nombre', s.dispositivo, Date.now())
    return { nombre }
  },

  // Con la contraseña actual o, si no se recuerda, con la de registro (restablecerla). Hace falta la sesión abierta.
  // Las demás sesiones se cierran: si alguien conocía la contraseña antigua, se queda fuera.
  'POST /api/cuenta/contrasena': async (c) => {
    const s = await sesionDe(c)
    const cuerpo = await leerJson(c.req, MAX_CUERPO_CUENTA)
    const nueva = validarContrasena(cuerpo.nueva)
    if (accesoPorIp.pasado(c.ip)) throw demasiados()
    const u = await c.almacen.usuario(s.usuarioId)
    if (!u) throw new ErrorPeticion(401, 'sin_sesion', 'Inicia sesión')
    const restablecer = cuerpo.claveRegistro !== undefined
    if (restablecer) {
      if (!esClaveRegistro(cuerpo.claveRegistro, env.CLAVE_REGISTRO)) {
        accesoPorIp.contar(c.ip)
        throw new ErrorPeticion(403, 'clave_registro', 'La contraseña de registro no es correcta')
      }
    } else if (!(await comprobarContrasena(String(cuerpo.actual ?? ''), u.contrasena))) {
      accesoPorIp.contar(c.ip)
      throw new ErrorPeticion(403, 'credenciales', 'La contraseña actual no es correcta')
    }
    await c.almacen.cambiarContrasena(u.id, await cifrarContrasena(nueva))
    await c.almacen.cerrarOtrasSesiones(u.id, s.hash)
    await c.almacen.anotar(u.id, restablecer ? 'restablecida' : 'contrasena', s.dispositivo, Date.now())
    return [204]
  },

  'GET /api/sesiones': async (c) => {
    const s = await sesionDe(c)
    const sesiones = await c.almacen.sesionesDe(s.usuarioId)
    return {
      sesiones: sesiones.map((x) => ({
        id: idSesion(x.tokenHash),
        dispositivo: x.dispositivo,
        creado: Number(x.creado),
        visto: Number(x.visto),
        actual: x.tokenHash === s.hash,
      })),
    }
  },

  // Cerrar la sesión de otro dispositivo ({ id }) o la de todos los demás ({ otras: true }).
  'POST /api/sesiones/cerrar': async (c) => {
    const s = await sesionDe(c)
    const cuerpo = await leerJson(c.req, MAX_CUERPO_CUENTA)
    const ahora = Date.now()
    if (cuerpo.otras === true) {
      await c.almacen.cerrarOtrasSesiones(s.usuarioId, s.hash)
      await c.almacen.anotar(s.usuarioId, 'cierre_otras', s.dispositivo, ahora)
      return [204]
    }
    const id = String(cuerpo.id ?? '')
    const objetivo = /^[0-9a-f]{16}$/.test(id) ? (await c.almacen.sesionesDe(s.usuarioId)).find((x) => idSesion(x.tokenHash) === id) : null
    if (!objetivo) throw new ErrorPeticion(404, 'sin_sesion_otra', 'Esa sesión ya no está abierta')
    if (objetivo.tokenHash === s.hash) throw new ErrorPeticion(400, 'sesion_actual', 'Para cerrar la sesión de este dispositivo, usa "Cerrar sesión"')
    await c.almacen.borrarSesion(objetivo.tokenHash)
    await c.almacen.anotar(s.usuarioId, 'cierre_remoto', objetivo.dispositivo, ahora)
    return [204]
  },

  'GET /api/actividad': async (c) => {
    const s = await sesionDe(c)
    const actividad = await c.almacen.actividad(s.usuarioId, 50)
    return { actividad: actividad.map((a) => ({ tipo: a.tipo, momento: Number(a.momento), dispositivo: a.dispositivo })) }
  },

  // --- llaves de acceso (passkeys.js) ---

  'POST /api/llaves/opciones': async (c) => {
    const s = await sesionDe(c)
    const u = await c.almacen.usuario(s.usuarioId)
    if (!u) throw new ErrorPeticion(401, 'sin_sesion', 'Inicia sesión')
    const llaves = await c.almacen.llavesDe(u.id)
    return {
      reto: nuevoReto('crear', u.id),
      usuario: usuarioDeLlave(u.id),
      nombre: u.nombre,
      rp: RP_NOMBRE,
      // Las que ya tiene: el navegador no deja crear otra en el mismo autenticador.
      excluir: llaves.map((l) => ({ id: l.id, transportes: l.transportes })),
      algoritmos: ALGORITMOS,
    }
  },

  'POST /api/llaves': async (c) => {
    const s = await sesionDe(c)
    const cuerpo = await leerJson(c.req, MAX_CUERPO_CUENTA)
    const nueva = comprobarCreacion(cuerpo, s.usuarioId, ORIGENES)
    const llaves = await c.almacen.llavesDe(s.usuarioId)
    if (llaves.length >= MAX_LLAVES) throw new ErrorPeticion(409, 'demasiadas_llaves', `Como mucho ${MAX_LLAVES} llaves de acceso: borra alguna que ya no uses`)
    if (llaves.some((l) => l.id === nueva.id)) throw new ErrorPeticion(409, 'llave_repetida', 'Esa llave de acceso ya está guardada')
    const ahora = Date.now()
    const llave = { ...nueva, usuarioId: s.usuarioId, nombre: dispositivo(cuerpo.dispositivo) || s.dispositivo, creada: ahora }
    await c.almacen.guardarLlave(llave)
    await c.almacen.anotar(s.usuarioId, 'llave_nueva', llave.nombre, ahora)
    return [201, { id: llave.id, nombre: llave.nombre, creada: ahora }]
  },

  'GET /api/llaves': async (c) => {
    const s = await sesionDe(c)
    const llaves = await c.almacen.llavesDe(s.usuarioId)
    return { llaves: llaves.map((l) => ({ id: l.id, nombre: l.nombre, creada: Number(l.creada), usada: Number(l.usada) })) }
  },

  'POST /api/llaves/borrar': async (c) => {
    const s = await sesionDe(c)
    const id = String((await leerJson(c.req, MAX_CUERPO_CUENTA)).id ?? '')
    const llave = (await c.almacen.llavesDe(s.usuarioId)).find((l) => l.id === id)
    if (!llave || !(await c.almacen.borrarLlave(s.usuarioId, id))) throw new ErrorPeticion(404, 'sin_llave', 'Esa llave de acceso ya no está')
    await c.almacen.anotar(s.usuarioId, 'llave_borrada', llave.nombre, Date.now())
    return [204]
  },

  // Entrar con una llave: el navegador ofrece las que tiene para esta web (no hace falta escribir el nombre).
  'POST /api/sesion/llave/opciones': async (c) => {
    if (accesoPorIp.pasado(c.ip)) throw demasiados()
    return { reto: nuevoReto('entrar') }
  },

  'POST /api/sesion/llave': async (c) => {
    const cuerpo = await leerJson(c.req, MAX_CUERPO_CUENTA)
    if (accesoPorIp.pasado(c.ip)) throw demasiados()
    const llave = await c.almacen.llave(idDeLlave(cuerpo))
    const usuario = llave ? await c.almacen.usuario(llave.usuarioId) : null
    if (!usuario) {
      accesoPorIp.contar(c.ip)
      throw new ErrorPeticion(401, 'llave_desconocida', 'Esta llave de acceso ya no vale: puede que se borrara o que fuera de una cuenta borrada. Entra con la contraseña')
    }
    let contador
    try {
      contador = comprobarEntrada(cuerpo, llave, ORIGENES)
    } catch (e) {
      accesoPorIp.contar(c.ip)
      throw e
    }
    await c.almacen.tocarLlave(llave.id, contador, Date.now())
    return abrirSesion(c, usuario, cuerpo, 'llave')
  },

  // --- errores de la web (errores.js) ---

  'POST /api/errores': async (c) => {
    const cuerpo = await leerJson(c.req, 2 * MAX_CUERPO_CUENTA)
    if (erroresPorIp.pasado(c.ip)) return [204]
    erroresPorIp.contar(c.ip)
    const ahora = Date.now()
    for (const e of limpiarErrores(cuerpo)) await c.almacen.anotarError({ ...e, firma: firmaError(e), momento: ahora })
    return [204]
  },

  'GET /api/errores': async (c) => {
    const clave = env.CLAVE_ERRORES || env.CLAVE_REGISTRO
    if (!clave) throw new ErrorPeticion(503, 'sin_clave', 'No hay clave para ver los errores')
    if (accesoPorIp.pasado(c.ip)) throw demasiados()
    if (!esClaveRegistro(/^Clave (.+)$/.exec(c.req.headers.authorization ?? '')?.[1], clave)) {
      accesoPorIp.contar(c.ip)
      throw new ErrorPeticion(403, 'clave', 'La clave no es correcta')
    }
    const desde = Number(new URL(c.req.url, 'http://x').searchParams.get('desde') ?? 0)
    const errores = await c.almacen.errores(Number.isSafeInteger(desde) && desde > 0 ? desde : 0, 200)
    return {
      errores: errores.map((e) => ({
        tipo: e.tipo, mensaje: e.mensaje, fuente: e.fuente, version: e.version, navegador: e.navegador,
        veces: Number(e.veces), primero: Number(e.primero), ultimo: Number(e.ultimo),
      })),
    }
  },

  'POST /api/sincronizar': async (c) => {
    const s = await sesionDe(c)
    return sincronizar(c.almacen, s.usuarioId, await leerJson(c.req, MAX_CUERPO))
  },
}

export const server = createServer(async (req, res) => {
  // La última entrada de X-Forwarded-For la pone el proxy de Hostinger; las anteriores las controla el cliente.
  // Comprobado en producción (2026-11-21): con un X-Forwarded-For falso, la última sigue siendo la IP real (la misma
  // que X-Real-IP), así que los límites de intentos van por persona y no se pueden saltar cambiando esa cabecera.
  const ip = req.headers['x-forwarded-for']?.toString().split(',').pop()?.trim() || req.socket.remoteAddress || ''
  const ruta = new URL(req.url || '/', 'http://localhost').pathname
  // Lo que las rutas añaden a la respuesta (la cookie de la sesión).
  const ctx = { cabeceras: {} }
  try {
    if (req.method === 'OPTIONS') {
      return enviar(req, res, 204, undefined, {
        'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type, Authorization',
        'Access-Control-Max-Age': '86400',
      })
    }
    const manejar = rutas[`${req.method} ${ruta}`]
    if (!manejar) return enviar(req, res, 404, { error: 'no_existe', mensaje: 'No existe' })
    // Contra peticiones falsificadas (CSRF): lo que cambia algo, si lo manda un navegador, tiene que salir de la web.
    // Los navegadores siempre ponen Origin en esas peticiones; sin él no es un navegador y no lleva la cookie de nadie.
    // Los informes de errores no cambian nada de nadie: los del navegador (report-uri) pueden llegar con otro Origin.
    const origen = req.headers.origin
    if (req.method !== 'GET' && origen && !ORIGENES.includes(origen) && ruta !== '/api/errores') {
      throw new ErrorPeticion(403, 'origen', 'Esta petición no viene de la web de Gestión de Socios')
    }
    if (porIp.pasado(ip)) throw demasiados()
    porIp.contar(ip)
    const almacen = await almacenListo
    const resultado = await manejar({ req, ip, almacen, ctx })
    const [estado, cuerpo] = Array.isArray(resultado) ? resultado : [200, resultado]
    return enviar(req, res, estado, cuerpo, ctx.cabeceras)
  } catch (e) {
    if (e instanceof ErrorPeticion) return enviar(req, res, e.estado, { error: e.codigo, mensaje: e.message }, ctx.cabeceras)
    console.error(`${req.method} ${ruta}:`, e)
    return enviar(req, res, 500, { error: 'interno', mensaje: 'Error del servidor. Vuelve a intentarlo' })
  }
})

// Sesiones caducadas fuera, una vez por hora.
setInterval(async () => {
  try {
    await (await almacenListo).podar(Date.now())
  } catch (e) {
    console.error('No se han podido podar las sesiones:', e.message)
  }
}, 3600_000).unref()

if (env.NODE_ENV !== 'test') server.listen(PORT, () => console.log(`Socios escuchando en ${PORT}`))
