import { TABLAS } from './sincronizacion.js'

/**
 * Almacén en memoria con la misma interfaz que el de MySQL: para los tests y para probar sin base de datos.
 * Las transacciones de una misma cuenta van una detrás de otra, como con el bloqueo de fila de MySQL.
 */
export function crearAlmacenMemoria() {
  const usuarios = new Map()
  const sesiones = new Map()
  const datos = new Map() // usuarioId → { tabla → Map(clave → fila) }
  const colas = new Map()
  const actividades = new Map() // usuarioId → [{ momento, tipo, dispositivo }], la más reciente primero
  const llaves = new Map() // id → { id, usuarioId, clavePublica, alg, contador, transportes, nombre, creada, usada }
  const errores = new Map() // firma → { firma, tipo, mensaje, fuente, version, navegador, veces, primero, ultimo }

  const datosDe = (id) => {
    if (!datos.has(id)) datos.set(id, Object.fromEntries(Object.keys(TABLAS).map((t) => [t, new Map()])))
    return datos.get(id)
  }
  const copia = (x) => (x ? { ...x } : null)

  return {
    usuarioPorClave: async (nombreClave) => copia([...usuarios.values()].find((u) => u.nombreClave === nombreClave)),
    usuario: async (id) => copia(usuarios.get(id)),
    async crearUsuario(u) {
      if ([...usuarios.values()].some((x) => x.nombreClave === u.nombreClave)) return false
      usuarios.set(u.id, { ...u, rev: 0 })
      return true
    },
    async cambiarNombre(id, nombre, nombreClave) {
      if ([...usuarios.values()].some((x) => x.nombreClave === nombreClave && x.id !== id)) return false
      Object.assign(usuarios.get(id), { nombre, nombreClave })
      return true
    },
    async cambiarContrasena(id, contrasena) {
      usuarios.get(id).contrasena = contrasena
    },
    async cerrarOtrasSesiones(usuarioId, hashActual) {
      for (const [h, s] of sesiones) if (s.usuarioId === usuarioId && h !== hashActual) sesiones.delete(h)
    },
    async tocarAcceso(id, t) {
      if (usuarios.has(id)) usuarios.get(id).ultimoAcceso = t
    },
    async borrarUsuario(id) {
      usuarios.delete(id)
      datos.delete(id)
      actividades.delete(id)
      for (const [k, l] of llaves) if (l.usuarioId === id) llaves.delete(k)
      for (const [h, s] of sesiones) if (s.usuarioId === id) sesiones.delete(h)
    },
    crearSesion: async (s) => void sesiones.set(s.tokenHash, { ...s }),
    sesion: async (hash) => copia(sesiones.get(hash)),
    sesionesDe: async (usuarioId) =>
      [...sesiones.values()].filter((s) => s.usuarioId === usuarioId).sort((a, b) => b.visto - a.visto).map(copia),
    async anotar(usuarioId, tipo, dispositivo, momento) {
      if (!actividades.has(usuarioId)) actividades.set(usuarioId, [])
      const lista = actividades.get(usuarioId)
      lista.unshift({ momento, tipo, dispositivo })
      lista.length = Math.min(lista.length, 100)
    },
    actividad: async (usuarioId, limite) => (actividades.get(usuarioId) ?? []).slice(0, limite).map(copia),
    guardarLlave: async (l) => void llaves.set(l.id, { ...l, transportes: [...l.transportes] }),
    llave: async (id) => copia(llaves.get(id)),
    llavesDe: async (usuarioId) => [...llaves.values()].filter((l) => l.usuarioId === usuarioId).sort((a, b) => b.creada - a.creada).map(copia),
    async tocarLlave(id, contador, usada) {
      const l = llaves.get(id)
      if (l) Object.assign(l, { contador, usada })
    },
    /** Falso si esa llave no es de la cuenta. */
    async borrarLlave(usuarioId, id) {
      if (llaves.get(id)?.usuarioId !== usuarioId) return false
      llaves.delete(id)
      return true
    },
    async anotarError(e) {
      const ya = errores.get(e.firma)
      if (ya) Object.assign(ya, { veces: ya.veces + 1, ultimo: e.momento })
      else errores.set(e.firma, { ...e, veces: 1, primero: e.momento, ultimo: e.momento })
      if (errores.size > 500) {
        const viejo = [...errores.values()].sort((a, b) => a.ultimo - b.ultimo)[0]
        errores.delete(viejo.firma)
      }
    },
    errores: async (desde, limite) =>
      [...errores.values()].filter((e) => e.ultimo > desde).sort((a, b) => b.ultimo - a.ultimo).slice(0, limite).map(copia),
    async tocarSesion(hash, visto, caduca) {
      const s = sesiones.get(hash)
      if (s) Object.assign(s, { visto, caduca })
    },
    borrarSesion: async (hash) => void sesiones.delete(hash),
    async podar(t) {
      for (const [h, s] of sesiones) if (s.caduca < t) sesiones.delete(h)
    },
    transaccion(usuarioId, fn) {
      const anterior = colas.get(usuarioId) ?? Promise.resolve()
      const actual = anterior.then(() => {
        const u = usuarios.get(usuarioId)
        if (!u) throw new Error('No existe la cuenta')
        const tablas = datosDe(usuarioId)
        return fn({
          rev: async () => u.rev,
          actuales: async (tabla, claves) =>
            new Map(claves.filter((c) => tablas[tabla].has(c)).map((c) => [c, structuredClone(tablas[tabla].get(c))])),
          async guardar(tabla, filas) {
            for (const f of filas) tablas[tabla].set(f[TABLAS[tabla].clave], structuredClone(f))
          },
          fijarRev: async (rev) => void (u.rev = rev),
          leer: async (tabla, desde, hasta) =>
            [...tablas[tabla].values()].filter((f) => f.rev > desde && f.rev <= hasta).sort((a, b) => a.rev - b.rev),
        })
      })
      colas.set(usuarioId, actual.catch(() => {}))
      return actual
    },
  }
}
