// Tests de la lógica de la PWA, con los mismos casos que los tests unitarios de la app Android (CalculoComisionesTest,
// CentimosTest, DateUtilsTest, NumberExtensionsTest, EscalaGraficoTest, BackupJsonTest y BackupCsvTest).
// Sin dependencias: node --test "pwa/test/*.test.mjs"
import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as F from '../js/formato.js';
import * as D from '../js/fechas.js';
import * as C from '../js/calculos.js';
import * as B from '../js/copias.js';

/** Los importes llevan un espacio que no se parte antes del símbolo; en los tests se compara con uno normal. */
const nb = (s) => s.replace(/ /g, ' ');
const iso = (f) => f.toISOString().slice(0, 10);
const dia = (a, m, d) => new Date(Date.UTC(a, m - 1, d));

test('importes en español: miles con punto (también con 4 cifras) y redondeo hacia arriba como Java', () => {
  assert.equal(nb(F.moneda(123.456)), '123,46 €');
  assert.equal(nb(F.moneda(123.456, 2, '+')), '+123,46 €');
  assert.equal(nb(F.moneda(8060.93)), '8.060,93 €');
  assert.equal(nb(F.moneda(-1234567)), '-1.234.567,00 €');
  assert.equal(nb(F.moneda(1250)), '1.250,00 €');
  assert.equal(nb(F.eurosCompacto(2510, '+')), '+2.510 €');
  assert.equal(nb(F.eurosCompacto(20)), '20 €');
  assert.equal(nb(F.eurosCompacto(10.5)), '10,50 €');
  assert.equal(F.eurosCompacto(50).includes(' '), false);
});

test('lo descontado lleva el signo al revés que la diferencia', () => {
  assert.equal(nb(F.descontadoConSigno(15.31)), '-15,31 €');
  assert.equal(nb(F.descontadoConSigno(-5)), '+5,00 €');
  assert.equal(nb(F.descontadoConSigno(0)), '0,00 €');
  assert.equal(nb(F.descontadoConSigno(-5, true)), '+5 €');
});

test('porcentajes, importes de los campos y lo que se escribe', () => {
  assert.equal(nb(F.porcentaje(21.48)), '21,48 %');
  assert.equal(nb(F.porcentaje(15)), '15 %');
  assert.equal(F.importeInput(10), '10');
  assert.equal(F.importeInput(10.5), '10.5');
  assert.equal(F.parseImporte('10,5'), 10.5);
  assert.equal(F.parseImporte('10.5'), 10.5);
  assert.equal(F.parseImporte(' 7 '), 7);
  for (const malo of ['', 'abc', 'NaN', null]) assert.equal(F.parseImporte(malo), null);
});

test('céntimos: medio céntimo hacia arriba, IRPF redondeado y sumas exactas', () => {
  assert.equal(C.Centimos.de(12.345), 1235);
  assert.equal(C.Centimos.de(2.675), 268);
  assert.equal(C.Centimos.de(0.125), 13);
  assert.equal(C.Centimos.de(-0.125), -13);
  assert.equal(C.Centimos.porcentaje(14_000, 21.48), 3007);
  assert.equal(C.Centimos.porcentaje(50, 25), 13);
  assert.equal(C.Centimos.suma(Array(10).fill(0.1), (x) => x), 1);
  assert.equal(C.Centimos.suma([0.1, 0.2], (x) => x), 0.3);
});

test('semanas ISO: la 1 empieza en diciembre y la 53 acaba en enero', () => {
  assert.equal(D.semanaKeyDe(dia(2025, 12, 29)), '2026-W01');
  assert.equal(D.semanaKeyDe(dia(2026, 12, 28)), '2026-W53');
  assert.equal(D.semanaKeyDe(dia(2027, 1, 1)), '2026-W53');
  assert.equal(D.semanaKeyDe(dia(2027, 1, 3)), '2026-W53');
  assert.equal(D.semanaKeyDe(dia(2027, 1, 4)), '2027-W01');
  assert.equal(D.esSemanaKeyValida('2026-W53'), true);
  assert.equal(D.esSemanaKeyValida('2027-W53'), false);
  assert.equal(D.esSemanaKeyValida('2025-W00'), false);
  assert.equal(D.esSemanaKeyValida('2025-9'), false);
  assert.equal(D.esSemanaKeyValida(''), false);
  assert.equal(iso(D.lunesDeSemanaKey('2026-W01')), '2025-12-29');
  assert.equal(iso(D.lunesDeSemanaKey('2026-W53')), '2026-12-28');
  assert.equal(iso(D.lunesDeSemanaKey('2025-W09')), '2025-02-24');
  assert.equal(D.semanasEntre('2026-W42', '2027-W02'), 13);
  assert.equal(D.semanasEntre('2026-W42', 'no-es-una-semana'), null);
  assert.equal(D.semanaKeyDesplazada('2026-W52', 2), '2027-W01');
  assert.equal(D.semanaKeyDesplazada('no-es-una-semana', 1), null);
});

test('textos de las semanas', () => {
  const rango = D.rangoSemanaTexto('2026-W01');
  assert.ok(rango.startsWith('29 de dic'));
  assert.ok(rango.includes('2 de ene'));
  assert.ok(rango.endsWith('(2026)'));
  assert.equal(D.rangoSemanaTexto('clave-rara'), 'clave-rara');
  assert.equal(D.rangoCorto('2026-W42'), '12–16 oct');
  assert.equal(D.rangoCorto('2026-W01'), '29 dic – 2 ene');
  assert.equal(D.mesConAnio('2026-W01'), 'ene 2026');
  assert.equal(D.mesConAnio('2026-W39'), 'sept 2026');
  assert.equal(D.rangoCortoConAnio('2026-W53'), '28 dic – 1 ene 2027');
  assert.equal(D.rangoCortoConAnio('2026-W01'), '29 dic – 2 ene 2026');
  assert.equal(D.rangoCortoConAnio('2026-W42'), '12–16 oct 2026');
});

test('tramos del bonus', () => {
  assert.deepEqual([0, 4, 5, 6, 7, 9, 10, 12, 13, 16].map(C.calcularBonusSemanal), [0, 0, 50, 50, 100, 100, 140, 140, 190, 240]);
  assert.deepEqual(C.siguienteMetaBonus(0), [5, 50]);
  assert.deepEqual(C.siguienteMetaBonus(7), [10, 140]);
  assert.deepEqual(C.siguienteMetaBonus(10), [13, 190]);
});

test('cuentas de una semana: base, bonus, bruto, IRPF y neto; con bonus manual y lo cobrado', () => {
  const socios = [20, 20, 20, 20, 20, 15, 18].map((c, i) => ({ colaboracion: c, hecho: i < 6, dia: 1, semanaKey: '2026-W42' }));
  const r = C.calcularResumenSemana(socios);
  assert.equal(r.gananciasBaseX2, 230);
  assert.equal(r.bonusSemanal, 50);
  assert.equal(r.totalBruto, 280);
  assert.equal(r.retencionIrpf, 60.14);
  assert.equal(r.netoCalculado, 219.86);
  assert.equal(r.totalNeto, 219.86);
  assert.equal(r.sociosFaltantes, 1);

  const sumado = C.calcularResumenSemana(socios, { activo: true, esMontoFijo: false, monto: 10 }, C.IRPF, { netoCobrado: 200 });
  assert.equal(sumado.bonusSemanal, 60);
  assert.equal(sumado.esBonoRegaloAplicado, true);
  assert.equal(sumado.totalNeto, 200);
  assert.equal(sumado.descontado, Number((sumado.netoCalculado - 200).toFixed(2)));

  const fijo = C.calcularResumenSemana(socios, { activo: true, esMontoFijo: true, monto: 0 });
  assert.equal(fijo.bonusSemanal, 0);
  const inactivo = C.calcularResumenSemana(socios, { activo: false, esMontoFijo: true, monto: 999 });
  assert.equal(inactivo.bonusSemanal, 50);
});

test('histórico: cada semana con su tramo y las semanas con solo lo cobrado también cuentan', () => {
  const socios = [
    ...Array.from({ length: 5 }, () => ({ colaboracion: 20, hecho: true, dia: 1, semanaKey: '2026-W40' })),
    { colaboracion: 15, hecho: true, dia: 2, semanaKey: '2026-W41' }
  ];
  const h = C.calcularResumenHistoricoGlobal(socios, new Map(), new Map([['2026-W43', { netoCobrado: 12.5 }]]));
  assert.deepEqual(h.semanas.map((s) => s.semanaKey), ['2026-W43', '2026-W41', '2026-W40']);
  assert.equal(h.bonus, 50);
  assert.equal(h.base, 230);
  assert.equal(C.mejorSemana(h.semanas).semanaKey, '2026-W40');
  const meses = C.agruparPorPeriodo(h.semanas, 'MES');
  assert.deepEqual(meses.map((m) => m.clave), ['2026-10']);
});

test('escala del gráfico', () => {
  const e = C.escalaPara([100, 250, 520]);
  assert.deepEqual(e.guias, [0, 200, 400, 600]);
  assert.equal(e.rango, 600);
  assert.equal(C.escalaPara([-30, 100]).suelo, -50);
  assert.deepEqual(C.escalaPara([]).guias, [0, 100]);
  assert.equal(C.pasoRedondo(0.8), 1);
});

const DATOS = {
  socios: [{ id: 'a', colaboracion: 20, hecho: true, dia: 2, semanaKey: '2026-W42', nombreSocio: 'Ana, "la de" Bilbao', notas: 'línea 1\nlínea 2', timestamp: 1760000000000 }],
  bonosRegalo: [{ semanaKey: '2026-W42', activo: true, esMontoFijo: false, monto: 12.5, nota: '' }],
  cobros: [{ semanaKey: '2026-W42', netoCobrado: 300, nota: '' }]
};

test('copia JSON: formato de la app Android (versión 5) y se vuelve a leer igual', () => {
  const json = B.escribirJson(DATOS, 123);
  assert.ok(json.includes('"version": 5'));
  assert.ok(json.includes('"colaboracion": 20.0'));
  assert.ok(json.includes('"monto": 12.5'));
  assert.deepEqual(B.leerJson(json).datos, DATOS);
  assert.equal(B.leerJson(json).fechaCopia, 123);
  // El formato antiguo era una lista de socios.
  assert.equal(B.leerJson(JSON.stringify([{ id: 'x', colaboracion: 10, diaSemanaId: 1, semanaKey: '2026-W01' }])).datos.socios.length, 1);
});

test('copia JSON: los registros imposibles se descartan y los cambios de IRPF solo se cuentan', () => {
  const lectura = B.leerJson(JSON.stringify({
    socios: [null, { colaboracion: -1, diaSemanaId: 1, semanaKey: '2026-W42' }, { colaboracion: 15, diaSemanaId: 9, semanaKey: '2026-W42' }, { colaboracion: 15, diaSemanaId: 1, semanaKey: '2027-W53' }],
    irpfVigencias: [1, 2]
  }));
  assert.equal(lectura.descartados, 4);
  assert.equal(lectura.irpfIgnorados, 2);
  assert.throws(() => B.leerJson('esto no es json'), /no es una copia de seguridad JSON válida/);
});

test('copia CSV: con BOM y versión, comillas en los campos y se vuelve a leer igual', () => {
  const csv = B.escribirCsv(DATOS);
  assert.ok(csv.startsWith('﻿# VERSION 5\n# SOCIOS\n'));
  assert.ok(csv.includes('"Ana, ""la de"" Bilbao"'));
  assert.deepEqual(B.leerCsv(csv.slice(1)).datos, DATOS);
  // Una hoja guardada desde Excel rellena con comas las líneas cortas.
  const excel = csv.slice(1).replace('# COBROS\n', '# COBROS,,,,\n');
  assert.equal(B.leerCsv(excel).datos.cobros.length, 1);
});
