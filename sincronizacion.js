import { ErrorPeticion } from './cuentas.js'

/**
 * Sincronización de la web entre los dispositivos donde se abre (móvil, ordenador…).
 *
 * Cada registro lleva `modificado` (cuándo se cambió por última vez, en el dispositivo), `borrado` y `campos`: cuándo
 * se cambió cada campo ({ nombreSocio: hora, borrado: hora… }). Si dos dispositivos cambian el mismo registro, se
 * juntan campo a campo: de cada campo gana el cambio más reciente, así que cambiar el nombre en el móvil y lo cobrado
 * en el ordenador conserva las dos cosas. Que exista o no (`borrado`) es un campo más: editar después de borrar lo
 * recupera y borrar después de editar lo borra. Un borrado es un registro más, para que llegue también a los demás.
 * Lo que manda un dispositivo sin `campos` (versiones de la web anteriores a la 1.0.17) cuenta como cambiado entero en
 * `modificado`: gana el registro más reciente, como antes.
 *
 * Cada cambio que el servidor acepta recibe una revisión (`rev`), un número que solo crece por cuenta:
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
  const modificado = Math.min(r.modificado, ahora)
  const fila = { [def.clave]: clave, modificado, borrado }
  for (const [campo, [validar, porDefecto]] of Object.entries(def.campos)) {
    if (borrado && r[campo] === undefined) {
      fila[campo] = porDefecto
      continue
    }
    const v = validar(r[campo])
    if (v === undefined) throw malo(tabla, campo)
    fila[campo] = v
  }
  fila.campos = r.campos === undefined ? todosEn(def, modificado) : validarCampos(tabla, r.campos, ahora)
  return fila
}

/** Todos los campos del registro (y su existencia) cambiados a la vez, en [t]. */
const todosEn = (def, t) => Object.fromEntries([...Object.keys(def.campos), 'borrado'].map((c) => [c, t]))

/** Las horas de cada campo que manda un dispositivo: solo las de campos que existen, como mucho [ahora]. */
function validarCampos(tabla, campos, ahora) {
  const def = TABLAS[tabla]
  if (!campos || typeof campos !== 'object' || Array.isArray(campos)) throw malo(tabla, 'campos')
  const r = {}
  for (const [campo, t] of Object.entries(campos)) {
    if (campo !== 'borrado' && !Object.hasOwn(def.campos, campo)) continue
    if (!Number.isSafeInteger(t) || t < 0) throw malo(tabla, `campos.${campo}`)
    r[campo] = Math.min(t, ahora)
  }
  return r
}

/** Las horas de cada campo de una fila guardada (las de antes de la 1.0.17 no las tienen: todo en `modificado`). */
function camposDe(def, fila) {
  let c = fila.campos
  if (typeof c === 'string') {
    try {
      c = JSON.parse(c)
    } catch {
      c = null
    }
  }
  return c && typeof c === 'object' ? c : todosEn(def, Number(fila.modificado))
}

/**
 * Junta lo que llega con lo guardado, campo a campo: gana la hora más reciente (a igualdad, lo guardado). Devuelve la
 * fila nueva o null si no cambia nada. Los campos de un registro borrado no se ven: mientras siga borrado, cambiarlos
 * no cuenta (al recuperarlo, el dispositivo manda todos).
 */
export function fusionar(tabla, actual, entrante) {
  const def = TABLAS[tabla]
  if (!actual) {
    // Lo que no trae hora cuenta como lo más antiguo posible (1, como lo que se sube al entrar con datos guardados).
    const campos = { ...todosEn(def, 1), ...entrante.campos }
    return { ...entrante, campos, modificado: Math.max(entrante.modificado, ...Object.values(campos)) }
  }
  const tActual = camposDe(def, actual)
  const fila = { [def.clave]: entrante[def.clave], borrado: Boolean(actual.borrado) }
  const campos = { ...tActual }
  let cambia = false
  for (const campo of [...Object.keys(def.campos), 'borrado']) {
    const t = entrante.campos[campo]
    if (t !== undefined && t > (tActual[campo] ?? 0)) {
      // Aunque el valor sea el mismo, la hora nueva se guarda: un cambio más antiguo que llegue después no lo pisa.
      fila[campo] = entrante[campo]
      campos[campo] = t
      cambia = true
    } else {
      fila[campo] = campo === 'borrado' ? Boolean(actual.borrado) : normal(def, campo, actual[campo])
    }
  }
  // Sigue borrado: lo de dentro no se ve, solo cuenta si deja de estarlo.
  if (fila.borrado && actual.borrado) cambia = false
  if (!cambia) return null
  fila.campos = campos
  fila.modificado = Math.max(...Object.values(campos))
  return fila
}

const normal = (def, campo, v) => (def.campos[campo][0] === booleano ? Boolean(v) : typeof v === 'bigint' ? Number(v) : v)

/**
 * Aplica los cambios del dispositivo y devuelve los del servidor.
 * `almacen.transaccion(usuarioId, fn)` da a `fn` una transacción con la cuenta bloqueada: rev(), actuales(),
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
      const actuales = await tx.actuales(tabla, filas.map((f) => f[clave]))
      // A igualdad gana lo que ya hay: así un dispositivo que reenvía lo mismo no genera revisiones nuevas.
      const ganadoras = filas.map((f) => fusionar(tabla, actuales.get(f[clave]), f)).filter(Boolean)
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

/** Lo que se devuelve de cada registro: sus campos, modificado, borrado y la hora de cada campo (sin usuario ni revisión). */
function limpiar(tabla, f) {
  const def = TABLAS[tabla]
  const r = { [def.clave]: f[def.clave] }
  for (const campo of Object.keys(def.campos)) r[campo] = normal(def, campo, f[campo])
  r.modificado = Number(f.modificado)
  r.borrado = Boolean(f.borrado)
  r.campos = camposDe(def, f)
  return r
}
