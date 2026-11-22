// La web (pwa/) servida en local con las mismas cabeceras que pone el .htaccess en Hostinger (la CSP con Trusted
// Types, sobre todo): lo que la política bloquee en producción también se bloquea aquí. El servidor de la cuenta va en
// http://localhost:3000 (js/entorno.js), así que la CSP lo apunta ahí en lugar de a socios-api.jfl06studio.com.
import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';

const TIPOS = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.png': 'image/png', '.ttf': 'font/ttf',
  '.woff2': 'font/woff2', '.svg': 'image/svg+xml', '.txt': 'text/plain; charset=utf-8'
};

/** Las cabeceras de seguridad del .htaccess, para servirlas en local (sin HSTS ni pasar a https). */
export async function cabecerasDelHtaccess(carpeta) {
  const ht = await readFile(join(carpeta, '.htaccess'), 'utf8');
  const cabeceras = {};
  for (const m of ht.matchAll(/Header always set ([\w-]+) "([^"]+)"/g)) {
    if (m[1] === 'Strict-Transport-Security') continue;
    cabeceras[m[1]] = m[2].replace('; upgrade-insecure-requests', '').replaceAll('https://socios-api.jfl06studio.com', 'http://localhost:3000');
  }
  return cabeceras;
}

export async function servirWeb(carpeta, puerto) {
  const cabeceras = await cabecerasDelHtaccess(carpeta);
  const servidor = createServer(async (req, res) => {
    try {
      let ruta = decodeURIComponent(new URL(req.url, 'http://x').pathname);
      if (ruta.endsWith('/')) ruta += 'index.html';
      const archivo = normalize(join(carpeta, ruta));
      if (!archivo.startsWith(normalize(carpeta))) throw new Error('fuera');
      await stat(archivo);
      res.writeHead(200, { ...cabeceras, 'Content-Type': TIPOS[extname(archivo)] ?? 'application/octet-stream', 'Cache-Control': 'no-cache' });
      res.end(await readFile(archivo));
    } catch {
      res.writeHead(404, cabeceras);
      res.end('No existe');
    }
  });
  await new Promise((r) => servidor.listen(puerto, '127.0.0.1', r));
  return servidor;
}
