// util/DateUtils.kt: semanas ISO-8601 (lunes a domingo; la semana 1 es la que contiene el primer jueves).
// Las fechas son Date a medianoche UTC: así sumar días nunca tropieza con los cambios de hora.

const DIA_MS = 86_400_000;
const PATRON_SEMANA = /^(\d{4})-W(\d{2})$/;

/** Meses abreviados como los escribe Java en es-ES ("sept", no "sep"), sin el punto. */
export const MESES_CORTOS = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sept', 'oct', 'nov', 'dic'];
export const MESES = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre'];

/** Hoy (o el día de [ms]) en la zona del dispositivo, como fecha UTC. */
export function fechaLocal(ms = Date.now()) {
  const d = new Date(ms);
  return new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()));
}

const fecha = (anio, mes, dia) => new Date(Date.UTC(anio, mes - 1, dia));
export const sumarDias = (f, dias) => new Date(f.getTime() + dias * DIA_MS);
/** 1 (lunes) a 7 (domingo). */
export const diaDeLaSemana = (f) => ((f.getUTCDay() + 6) % 7) + 1;
const lunesDe = (f) => sumarDias(f, 1 - diaDeLaSemana(f));

/** Año y semana ISO de [f]. */
function semanaIso(f) {
  const jueves = sumarDias(f, 4 - diaDeLaSemana(f));
  const anio = jueves.getUTCFullYear();
  const semana = Math.floor((jueves - fecha(anio, 1, 1)) / DIA_MS / 7) + 1;
  return [anio, semana];
}

const clave = (anio, semana) => `${String(anio).padStart(4, '0')}-W${String(semana).padStart(2, '0')}`;

/** Clave "AAAA-Wss" de la semana ISO a la que pertenece [f]. */
export const semanaKeyDe = (f) => clave(...semanaIso(f));

/** Lunes de la semana actual desplazada [offset] semanas. */
export const lunesDeSemana = (offset = 0) => sumarDias(lunesDe(fechaLocal()), offset * 7);

export const getSemanaKey = (offset = 0) => semanaKeyDe(lunesDeSemana(offset));

/** Semanas ISO del año ISO [anio] (52 o 53). */
export const semanasEnAnio = (anio) => semanaIso(fecha(anio, 12, 28))[1];

/** [año, semana] de una clave con forma "AAAA-Wss", exista o no; null si no tiene esa forma. */
export function partesDeSemanaKey(semanaKey) {
  const m = PATRON_SEMANA.exec(semanaKey ?? '');
  return m ? [Number(m[1]), Number(m[2])] : null;
}

/** Lunes de la semana [semanaKey], o null si no es una semana ISO válida. */
export function lunesDeSemanaKey(semanaKey) {
  const partes = partesDeSemanaKey(semanaKey);
  if (!partes) return null;
  const [anio, semana] = partes;
  if (semana < 1 || semana > semanasEnAnio(anio)) return null;
  // El 4 de enero siempre está en la semana 1 de su año ISO.
  return sumarDias(lunesDe(fecha(anio, 1, 4)), (semana - 1) * 7);
}

export const esSemanaKeyValida = (semanaKey) => lunesDeSemanaKey(semanaKey) != null;

/** Semanas de [desde] a [hasta] (negativo si [hasta] es anterior); null si alguna clave no es válida. */
export function semanasEntre(desde, hasta) {
  const a = lunesDeSemanaKey(desde);
  const b = lunesDeSemanaKey(hasta);
  if (!a || !b) return null;
  return Math.round((b - a) / DIA_MS / 7);
}

/** La semana que está [semanas] después de [semanaKey]; null si la clave no es válida. */
export function semanaKeyDesplazada(semanaKey, semanas) {
  const lunes = lunesDeSemanaKey(semanaKey);
  return lunes ? semanaKeyDe(sumarDias(lunes, semanas * 7)) : null;
}

const diaCorto = (f) => `${f.getUTCDate()} ${MESES_CORTOS[f.getUTCMonth()]}`;
const diaLargo = (f) => `${f.getUTCDate()} de ${MESES_CORTOS[f.getUTCMonth()]}.`;

/** "24 de feb. - 28 de feb. (2025)", o la propia clave si no es válida. */
export function rangoSemanaTexto(semanaKey) {
  const lunes = lunesDeSemanaKey(semanaKey);
  if (!lunes) return semanaKey;
  return `${diaLargo(lunes)} - ${diaLargo(sumarDias(lunes, 4))} (${semanaIso(lunes)[0]})`;
}

/** "12–16 oct" o "29 dic – 2 ene"; la propia clave si no es válida. */
export function rangoCorto(semanaKey) {
  const lunes = lunesDeSemanaKey(semanaKey);
  if (!lunes) return semanaKey;
  const viernes = sumarDias(lunes, 4);
  return lunes.getUTCMonth() === viernes.getUTCMonth()
    ? `${lunes.getUTCDate()}–${diaCorto(viernes)}`
    : `${diaCorto(lunes)} – ${diaCorto(viernes)}`;
}

/** [rangoCorto] con el año del viernes: "12–16 oct 2026", "28 dic – 1 ene 2027". */
export function rangoCortoConAnio(semanaKey) {
  const lunes = lunesDeSemanaKey(semanaKey);
  if (!lunes) return semanaKey;
  return `${rangoCorto(semanaKey)} ${sumarDias(lunes, 4).getUTCFullYear()}`;
}

/** El jueves de la semana, que decide su mes y su año (criterio ISO). */
export function juevesDe(semanaKey) {
  const lunes = lunesDeSemanaKey(semanaKey);
  return lunes ? sumarDias(lunes, 3) : null;
}

/** "oct 2026": el mes de su jueves. */
export function mesConAnio(semanaKey) {
  const jueves = juevesDe(semanaKey);
  return jueves ? `${MESES_CORTOS[jueves.getUTCMonth()]} ${jueves.getUTCFullYear()}` : semanaKey;
}

/** "hoy a las 16:05", "ayer a las 9:30" o "el 3 oct". */
export function momentoRelativo(ms) {
  const momento = new Date(ms);
  const dia = fechaLocal(ms);
  const hoy = fechaLocal();
  const hora = `${momento.getHours()}:${String(momento.getMinutes()).padStart(2, '0')}`;
  if (dia.getTime() === hoy.getTime()) return `hoy a las ${hora}`;
  if (dia.getTime() === sumarDias(hoy, -1).getTime()) return `ayer a las ${hora}`;
  return `el ${diaCorto(dia)}`;
}

/** "Octubre 2026". */
export const nombreMes = (mes, anio) => `${MESES[mes - 1][0].toUpperCase()}${MESES[mes - 1].slice(1)} ${anio}`;

// --- Días laborables (model/DiaSemana.kt) ---

export const DIAS = [
  { id: 1, corto: 'Lun', completo: 'Lunes' },
  { id: 2, corto: 'Mar', completo: 'Martes' },
  { id: 3, corto: 'Mié', completo: 'Miércoles' },
  { id: 4, corto: 'Jue', completo: 'Jueves' },
  { id: 5, corto: 'Vie', completo: 'Viernes' }
];

export const diaPorId = (id) => DIAS.find((d) => d.id === id) ?? null;

/** Día laborable de hoy: el sábado cuenta como viernes y el domingo como lunes. */
export function diaSemanaActual() {
  const d = diaDeLaSemana(fechaLocal());
  return d === 6 ? 5 : d === 7 ? 1 : d;
}

/** 0 (lunes) a 6 (domingo), para la franja de los días. */
export const indiceDeHoy = () => diaDeLaSemana(fechaLocal()) - 1;
