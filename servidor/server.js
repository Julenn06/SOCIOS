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
 *   POST   /api/sincronizar          { desde, cambios }                         → { rev, mas, cambios } (ver sincronizacion.js)
 *
 * Variables de entorno:
 *   CLAVE_REGISTRO     contraseña que hay que saber para crear una cuenta (sin ella, el registro está cerrado)
 *   DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD   los pone Hostinger al crear la base de datos del sitio
 *                      (también valen MYSQL_*; sin ninguna, todo va en memoria: solo para probar)
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

const demasiados = () => new ErrorPeticion(429, 'demasiados_intentos', 'Demasiados intentos. Espera unos minutos y vuelve a probar')

// --- utilidades -----------------------------------------------------------

function cabecerasCors(req) {
  const origen = req.headers.origin
  return origen && ORIGENES.includes(origen) ? { 'Access-Control-Allow-Origin': origen, Vary: 'Origin' } : { Vary: 'Origin' }
}

function enviar(req, res, estado, cuerpo, extra = {}) {
  res.writeHead(estado, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
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

async function abrirSesion(almacen, usuario, d) {
  const token = nuevoToken()
  const ahora = Date.now()
  await almacen.crearSesion({ tokenHash: hashToken(token), usuarioId: usuario.id, dispositivo: dispositivo(d), creado: ahora, visto: ahora, caduca: ahora + SESION_MS })
  await almacen.tocarAcceso(usuario.id, ahora)
  return { token, nombre: usuario.nombre }
}

/** La sesión de la petición; la renueva (otros 180 días) como mucho una vez por hora. */
async function sesionDe(almacen, req) {
  const token = /^Bearer (.+)$/.exec(req.headers.authorization ?? '')?.[1]
  if (!esToken(token)) throw new ErrorPeticion(401, 'sin_sesion', 'Inicia sesión')
  const hash = hashToken(token)
  const s = await almacen.sesion(hash)
  const ahora = Date.now()
  if (!s || Number(s.caduca) < ahora) throw new ErrorPeticion(401, 'sin_sesion', 'La sesión ha caducado. Vuelve a iniciar sesión')
  if (ahora - Number(s.visto) > RENOVAR_SESION_MS) await almacen.tocarSesion(hash, ahora, ahora + SESION_MS)
  return { ...s, hash }
}

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

  'POST /api/registro': async ({ req, ip, almacen }) => {
    const cuerpo = await leerJson(req, MAX_CUERPO_CUENTA)
    comprobarClaveRegistro(cuerpo, ip)
    const nombre = validarNombre(cuerpo.nombre)
    const contrasena = validarContrasena(cuerpo.contrasena)
    accesoPorIp.contar(ip)
    const ahora = Date.now()
    const usuario = { id: nuevoId(), nombre, nombreClave: claveNombre(nombre), contrasena: await cifrarContrasena(contrasena), creado: ahora, ultimoAcceso: ahora }
    if (!(await almacen.crearUsuario(usuario))) throw new ErrorPeticion(409, 'nombre_ocupado', 'Ya hay una cuenta con ese nombre. Inicia sesión o elige otro')
    return [201, await abrirSesion(almacen, usuario, cuerpo.dispositivo)]
  },

  'POST /api/sesion': async ({ req, ip, almacen }) => {
    const cuerpo = await leerJson(req, MAX_CUERPO_CUENTA)
    const clave = claveNombre(cuerpo.nombre)
    if (accesoPorIp.pasado(ip) || fallosPorCuenta.pasado(clave)) throw demasiados()
    const usuario = clave ? await almacen.usuarioPorClave(clave) : null
    if (!(await comprobarContrasena(String(cuerpo.contrasena ?? ''), usuario?.contrasena))) {
      accesoPorIp.contar(ip)
      fallosPorCuenta.contar(clave)
      throw new ErrorPeticion(401, 'credenciales', 'El nombre o la contraseña no son correctos')
    }
    fallosPorCuenta.olvidar(clave)
    return abrirSesion(almacen, usuario, cuerpo.dispositivo)
  },

  'DELETE /api/sesion': async ({ req, almacen }) => {
    const s = await sesionDe(almacen, req)
    await almacen.borrarSesion(s.hash)
    return [204]
  },

  'GET /api/cuenta': async ({ req, almacen }) => {
    const s = await sesionDe(almacen, req)
    const u = await almacen.usuario(s.usuarioId)
    if (!u) throw new ErrorPeticion(401, 'sin_sesion', 'Inicia sesión')
    return { nombre: u.nombre, creado: Number(u.creado) }
  },

  'DELETE /api/cuenta': async ({ req, ip, almacen }) => {
    const s = await sesionDe(almacen, req)
    const cuerpo = await leerJson(req, MAX_CUERPO_CUENTA)
    if (accesoPorIp.pasado(ip)) throw demasiados()
    const u = await almacen.usuario(s.usuarioId)
    if (!u || !(await comprobarContrasena(String(cuerpo.contrasena ?? ''), u.contrasena))) {
      accesoPorIp.contar(ip)
      throw new ErrorPeticion(403, 'credenciales', 'La contraseña no es correcta')
    }
    await almacen.borrarUsuario(u.id)
    return [204]
  },

  'POST /api/sincronizar': async ({ req, almacen }) => {
    const s = await sesionDe(almacen, req)
    return sincronizar(almacen, s.usuarioId, await leerJson(req, MAX_CUERPO))
  },
}

export const server = createServer(async (req, res) => {
  // La última entrada de X-Forwarded-For la pone el proxy de Hostinger; las anteriores las controla el cliente.
  const ip = req.headers['x-forwarded-for']?.toString().split(',').pop()?.trim() || req.socket.remoteAddress || ''
  const ruta = new URL(req.url || '/', 'http://localhost').pathname
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
    if (porIp.pasado(ip)) throw demasiados()
    porIp.contar(ip)
    const almacen = await almacenListo
    const resultado = await manejar({ req, ip, almacen })
    const [estado, cuerpo] = Array.isArray(resultado) ? resultado : [200, resultado]
    return enviar(req, res, estado, cuerpo)
  } catch (e) {
    if (e instanceof ErrorPeticion) return enviar(req, res, e.estado, { error: e.codigo, mensaje: e.message })
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
