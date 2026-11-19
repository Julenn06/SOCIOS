import { createHash, randomBytes, scrypt as scryptCb, timingSafeEqual } from 'node:crypto'
import { promisify } from 'node:util'

const scrypt = promisify(scryptCb)

/**
 * Cuentas: un nombre ("¿Cómo te llamas?") y una contraseña.
 *
 * El nombre se compara por su clave: sin tildes, en minúsculas y con los espacios normalizados, así que "Julen",
 * "JULEN" y " julén " son la misma cuenta. Se guarda además tal como se escribió, para mostrarlo.
 * Las contraseñas se guardan con scrypt y sal; los tokens de sesión, solo por su SHA-256.
 */

export const NOMBRE_MIN = 2
export const NOMBRE_MAX = 40
export const CONTRASENA_MIN = 8
export const CONTRASENA_MAX = 200
export const SESION_MS = 180 * 24 * 3600_000

export class ErrorPeticion extends Error {
  constructor(estado, codigo, mensaje) {
    super(mensaje)
    this.estado = estado
    this.codigo = codigo
  }
}

/** El nombre tal como se mostrará: sin espacios de más. */
export const nombreVisible = (nombre) => String(nombre ?? '').replace(/\s+/g, ' ').trim()

/** La clave con la que se busca la cuenta: da igual mayúsculas, minúsculas, tildes o espacios. */
export const claveNombre = (nombre) =>
  nombreVisible(nombre).normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase()

export function validarNombre(nombre) {
  const visible = nombreVisible(nombre)
  const largo = [...visible].length
  if (largo < NOMBRE_MIN || largo > NOMBRE_MAX || !/[\p{L}\p{N}]/u.test(visible)) {
    throw new ErrorPeticion(400, 'nombre', `Escribe tu nombre (de ${NOMBRE_MIN} a ${NOMBRE_MAX} letras)`)
  }
  return visible
}

export function validarContrasena(contrasena) {
  if (typeof contrasena !== 'string' || contrasena.length < CONTRASENA_MIN || contrasena.length > CONTRASENA_MAX) {
    throw new ErrorPeticion(400, 'contrasena', `La contraseña tiene que tener al menos ${CONTRASENA_MIN} caracteres`)
  }
  return contrasena
}

const N = 16384
const R = 8
const P = 1
const LARGO = 32

export async function cifrarContrasena(contrasena) {
  const sal = randomBytes(16)
  const hash = await scrypt(contrasena.normalize('NFC'), sal, LARGO, { N, r: R, p: P })
  return `scrypt$${N}$${R}$${P}$${sal.toString('base64url')}$${hash.toString('base64url')}`
}

// Con una cuenta que no existe se hace la misma cuenta, para que el tiempo de respuesta no lo delate.
const FALSO = `scrypt$${N}$${R}$${P}$${randomBytes(16).toString('base64url')}$${randomBytes(LARGO).toString('base64url')}`

export async function comprobarContrasena(contrasena, guardada = FALSO) {
  const [tipo, n, r, p, sal, hash] = String(guardada).split('$')
  if (tipo !== 'scrypt' || !sal || !hash) return false
  const esperado = Buffer.from(hash, 'base64url')
  const calculado = await scrypt(String(contrasena).normalize('NFC'), Buffer.from(sal, 'base64url'), esperado.length, {
    N: Number(n),
    r: Number(r),
    p: Number(p),
  })
  return timingSafeEqual(calculado, esperado) && guardada !== FALSO
}

/** Compara la contraseña de registro sin delatar por el tiempo en qué carácter falla. */
export function esClaveRegistro(escrita, correcta) {
  if (!correcta || typeof escrita !== 'string') return false
  const a = createHash('sha256').update(escrita.normalize('NFC')).digest()
  const b = createHash('sha256').update(String(correcta).normalize('NFC')).digest()
  return timingSafeEqual(a, b)
}

export const nuevoToken = () => randomBytes(32).toString('base64url')
export const nuevoId = () => randomBytes(12).toString('base64url')
export const hashToken = (token) => createHash('sha256').update(String(token)).digest('hex')
export const esToken = (t) => typeof t === 'string' && /^[A-Za-z0-9_-]{40,64}$/.test(t)
