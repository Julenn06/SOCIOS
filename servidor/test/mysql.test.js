import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'

// El almacén MySQL de verdad (migraciones incluidas). Solo con una base de datos de pruebas: en el workflow del
// servidor hay un MySQL para esto (MYSQL_PRUEBAS=1 y MYSQL_*); en local, sin ella, se salta.
const hay = process.env.MYSQL_PRUEBAS === '1'
let almacen

before(async () => {
  if (!hay) return
  const { crearAlmacenMysql } = await import('../almacen-mysql.js')
  almacen = await crearAlmacenMysql(process.env)
  // Dos veces: las migraciones ya hechas no se repiten.
  await (await crearAlmacenMysql(process.env)).pool.end()
})
after(async () => almacen?.pool.end())

const AHORA = 1_800_000_000_000
const socio = (id, extra = {}) => ({
  id, colaboracionCentimos: 2000, hecho: true, diaSemanaId: 1, semanaKey: '2026-W47', nombreSocio: 'Ana', notas: '', timestamp: 1, modificado: 10, ...extra,
})

test('MySQL: cuenta, sincronización campo a campo y borrado en cascada', { skip: !hay && 'Sin MySQL de pruebas' }, async () => {
  const { sincronizar } = await import('../sincronizacion.js')
  const u = { id: 'mysql1', nombre: 'Ana', nombreClave: 'ana-mysql', contrasena: 'x', creado: 1, ultimoAcceso: 1 }
  assert.equal(await almacen.crearUsuario(u), true)
  // Una fila "de antes" (sin campos), como las que hay en producción.
  await sincronizar(almacen, u.id, { desde: 0, cambios: { socios: [socio('s1')] } }, AHORA)
  await almacen.pool.query('UPDATE socios SET campos = NULL WHERE usuarioId = ?', [u.id])
  await sincronizar(almacen, u.id, { desde: 1, cambios: { socios: [socio('s1', { nombreSocio: 'Ana María', modificado: 30, campos: { nombreSocio: 30 } })] } }, AHORA)
  const r = await sincronizar(almacen, u.id, { desde: 1, cambios: { socios: [socio('s1', { colaboracionCentimos: 3500, modificado: 20, campos: { colaboracionCentimos: 20 } })] } }, AHORA)
  const s = r.cambios.socios.at(-1)
  assert.equal(s.nombreSocio, 'Ana María')
  assert.equal(s.colaboracionCentimos, 3500)
  assert.equal(s.hecho, true)
  assert.equal(s.campos.nombreSocio, 30)
  assert.equal(s.campos.hecho, 10)
  // Reenviar lo mismo no crea revisiones.
  const otra = await sincronizar(almacen, u.id, { desde: r.rev, cambios: { socios: [socio('s1', { colaboracionCentimos: 3500, modificado: 20, campos: { colaboracionCentimos: 20 } })] } }, AHORA)
  assert.equal(otra.rev, r.rev)

  await almacen.anotar(u.id, 'entrada', 'Chrome', AHORA)
  await almacen.guardarLlave({ id: 'llave-' + 'x'.repeat(300), usuarioId: u.id, clavePublica: 'cHVibGljYQ', alg: -7, contador: 0, transportes: ['internal', 'hybrid'], nombre: 'Chrome en Android', creada: AHORA })
  const llave = await almacen.llave('llave-' + 'x'.repeat(300))
  assert.deepEqual(llave.transportes, ['internal', 'hybrid'])
  assert.equal(Number(llave.alg), -7)
  await almacen.tocarLlave(llave.id, 3, AHORA + 1)
  assert.equal(Number((await almacen.llavesDe(u.id))[0].contador), 3)
  assert.equal(await almacen.borrarLlave('otra-cuenta', llave.id), false)

  await almacen.borrarUsuario(u.id)
  assert.equal(await almacen.llave(llave.id), null)
  const [[{ n }]] = await almacen.pool.query('SELECT COUNT(*) AS n FROM socios WHERE usuarioId = ?', [u.id])
  assert.equal(Number(n), 0)
})

test('MySQL: los errores iguales se cuentan juntos y quedan los 500 últimos', { skip: !hay && 'Sin MySQL de pruebas' }, async () => {
  const e = { firma: 'a'.repeat(32), tipo: 'error', mensaje: 'Falló', fuente: '/js/a.js:1:2', version: '1.0.17', navegador: 'Chrome', momento: AHORA }
  await almacen.anotarError(e)
  await almacen.anotarError({ ...e, momento: AHORA + 5 })
  const [uno] = await almacen.errores(AHORA - 1, 10)
  assert.equal(Number(uno.veces), 2)
  assert.equal(Number(uno.ultimo), AHORA + 5)
  for (let i = 0; i < 505; i++) await almacen.anotarError({ ...e, firma: String(i).padStart(32, '0'), momento: AHORA + 10 + i })
  const [[{ n }]] = await almacen.pool.query('SELECT COUNT(*) AS n FROM errores')
  assert.ok(Number(n) <= 500, `${n} errores guardados`)
})
