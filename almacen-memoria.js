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
    async tocarAcceso(id, t) {
      if (usuarios.has(id)) usuarios.get(id).ultimoAcceso = t
    },
    async borrarUsuario(id) {
      usuarios.delete(id)
      datos.delete(id)
      for (const [h, s] of sesiones) if (s.usuarioId === id) sesiones.delete(h)
    },
    crearSesion: async (s) => void sesiones.set(s.tokenHash, { ...s }),
    sesion: async (hash) => copia(sesiones.get(hash)),
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
          modificados: async (tabla, claves) =>
            new Map(claves.filter((c) => tablas[tabla].has(c)).map((c) => [c, tablas[tabla].get(c).modificado])),
          async guardar(tabla, filas) {
            for (const f of filas) tablas[tabla].set(f[TABLAS[tabla].clave], { ...f })
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
