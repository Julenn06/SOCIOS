// data/backup: copias JSON y CSV con el mismo formato que la app Android (versión 5), para pasar los datos de una a
// otra en los dos sentidos. Al leer se validan registro a registro: los incompletos se descartan y se cuentan.
import { IMPORTE_MAXIMO } from './calculos.js';
import { diaPorId, esSemanaKeyValida } from './fechas.js';
import { parseImporte } from './formato.js';
import { errorParaUsuario, nuevoId } from './estado.js';

export const VERSION_ACTUAL = 5;
const BOM = '﻿';
const TAMANO_MAXIMO = 20 * 1024 * 1024;
const MENSAJE_JSON_NO_VALIDO = 'el archivo no es una copia de seguridad JSON válida';

// --- Escribir ---

export function aDto(datos, timestamp = Date.now()) {
  return {
    version: VERSION_ACTUAL,
    timestamp,
    socios: datos.socios.map((s) => ({
      id: s.id,
      colaboracion: s.colaboracion,
      hecho: s.hecho,
      diaSemanaId: s.dia,
      semanaKey: s.semanaKey,
      nombreSocio: s.nombreSocio,
      notas: s.notas,
      timestamp: s.timestamp
    })),
    bonosRegalo: datos.bonosRegalo.map((b) => ({ semanaKey: b.semanaKey, activo: b.activo, esMontoFijo: b.esMontoFijo, monto: b.monto, nota: b.nota })),
    cobros: datos.cobros.map((c) => ({ semanaKey: c.semanaKey, netoCobrado: c.netoCobrado, nota: c.nota }))
  };
}

/** Un Double de Kotlin se escribe siempre con decimal ("20.0"): así la app Android lee igual la copia. */
function numeroKotlin(n) {
  return Number.isInteger(n) ? `${n}.0` : String(n);
}

export function escribirJson(datos, timestamp = Date.now()) {
  const dto = aDto(datos, timestamp);
  // JSON con sangría de 4, como kotlinx.serialization con prettyPrint.
  const marca = `~num${Math.random().toString(36).slice(2)}~`;
  const texto = JSON.stringify(dto, (k, v) => (['colaboracion', 'monto', 'netoCobrado'].includes(k) && typeof v === 'number' ? `${marca}${numeroKotlin(v)}` : v), 4);
  return texto.replace(new RegExp(`"${marca}([^"]*)"`, 'g'), '$1');
}

function escapar(valor) {
  const texto = String(valor);
  const comillas = /[,"\n\r]/.test(texto) || texto !== texto.trim();
  return comillas ? `"${texto.replace(/"/g, '""')}"` : texto;
}

const fila = (...valores) => valores.map(escapar).join(',') + '\n';

export function escribirCsv(datos) {
  let out = BOM + `# VERSION ${VERSION_ACTUAL}\n# SOCIOS\nID,Colaboracion,Hecho,DiaSemanaId,SemanaKey,NombreSocio,Notas,Timestamp\n`;
  for (const s of datos.socios) out += fila(s.id, numeroKotlin(s.colaboracion), s.hecho, s.dia, s.semanaKey, s.nombreSocio, s.notas, s.timestamp);
  if (datos.bonosRegalo.length) {
    out += '# BONOS_REGALO\nSemanaKey,Activo,EsMontoFijo,Monto,Nota\n';
    for (const b of datos.bonosRegalo) out += fila(b.semanaKey, b.activo, b.esMontoFijo, numeroKotlin(b.monto), b.nota);
  }
  if (datos.cobros.length) {
    out += '# COBROS\nSemanaKey,NetoCobrado,Nota\n';
    for (const c of datos.cobros) out += fila(c.semanaKey, numeroKotlin(c.netoCobrado), c.nota);
  }
  return out;
}

// --- Leer ---

/** El texto de un archivo, sin BOM; uno enorme (un vídeo elegido por error) no se lee. */
export async function leerTexto(archivo) {
  if (archivo.size > TAMANO_MAXIMO) throw errorParaUsuario('el archivo es demasiado grande para ser una copia de seguridad');
  const texto = await archivo.text();
  return texto.startsWith(BOM) ? texto.slice(1) : texto;
}

const claveValida = (t) => {
  const k = typeof t === 'string' ? t.trim() : null;
  return k && esSemanaKeyValida(k) ? k : null;
};
const numeroDe = (v) => (typeof v === 'number' ? v : typeof v === 'string' ? parseImporte(v) : null);
const booleanoDe = (v) => (typeof v === 'boolean' ? v : v === 'true' ? true : v === 'false' ? false : null);
const textoDe = (v) => (v == null ? '' : String(v));

function aSocio(d, ahora) {
  const colaboracion = numeroDe(d.colaboracion);
  if (colaboracion == null || !Number.isFinite(colaboracion) || colaboracion <= 0 || colaboracion > IMPORTE_MAXIMO) return null;
  const diaId = typeof d.diaSemanaId === 'number' ? d.diaSemanaId : parseInt(d.diaSemanaId, 10);
  if (!diaPorId(diaId)) return null;
  const semanaKey = claveValida(d.semanaKey);
  if (!semanaKey) return null;
  const id = typeof d.id === 'string' && d.id.trim() ? d.id.trim() : nuevoId();
  const ts = typeof d.timestamp === 'number' ? d.timestamp : d.timestamp != null && /^\d+$/.test(String(d.timestamp)) ? Number(d.timestamp) : null;
  return {
    id,
    colaboracion,
    hecho: booleanoDe(d.hecho) ?? true,
    dia: diaId,
    semanaKey,
    nombreSocio: textoDe(d.nombreSocio),
    notas: textoDe(d.notas),
    timestamp: ts ?? ahora
  };
}

function aBono(d) {
  const semanaKey = claveValida(d.semanaKey);
  const monto = numeroDe(d.monto);
  if (!semanaKey || monto == null || !Number.isFinite(monto) || monto < 0 || monto > IMPORTE_MAXIMO) return null;
  return { semanaKey, activo: booleanoDe(d.activo) ?? true, esMontoFijo: booleanoDe(d.esMontoFijo) ?? true, monto, nota: textoDe(d.nota) };
}

function aCobro(d) {
  const semanaKey = claveValida(d.semanaKey);
  const neto = numeroDe(d.netoCobrado);
  // Puede ser negativo: así quedaban las semanas de la 1.9.0 con más devoluciones que ganancias.
  if (!semanaKey || neto == null || !Number.isFinite(neto) || Math.abs(neto) > IMPORTE_MAXIMO) return null;
  return { semanaKey, netoCobrado: neto, nota: textoDe(d.nota) };
}

/** Convierte lo leído en datos válidos; los registros nulos o incompletos se descartan y se cuentan. */
export function desdeDto(dto, ahora = Date.now()) {
  let descartados = Array.isArray(dto.devoluciones) ? dto.devoluciones.length : 0;
  const validos = (lista, convertir) => {
    const out = [];
    for (const r of Array.isArray(lista) ? lista : []) {
      const v = r && typeof r === 'object' ? convertir(r) : null;
      if (v) out.push(v);
      else descartados++;
    }
    return out;
  };
  const socios = validos(dto.socios, (d) => aSocio(d, ahora));
  const bonos = validos(dto.bonosRegalo, aBono);
  const cobros = validos(dto.cobros, aCobro);
  // Si se repite un registro, prevalece el último.
  const ultimos = (lista, clave) => [...new Map(lista.map((x) => [x[clave], x])).values()];
  return {
    datos: { socios: ultimos(socios, 'id'), bonosRegalo: ultimos(bonos, 'semanaKey'), cobros: ultimos(cobros, 'semanaKey') },
    descartados,
    irpfIgnorados: Array.isArray(dto.irpfVigencias) ? dto.irpfVigencias.length : 0,
    fechaCopia: typeof dto.timestamp === 'number' ? dto.timestamp : null
  };
}

export function leerJson(texto) {
  let raiz;
  try {
    raiz = JSON.parse(texto);
  } catch {
    throw errorParaUsuario(MENSAJE_JSON_NO_VALIDO);
  }
  if (Array.isArray(raiz)) return desdeDto({ socios: raiz });
  if (raiz && typeof raiz === 'object') return desdeDto(raiz);
  throw errorParaUsuario(MENSAJE_JSON_NO_VALIDO);
}

/** RFC 4180: comillas dobles, "" como comilla escapada y saltos de línea dentro de un campo entre comillas. */
export function parsearRegistros(texto) {
  const registros = [];
  let campos = [];
  let campo = '';
  let entre = false;
  let i = 0;
  const cerrarCampo = () => {
    campos.push(campo);
    campo = '';
  };
  const cerrarRegistro = () => {
    cerrarCampo();
    registros.push(campos);
    campos = [];
  };
  while (i < texto.length) {
    const c = texto[i];
    if (entre && c === '"' && texto[i + 1] === '"') {
      campo += '"';
      i++;
    } else if (c === '"') entre = !entre;
    else if (!entre && c === ',') cerrarCampo();
    else if (!entre && (c === '\n' || c === '\r')) {
      if (c === '\r' && texto[i + 1] === '\n') i++;
      cerrarRegistro();
    } else campo += c;
    i++;
  }
  if (campo || campos.length) cerrarRegistro();
  return registros;
}

const MINIMOS = { SOCIOS: 5, BONOS: 4, IRPF: 2, COBROS: 2, IGNORADA: Infinity };

function parseHora(texto) {
  const limpio = texto.trim();
  if (/^-?\d+$/.test(limpio)) return Number(limpio);
  const n = Number(limpio.replace(',', '.'));
  return Number.isFinite(n) && n >= 0 ? Math.trunc(n) : null;
}

function parseBooleano(texto) {
  const t = texto.trim().toLowerCase();
  if (t === 'true' || t === '1') return true;
  if (t === 'false' || t === '0') return false;
  return null;
}

export function leerCsv(texto) {
  const socios = [];
  const bonos = [];
  const cobros = [];
  let descartados = 0;
  let irpfIgnorados = 0;
  let seccion = 'SOCIOS';
  for (const campos of parsearRegistros(texto)) {
    if (campos.every((c) => !c.trim())) continue;
    const primero = campos[0].trim();
    if (primero.startsWith('#') && campos.slice(1).every((c) => !c.trim())) {
      if (/^#\s*VERSION\s+\d+$/i.test(primero)) continue;
      const p = primero.toUpperCase();
      seccion = p === '# BONOS_REGALO' ? 'BONOS' : p === '# IRPF' ? 'IRPF' : p === '# COBROS' ? 'COBROS' : p === '# DEVOLUCIONES' ? 'IGNORADA' : 'SOCIOS';
      continue;
    }
    if (['id', 'semanakey', 'desdesemanakey'].includes(primero.toLowerCase())) continue;
    if (campos.length < MINIMOS[seccion]) {
      descartados++;
      continue;
    }
    if (seccion === 'SOCIOS') {
      socios.push({
        id: campos[0].trim(),
        colaboracion: parseImporte(campos[1]),
        hecho: parseBooleano(campos[2]),
        diaSemanaId: /^\s*\d+\s*$/.test(campos[3]) ? Number(campos[3]) : null,
        semanaKey: campos[4].trim(),
        nombreSocio: campos[5] ?? null,
        notas: campos[6] ?? null,
        timestamp: campos[7] != null ? parseHora(campos[7]) : null
      });
    } else if (seccion === 'BONOS') {
      bonos.push({ semanaKey: campos[0].trim(), activo: parseBooleano(campos[1]), esMontoFijo: parseBooleano(campos[2]), monto: parseImporte(campos[3]), nota: campos[4] ?? null });
    } else if (seccion === 'IRPF') {
      irpfIgnorados++;
    } else if (seccion === 'COBROS') {
      cobros.push({ semanaKey: campos[0].trim(), netoCobrado: parseImporte(campos[1]), nota: campos[2] ?? null });
    }
  }
  const r = desdeDto({ socios, bonosRegalo: bonos, cobros });
  return { ...r, descartados: r.descartados + descartados, irpfIgnorados };
}
