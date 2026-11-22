import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { createHash, generateKeyPairSync, sign } from 'node:crypto'

process.env.NODE_ENV = 'test'
process.env.CLAVE_REGISTRO = 'clave de prueba'
process.env.MYSQL_DATABASE = ''
process.env.DB_NAME = ''
process.env.ALLOWED_ORIGIN = 'https://socios.jfl06studio.com'
const { server } = await import('../server.js')
const { limpiarErrores } = await import('../errores.js')

const WEB = 'https://socios.jfl06studio.com'
let base
before(async () => {
  await new Promise((r) => server.listen(0, r))
  base = `http://127.0.0.1:${server.address().port}`
})
after(() => server.close())

const pedir = async (metodo, ruta, cuerpo, { token, ip = '10.9.0.1', cabeceras = {} } = {}) => {
  const r = await fetch(base + ruta, {
    method: metodo,
    headers: { 'Content-Type': 'application/json', 'X-Forwarded-For': ip, Origin: WEB, ...(token ? { Authorization: `Bearer ${token}` } : {}), ...cabeceras },
    body: cuerpo === undefined ? undefined : typeof cuerpo === 'string' ? cuerpo : JSON.stringify(cuerpo),
  })
  const texto = await r.text()
  return { estado: r.status, cuerpo: texto ? JSON.parse(texto) : null, cabeceras: r.headers }
}

const b64u = (b) => Buffer.from(b).toString('base64url')
const sha256 = (d) => createHash('sha256').update(d).digest()

/** Un autenticador de mentira, como el de un móvil: una clave P-256 y su contador. */
function autenticador({ rpId = 'socios.jfl06studio.com', origen = WEB, uv = true, contadorFijo = null } = {}) {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' })
  const id = Buffer.from(Array.from({ length: 16 }, () => Math.floor(Math.random() * 256)))
  let contador = 0
  const datos = (extra, banderas) => {
    const c = Buffer.alloc(4)
    c.writeUInt32BE(contadorFijo ?? ++contador)
    return Buffer.concat([sha256(rpId), Buffer.from([banderas]), c, extra])
  }
  const cliente = (type, challenge) => Buffer.from(JSON.stringify({ type, challenge, origin: origen }))
  return {
    id: b64u(id),
    crear(reto) {
      const largo = Buffer.alloc(2)
      largo.writeUInt16BE(id.length)
      // AAGUID (16 bytes), longitud del id, id y la clave en COSE (no se lee: la SPKI va aparte).
      const atestada = Buffer.concat([Buffer.alloc(16), largo, id, Buffer.from([0xa0])])
      return {
        id: b64u(id),
        clientDataJSON: b64u(cliente('webauthn.create', reto)),
        authenticatorData: b64u(datos(atestada, 0x41 | (uv ? 0x04 : 0))),
        publicKey: b64u(publicKey.export({ format: 'der', type: 'spki' })),
        alg: -7,
        transports: ['internal', 'hybrid'],
        dispositivo: 'Chrome en Android',
      }
    },
    firmar(reto, usuario) {
      const cd = cliente('webauthn.get', reto)
      const ad = datos(Buffer.alloc(0), 0x01 | (uv ? 0x04 : 0))
      return {
        id: b64u(id),
        clientDataJSON: b64u(cd),
        authenticatorData: b64u(ad),
        signature: b64u(sign('sha256', Buffer.concat([ad, sha256(cd)]), privateKey)),
        userHandle: usuario,
        dispositivo: 'Safari en iPhone',
        modo: 'cookie',
      }
    },
  }
}

async function cuentaNueva(nombre, ip) {
  const r = await pedir('POST', '/api/registro', { claveRegistro: 'clave de prueba', nombre, contrasena: 'una contraseña larga' }, { ip })
  assert.equal(r.estado, 201)
  return r.cuerpo.token
}

test('crear una llave de acceso, entrar con ella sin contraseña y borrarla', async () => {
  const token = await cuentaNueva('Llavera', '10.9.1.1')
  const op = await pedir('POST', '/api/llaves/opciones', {}, { token })
  assert.equal(op.estado, 200)
  assert.equal(op.cuerpo.nombre, 'Llavera')
  assert.deepEqual(op.cuerpo.excluir, [])
  const a = autenticador()
  const creada = await pedir('POST', '/api/llaves', a.crear(op.cuerpo.reto), { token })
  assert.equal(creada.estado, 201)
  assert.equal(creada.cuerpo.nombre, 'Chrome en Android')
  // El reto ya se ha gastado.
  assert.equal((await pedir('POST', '/api/llaves', a.crear(op.cuerpo.reto), { token })).estado, 400)

  const reto = (await pedir('POST', '/api/sesion/llave/opciones', {})).cuerpo.reto
  const r = await pedir('POST', '/api/sesion/llave', a.firmar(reto, op.cuerpo.usuario), { ip: '10.9.1.2' })
  assert.equal(r.estado, 200)
  assert.match(r.cabeceras.get('set-cookie'), /^__Host-socios_sesion=.+HttpOnly/)
  assert.equal(r.cuerpo.nombre, 'Llavera')
  assert.equal(r.cuerpo.token, undefined)

  // Una firma vieja (reto gastado) no vale otra vez.
  assert.equal((await pedir('POST', '/api/sesion/llave', a.firmar(reto, op.cuerpo.usuario))).estado, 400)

  const lista = await pedir('GET', '/api/llaves', undefined, { token })
  assert.equal(lista.cuerpo.llaves.length, 1)
  assert.ok(lista.cuerpo.llaves[0].usada > 0)
  assert.equal((await pedir('POST', '/api/llaves/opciones', {}, { token })).cuerpo.excluir[0].id, a.id)
  const actividad = (await pedir('GET', '/api/actividad', undefined, { token })).cuerpo.actividad.map((x) => x.tipo)
  assert.deepEqual(actividad.slice(0, 2), ['llave', 'llave_nueva'])

  assert.equal((await pedir('POST', '/api/llaves/borrar', { id: a.id }, { token })).estado, 204)
  assert.equal((await pedir('POST', '/api/llaves/borrar', { id: a.id }, { token })).estado, 404)
  const otra = (await pedir('POST', '/api/sesion/llave/opciones', {})).cuerpo.reto
  const borrada = await pedir('POST', '/api/sesion/llave', a.firmar(otra, op.cuerpo.usuario))
  assert.equal(borrada.estado, 401)
  assert.equal(borrada.cuerpo.error, 'llave_desconocida')
})

test('una llave de otra web, sin verificar al usuario, con firma falsa o copiada no vale', async () => {
  const token = await cuentaNueva('Desconfiada', '10.9.2.1')
  const crear = async (a) => pedir('POST', '/api/llaves', a.crear((await pedir('POST', '/api/llaves/opciones', {}, { token })).cuerpo.reto), { token })
  assert.equal((await crear(autenticador({ origen: 'https://socios.jfl06studio.com.falsa.es' }))).cuerpo.mensaje, 'La llave de acceso no es válida (origen)')
  assert.equal((await crear(autenticador({ rpId: 'otra.com' }))).cuerpo.mensaje, 'La llave de acceso no es válida (web)')
  assert.equal((await crear(autenticador({ uv: false }))).cuerpo.mensaje, 'La llave de acceso no es válida (sin verificar al usuario)')

  const buena = autenticador({ contadorFijo: 5 })
  assert.equal((await crear(buena)).estado, 201)
  const usuario = (await pedir('POST', '/api/llaves/opciones', {}, { token })).cuerpo.usuario
  const entrar = async (cambiar = (x) => x) =>
    pedir('POST', '/api/sesion/llave', cambiar(buena.firmar((await pedir('POST', '/api/sesion/llave/opciones', {}, { ip: '10.9.2.9' })).cuerpo.reto, usuario)), { ip: '10.9.2.9' })
  // Firma de otro contenido.
  assert.equal((await entrar((x) => ({ ...x, signature: buena.firmar('otro', usuario).signature }))).estado, 401)
  // De otra cuenta.
  assert.equal((await entrar((x) => ({ ...x, userHandle: b64u('otra') }))).cuerpo.mensaje, 'La llave de acceso no es válida (cuenta)')
  // El contador no avanza (5 al crearla y 5 ahora): es una copia.
  assert.match((await entrar()).cuerpo.mensaje, /copiada/)
})

test('los errores de la web se guardan sin datos personales y solo los ve quien tiene la clave', async () => {
  const limpio = limpiarErrores({
    errores: [
      { tipo: 'error', mensaje: 'Unexpected token "Juan Pérez" is not valid JSON', fuente: 'https://socios.jfl06studio.com/js/estado.js?v=2#x', linea: 12, columna: 3, version: '1.0.17', navegador: 'Safari en iPhone' },
      { tipo: 'inventado', mensaje: 'x' },
    ],
  })
  assert.deepEqual(limpio, [{ tipo: 'error', mensaje: 'Unexpected token "…" is not valid JSON', fuente: '/js/estado.js:12:3', version: '1.0.17', navegador: 'Safari en iPhone' }])
  const csp = limpiarErrores({ 'csp-report': { 'effective-directive': 'script-src-elem', 'blocked-uri': 'https://malo.com/x.js?token=1', 'source-file': 'https://socios.jfl06studio.com/', 'line-number': 4 } })
  assert.equal(csp[0].mensaje, 'script-src-elem bloqueó https://malo.com/x.js')

  const error = { tipo: 'promesa', mensaje: 'Falló', fuente: '/js/a.js', version: '1.0.17', navegador: 'Chrome' }
  for (let i = 0; i < 3; i++) assert.equal((await pedir('POST', '/api/errores', { errores: [error] }, { ip: '10.9.3.1' })).estado, 204)
  const informe = JSON.stringify({ 'csp-report': { 'violated-directive': 'style-src', 'blocked-uri': 'inline' } })
  assert.equal((await pedir('POST', '/api/errores', informe, { ip: '10.9.3.1', cabeceras: { 'Content-Type': 'application/csp-report' } })).estado, 204)

  const clave = { Authorization: 'Clave clave de prueba' }
  assert.equal((await pedir('GET', '/api/errores', undefined, { ip: '10.9.3.2' })).estado, 403)
  assert.equal((await pedir('GET', '/api/errores', undefined, { ip: '10.9.3.2', cabeceras: { Authorization: 'Clave mala' } })).estado, 403)
  const r = await pedir('GET', '/api/errores?desde=1', undefined, { ip: '10.9.3.2', cabeceras: clave })
  assert.equal(r.estado, 200)
  const promesa = r.cuerpo.errores.find((e) => e.tipo === 'promesa')
  assert.equal(promesa.veces, 3)
  assert.ok(r.cuerpo.errores.some((e) => e.tipo === 'csp' && e.mensaje === 'style-src bloqueó inline'))
  assert.equal((await pedir('GET', `/api/errores?desde=${promesa.ultimo + 1e6}`, undefined, { ip: '10.9.3.2', cabeceras: clave })).cuerpo.errores.length, 0)
})
