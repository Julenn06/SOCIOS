// Prepara una versión nueva de la PWA: cambia el número en js/version.js, sw.js y version.json, calcula lo que ocupa
// la descarga y comprueba que el service worker guarda todos los archivos.
//   node pwa/herramientas/version.mjs 1.1.0 "Novedad uno" "Novedad dos"
//   node pwa/herramientas/version.mjs --comprobar      (solo comprueba la lista y el tamaño)
import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const pwa = join(dirname(fileURLToPath(import.meta.url)), '..');
const [primero, ...novedades] = process.argv.slice(2);
const soloComprobar = primero === '--comprobar';
if (!soloComprobar && !/^\d+\.\d+\.\d+$/.test(primero ?? '')) {
  console.error('Uso: node pwa/herramientas/version.mjs X.Y.Z "novedad" ... | --comprobar');
  process.exit(1);
}

const sw = readFileSync(join(pwa, 'sw.js'), 'utf8');
const listaDe = (nombre) => [...sw.match(new RegExp(`const ${nombre} = \\[([\\s\\S]*?)\\];`))[1].matchAll(/'([^']+)'/g)].map((m) => m[1]);
const lista = listaDe('ARCHIVOS');
// Lo del PDF se guarda aparte, la primera vez que se pide (ver sw.js).
const bajoDemanda = listaDe('BAJO_DEMANDA');

// Todo lo que usa la app tiene que estar en la lista: si no, sin conexión fallaría.
const todos = (carpeta) => readdirSync(carpeta).flatMap((f) => {
  const ruta = join(carpeta, f);
  return statSync(ruta).isDirectory() ? todos(ruta) : [relative(pwa, ruta).replaceAll('\\', '/')];
});
const necesarios = ['js', 'css', 'fonts', 'img', 'icons', 'vendor'].flatMap((c) => todos(join(pwa, c)))
  .filter((f) => /\.(js|css|ttf|png|webmanifest)$/.test(f));
const faltan = necesarios.filter((f) => !lista.includes(f) && !bajoDemanda.includes(f));
const sobran = [...lista, ...bajoDemanda].filter((f) => f !== './' && !existsSync(join(pwa, f)));
if (faltan.length || sobran.length) {
  if (faltan.length) console.error('Faltan en sw.js:', faltan.join(', '));
  if (sobran.length) console.error('No existen:', sobran.join(', '));
  process.exit(1);
}
// La Content-Security-Policy del .htaccess solo deja ejecutar los scripts en línea de index.html cuyo hash lleva: si
// se cambia uno sin cambiar su hash, la página no aplicaría el tema al abrirse.
const indice = readFileSync(join(pwa, 'index.html'), 'utf8');
const htaccess = readFileSync(join(pwa, '.htaccess'), 'utf8');
// Todo script en línea, se escriba como se escriba (<SCRIPT>, con atributos, "</script >"); los que cargan un archivo
// (src=) no llevan hash.
const sinHash = [...indice.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi)]
  .filter((m) => !/\bsrc\s*=/i.test(m[1]))
  .map((m) => `'sha256-${createHash('sha256').update(m[2]).digest('base64')}'`)
  .filter((hash) => !htaccess.includes(hash));
if (sinHash.length) {
  console.error(`El script en línea de index.html ha cambiado: pon ${sinHash.join(' ')} en el script-src del .htaccess`);
  process.exit(1);
}

const tamano = lista.filter((f) => f !== './').reduce((t, f) => t + statSync(join(pwa, f)).size, 0) + statSync(join(pwa, 'index.html')).size;
console.log(`${lista.length} archivos, ${(tamano / 1024 / 1024).toFixed(2)} MB`);

const info = JSON.parse(readFileSync(join(pwa, 'version.json'), 'utf8'));
info.tamano = tamano;
if (!soloComprobar) {
  info.version = primero;
  if (novedades.length) info.novedades = novedades;
  writeFileSync(join(pwa, 'js', 'version.js'), readFileSync(join(pwa, 'js', 'version.js'), 'utf8').replace(/VERSION = '[^']*'/, `VERSION = '${primero}'`));
  writeFileSync(join(pwa, 'sw.js'), sw.replace(/const VERSION = '[^']*'/, `const VERSION = '${primero}'`));
  console.log(`Versión ${primero}`);
}
writeFileSync(join(pwa, 'version.json'), `${JSON.stringify(info, null, 2)}\n`);
