import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'

process.env.NODE_ENV = 'test'
process.env.CLAVE_REGISTRO = 'clave de prueba'
process.env.MYSQL_DATABASE = ''
process.env.DB_NAME = ''
process.env.ALLOWED_ORIGIN = 'https://socios.jfl06studio.com'
const { server } = await import('../server.js')

let base
before(async () => {
  await new Promise((r) => server.listen(0, r))
  base = `http://127.0.0.1:${server.address().port}`
})
after(() => server.close())

const pedir = async (metodo, ruta, cuerpo, { token, ip = '10.0.0.1', origen } = {}) => {
  const r = await fetch(base + ruta, {
    method: metodo,
    headers: {
      'Content-Type': 'application/json',
      'X-Forwarded-For': ip,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(origen ? { Origin: origen } : {}),
    },
    body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
  })
  const texto = await r.text()
  return { estado: r.status, cuerpo: texto ? JSON.parse(texto) : null, cabeceras: r.headers }
}

test('sin la contraseña de registro no se puede crear una cuenta', async () => {
  assert.equal((await pedir('POST', '/api/registro/comprobar', { claveRegistro: 'mala' }, { ip: '10.0.1.1' })).estado, 403)
  assert.equal((await pedir('POST', '/api/registro/comprobar', { claveRegistro: 'clave de prueba' }, { ip: '10.0.1.1' })).estado, 204)
  const r = await pedir('POST', '/api/registro', { claveRegistro: 'otra', nombre: 'Pirata', contrasena: '12345678' }, { ip: '10.0.1.1' })
  assert.equal(r.estado, 403)
  assert.equal(r.cuerpo.mensaje, 'La contraseña de registro no es correcta')
})

test('registrarse, entrar con el nombre escrito de cualquier forma y sincronizar', async () => {
  const reg = await pedir('POST', '/api/registro', { claveRegistro: 'clave de prueba', nombre: ' Julén ', contrasena: 'una contraseña', dispositivo: 'Web' }, { ip: '10.0.2.1' })
  assert.equal(reg.estado, 201)
  assert.equal(reg.cuerpo.nombre, 'Julén')
  // El mismo nombre en otra forma ya está cogido.
  const repe = await pedir('POST', '/api/registro', { claveRegistro: 'clave de prueba', nombre: 'JULEN', contrasena: 'otra contraseña' }, { ip: '10.0.2.1' })
  assert.equal(repe.estado, 409)

  const entrar = await pedir('POST', '/api/sesion', { nombre: 'julen', contrasena: 'una contraseña', dispositivo: 'Android' }, { ip: '10.0.2.2' })
  assert.equal(entrar.estado, 200)
  assert.equal(entrar.cuerpo.nombre, 'Julén')
  const mal = await pedir('POST', '/api/sesion', { nombre: 'julen', contrasena: 'no es esta' }, { ip: '10.0.2.2' })
  assert.equal(mal.estado, 401)
  assert.equal((await pedir('POST', '/api/sesion', { nombre: 'nadie', contrasena: 'una contraseña' }, { ip: '10.0.2.2' })).estado, 401)

  // Lo que sube la web lo recibe el móvil.
  const web = reg.cuerpo.token
  const movil = entrar.cuerpo.token
  const subida = await pedir('POST', '/api/sincronizar', {
    desde: 0,
    cambios: { socios: [{ id: 'a1', colaboracionCentimos: 1800, hecho: true, diaSemanaId: 3, semanaKey: '2026-W47', nombreSocio: 'Ane', notas: '', timestamp: 5, modificado: 5 }] },
  }, { token: web })
  assert.equal(subida.estado, 200)
  const bajada = await pedir('POST', '/api/sincronizar', { desde: 0 }, { token: movil })
  assert.equal(bajada.cuerpo.cambios.socios[0].nombreSocio, 'Ane')
  assert.equal((await pedir('GET', '/api/cuenta', undefined, { token: movil })).cuerpo.nombre, 'Julén')

  // Cerrar sesión en el móvil no cierra la de la web.
  assert.equal((await pedir('DELETE', '/api/sesion', undefined, { token: movil })).estado, 204)
  assert.equal((await pedir('POST', '/api/sincronizar', { desde: 0 }, { token: movil })).estado, 401)
  assert.equal((await pedir('GET', '/api/cuenta', undefined, { token: web })).estado, 200)

  // Borrar la cuenta pide la contraseña y se lleva los datos y las sesiones.
  assert.equal((await pedir('DELETE', '/api/cuenta', { contrasena: 'mala mala' }, { token: web, ip: '10.0.2.3' })).estado, 403)
  assert.equal((await pedir('DELETE', '/api/cuenta', { contrasena: 'una contraseña' }, { token: web, ip: '10.0.2.3' })).estado, 204)
  assert.equal((await pedir('GET', '/api/cuenta', undefined, { token: web })).estado, 401)
  assert.equal((await pedir('POST', '/api/sesion', { nombre: 'julen', contrasena: 'una contraseña' }, { ip: '10.0.2.4' })).estado, 401)
})

test('cambiar el nombre y la contraseña, y restablecerla con la contraseña de registro', async () => {
  const reg = await pedir('POST', '/api/registro', { claveRegistro: 'clave de prueba', nombre: 'Aitor', contrasena: 'primera-1234' }, { ip: '10.0.6.1' })
  const otro = await pedir('POST', '/api/sesion', { nombre: 'aitor', contrasena: 'primera-1234' }, { ip: '10.0.6.2' })
  await pedir('POST', '/api/registro', { claveRegistro: 'clave de prueba', nombre: 'Maite', contrasena: 'de-maite-123' }, { ip: '10.0.6.1' })
  const t = reg.cuerpo.token

  // El nombre: libre sí, el de otra cuenta no; el mismo con otras mayúsculas también vale.
  assert.equal((await pedir('POST', '/api/cuenta/nombre', { nombre: 'MAITE' }, { token: t })).estado, 409)
  assert.equal((await pedir('POST', '/api/cuenta/nombre', { nombre: 'x' }, { token: t })).estado, 400)
  assert.equal((await pedir('POST', '/api/cuenta/nombre', { nombre: 'AITOR' }, { token: t })).cuerpo.nombre, 'AITOR')
  const cambio = await pedir('POST', '/api/cuenta/nombre', { nombre: ' Aitor Pérez ' }, { token: t })
  assert.equal(cambio.cuerpo.nombre, 'Aitor Pérez')
  assert.equal((await pedir('POST', '/api/sesion', { nombre: 'aitor perez', contrasena: 'primera-1234' }, { ip: '10.0.6.3' })).estado, 200)
  assert.equal((await pedir('POST', '/api/sesion', { nombre: 'aitor', contrasena: 'primera-1234' }, { ip: '10.0.6.3' })).estado, 401)

  // La contraseña: con la actual mal, no; bien, sí, y las demás sesiones se cierran.
  assert.equal((await pedir('POST', '/api/cuenta/contrasena', { actual: 'no-es-esta', nueva: 'segunda-1234' }, { token: t, ip: '10.0.6.4' })).estado, 403)
  assert.equal((await pedir('POST', '/api/cuenta/contrasena', { actual: 'primera-1234', nueva: 'corta' }, { token: t, ip: '10.0.6.4' })).estado, 400)
  assert.equal((await pedir('POST', '/api/cuenta/contrasena', { actual: 'primera-1234', nueva: 'segunda-1234' }, { token: t, ip: '10.0.6.4' })).estado, 204)
  assert.equal((await pedir('GET', '/api/cuenta', undefined, { token: otro.cuerpo.token })).estado, 401)
  assert.equal((await pedir('GET', '/api/cuenta', undefined, { token: t })).estado, 200)
  assert.equal((await pedir('POST', '/api/sesion', { nombre: 'aitor pérez', contrasena: 'segunda-1234' }, { ip: '10.0.6.5' })).estado, 200)

  // Restablecerla sin recordar la actual: solo con la contraseña de registro buena.
  assert.equal((await pedir('POST', '/api/cuenta/contrasena', { claveRegistro: 'mala', nueva: 'tercera-1234' }, { token: t, ip: '10.0.6.6' })).estado, 403)
  assert.equal((await pedir('POST', '/api/cuenta/contrasena', { claveRegistro: 'clave de prueba', nueva: 'tercera-1234' }, { token: t, ip: '10.0.6.6' })).estado, 204)
  assert.equal((await pedir('POST', '/api/sesion', { nombre: 'Aitor Perez', contrasena: 'tercera-1234' }, { ip: '10.0.6.7' })).estado, 200)
  // Sin sesión no se puede.
  assert.equal((await pedir('POST', '/api/cuenta/contrasena', { claveRegistro: 'clave de prueba', nueva: 'cuarta-1234' })).estado, 401)
})

test('tras muchos fallos con una cuenta, espera aunque cambie de IP', async () => {
  await pedir('POST', '/api/registro', { claveRegistro: 'clave de prueba', nombre: 'Miren', contrasena: 'la buena 123' }, { ip: '10.0.3.1' })
  for (let i = 0; i < 10; i++) await pedir('POST', '/api/sesion', { nombre: 'miren', contrasena: 'fallo' }, { ip: `10.0.4.${i}` })
  const r = await pedir('POST', '/api/sesion', { nombre: 'MIREN', contrasena: 'la buena 123' }, { ip: '10.0.5.1' })
  assert.equal(r.estado, 429)
  assert.match(r.cuerpo.mensaje, /Espera unos minutos/)
})

test('sin sesión no hay datos, y solo la web propia puede llamar desde el navegador', async () => {
  assert.equal((await pedir('POST', '/api/sincronizar', { desde: 0 })).estado, 401)
  assert.equal((await pedir('POST', '/api/sincronizar', { desde: 0 }, { token: 'x'.repeat(43) })).estado, 401)
  const propia = await pedir('GET', '/api/salud', undefined, { origen: 'https://socios.jfl06studio.com' })
  assert.equal(propia.cabeceras.get('access-control-allow-origin'), 'https://socios.jfl06studio.com')
  const ajena = await pedir('GET', '/api/salud', undefined, { origen: 'https://malvada.example' })
  assert.equal(ajena.cabeceras.get('access-control-allow-origin'), null)
  assert.equal((await pedir('GET', '/api/no-existe')).estado, 404)
  assert.equal((await pedir('POST', '/api/sesion', undefined)).estado, 401)
})

// Hostinger (LiteSpeed lsnode) carga el punto de entrada con require(): un await de nivel superior lo tumbaría.
test('server.js se puede cargar con require(), como en Hostinger', () => {
  const r = spawnSync(process.execPath, ['-e', "require('./server.js'); process.exit(0)"], {
    cwd: new URL('..', import.meta.url),
    env: { ...process.env, PORT: '0', MYSQL_DATABASE: '', NODE_ENV: 'test' },
    encoding: 'utf8',
    timeout: 20_000,
  })
  assert.equal(r.status, 0, r.stderr)
})
