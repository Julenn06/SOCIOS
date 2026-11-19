import { ErrorPeticion } from './cuentas.js'

/**
 * Sincronización de la web entre los dispositivos donde se abre (móvil, ordenador…).
 *
 * Cada registro lleva `modificado` (cuándo se cambió por última vez, en el dispositivo) y `borrado`. Si dos
 * dispositivos cambian lo mismo, gana el cambio más reciente; un borrado es un registro más, para que llegue también
 * a los demás. Cada cambio que el servidor acepta recibe una revisión (`rev`), un número que solo crece por cuenta:
 * cada dispositivo recuerda hasta qué revisión tiene y pide lo posterior.
 *
 * Una sola petición hace las dos cosas: sube lo que ha cambiado en el dispositivo y devuelve lo que ha cambiado en
 * el servidor desde `desde`. Se devuelve por tandas de VENTANA revisiones (`mas: true` si quedan).
 */

export const MAX_CAMBIOS = 5000
export const VENTANA = 5000
// Los ajustes que viajan con la cuenta. El bloqueo con huella no: es de cada dispositivo.
const AJUSTES = new Set(['tema', 'ultimaCuota', 'pendientesOcultos'])

const SEMANA = /^\d{4}-W(0[1-9]|[1-4]\d|5[0-3])$/
const ID = /^[A-Za-z0-9_.:-]{1,64}$/

const entero = (min, max) => (v) => (Number.isSafeInteger(v) && v >= min && v <= max ? v : undefined)
const booleano = (v) => (typeof v === 'boolean' ? v : undefined)
const texto = (max) => (v) => (typeof v === 'string' && v.length <= max ? v : undefined)
const semana = (v) => (typeof v === 'string' && SEMANA.test(v) ? v : undefined)

/** Las tablas: su clave y sus campos, con cómo se validan y su valor si llega un borrado sin ellos. */
export const TABLAS = {
  socios: {
    clave: 'id',
    claveValida: (v) => typeof v === 'string' && ID.test(v),
    campos: {
      colaboracionCentimos: [entero(0, 100_000_000), 0],
      hecho: [booleano, false],
      diaSemanaId: [entero(1, 7), 1],
      semanaKey: [semana, '2000-W01'],
      nombreSocio: [texto(200), ''],
      notas: [texto(2000), ''],
      timestamp: [entero(0, Number.MAX_SAFE_INTEGER), 0],
    },
  },
  bonosRegalo: {
    clave: 'semanaKey',
    claveValida: (v) => semana(v) !== undefined,
    campos: {
      activo: [booleano, false],
      esMontoFijo: [booleano, true],
      montoCentimos: [entero(-100_000_000, 100_000_000), 0],
      nota: [texto(500), ''],
    },
  },
  cobros: {
    clave: 'semanaKey',
    claveValida: (v) => semana(v) !== undefined,
    campos: {
      netoCobradoCentimos: [entero(-1_000_000_000, 1_000_000_000), 0],
      nota: [texto(500), ''],
    },
  },
  ajustes: {
    clave: 'clave',
    claveValida: (v) => AJUSTES.has(v),
    campos: {
      valor: [texto(50_000), ''],
    },
  },
}

const malo = (tabla, motivo) => new ErrorPeticion(400, 'datos', `Hay un dato no válido en ${tabla}: ${motivo}`)

/** Comprueba un registro que llega de un dispositivo y lo deja con todos sus campos. */
export function validarRegistro(tabla, r, ahora) {
  const def = TABLAS[tabla]
  if (!r || typeof r !== 'object') throw malo(tabla, 'no es un registro')
  const clave = r[def.clave]
  if (!def.claveValida(clave)) throw malo(tabla, def.clave)
  if (!Number.isSafeInteger(r.modificado) || r.modificado < 1) throw malo(tabla, 'modificado')
  const borrado = r.borrado === true
  // El reloj de un dispositivo adelantado no puede hacer que sus cambios ganen siempre.
  const fila = { [def.clave]: clave, modificado: Math.min(r.modificado, ahora), borrado }
  for (const [campo, [validar, porDefecto]] of Object.entries(def.campos)) {
    if (borrado && r[campo] === undefined) {
      fila[campo] = porDefecto
      continue
    }
    const v = validar(r[campo])
    if (v === undefined) throw malo(tabla, campo)
    fila[campo] = v
  }
  return fila
}

/**
 * Aplica los cambios del dispositivo y devuelve los del servidor.
 * `almacen.transaccion(usuarioId, fn)` da a `fn` una transacción con la cuenta bloqueada: rev(), modificados(),
 * guardar(), fijarRev() y leer() (ver almacen-memoria.js y almacen-mysql.js).
 */
export async function sincronizar(almacen, usuarioId, peticion, ahora = Date.now()) {
  const desde = peticion?.desde ?? 0
  if (!Number.isSafeInteger(desde) || desde < 0) throw new ErrorPeticion(400, 'desde', 'Revisión no válida')
  const entrada = {}
  let total = 0
  for (const tabla of Object.keys(TABLAS)) {
    const lista = peticion?.cambios?.[tabla] ?? []
    if (!Array.isArray(lista)) throw malo(tabla, 'no es una lista')
    total += lista.length
    if (total > MAX_CAMBIOS) throw new ErrorPeticion(413, 'demasiados', `Como mucho ${MAX_CAMBIOS} cambios por envío`)
    // Si llega el mismo registro dos veces, vale el más reciente.
    const porClave = new Map()
    for (const r of lista) {
      const fila = validarRegistro(tabla, r, ahora)
      const clave = fila[TABLAS[tabla].clave]
      if (!porClave.has(clave) || porClave.get(clave).modificado < fila.modificado) porClave.set(clave, fila)
    }
    entrada[tabla] = [...porClave.values()]
  }

  return almacen.transaccion(usuarioId, async (tx) => {
    let rev = await tx.rev()
    if (desde > rev) return { rev, mas: false, reiniciar: true, cambios: vacio() }
    for (const [tabla, filas] of Object.entries(entrada)) {
      if (!filas.length) continue
      const clave = TABLAS[tabla].clave
      const actuales = await tx.modificados(tabla, filas.map((f) => f[clave]))
      // A igualdad gana lo que ya hay: así un dispositivo que reenvía lo mismo no genera revisiones nuevas.
      const ganadoras = filas.filter((f) => !(actuales.get(f[clave]) >= f.modificado))
      for (const f of ganadoras) f.rev = ++rev
      if (ganadoras.length) await tx.guardar(tabla, ganadoras)
    }
    await tx.fijarRev(rev)
    const hasta = Math.min(rev, desde + VENTANA)
    const cambios = {}
    for (const tabla of Object.keys(TABLAS)) cambios[tabla] = (await tx.leer(tabla, desde, hasta)).map((f) => limpiar(tabla, f))
    return { rev: hasta, mas: hasta < rev, cambios }
  })
}

const vacio = () => Object.fromEntries(Object.keys(TABLAS).map((t) => [t, []]))

/** Lo que se devuelve de cada registro: sus campos, modificado y borrado (sin usuario ni revisión). */
function limpiar(tabla, f) {
  const def = TABLAS[tabla]
  const r = { [def.clave]: f[def.clave] }
  for (const [campo, [validar]] of Object.entries(def.campos)) {
    const v = f[campo]
    r[campo] = validar === booleano ? Boolean(v) : typeof v === 'bigint' ? Number(v) : v
  }
  r.modificado = Number(f.modificado)
  r.borrado = Boolean(f.borrado)
  return r
}
