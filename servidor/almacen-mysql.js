import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import mysql from 'mysql2/promise'
import { TABLAS } from './sincronizacion.js'

/**
 * Almacén MySQL (base de datos de Hostinger en 127.0.0.1), como el de Marea. Las migraciones de
 * `migraciones/NNN_*.sql` se aplican en orden al arrancar y quedan en `schema_migrations`.
 */
export async function crearAlmacenMysql(env) {
  const c = datosConexion(env)
  const pool = mysql.createPool({
    ...c,
    connectionLimit: 5,
    multipleStatements: true,
    charset: 'utf8mb4',
  })
  await migrar(pool)

  const uno = async (sql, params) => (await pool.execute(sql, params))[0][0] ?? null
  const ejecutar = async (sql, params) => (await pool.execute(sql, params))[0]
  const usuarioCampos = 'id, nombre, nombreClave, contrasena, rev, creado, ultimoAcceso'

  return {
    pool,
    usuarioPorClave: (nombreClave) => uno(`SELECT ${usuarioCampos} FROM usuarios WHERE nombreClave = ?`, [nombreClave]),
    usuario: (id) => uno(`SELECT ${usuarioCampos} FROM usuarios WHERE id = ?`, [id]),
    async crearUsuario(u) {
      try {
        await ejecutar('INSERT INTO usuarios (id, nombre, nombreClave, contrasena, rev, creado, ultimoAcceso) VALUES (?, ?, ?, ?, 0, ?, ?)', [
          u.id, u.nombre, u.nombreClave, u.contrasena, u.creado, u.ultimoAcceso,
        ])
        return true
      } catch (e) {
        if (e.code === 'ER_DUP_ENTRY') return false
        throw e
      }
    },
    tocarAcceso: (id, t) => ejecutar('UPDATE usuarios SET ultimoAcceso = ? WHERE id = ?', [t, id]),
    /** Falso si otra cuenta ya usa ese nombre (el índice único de nombreClave). */
    async cambiarNombre(id, nombre, nombreClave) {
      try {
        await ejecutar('UPDATE usuarios SET nombre = ?, nombreClave = ? WHERE id = ?', [nombre, nombreClave, id])
        return true
      } catch (e) {
        if (e.code === 'ER_DUP_ENTRY') return false
        throw e
      }
    },
    cambiarContrasena: (id, contrasena) => ejecutar('UPDATE usuarios SET contrasena = ? WHERE id = ?', [contrasena, id]),
    cerrarOtrasSesiones: (usuarioId, hashActual) =>
      ejecutar('DELETE FROM sesiones WHERE usuarioId = ? AND tokenHash <> ?', [usuarioId, hashActual]),
    // Las sesiones y los datos se borran con la cuenta (ON DELETE CASCADE).
    borrarUsuario: (id) => ejecutar('DELETE FROM usuarios WHERE id = ?', [id]),
    crearSesion: (s) =>
      ejecutar('INSERT INTO sesiones (tokenHash, usuarioId, dispositivo, creado, visto, caduca) VALUES (?, ?, ?, ?, ?, ?)', [
        s.tokenHash, s.usuarioId, s.dispositivo, s.creado, s.visto, s.caduca,
      ]),
    sesion: (hash) => uno('SELECT tokenHash, usuarioId, dispositivo, creado, visto, caduca FROM sesiones WHERE tokenHash = ?', [hash]),
    tocarSesion: (hash, visto, caduca) => ejecutar('UPDATE sesiones SET visto = ?, caduca = ? WHERE tokenHash = ?', [visto, caduca, hash]),
    borrarSesion: (hash) => ejecutar('DELETE FROM sesiones WHERE tokenHash = ?', [hash]),
    podar: (t) => ejecutar('DELETE FROM sesiones WHERE caduca < ?', [t]),

    /** Toda la sincronización de una cuenta en una transacción, con su fila de usuarios bloqueada. */
    async transaccion(usuarioId, fn) {
      const con = await pool.getConnection()
      try {
        await con.beginTransaction()
        const [[u]] = await con.query('SELECT rev FROM usuarios WHERE id = ? FOR UPDATE', [usuarioId])
        if (!u) throw new Error('No existe la cuenta')
        const resultado = await fn(transaccion(con, usuarioId, Number(u.rev)))
        await con.commit()
        return resultado
      } catch (e) {
        await con.rollback().catch(() => {})
        throw e
      } finally {
        con.release()
      }
    },
  }
}

/**
 * Los datos de conexión: los DB_* que pone Hostinger al crear la base de datos del sitio (hosting_databases_setup-website)
 * o, si no, MYSQL_*. "localhost" se cambia por 127.0.0.1: en Hostinger puede resolverse a IPv6 y el usuario de la base
 * de datos no tiene permiso por ahí.
 */
export function datosConexion(env) {
  const host = env.DB_HOST || env.MYSQL_HOST || '127.0.0.1'
  return {
    host: host === 'localhost' ? '127.0.0.1' : host,
    port: Number(env.DB_PORT || env.MYSQL_PORT || 3306),
    user: env.DB_USER || env.MYSQL_USER,
    password: env.DB_PASSWORD || env.MYSQL_PASSWORD,
    database: env.DB_NAME || env.MYSQL_DATABASE,
  }
}

const id = (nombre) => `\`${nombre}\``
const columnas = (tabla) => [TABLAS[tabla].clave, ...Object.keys(TABLAS[tabla].campos), 'modificado', 'borrado', 'rev']

function transaccion(con, usuarioId, revInicial) {
  return {
    rev: async () => revInicial,
    async modificados(tabla, claves) {
      const clave = TABLAS[tabla].clave
      const mapa = new Map()
      for (let i = 0; i < claves.length; i += 500) {
        const trozo = claves.slice(i, i + 500)
        const [filas] = await con.query(
          `SELECT ${id(clave)} AS clave, modificado FROM ${id(tabla)} WHERE usuarioId = ? AND ${id(clave)} IN (?)`,
          [usuarioId, trozo],
        )
        for (const f of filas) mapa.set(f.clave, Number(f.modificado))
      }
      return mapa
    },
    async guardar(tabla, filas) {
      const cols = columnas(tabla)
      const actualizar = cols.slice(1).map((c) => `${id(c)} = VALUES(${id(c)})`).join(', ')
      for (let i = 0; i < filas.length; i += 200) {
        const valores = filas.slice(i, i + 200).map((f) => [usuarioId, ...cols.map((c) => (typeof f[c] === 'boolean' ? Number(f[c]) : f[c]))])
        await con.query(
          `INSERT INTO ${id(tabla)} (usuarioId, ${cols.map(id).join(', ')}) VALUES ? ON DUPLICATE KEY UPDATE ${actualizar}`,
          [valores],
        )
      }
    },
    fijarRev: (rev) => con.query('UPDATE usuarios SET rev = ? WHERE id = ?', [rev, usuarioId]),
    async leer(tabla, desde, hasta) {
      const [filas] = await con.query(
        `SELECT ${columnas(tabla).map(id).join(', ')} FROM ${id(tabla)} WHERE usuarioId = ? AND rev > ? AND rev <= ? ORDER BY rev`,
        [usuarioId, desde, hasta],
      )
      return filas
    },
  }
}

async function migrar(pool) {
  await pool.query(
    'CREATE TABLE IF NOT EXISTS schema_migrations (version VARCHAR(64) NOT NULL PRIMARY KEY, applied_at BIGINT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
  )
  const [filas] = await pool.query('SELECT version FROM schema_migrations')
  const hechas = new Set(filas.map((f) => f.version))
  const carpeta = join(dirname(fileURLToPath(import.meta.url)), 'migraciones')
  for (const archivo of readdirSync(carpeta).filter((f) => f.endsWith('.sql')).sort()) {
    if (hechas.has(archivo)) continue
    const con = await pool.getConnection()
    try {
      await con.query(readFileSync(join(carpeta, archivo), 'utf8'))
      await con.query('INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)', [archivo, Date.now()])
      console.log(`Migración aplicada: ${archivo}`)
    } finally {
      con.release()
    }
  }
}
