import { test } from 'node:test'
import assert from 'node:assert/strict'
import { crearAlmacenMemoria } from '../almacen-memoria.js'
import { sincronizar, VENTANA } from '../sincronizacion.js'

const AHORA = 1_800_000_000_000
const socio = (id, extra = {}) => ({
  id, colaboracionCentimos: 2000, hecho: true, diaSemanaId: 1, semanaKey: '2026-W47', nombreSocio: 'Ana', notas: '', timestamp: 1, modificado: 10, ...extra,
})

async function cuenta() {
  const almacen = crearAlmacenMemoria()
  await almacen.crearUsuario({ id: 'u1', nombre: 'Ana', nombreClave: 'ana', contrasena: 'x', creado: 1, ultimoAcceso: 1 })
  return almacen
}

test('lo que sube un dispositivo le llega al otro, y cada uno solo pide lo que le falta', async () => {
  const a = await cuenta()
  const r1 = await sincronizar(a, 'u1', { desde: 0, cambios: { socios: [socio('s1'), socio('s2')], cobros: [{ semanaKey: '2026-W47', netoCobradoCentimos: 21986, nota: '', modificado: 10 }] } }, AHORA)
  assert.equal(r1.rev, 3)
  assert.equal(r1.mas, false)
  // El otro dispositivo, desde cero, lo recibe todo.
  const r2 = await sincronizar(a, 'u1', { desde: 0, cambios: {} }, AHORA)
  assert.deepEqual(r2.cambios.socios.map((s) => s.id).sort(), ['s1', 's2'])
  assert.equal(r2.cambios.cobros[0].netoCobradoCentimos, 21986)
  assert.equal(r2.cambios.socios[0].hecho, true)
  assert.equal(r2.cambios.socios[0].borrado, false)
  // Y después, nada nuevo.
  const r3 = await sincronizar(a, 'u1', { desde: r2.rev, cambios: {} }, AHORA)
  assert.equal(r3.rev, 3)
  assert.equal(r3.cambios.socios.length, 0)
})

test('gana el cambio más reciente; reenviar lo mismo no crea revisiones', async () => {
  const a = await cuenta()
  await sincronizar(a, 'u1', { desde: 0, cambios: { socios: [socio('s1', { nombreSocio: 'Nuevo', modificado: 20 })] } }, AHORA)
  // Llega tarde un cambio más antiguo: no pisa al nuevo.
  const viejo = await sincronizar(a, 'u1', { desde: 1, cambios: { socios: [socio('s1', { nombreSocio: 'Viejo', modificado: 15 })] } }, AHORA)
  assert.equal(viejo.rev, 1)
  const todo = await sincronizar(a, 'u1', { desde: 0, cambios: { socios: [socio('s1', { nombreSocio: 'Nuevo', modificado: 20 })] } }, AHORA)
  assert.equal(todo.rev, 1)
  assert.equal(todo.cambios.socios[0].nombreSocio, 'Nuevo')
})

test('un borrado llega a los demás dispositivos como un registro más', async () => {
  const a = await cuenta()
  await sincronizar(a, 'u1', { desde: 0, cambios: { socios: [socio('s1')] } }, AHORA)
  await sincronizar(a, 'u1', { desde: 1, cambios: { socios: [{ id: 's1', borrado: true, modificado: 30 }] } }, AHORA)
  const r = await sincronizar(a, 'u1', { desde: 1, cambios: {} }, AHORA)
  assert.equal(r.cambios.socios.length, 1)
  assert.equal(r.cambios.socios[0].borrado, true)
  // Un cambio anterior al borrado no lo resucita.
  const tarde = await sincronizar(a, 'u1', { desde: 2, cambios: { socios: [socio('s1', { modificado: 25 })] } }, AHORA)
  assert.equal(tarde.cambios.socios.length, 0)
})

test('un reloj adelantado no gana para siempre', async () => {
  const a = await cuenta()
  await sincronizar(a, 'u1', { desde: 0, cambios: { socios: [socio('s1', { modificado: AHORA + 1e9 })] } }, AHORA)
  const r = await sincronizar(a, 'u1', { desde: 0, cambios: { socios: [socio('s1', { nombreSocio: 'Después', modificado: AHORA + 5 })] } }, AHORA + 10)
  assert.equal(r.cambios.socios[0].nombreSocio, 'Después')
})

test('ajustes de la cuenta; el bloqueo y lo desconocido no se aceptan', async () => {
  const a = await cuenta()
  const r = await sincronizar(a, 'u1', { desde: 0, cambios: { ajustes: [{ clave: 'tema', valor: 'OSCURO', modificado: 5 }] } }, AHORA)
  assert.equal(r.cambios.ajustes[0].valor, 'OSCURO')
  await assert.rejects(sincronizar(a, 'u1', { desde: 0, cambios: { ajustes: [{ clave: 'bloqueo', valor: 'true', modificado: 5 }] } }, AHORA), /ajustes: clave/)
})

test('los datos imposibles se rechazan y no se guarda nada de ese envío', async () => {
  const a = await cuenta()
  for (const malo of [
    socio('s1', { colaboracionCentimos: -5 }),
    socio('s1', { colaboracionCentimos: 1.5 }),
    socio('s1', { semanaKey: '2026-W54' }),
    socio('s1', { diaSemanaId: 0 }),
    socio('id con espacios'),
    socio('s1', { modificado: 0 }),
    socio('s1', { notas: 'x'.repeat(2001) }),
  ]) {
    await assert.rejects(sincronizar(a, 'u1', { desde: 0, cambios: { socios: [socio('bien'), malo] } }, AHORA), /dato no válido/)
  }
  const r = await sincronizar(a, 'u1', { desde: 0, cambios: {} }, AHORA)
  assert.equal(r.cambios.socios.length, 0)
  await assert.rejects(sincronizar(a, 'u1', { desde: -1 }, AHORA), /Revisión/)
})

test('muchos cambios se devuelven por tandas', async () => {
  const a = await cuenta()
  const muchos = Array.from({ length: VENTANA }, (_, i) => socio(`s${i}`))
  await sincronizar(a, 'u1', { desde: 0, cambios: { socios: muchos } }, AHORA)
  await sincronizar(a, 'u1', { desde: VENTANA, cambios: { socios: [socio('ultimo')] } }, AHORA)
  const p1 = await sincronizar(a, 'u1', { desde: 0 }, AHORA)
  assert.equal(p1.cambios.socios.length, VENTANA)
  assert.equal(p1.mas, true)
  const p2 = await sincronizar(a, 'u1', { desde: p1.rev }, AHORA)
  assert.deepEqual(p2.cambios.socios.map((s) => s.id), ['ultimo'])
  assert.equal(p2.mas, false)
  await assert.rejects(sincronizar(a, 'u1', { cambios: { socios: [...muchos, socio('uno-mas')] } }, AHORA), /Como mucho/)
})

test('si el dispositivo va por delante de la cuenta (cuenta nueva), empieza de cero', async () => {
  const a = await cuenta()
  const r = await sincronizar(a, 'u1', { desde: 50 }, AHORA)
  assert.equal(r.reiniciar, true)
})

test('dos dispositivos cambian campos distintos del mismo socio: se conservan los dos cambios', async () => {
  const a = await cuenta()
  await sincronizar(a, 'u1', { desde: 0, cambios: { socios: [socio('s1')] } }, AHORA)
  // El móvil cambia el nombre (más tarde) y el ordenador, sin conexión, lo cobrado (antes): antes ganaba el registro
  // entero del móvil y se perdía lo cobrado.
  await sincronizar(a, 'u1', { desde: 1, cambios: { socios: [socio('s1', { nombreSocio: 'Ana María', modificado: 30, campos: { nombreSocio: 30 } })] } }, AHORA)
  const r = await sincronizar(a, 'u1', { desde: 2, cambios: { socios: [socio('s1', { colaboracionCentimos: 3500, modificado: 20, campos: { colaboracionCentimos: 20 } })] } }, AHORA)
  assert.equal(r.cambios.socios.length, 1)
  const s = r.cambios.socios[0]
  assert.equal(s.nombreSocio, 'Ana María')
  assert.equal(s.colaboracionCentimos, 3500)
  assert.equal(s.modificado, 30)
  assert.deepEqual({ n: s.campos.nombreSocio, c: s.campos.colaboracionCentimos, h: s.campos.hecho }, { n: 30, c: 20, h: 10 })
})

test('del mismo campo gana el cambio más reciente, aunque llegue antes', async () => {
  const a = await cuenta()
  await sincronizar(a, 'u1', { desde: 0, cambios: { socios: [socio('s1')] } }, AHORA)
  await sincronizar(a, 'u1', { desde: 1, cambios: { socios: [socio('s1', { notas: 'nueva', modificado: 40, campos: { notas: 40 } })] } }, AHORA)
  const r = await sincronizar(a, 'u1', { desde: 2, cambios: { socios: [socio('s1', { notas: 'vieja', modificado: 35, campos: { notas: 35 } })] } }, AHORA)
  assert.equal(r.rev, 2)
  assert.equal(r.cambios.socios.length, 0)
})

test('existir es un campo más: borrar y recuperar se juntan con lo demás', async () => {
  const a = await cuenta()
  await sincronizar(a, 'u1', { desde: 0, cambios: { socios: [socio('s1')] } }, AHORA)
  // Se borra en un dispositivo; en otro se había cambiado el nombre antes: sigue borrado y no hay revisión nueva.
  await sincronizar(a, 'u1', { desde: 1, cambios: { socios: [{ id: 's1', borrado: true, modificado: 50, campos: { borrado: 50 } }] } }, AHORA)
  const antes = await sincronizar(a, 'u1', { desde: 2, cambios: { socios: [socio('s1', { nombreSocio: 'Otro', modificado: 45, campos: { nombreSocio: 45, borrado: 45 } })] } }, AHORA)
  assert.equal(antes.rev, 2)
  // Deshacer el borrado (después) lo recupera con todos sus campos.
  const despues = await sincronizar(a, 'u1', { desde: 2, cambios: { socios: [socio('s1', { nombreSocio: 'Ana', modificado: 60, campos: Object.fromEntries(['colaboracionCentimos', 'hecho', 'diaSemanaId', 'semanaKey', 'nombreSocio', 'notas', 'timestamp', 'borrado'].map((c) => [c, 60])) })] } }, AHORA)
  assert.equal(despues.cambios.socios[0].borrado, false)
  assert.equal(despues.cambios.socios[0].nombreSocio, 'Ana')
})

test('las horas por campo se validan y no pasan de ahora', async () => {
  const a = await cuenta()
  await assert.rejects(sincronizar(a, 'u1', { desde: 0, cambios: { socios: [socio('s1', { campos: { notas: -1 } })] } }, AHORA), /campos\.notas/)
  await assert.rejects(sincronizar(a, 'u1', { desde: 0, cambios: { socios: [socio('s1', { campos: [1] })] } }, AHORA), /campos/)
  const r = await sincronizar(a, 'u1', { desde: 0, cambios: { socios: [socio('s1', { campos: { notas: AHORA + 1e9, inventado: 5 } })] } }, AHORA)
  assert.equal(r.cambios.socios[0].campos.notas, AHORA)
  assert.equal(r.cambios.socios[0].campos.inventado, undefined)
})
