import { createHash, createPublicKey, randomBytes, timingSafeEqual, verify } from 'node:crypto'
import { ErrorPeticion } from './cuentas.js'

/**
 * Llaves de acceso (passkeys, WebAuthn): entrar con la huella, la cara o el PIN del dispositivo, sin contraseña. La
 * clave privada no sale nunca del dispositivo (o del gestor de contraseñas que la sincroniza); aquí solo se guarda la
 * pública. Una llave solo vale para la web donde se creó (el navegador lo comprueba), así que no sirve en una web falsa.
 *
 * Sin dependencias: el navegador da la clave pública ya en formato SPKI (getPublicKey()), así que no hace falta leer
 * CBOR; de los datos del autenticador solo se leen sus campos fijos.
 */

export const RP_NOMBRE = 'Gestión de Socios'
const RETO_MS = 5 * 60_000
const MAX_RETOS = 10_000
// ES256 (casi todos), EdDSA y RS256 (Windows Hello).
export const ALGORITMOS = [-7, -8, -257]

const b64u = (buf) => Buffer.from(buf).toString('base64url')
const deB64u = (texto, max = 4096) => {
  if (typeof texto !== 'string' || texto.length > max || !/^[A-Za-z0-9_-]*$/.test(texto)) throw malo('codificación')
  return Buffer.from(texto, 'base64url')
}
const malo = (motivo) => new ErrorPeticion(400, 'llave', `La llave de acceso no es válida (${motivo})`)
const sha256 = (datos) => createHash('sha256').update(datos).digest()

// --- retos (un solo uso, 5 minutos) --------------------------------------

const retos = new Map()
setInterval(() => {
  const ahora = Date.now()
  for (const [r, v] of retos) if (v.caduca < ahora) retos.delete(r)
}, 60_000).unref()

/** Un reto nuevo para [proposito] ('crear' con la cuenta, o 'entrar'). */
export function nuevoReto(proposito, usuarioId = null) {
  if (retos.size >= MAX_RETOS) retos.delete(retos.keys().next().value)
  const reto = b64u(randomBytes(32))
  retos.set(reto, { proposito, usuarioId, caduca: Date.now() + RETO_MS })
  return reto
}

/** Gasta el reto: tiene que existir, ser para esto y no haber caducado. */
function gastarReto(reto, proposito, usuarioId = null) {
  const v = retos.get(reto)
  retos.delete(reto)
  if (!v || v.caduca < Date.now() || v.proposito !== proposito || v.usuarioId !== usuarioId) {
    throw new ErrorPeticion(400, 'reto', 'La petición ha caducado. Vuelve a probar')
  }
}

// --- comprobaciones comunes ----------------------------------------------

/**
 * clientDataJSON: tipo, reto y que venga de la web. Devuelve el identificador de la web (rpId), que es su dominio:
 * las llaves de socios.jfl06studio.com solo valen ahí.
 */
function comprobarCliente(clientDataJSON, tipo, origenes) {
  let datos
  try {
    datos = JSON.parse(clientDataJSON.toString('utf8'))
  } catch {
    throw malo('datos del cliente')
  }
  if (datos?.type !== tipo) throw malo('tipo')
  if (!origenes.includes(datos.origin)) throw malo('origen')
  if (datos.crossOrigin === true) throw malo('origen cruzado')
  return { reto: String(datos.challenge ?? ''), rpId: new URL(datos.origin).hostname }
}

/** Los datos del autenticador: para esta web, con presencia (UP) y verificación del usuario (UV: huella, cara, PIN). */
function leerAutenticador(datos, rpId) {
  if (datos.length < 37) throw malo('datos del autenticador')
  if (!timingSafeEqual(datos.subarray(0, 32), sha256(rpId))) throw malo('web')
  const banderas = datos[32]
  if (!(banderas & 0x01)) throw malo('sin presencia')
  if (!(banderas & 0x04)) throw malo('sin verificar al usuario')
  return { banderas, contador: datos.readUInt32BE(33) }
}

// --- crear ----------------------------------------------------------------

/**
 * Comprueba una llave recién creada ({ id, clientDataJSON, authenticatorData, publicKey, alg, transports }, todo en
 * base64url) y devuelve lo que se guarda.
 */
export function comprobarCreacion(cuerpo, usuarioId, origenes) {
  const clientDataJSON = deB64u(cuerpo.clientDataJSON)
  const { reto, rpId } = comprobarCliente(clientDataJSON, 'webauthn.create', origenes)
  gastarReto(reto, 'crear', usuarioId)
  const datos = deB64u(cuerpo.authenticatorData, 8192)
  const { banderas, contador } = leerAutenticador(datos, rpId)
  if (!(banderas & 0x40)) throw malo('sin credencial')
  // La credencial que dice el autenticador tiene que ser la que llega: 16 bytes de AAGUID, longitud, id.
  if (datos.length < 55) throw malo('credencial')
  const largo = datos.readUInt16BE(53)
  const idAutenticador = datos.subarray(55, 55 + largo)
  const id = deB64u(cuerpo.id, 1400)
  if (!id.length || largo > 1023 || !idAutenticador.equals(id)) throw malo('identificador')
  const alg = Number(cuerpo.alg)
  if (!ALGORITMOS.includes(alg)) throw malo('algoritmo')
  const publica = deB64u(cuerpo.publicKey)
  let clave
  try {
    clave = createPublicKey({ key: publica, format: 'der', type: 'spki' })
  } catch {
    throw malo('clave pública')
  }
  const tipoEsperado = { [-7]: 'ec', [-8]: 'ed25519', [-257]: 'rsa' }[alg]
  if (clave.asymmetricKeyType !== tipoEsperado) throw malo('clave pública')
  const transportes = Array.isArray(cuerpo.transports) ? cuerpo.transports.filter((t) => typeof t === 'string' && /^[a-z-]{1,20}$/.test(t)).slice(0, 8) : []
  return { id: b64u(id), clavePublica: b64u(publica), alg, contador, transportes }
}

// --- entrar ---------------------------------------------------------------

/** El id de la llave con la que se quiere entrar (para buscarla antes de comprobar la firma). */
export const idDeLlave = (cuerpo) => b64u(deB64u(cuerpo.id, 1400))

/**
 * Comprueba la firma de una llave guardada ({ id, clientDataJSON, authenticatorData, signature, userHandle }) y
 * devuelve el contador nuevo. [llave]: { usuarioId, clavePublica, alg, contador }.
 */
export function comprobarEntrada(cuerpo, llave, origenes) {
  const clientDataJSON = deB64u(cuerpo.clientDataJSON)
  const { reto, rpId } = comprobarCliente(clientDataJSON, 'webauthn.get', origenes)
  gastarReto(reto, 'entrar')
  const datos = deB64u(cuerpo.authenticatorData, 8192)
  const { contador } = leerAutenticador(datos, rpId)
  if (cuerpo.userHandle != null && cuerpo.userHandle !== '' && deB64u(cuerpo.userHandle, 128).toString('utf8') !== llave.usuarioId) {
    throw malo('cuenta')
  }
  const firma = deB64u(cuerpo.signature, 2048)
  const firmado = Buffer.concat([datos, sha256(clientDataJSON)])
  const clave = createPublicKey({ key: Buffer.from(llave.clavePublica, 'base64url'), format: 'der', type: 'spki' })
  const alg = Number(llave.alg)
  const valida = alg === -8 ? verify(null, firmado, clave, firma) : verify('sha256', firmado, alg === -7 ? { key: clave, dsaEncoding: 'der' } : clave, firma)
  if (!valida) throw new ErrorPeticion(401, 'credenciales', 'No se ha podido comprobar la llave de acceso')
  // Un contador que no avanza delata una llave copiada (las sincronizadas por el gestor de contraseñas dan siempre 0).
  const anterior = Number(llave.contador)
  if ((contador !== 0 || anterior !== 0) && contador <= anterior) {
    throw new ErrorPeticion(401, 'credenciales', 'Esta llave de acceso parece copiada: bórrala en Más → Cuenta y crea otra')
  }
  return contador
}

export const usuarioDeLlave = (usuarioId) => b64u(Buffer.from(usuarioId, 'utf8'))
