// Convierte los iconos vectoriales de la app Android (app/src/main/res/drawable/ic_*.xml) en js/iconos.js, con un
// SVG por icono que se pinta con currentColor. Se vuelve a ejecutar si cambian los iconos de la app:
//   node pwa/herramientas/iconos.mjs
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const carpeta = join(raiz, 'app', 'src', 'main', 'res', 'drawable');
const atributo = (texto, nombre) => texto.match(new RegExp(`android:${nombre}="([^"]*)"`))?.[1];

const iconos = {};
for (const archivo of readdirSync(carpeta).filter((f) => /^ic_.*\.xml$/.test(f)).sort()) {
  const xml = readFileSync(join(carpeta, archivo), 'utf8');
  if (!xml.includes('<vector')) continue;
  const ancho = atributo(xml, 'viewportWidth');
  const alto = atributo(xml, 'viewportHeight');
  // Cada <group> con su transformación y cada <path> dentro.
  let cuerpo = '';
  const grupos = xml.split(/<group/).slice(1);
  if (grupos.length) {
    for (const g of grupos) {
      const sx = atributo(g, 'scaleX') ?? 1, sy = atributo(g, 'scaleY') ?? 1;
      const tx = atributo(g, 'translateX') ?? 0, ty = atributo(g, 'translateY') ?? 0;
      const caminos = [...g.matchAll(/android:pathData="([^"]*)"/g)].map((m) => `<path d="${m[1]}"/>`).join('');
      cuerpo += `<g transform="translate(${tx} ${ty}) scale(${sx} ${sy})">${caminos}</g>`;
    }
  } else {
    cuerpo = [...xml.matchAll(/android:pathData="([^"]*)"/g)].map((m) => `<path d="${m[1]}"/>`).join('');
  }
  const nombre = archivo.replace(/^ic_/, '').replace(/\.xml$/, '');
  iconos[nombre] = `<svg viewBox="0 0 ${ancho} ${alto}" fill="currentColor" aria-hidden="true" focusable="false">${cuerpo}</svg>`;
}

const salida = `// Generado por herramientas/iconos.mjs a partir de los iconos de la app (Phosphor, licencia MIT). No editar.
export const ICONOS = ${JSON.stringify(iconos, null, 1)};

/** El SVG del icono [nombre] (sin "ic_"), que toma el color del texto. */
export const icono = (nombre) => ICONOS[nombre] ?? '';
`;
writeFileSync(join(raiz, 'pwa', 'js', 'iconos.js'), salida);
console.log(`${Object.keys(iconos).length} iconos`);
