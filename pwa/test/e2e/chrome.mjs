// Un Chrome sin pantalla manejado por el protocolo de DevTools, sin dependencias (Node trae WebSocket). Lo justo para
// los recorridos de recorridos.test.mjs: abrir pestañas en perfiles separados (como dos dispositivos), evaluar código,
// esperar a que algo pase y apuntar todo error o violación de la política de seguridad.
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const RUTAS = [
  process.env.CHROME,
  '/usr/bin/google-chrome',
  '/usr/bin/google-chrome-stable',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
].filter(Boolean);

export const rutaChrome = () => RUTAS.find((r) => existsSync(r)) ?? null;

const espera = (ms) => new Promise((r) => setTimeout(r, ms));

// Lo que se apunta en cada documento antes de que cargue nada: violaciones de la CSP o de Trusted Types y errores.
const VIGIA = `
  window.__problemas = [];
  document.addEventListener('securitypolicyviolation', (e) => window.__problemas.push('CSP ' + e.effectiveDirective + ' ' + (e.blockedURI || '(en línea)') + ' ' + (e.sourceFile || '') + ':' + e.lineNumber));
  addEventListener('error', (e) => { if (!(e.target && e.target !== window && e.target.tagName) && !/^ResizeObserver loop/.test(e.message)) window.__problemas.push('ERROR ' + e.message + ' ' + (e.filename || '') + ':' + e.lineno); });
  addEventListener('unhandledrejection', (e) => window.__problemas.push('PROMESA ' + (e.reason && (e.reason.stack || e.reason.message) || e.reason)));
`;

export async function abrirChrome() {
  const ruta = rutaChrome();
  if (!ruta) throw new Error('No hay Chrome: pon su ruta en la variable CHROME');
  const perfil = mkdtempSync(join(tmpdir(), 'socios-e2e-'));
  const proceso = spawn(ruta, [
    '--headless=new', '--remote-debugging-port=0', `--user-data-dir=${perfil}`, '--no-first-run', '--no-default-browser-check',
    '--disable-gpu', '--disable-extensions', '--disable-background-networking', '--window-size=412,915', '--lang=es-ES'
  ], { stdio: 'ignore' });
  // Chrome escribe en el perfil el puerto que ha cogido.
  let linea = null;
  for (let i = 0; i < 100 && !linea; i++) {
    await espera(100);
    try {
      linea = readFileSync(join(perfil, 'DevToolsActivePort'), 'utf8').split('\n');
    } catch {
      // Aún no.
    }
  }
  if (!linea) throw new Error('Chrome no ha arrancado');
  const ws = new WebSocket(`ws://127.0.0.1:${linea[0].trim()}${linea[1].trim()}`);
  await new Promise((r, x) => {
    ws.addEventListener('open', r, { once: true });
    ws.addEventListener('error', x, { once: true });
  });

  let id = 0;
  const esperando = new Map();
  const oyentes = new Set();
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.id && esperando.has(m.id)) {
      const { r, x, method } = esperando.get(m.id);
      esperando.delete(m.id);
      if (m.error) x(new Error(`${method}: ${m.error.message}`));
      else r(m.result);
    } else if (m.method) oyentes.forEach((f) => f(m));
  });
  const enviar = (method, params = {}, sessionId) => new Promise((r, x) => {
    const n = ++id;
    esperando.set(n, { r, x, method });
    ws.send(JSON.stringify({ id: n, method, params, ...(sessionId && { sessionId }) }));
  });

  /** Una pestaña en un perfil propio (sin cookies ni datos de las demás): un dispositivo distinto. */
  async function nuevaPestana({ separada = true } = {}) {
    const { browserContextId } = separada ? await enviar('Target.createBrowserContext') : {};
    const { targetId } = await enviar('Target.createTarget', { url: 'about:blank', ...(browserContextId && { browserContextId }) });
    const { sessionId } = await enviar('Target.attachToTarget', { targetId, flatten: true });
    const cdp = (method, params) => enviar(method, params, sessionId);
    const excepciones = [];
    oyentes.add((m) => {
      if (m.sessionId !== sessionId) return;
      if (m.method === 'Runtime.exceptionThrown') excepciones.push(m.params.exceptionDetails?.exception?.description ?? m.params.exceptionDetails?.text);
    });
    await cdp('Page.enable');
    await cdp('Runtime.enable');
    await cdp('Page.addScriptToEvaluateOnNewDocument', { source: VIGIA });

    const pestana = {
      cdp,
      excepciones,
      /** Evalúa [expresion] en la página (esperando su promesa) y devuelve el valor. */
      async evaluar(expresion) {
        const r = await cdp('Runtime.evaluate', { expression: expresion, awaitPromise: true, returnByValue: true, userGesture: true });
        if (r.exceptionDetails) throw new Error(`En la página: ${r.exceptionDetails.exception?.description ?? r.exceptionDetails.text}`);
        return r.result.value;
      },
      /** Espera a que [expresion] dé algo verdadero (y lo devuelve). */
      async esperar(expresion, { ms = 15_000, que = expresion } = {}) {
        const fin = Date.now() + ms;
        let ultimo;
        while (Date.now() < fin) {
          try {
            ultimo = await pestana.evaluar(expresion);
            if (ultimo) return ultimo;
          } catch (e) {
            ultimo = e.message;
          }
          await espera(150);
        }
        throw new Error(`No ha pasado a tiempo: ${que} (último: ${JSON.stringify(ultimo)})`);
      },
      async ir(url) {
        await cdp('Page.navigate', { url });
        await pestana.esperar('document.readyState === "complete"');
      },
      async recargar() {
        await cdp('Page.reload', { ignoreCache: false });
        await espera(300);
        await pestana.esperar('document.readyState === "complete"');
      },
      /** Sin conexión (o con ella otra vez). */
      async conexion(hay) {
        await cdp('Network.enable');
        await cdp('Network.emulateNetworkConditions', { offline: !hay, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
      },
      /** Errores y violaciones apuntados en esta página (y los vacía). */
      async problemas() {
        const lista = [...(await pestana.evaluar('window.__problemas ?? []')), ...excepciones.splice(0)];
        await pestana.evaluar('window.__problemas = []');
        return lista;
      }
    };
    return pestana;
  }

  return {
    nuevaPestana,
    async cerrar() {
      try {
        await enviar('Browser.close');
      } catch {
        // Ya cerrado.
      }
      ws.close();
      await new Promise((r) => (proceso.exitCode !== null ? r() : proceso.once('exit', r)));
      for (let i = 0; i < 10; i++) {
        try {
          rmSync(perfil, { recursive: true, force: true });
          break;
        } catch {
          await espera(200);
        }
      }
    }
  };
}
