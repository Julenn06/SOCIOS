// util/Centimos.kt, util/CalculoComisiones.kt y util/EscalaGrafico.kt.
import { juevesDe, semanaKeyDesplazada, semanasEntre } from './fechas.js';

/** Retención de IRPF: la misma en todas las semanas. */
export const IRPF = 21.48;

export const CUOTAS = [10, 12, 15, 18, 20, 25, 30];
export const CUOTA_POR_DEFECTO = 20;
export const BONUS = [50, 100, 140];
/** Tope de cualquier importe: más es un error al teclearlo o un archivo dañado. */
export const IMPORTE_MAXIMO = 100_000;

// --- Céntimos enteros: así se suman las cuentas, sin arrastrar restos de los decimales ---

export const Centimos = {
  /** Los céntimos de [euros], redondeando el medio céntimo hacia arriba (12,345 € → 1235). */
  de(euros) {
    const c = Math.round(Number((Math.abs(euros) * 100).toPrecision(15)));
    return euros < 0 ? -c : c;
  },
  aEuros: (centimos) => centimos / 100,
  /** El [pct] % de [centimos], redondeado a céntimos (la retención de IRPF de un bruto). */
  porcentaje(centimos, pct) {
    const decimales = (String(pct).split('.')[1] ?? '').length;
    const p = Math.round(pct * 10 ** decimales);
    const divisor = 10 ** (decimales + 2);
    const total = Math.abs(centimos) * p;
    let q = Math.floor(total / divisor);
    if ((total % divisor) * 2 >= divisor) q++;
    return centimos < 0 ? -q : q;
  },
  suma(elementos, importe) {
    let total = 0;
    for (const e of elementos) total += Centimos.de(importe(e));
    return Centimos.aEuros(total);
  }
};

export const redondearCentimos = (importe) => Centimos.aEuros(Centimos.de(importe));

export const calcularBaseX2 = (colaboracion) => colaboracion * 2;

/** < 5: 0 €; 5–6: 50 €; 7–9: 100 €; 10: 140 €; y 50 € más cada 3 socios (13 → 190 €, 16 → 240 €…). */
export function calcularBonusSemanal(hechos) {
  if (hechos < 5) return 0;
  if (hechos <= 6) return 50;
  if (hechos <= 9) return 100;
  return 140 + Math.floor((hechos - 10) / 3) * 50;
}

/** Socios con los que empieza cada tramo: 5, 7, 10, 13, 16… */
export function* tramosBonus() {
  yield 5;
  yield 7;
  for (let t = 10; ; t += 3) yield t;
}

export function siguienteMetaBonus(hechos) {
  for (const t of tramosBonus()) if (t > hechos) return [t, calcularBonusSemanal(t)];
  return [0, 0];
}

/** Los tramos hasta [hasta] incluido. */
export function tramosHasta(hasta) {
  const tramos = [];
  for (const t of tramosBonus()) {
    if (t > hasta) break;
    tramos.push(t);
  }
  return tramos;
}

/**
 * Cuentas de una semana: base (cuotas × 2) + bonus = bruto; bruto − IRPF = neto calculado. Con lo cobrado apuntado,
 * el neto de la semana es ese y la diferencia con el calculado es lo descontado.
 */
export function calcularResumenSemana(socios, bono = null, porcentajeIrpf = IRPF, cobro = null) {
  const hechos = socios.filter((s) => s.hecho);
  const sumaCuotas = hechos.reduce((t, s) => t + Centimos.de(s.colaboracion), 0);
  const baseX2 = sumaCuotas * 2;
  const bonusAuto = Centimos.de(calcularBonusSemanal(hechos.length));
  const aplicado = bono && bono.activo ? bono : null;
  const bonusFinal = !aplicado ? bonusAuto : aplicado.esMontoFijo ? Centimos.de(aplicado.monto) : bonusAuto + Centimos.de(aplicado.monto);
  const bruto = baseX2 + bonusFinal;
  const irpf = Centimos.porcentaje(bruto, porcentajeIrpf);
  const neto = bruto - irpf;
  const cobrado = cobro && cobro.netoCobrado != null ? Centimos.de(cobro.netoCobrado) : null;
  const [meta, bonusMeta] = siguienteMetaBonus(hechos.length);
  const netoCalculado = Centimos.aEuros(neto);
  const netoCobrado = cobrado == null ? null : Centimos.aEuros(cobrado);
  return {
    totalSociosRegistrados: socios.length,
    totalSociosHechos: hechos.length,
    gananciasBaseX2: Centimos.aEuros(baseX2),
    bonusSemanal: Centimos.aEuros(bonusFinal),
    esBonoRegaloAplicado: aplicado != null,
    totalBruto: Centimos.aEuros(bruto),
    porcentajeIrpf,
    retencionIrpf: Centimos.aEuros(irpf),
    netoCalculado,
    netoCobrado,
    totalNeto: Centimos.aEuros(cobrado ?? neto),
    siguienteMetaSocios: meta,
    siguienteMetaBonus: bonusMeta,
    sociosFaltantes: Math.max(0, meta - hechos.length),
    tieneCobroReal: cobrado != null,
    /** Positivo si te han descontado, negativo si has cobrado de más. */
    descontado: cobrado == null ? 0 : Centimos.aEuros(neto - cobrado)
  };
}

/** Cada semana por separado (su tramo, su bono y lo cobrado) y luego la suma. */
export function calcularResumenHistoricoGlobal(socios, bonos = new Map(), cobros = new Map()) {
  const porSemana = Map.groupBy(socios, (s) => s.semanaKey);
  const claves = new Set(porSemana.keys());
  for (const [k, b] of bonos) if (b.activo) claves.add(k);
  for (const k of cobros.keys()) claves.add(k);
  const semanas = [...claves].sort().reverse().map((semanaKey) => ({
    semanaKey,
    resumen: calcularResumenSemana(porSemana.get(semanaKey) ?? [], bonos.get(semanaKey) ?? null, IRPF, cobros.get(semanaKey) ?? null)
  }));
  const r = semanas.map((s) => s.resumen);
  return {
    irpf: Centimos.suma(r, (x) => x.retencionIrpf),
    neto: Centimos.suma(r, (x) => x.totalNeto),
    bruto: Centimos.suma(r, (x) => x.totalBruto),
    base: Centimos.suma(r, (x) => x.gananciasBaseX2),
    bonus: Centimos.suma(r, (x) => x.bonusSemanal),
    descontado: Centimos.suma(r, (x) => x.descontado),
    hechos: r.reduce((t, x) => t + x.totalSociosHechos, 0),
    registrados: r.reduce((t, x) => t + x.totalSociosRegistrados, 0),
    semanas
  };
}

const claveMes = (anio, mes) => `${String(anio).padStart(4, '0')}-${String(mes).padStart(2, '0')}`;

/** Por mes ('MES') o por año ('ANIO'); una semana que cruza cuenta en el mes o año de su jueves. */
export function agruparPorPeriodo(semanas, periodo) {
  const grupos = new Map();
  for (const item of semanas) {
    const jueves = juevesDe(item.semanaKey);
    if (!jueves) continue;
    const anio = jueves.getUTCFullYear();
    const mes = periodo === 'MES' ? jueves.getUTCMonth() + 1 : null;
    const k = `${anio}|${mes}`;
    if (!grupos.has(k)) grupos.set(k, { anio, mes, items: [] });
    grupos.get(k).items.push(item.resumen);
  }
  return [...grupos.values()]
    .map(({ anio, mes, items }) => ({
      anio,
      mes,
      clave: mes ? claveMes(anio, mes) : String(anio),
      sociosHechos: items.reduce((t, x) => t + x.totalSociosHechos, 0),
      bruto: Centimos.suma(items, (x) => x.totalBruto),
      descontado: Centimos.suma(items, (x) => x.descontado),
      irpf: Centimos.suma(items, (x) => x.retencionIrpf),
      neto: Centimos.suma(items, (x) => x.totalNeto)
    }))
    .sort((a, b) => b.anio - a.anio || (b.mes ?? 0) - (a.mes ?? 0));
}

/** La semana con más neto, o null si ninguna ha dado neto. */
export function mejorSemana(semanas) {
  let mejor = null;
  for (const s of semanas) if (!mejor || s.resumen.totalNeto > mejor.resumen.totalNeto) mejor = s;
  return mejor && mejor.resumen.totalNeto > 0 ? mejor : null;
}

/** Diez años de semanas: el gráfico se desplaza, pero no crece sin límite. */
export const MAX_BARRAS = 520;
const acotar = (v, min, max) => Math.min(Math.max(v, min), max);

export function ultimasSemanas(semanas, hasta, cantidad = 12) {
  const netos = new Map(semanas.map((s) => [s.semanaKey, s.resumen.totalNeto]));
  const barras = [];
  for (let atras = cantidad - 1; atras >= 0; atras--) {
    const k = semanaKeyDesplazada(hasta, -atras);
    if (k) barras.push({ clave: k, neto: netos.get(k) ?? 0 });
  }
  return barras;
}

export function evolucionSemanas(semanas, hasta, minimo = 12) {
  const primera = semanas.reduce((m, s) => (m == null || s.semanaKey < m ? s.semanaKey : m), null);
  const desde = primera ? (semanasEntre(primera, hasta) ?? -1) + 1 : 0;
  return ultimasSemanas(semanas, hasta, acotar(desde, minimo, MAX_BARRAS));
}

export function ultimosPeriodos(periodos, periodo, hasta, cantidad = 12) {
  const jueves = juevesDe(hasta);
  if (!jueves) return [];
  const netos = new Map(periodos.map((p) => [p.clave, p.neto]));
  const anioJ = jueves.getUTCFullYear();
  if (periodo === 'MES') {
    const barras = [];
    for (let atras = cantidad - 1; atras >= 0; atras--) {
      const d = new Date(Date.UTC(anioJ, jueves.getUTCMonth() - atras, 1));
      const k = claveMes(d.getUTCFullYear(), d.getUTCMonth() + 1);
      barras.push({ clave: k, neto: netos.get(k) ?? 0 });
    }
    return barras;
  }
  const primero = Math.min(periodos.reduce((m, p) => Math.min(m, p.anio), anioJ), anioJ);
  const barras = [];
  for (let a = Math.max(primero, anioJ - cantidad + 1); a <= anioJ; a++) barras.push({ clave: String(a), neto: netos.get(String(a)) ?? 0 });
  return barras;
}

export function evolucionPeriodos(periodos, periodo, hasta, minimo = 12) {
  const jueves = juevesDe(hasta);
  if (!jueves) return [];
  let desde = 0;
  if (periodos.length) {
    if (periodo === 'MES') {
      const min = Math.min(...periodos.map((p) => p.anio * 12 + (p.mes ?? 1)));
      desde = jueves.getUTCFullYear() * 12 + jueves.getUTCMonth() + 1 - min + 1;
    } else {
      desde = jueves.getUTCFullYear() - Math.min(...periodos.map((p) => p.anio)) + 1;
    }
  }
  return ultimosPeriodos(periodos, periodo, hasta, acotar(desde, minimo, MAX_BARRAS));
}

// --- Escala del gráfico (líneas de guía de números redondos) ---

export function pasoRedondo(aprox) {
  if (aprox <= 0) return 100;
  const potencia = 10 ** Math.floor(Math.log10(aprox));
  const f = aprox / potencia;
  return potencia * (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10);
}

export function escalaPara(valores) {
  const lista = [...valores];
  const maximo = lista.length ? Math.max(0, ...lista) : 0;
  const minimo = lista.length ? Math.min(0, ...lista) : 0;
  const paso = pasoRedondo(Math.max(maximo, -minimo) / 3);
  const suelo = Math.floor(minimo / paso) * paso;
  const techo = Math.max(Math.ceil(maximo / paso) * paso, paso);
  const guias = [];
  for (let v = suelo; v <= techo + paso / 1000; v += paso) guias.push(v);
  return { suelo, techo, paso, rango: techo - suelo, guias, esCero: (v) => Math.abs(v) < paso / 1000 };
}
