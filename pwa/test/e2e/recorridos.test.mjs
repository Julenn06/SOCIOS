// Recorridos completos de la web en un Chrome de verdad, contra el servidor de la cuenta (en memoria): registrarse,
// socios, paneles, PDF, dos dispositivos sincronizando, llave de acceso, sin conexión y errores. Falla si en algún
// momento hay un error de JavaScript o la política de seguridad (CSP, Trusted Types) bloquea algo, y si un control
// no tiene nombre accesible o es demasiado pequeño para el dedo.
//   node --test pwa/test/e2e/            (Chrome en la ruta de siempre o en la variable CHROME)
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { abrirChrome, rutaChrome } from './chrome.mjs';
import { servirWeb } from './servidor-web.mjs';

const PWA = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const WEB = 'http://localhost:8123';
const API = 'http://localhost:3000';
const CLAVE = 'clave de los recorridos';
const NOMBRE = 'Recorrido';
const CONTRASENA = 'una contraseña de prueba';

process.env.NODE_ENV = 'test';
process.env.CLAVE_REGISTRO = CLAVE;
process.env.ALLOWED_ORIGIN = WEB;
process.env.DB_NAME = '';
process.env.MYSQL_DATABASE = '';

// En la integración continua tiene que haber Chrome; en local, sin él se salta.
const sinChrome = !rutaChrome() && !process.env.CI ? 'No hay Chrome (pon su ruta en CHROME)' : false;

let chrome, web, api, a, b;

before(async () => {
  if (sinChrome) return;
  const { server } = await import('../../../servidor/server.js');
  api = server;
  await new Promise((r) => api.listen(3000, r));
  web = await servirWeb(PWA, 8123);
  chrome = await abrirChrome();
});

after(async () => {
  await chrome?.cerrar();
  web?.close();
  api?.close();
});

// --- ayudas en la página ---

const listo = `!document.documentElement.classList.contains('cargando') && !document.querySelector('dialog.pantalla-cuenta[open]')`;
const sincronizado = `(async () => { const n = await import('/js/nube.js'); await n.sincronizar(); return n.cuenta.estado === 'AL_DIA'; })()`;
const escribir = `const escribir = (i, v) => { i.value = v; i.dispatchEvent(new Event('input', { bubbles: true })); };`;

/** Que no haya ningún error ni nada bloqueado por la política de seguridad desde la última vez. */
async function sinProblemas(p, donde) {
  assert.deepEqual(await p.problemas(), [], `Problemas en ${donde}`);
}

/**
 * Accesibilidad de lo que se ve: todo control con nombre (lo que lee el lector de pantalla) y de al menos 24×24 px
 * (WCAG 2.5.8). Lo que queda inerte detrás de un diálogo no cuenta.
 */
async function accesible(p, donde) {
  const { nodes } = await p.cdp('Accessibility.getFullAXTree');
  const ROLES = new Set(['button', 'link', 'textbox', 'checkbox', 'switch', 'combobox', 'menuitem', 'tab', 'radio', 'slider', 'spinbutton', 'searchbox']);
  const sinNombre = nodes
    .filter((n) => !n.ignored && ROLES.has(n.role?.value) && !String(n.name?.value ?? '').trim())
    .map((n) => `${n.role.value} sin nombre (nodo ${n.backendDOMNodeId})`);
  const pequenos = await p.evaluar(`(() => {
    const visibles = [...document.querySelectorAll('button, a[href], input:not([type=hidden]), select, textarea, [role=button], [role=switch], [role=tab], [role=radio]')]
      .filter((el) => !el.closest('[inert], .oculto') && el.checkVisibility?.({ checkOpacity: true, checkVisibilityCSS: true }));
    const arriba = [...document.querySelectorAll('dialog[open]')].at(-1);
    return visibles
      .filter((el) => !arriba || arriba.contains(el))
      .map((el) => [el, el.getBoundingClientRect()])
      .filter(([el, r]) => r.width > 0 && (r.width < 24 || r.height < 24) && !(el.tagName === 'INPUT' && ['checkbox', 'radio'].includes(el.type) && el.labels?.length))
      .map(([el, r]) => el.tagName.toLowerCase() + '.' + [...el.classList].join('.') + ' "' + (el.getAttribute('aria-label') || el.textContent).trim().slice(0, 30) + '" ' + Math.round(r.width) + 'x' + Math.round(r.height));
  })()`);
  assert.deepEqual([...sinNombre, ...pequenos], [], `Accesibilidad en ${donde}`);
}

async function registrarse(p) {
  await p.evaluar(`(async () => {
    ${escribir}
    const w = (t) => new Promise((r) => setTimeout(r, t));
    const d = document.querySelector('dialog.pantalla-cuenta');
    [...d.querySelectorAll('.opcion')][1].click();
    await w(200);
    escribir(d.querySelector('form input'), ${JSON.stringify(CLAVE)});
    d.querySelector('form').requestSubmit();
  })()`);
  await p.esperar(`document.querySelectorAll('dialog.pantalla-cuenta form input').length === 3`, { que: 'el formulario de registro' });
  await p.evaluar(`(() => {
    ${escribir}
    const ins = document.querySelectorAll('dialog.pantalla-cuenta form input');
    escribir(ins[0], ${JSON.stringify(NOMBRE)});
    escribir(ins[1], ${JSON.stringify(CONTRASENA)});
    escribir(ins[2], ${JSON.stringify(CONTRASENA)});
    document.querySelector('dialog.pantalla-cuenta form').requestSubmit();
  })()`);
  await p.esperar(listo, { que: 'la app después de registrarse' });
}

async function entrarConContrasena(p) {
  await p.evaluar(`(() => {
    ${escribir}
    const f = document.querySelector('dialog.pantalla-cuenta form');
    const ins = f.querySelectorAll('input');
    escribir(ins[0], ${JSON.stringify(NOMBRE.toUpperCase())});
    escribir(ins[1], ${JSON.stringify(CONTRASENA)});
    f.requestSubmit();
  })()`);
  await p.esperar(listo, { que: 'la app después de entrar' });
}

const cuantosSocios = `(async () => (await import('/js/estado.js')).todosLosSocios().length)()`;
const socio = (id) => `(async () => (await import('/js/estado.js')).todosLosSocios().find((s) => s.id === ${JSON.stringify(id)}))()`;

// --- recorridos ---

test('registrarse y ver la app sin errores', { skip: sinChrome, timeout: 60_000 }, async () => {
  a = await chrome.nuevaPestana();
  await a.ir(`${WEB}/`);
  await a.esperar(`!!document.querySelector('dialog.pantalla-cuenta[open]')`, { que: 'la pantalla de entrar' });
  await accesible(a, 'la pantalla de entrar');
  await registrarse(a);
  await a.esperar(sincronizado, { que: 'la primera sincronización' });
  await accesible(a, 'la pantalla principal');
  await sinProblemas(a, 'el registro');
});

test('socios, todos los paneles y el PDF, sin nada bloqueado por la política de seguridad', { skip: sinChrome, timeout: 90_000 }, async () => {
  const hechos = await a.evaluar(`(async () => {
    const w = (t) => new Promise((r) => setTimeout(r, t));
    const e = await import('/js/estado.js');
    const semana = e.getSemanaActual();
    // Con HTML en el nombre y las notas: tiene que verse como texto, sin ejecutar nada.
    for (let i = 0; i < 6; i++) {
      await e.guardarSocio({ id: 'p' + i, colaboracion: [12, 18, 30, 45, 20, 25][i], hecho: i % 2 === 0, dia: (i % 5) + 1, semanaKey: semana,
        nombreSocio: 'Socio <b>' + i + '</b>', notas: i === 1 ? '<img src=x onerror="window.__xss=1">' : '', timestamp: Date.now() + i }, true);
    }
    await w(800);
    return e.todosLosSocios().length;
  })()`);
  assert.equal(hechos, 6);
  await a.esperar(sincronizado, { que: 'subir los socios' });

  const paneles = [
    ['Más', 'hojas/mas.js', 'm.abrirMas()'],
    ['Seguridad', 'hojas/seguridad.js', 'm.abrirSeguridad()'],
    ['Cuenta', 'hojas/cuenta.js', 'm.abrirAjustesCuenta()'],
    ['Copias', 'hojas/copias.js', 'm.abrirCopias()'],
    ['Histórico', 'hojas/historico.js', 'm.abrirHistorico()'],
    ['Lista de socios', 'hojas/lista-socios.js', "m.abrirListaSocios('PENDIENTES')"],
    ['Socio nuevo', 'hojas/socio.js', 'm.abrirFormularioSocio({ semanaKey: e.getSemanaActual(), dia: 1 })'],
    ['Editar socio', 'hojas/socio.js', 'm.abrirFormularioSocio({ socio: e.todosLosSocios()[1], semanaKey: e.getSemanaActual(), dia: 2 })'],
    ['Bonus', 'hojas/ajustes-semana.js', 'm.abrirBonus(e.ui().semanaKey, e.ui().bonoInfoSemana)'],
    ['Cobro', 'hojas/ajustes-semana.js', 'm.abrirCobro(e.ui())']
  ];
  for (const [nombre, modulo, abrir] of paneles) {
    await a.evaluar(`(async () => {
      const e = await import('/js/estado.js');
      const m = await import('/js/${modulo}');
      ${abrir};
      await new Promise((r) => setTimeout(r, 900));
    })()`);
    await accesible(a, `el panel ${nombre}`);
    await a.evaluar(`(async () => { (await import('/js/paneles.js')).cerrarTodo(); await new Promise((r) => setTimeout(r, 600)); })()`);
    await sinProblemas(a, `el panel ${nombre}`);
  }
  assert.equal(await a.evaluar('window.__xss ?? null'), null, 'Se ha ejecutado HTML de una nota');
  assert.equal(await a.evaluar(`!!document.querySelector('img[src="x"]')`), false, 'Se ha pintado HTML de una nota');

  // El PDF: el generador y sus fuentes se piden la primera vez y el service worker los guarda aparte.
  await a.esperar('!!navigator.serviceWorker.controller', { que: 'el service worker' });
  const pdf = await a.evaluar(`(async () => {
    const e = await import('/js/estado.js');
    const { generarPdf } = await import('/js/pdf.js');
    const blob = await generarPdf(e.cargarDatos({ tipo: 'HISTORICO' }), { tipo: 'HISTORICO' });
    const cabecera = new TextDecoder().decode((await blob.arrayBuffer()).slice(0, 5));
    return { tamano: blob.size, cabecera };
  })()`);
  assert.equal(pdf.cabecera, '%PDF-');
  assert.ok(pdf.tamano > 20_000, `PDF de ${pdf.tamano} bytes`);
  await a.esperar(`(async () => (await (await caches.open('pdf-socios')).keys()).length === 5)()`, { que: 'el PDF guardado para sin conexión' });
  await sinProblemas(a, 'el PDF');
});

test('dos dispositivos cambian campos distintos del mismo socio sin conexión: se conservan los dos', { skip: sinChrome, timeout: 90_000 }, async () => {
  b = await chrome.nuevaPestana();
  await b.ir(`${WEB}/`);
  await b.esperar(`!!document.querySelector('dialog.pantalla-cuenta[open]')`);
  await entrarConContrasena(b);
  await b.esperar(`(async () => (await import('/js/estado.js')).todosLosSocios().length === 6)()`, { que: 'los socios en el otro dispositivo' });

  // El primero, sin conexión, cambia lo que paga; después, el segundo cambia el nombre (más reciente).
  await a.conexion(false);
  await a.evaluar(`(async () => { const e = await import('/js/estado.js'); await e.guardarSocio({ ...e.todosLosSocios().find((s) => s.id === 'p0'), colaboracion: 99 }, false); })()`);
  await new Promise((r) => setTimeout(r, 30));
  await b.evaluar(`(async () => { const e = await import('/js/estado.js'); await e.guardarSocio({ ...e.todosLosSocios().find((s) => s.id === 'p0'), nombreSocio: 'Cambiado en el otro' }, false); })()`);
  await b.esperar(sincronizado);
  await a.conexion(true);
  await a.esperar(sincronizado, { que: 'sincronizar al volver la conexión' });
  await b.esperar(sincronizado);
  for (const [p, quien] of [[a, 'el primero'], [b, 'el segundo']]) {
    const s = await p.esperar(`(async () => { const s = await ${socio('p0')}; return s.colaboracion === 99 && s.nombreSocio === 'Cambiado en el otro' && s; })()`, { que: `los dos cambios en ${quien}` });
    assert.equal(s.colaboracion, 99);
  }
  await sinProblemas(a, 'la sincronización (primero)');
  await sinProblemas(b, 'la sincronización (segundo)');
});

test('crear una llave de acceso y entrar con ella sin contraseña', { skip: sinChrome, timeout: 90_000 }, async () => {
  // Un autenticador de mentira con huella, como el de un móvil.
  await b.cdp('WebAuthn.enable', { enableUI: false });
  await b.cdp('WebAuthn.addVirtualAuthenticator', {
    options: { protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true }
  });
  await b.evaluar(`(async () => (await import('/js/nube.js')).crearLlave())()`);
  await b.evaluar(`(async () => { (await import('/js/hojas/seguridad.js')).abrirSeguridad(); })()`);
  await b.esperar(`document.querySelectorAll('[data-id="llaves"] .fila-seguridad').length === 1`, { que: 'la llave en Seguridad' });
  await accesible(b, 'Seguridad con una llave');
  await b.evaluar(`(async () => { (await import('/js/paneles.js')).cerrarTodo(); await new Promise((r) => setTimeout(r, 600)); })()`);

  await b.evaluar(`(async () => (await import('/js/nube.js')).salir())()`);
  await b.esperar(`!!document.querySelector('dialog.pantalla-cuenta[open] .cuenta-llave button')`, { que: 'el botón de la llave' });
  await b.evaluar(`document.querySelector('dialog.pantalla-cuenta .cuenta-llave button').click()`);
  await b.esperar(listo, { que: 'entrar con la llave' });
  assert.equal(await b.evaluar(`(async () => (await import('/js/nube.js')).cuenta.sesion?.nombre)()`), NOMBRE);
  await b.esperar(`(async () => (await import('/js/estado.js')).todosLosSocios().length === 6)()`, { que: 'los datos después de entrar con la llave' });
  const actividad = await b.evaluar(`(async () => (await (await import('/js/nube.js')).leerActividad()).map((x) => x.tipo))()`);
  assert.deepEqual(actividad.slice(0, 2), ['llave', 'llave_nueva']);
  await sinProblemas(b, 'la llave de acceso');
});

test('sin conexión la app se abre igual y saca el PDF', { skip: sinChrome, timeout: 60_000 }, async () => {
  await a.conexion(false);
  await a.recargar();
  await a.esperar(listo, { que: 'la app sin conexión' });
  assert.equal(await a.evaluar(cuantosSocios), 6);
  const tamano = await a.evaluar(`(async () => {
    const e = await import('/js/estado.js');
    return (await (await import('/js/pdf.js')).generarPdf(e.cargarDatos({ tipo: 'HISTORICO' }), { tipo: 'HISTORICO' })).size;
  })()`);
  assert.ok(tamano > 20_000);
  await a.conexion(true);
  await sinProblemas(a, 'sin conexión');
});

test('los errores llegan al servidor sin datos personales, y en todo el recorrido no ha habido ninguno más', { skip: sinChrome, timeout: 30_000 }, async () => {
  await a.evaluar(`(async () => (await import('/js/errores.js')).anotarError({ tipo: 'error', mensaje: 'Prueba con "Juan Pérez"', fuente: location.origin + '/js/prueba.js?token=1', linea: 3 }))()`);
  const leer = async () => (await (await fetch(`${API}/api/errores`, { headers: { Authorization: `Clave ${CLAVE}` } })).json()).errores;
  let errores = [];
  for (let i = 0; i < 40 && !errores.some((e) => e.mensaje.startsWith('Prueba')); i++) {
    await new Promise((r) => setTimeout(r, 250));
    errores = await leer();
  }
  const prueba = errores.find((e) => e.mensaje === 'Prueba con "…"');
  assert.ok(prueba, `No ha llegado: ${JSON.stringify(errores)}`);
  assert.equal(prueba.fuente, `${WEB}/js/prueba.js:3:0`);
  assert.match(prueba.navegador, /^Chrome/);
  // Lo único que ha llegado es la prueba: ni la web ni el navegador (report-uri) han contado otro fallo.
  assert.deepEqual(errores.filter((e) => e !== prueba), []);
});
