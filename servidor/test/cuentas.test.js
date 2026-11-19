import { test } from 'node:test'
import assert from 'node:assert/strict'
import { claveNombre, cifrarContrasena, comprobarContrasena, esClaveRegistro, nombreVisible, validarContrasena, validarNombre } from '../cuentas.js'

test('el nombre se reconoce igual con mayúsculas, minúsculas, tildes o espacios', () => {
  const clave = claveNombre('Julen')
  for (const n of ['julen', 'JULEN', '  Julén ', 'JuLeN', 'júlen']) assert.equal(claveNombre(n), clave)
  assert.equal(claveNombre('Ana  María'), claveNombre('ana maria'))
  assert.notEqual(claveNombre('Ana'), claveNombre('Anna'))
  assert.equal(nombreVisible('  Ana   María '), 'Ana María')
})

test('nombres y contraseñas válidos', () => {
  assert.equal(validarNombre('  Iñaki '), 'Iñaki')
  for (const malo of ['', ' ', 'a', '!!', 'x'.repeat(41), null]) assert.throws(() => validarNombre(malo), /Escribe tu nombre/)
  assert.equal(validarContrasena('12345678'), '12345678')
  for (const mala of ['1234567', '', null, 12345678]) assert.throws(() => validarContrasena(mala), /al menos 8/)
})

test('contraseñas con scrypt: la buena entra, las demás no', async () => {
  const guardada = await cifrarContrasena('caballo-grapa')
  assert.match(guardada, /^scrypt\$16384\$8\$1\$/)
  assert.equal(await comprobarContrasena('caballo-grapa', guardada), true)
  assert.equal(await comprobarContrasena('Caballo-grapa', guardada), false)
  assert.equal(await comprobarContrasena('caballo-grapa'), false)
  assert.equal(await comprobarContrasena('x', 'basura'), false)
  assert.notEqual(await cifrarContrasena('caballo-grapa'), guardada)
})

test('la contraseña de registro', () => {
  assert.equal(esClaveRegistro('secreta', 'secreta'), true)
  assert.equal(esClaveRegistro('Secreta', 'secreta'), false)
  assert.equal(esClaveRegistro('secreta', ''), false)
  assert.equal(esClaveRegistro(undefined, 'secreta'), false)
})

test('datos de conexión: los DB_* de Hostinger, con localhost cambiado por 127.0.0.1', async () => {
  const { datosConexion } = await import('../almacen-mysql.js')
  assert.deepEqual(datosConexion({ DB_HOST: 'localhost', DB_PORT: '3306', DB_NAME: 'u1_socios', DB_USER: 'u1_socios', DB_PASSWORD: 'x' }), {
    host: '127.0.0.1', port: 3306, user: 'u1_socios', password: 'x', database: 'u1_socios',
  })
  assert.equal(datosConexion({ MYSQL_DATABASE: 'otra', MYSQL_HOST: '10.0.0.5' }).host, '10.0.0.5')
})
