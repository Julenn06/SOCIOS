// Vigilancia de la web y del servidor (lo lanza .github/workflows/vigilancia.yml cada 30 minutos):
//  1. Que respondan y sigan con sus cabeceras de seguridad. Si algo falla (tres intentos), se abre un issue
//     "La web o el servidor no responden"; cuando vuelve a ir bien, se cierra solo.
//  2. Los errores nuevos de la web en los dispositivos (servidor/errores.js). Si hay, se abre o se amplía el issue
//     "Errores nuevos en la web". Como el repositorio es público, en el issue solo va lo que no puede traer nada de
//     nadie (tipo, archivo y línea, versión, navegador, cuántas veces): los mensajes se piden con la clave a la API.
//
// Variables: GITHUB_TOKEN, GITHUB_REPOSITORY, CLAVE_ERRORES (sin ella, solo se mira lo primero).
//   node .github/scripts/vigilancia.mjs --sin-issues     (en local: solo dice qué ve)

const WEB = 'https://socios.jfl06studio.com';
const API = 'https://socios-api.jfl06studio.com';
const SIN_ISSUES = process.argv.includes('--sin-issues');
const { GITHUB_TOKEN, GITHUB_REPOSITORY, CLAVE_ERRORES } = process.env;
const ETIQUETA_CAIDA = 'vigilancia';
const ETIQUETA_ERRORES = 'errores-web';

const espera = (ms) => new Promise((r) => setTimeout(r, ms));
const pedir = (url, opciones = {}) => fetch(url, { ...opciones, signal: AbortSignal.timeout(20_000), headers: { 'User-Agent': 'socios-vigilancia', ...opciones.headers } });

// --- 1. que todo responda ---------------------------------------------------

const COMPROBACIONES = [
  ['La web abre y lleva su política de seguridad', async () => {
    const r = await pedir(`${WEB}/`);
    if (r.status !== 200) return `responde ${r.status}`;
    const csp = r.headers.get('content-security-policy') ?? '';
    if (!csp.includes("require-trusted-types-for 'script'")) return 'falta la CSP con Trusted Types';
    if (!r.headers.get('strict-transport-security')) return 'falta HSTS';
    return null;
  }],
  ['La versión publicada se puede leer', async () => {
    const r = await pedir(`${WEB}/version.json`, { headers: { 'Cache-Control': 'no-cache' } });
    if (r.status !== 200) return `responde ${r.status}`;
    const { version } = await r.json();
    return /^\d+\.\d+\.\d+$/.test(version ?? '') ? null : `versión rara: ${version}`;
  }],
  ['El servidor de la cuenta responde', async () => {
    const r = await pedir(`${API}/api/salud`);
    if (r.status !== 200) return `responde ${r.status}`;
    if ((await r.json()).ok !== true) return 'no dice ok';
    if (!(r.headers.get('content-security-policy') ?? '').includes("default-src 'none'")) return 'faltan sus cabeceras de seguridad';
    return null;
  }],
  ['El servidor rechaza peticiones de otras webs', async () => {
    const r = await pedir(`${API}/api/sincronizar`, { method: 'POST', headers: { Origin: 'https://otra-web.example', 'Content-Type': 'application/json' }, body: '{}' });
    return r.status === 403 ? null : `responde ${r.status} en lugar de 403`;
  }]
];

async function comprobar() {
  const fallos = [];
  for (const [nombre, f] of COMPROBACIONES) {
    let motivo = null;
    for (let intento = 1; intento <= 3; intento++) {
      try {
        motivo = await f();
      } catch (e) {
        motivo = e?.cause?.code ?? e?.name ?? String(e);
      }
      if (!motivo) break;
      if (intento < 3) await espera(20_000);
    }
    console.log(`${motivo ? '✖' : '✔'} ${nombre}${motivo ? `: ${motivo}` : ''}`);
    if (motivo) fallos.push(`- **${nombre}**: ${motivo}`);
  }
  return fallos;
}

// --- GitHub -----------------------------------------------------------------

async function github(metodo, ruta, cuerpo) {
  const r = await fetch(`https://api.github.com/repos/${GITHUB_REPOSITORY}${ruta}`, {
    method: metodo,
    headers: { Authorization: `Bearer ${GITHUB_TOKEN}`, Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'socios-vigilancia' },
    body: cuerpo && JSON.stringify(cuerpo)
  });
  if (!r.ok) throw new Error(`GitHub ${metodo} ${ruta}: ${r.status} ${await r.text()}`);
  return r.status === 204 ? null : r.json();
}
const issueAbierto = async (etiqueta) => (await github('GET', `/issues?state=open&labels=${etiqueta}&per_page=1`))[0] ?? null;
const ahoraTexto = () => new Date().toLocaleString('es-ES', { timeZone: 'Europe/Madrid', dateStyle: 'short', timeStyle: 'short' });

async function avisarCaida(fallos) {
  const abierto = await issueAbierto(ETIQUETA_CAIDA);
  if (fallos.length) {
    const texto = `Comprobado el ${ahoraTexto()} (hora de Madrid), tres intentos:\n\n${fallos.join('\n')}`;
    if (abierto) return void (await github('POST', `/issues/${abierto.number}/comments`, { body: `Sigue fallando.\n\n${texto}` }));
    await github('POST', '/issues', {
      title: 'La web o el servidor no responden',
      labels: [ETIQUETA_CAIDA],
      body: `${texto}\n\nEste issue lo abre la vigilancia (.github/workflows/vigilancia.yml) y se cierra solo cuando todo vuelve a funcionar.`
    });
  } else if (abierto) {
    await github('POST', `/issues/${abierto.number}/comments`, { body: `Vuelve a funcionar todo (${ahoraTexto()}).` });
    await github('PATCH', `/issues/${abierto.number}`, { state: 'closed', state_reason: 'completed' });
  }
}

// --- 2. errores nuevos ------------------------------------------------------

/** Solo lo que no puede traer texto de nadie: lo demás se cambia por "—". */
const seguro = {
  tipo: (v) => (['error', 'promesa', 'csp', 'sw'].includes(v) ? v : '—'),
  fuente: (v) => (/^(https?:\/\/localhost:\d+)?\/[\w./-]{0,80}(:\d{1,6}){0,2}$/.test(v ?? '') ? v : '—'),
  version: (v) => (/^\d{1,4}\.\d{1,4}\.\d{1,4}$/.test(v ?? '') ? v : '—'),
  navegador: (v) => (/^[A-Za-z ]{1,20}( en [A-Za-z]{1,12})?( \(instalada\))?$/.test(v ?? '') ? v : '—')
};
const MARCA = /<!-- hasta:(\d+) -->/g;

async function avisarErrores() {
  const abierto = await issueAbierto(ETIQUETA_ERRORES);
  // Desde lo último que se contó en el issue abierto o, si no hay, desde hace una hora.
  let desde = Date.now() - 3600_000;
  if (abierto) {
    const textos = [abierto.body, ...(await github('GET', `/issues/${abierto.number}/comments?per_page=100`)).map((c) => c.body)];
    for (const t of textos) for (const m of String(t ?? '').matchAll(MARCA)) desde = Math.max(desde, Number(m[1]));
  }
  const r = await pedir(`${API}/api/errores?desde=${desde}`, { headers: { Authorization: `Clave ${CLAVE_ERRORES}` } });
  if (!r.ok) throw new Error(`No se han podido leer los errores: ${r.status}`);
  const errores = (await r.json()).errores;
  console.log(`${errores.length} errores nuevos desde ${new Date(desde).toISOString()}`);
  if (!errores.length || SIN_ISSUES) return errores.forEach((e) => console.log(' ', e.veces, e.tipo, e.fuente, e.version, e.navegador, e.mensaje));
  const hasta = Math.max(...errores.map((e) => e.ultimo));
  const filas = errores.map((e) => `| ${Number(e.veces) | 0} | ${seguro.tipo(e.tipo)} | \`${seguro.fuente(e.fuente)}\` | ${seguro.version(e.version)} | ${seguro.navegador(e.navegador)} |`);
  const tabla = `| Veces | Tipo | Dónde | Versión | Navegador |\n|---:|---|---|---|---|\n${filas.join('\n')}\n\n<!-- hasta:${hasta} -->`;
  if (abierto) return void (await github('POST', `/issues/${abierto.number}/comments`, { body: `Más errores (${ahoraTexto()}):\n\n${tabla}` }));
  await github('POST', '/issues', {
    title: 'Errores nuevos en la web',
    labels: [ETIQUETA_ERRORES],
    body: `La web ha fallado en algún dispositivo (${ahoraTexto()}, hora de Madrid):\n\n${tabla}\n\n` +
      'Los mensajes no se ponen aquí (el repositorio es público): se leen en `GET /api/errores` con la clave ' +
      '(`Authorization: Clave …`, ver servidor/errores.js). Cierra el issue cuando esté resuelto; si vuelve a pasar, se abre otro.'
  });
}

// ---------------------------------------------------------------------------

const fallos = await comprobar();
if (!SIN_ISSUES) await avisarCaida(fallos);
if (CLAVE_ERRORES) await avisarErrores();
else console.log('Sin CLAVE_ERRORES: no se miran los errores de la web');
