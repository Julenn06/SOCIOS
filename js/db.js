// La base de datos (Room en la app): socios, bonos ajustados a mano y lo cobrado, en IndexedDB. Todo se carga en
// memoria al abrir (son pocos miles de registros como mucho) y cada cambio se escribe en una transacción.

const NOMBRE = 'socios';
// 2: el almacén "pendientes" (lo que falta por subir a la cuenta, ver nube.js).
const VERSION = 2;

let db = null;

function abrir() {
  return new Promise((resolver, rechazar) => {
    const peticion = indexedDB.open(NOMBRE, VERSION);
    peticion.onupgradeneeded = () => {
      const d = peticion.result;
      if (!d.objectStoreNames.contains('socios')) {
        const socios = d.createObjectStore('socios', { keyPath: 'id' });
        socios.createIndex('semanaKey', 'semanaKey');
      }
      if (!d.objectStoreNames.contains('bonos')) d.createObjectStore('bonos', { keyPath: 'semanaKey' });
      if (!d.objectStoreNames.contains('cobros')) d.createObjectStore('cobros', { keyPath: 'semanaKey' });
      if (!d.objectStoreNames.contains('pendientes')) d.createObjectStore('pendientes', { keyPath: 'id' });
    };
    peticion.onsuccess = () => resolver(peticion.result);
    peticion.onerror = () => rechazar(peticion.error);
    peticion.onblocked = () => rechazar(new Error('La base de datos está abierta en otra pestaña con una versión anterior'));
  });
}

function leerTodo(almacen) {
  return new Promise((resolver, rechazar) => {
    const p = db.transaction(almacen, 'readonly').objectStore(almacen).getAll();
    p.onsuccess = () => resolver(p.result);
    p.onerror = () => rechazar(p.error);
  });
}

/** Abre la base de datos y devuelve todo lo guardado. */
export async function cargar() {
  db = await abrir();
  // Que el navegador no la borre por falta de espacio sin preguntar.
  navigator.storage?.persist?.().catch(() => {});
  return leerTodoGuardado();
}

/** Todo lo guardado, otra vez (lo ha cambiado otra ventana). */
export async function leerTodoGuardado() {
  const [socios, bonos, cobros] = await Promise.all([leerTodo('socios'), leerTodo('bonos'), leerTodo('cobros')]);
  return { socios, bonos, cobros };
}

/** La clave de cada registro en su almacén. */
export const CLAVES = { socios: 'id', bonos: 'semanaKey', cobros: 'semanaKey' };
const idPendiente = (almacen, clave) => `${almacen}:${clave}`;

const oyentesLocales = new Set();
/** Para la sincronización: algo ha cambiado aquí y hay que subirlo. */
export const alCambiarLocal = (f) => (oyentesLocales.add(f), () => oyentesLocales.delete(f));
const avisarLocal = () => oyentesLocales.forEach((f) => f());

function terminar(tx, resolver, rechazar) {
  tx.oncomplete = () => resolver();
  tx.onerror = () => rechazar(tx.error);
  tx.onabort = () => rechazar(tx.error ?? new Error('Transacción cancelada'));
}

/**
 * Aplica [cambios] en una sola transacción: o se guarda todo o nada. Cada cambio: { almacen, poner } o
 * { almacen, borrar: clave }. En la misma transacción queda anotado como pendiente de subir a la cuenta, con la hora
 * del cambio: así no se pierde aunque se cierre la app sin conexión.
 */
export function escribir(cambios) {
  return new Promise((resolver, rechazar) => {
    const almacenes = [...new Set(cambios.map((c) => c.almacen)), 'pendientes'];
    const tx = db.transaction(almacenes, 'readwrite');
    const pendientes = tx.objectStore('pendientes');
    const ahora = Date.now();
    for (const c of cambios) {
      const almacen = tx.objectStore(c.almacen);
      const clave = 'borrar' in c ? c.borrar : c.poner[CLAVES[c.almacen]];
      if ('borrar' in c) almacen.delete(c.borrar);
      else almacen.put(c.poner);
      pendientes.put({ id: idPendiente(c.almacen, clave), almacen: c.almacen, clave, modificado: ahora });
    }
    terminar(tx, () => {
      resolver();
      avisarLocal();
    }, rechazar);
  });
}

export function leerPendientes() {
  return leerTodo('pendientes');
}

/** Un registro (o undefined). */
export function leer(almacen, clave) {
  return new Promise((resolver, rechazar) => {
    const p = db.transaction(almacen, 'readonly').objectStore(almacen).get(clave);
    p.onsuccess = () => resolver(p.result);
    p.onerror = () => rechazar(p.error);
  });
}

/**
 * Lo que llega de la cuenta, en una transacción: [registros] son { almacen, clave, poner | null, modificado } y
 * [enviados] los pendientes que se acaban de subir ({ id, modificado }). Un registro no se aplica si aquí hay un
 * cambio pendiente más reciente (se subirá y ganará); los pendientes subidos que no han vuelto a cambiar se quitan.
 * Devuelve cuántos registros se han aplicado.
 */
export function aplicarRemoto(registros, enviados) {
  return new Promise((resolver, rechazar) => {
    const tx = db.transaction(['socios', 'bonos', 'cobros', 'pendientes'], 'readwrite');
    const pendientes = tx.objectStore('pendientes');
    let aplicados = 0;
    const lectura = pendientes.getAll();
    lectura.onsuccess = () => {
      const actuales = new Map(lectura.result.map((p) => [p.id, p]));
      const subidos = new Map(enviados.map((e) => [e.id, e.modificado]));
      for (const e of enviados) {
        if (actuales.get(e.id)?.modificado === e.modificado) {
          pendientes.delete(e.id);
          actuales.delete(e.id);
        }
      }
      for (const r of registros) {
        const id = idPendiente(r.almacen, r.clave);
        // El servidor devuelve también lo que se le acaba de subir: es lo mismo que ya hay aquí. Sin escribirlo ni
        // contarlo, para no releer los datos y repintar la pantalla después de cada cambio propio.
        if (subidos.get(id) === r.modificado) continue;
        const p = actuales.get(id);
        if (p && p.modificado > r.modificado) continue;
        if (p) pendientes.delete(id);
        const almacen = tx.objectStore(r.almacen);
        if (r.poner) almacen.put(r.poner);
        else almacen.delete(r.clave);
        aplicados++;
      }
    };
    terminar(tx, () => resolver(aplicados), rechazar);
  });
}

/**
 * Al entrar en una cuenta con datos ya guardados en este dispositivo: todo pasa a pendiente de subir, con una hora
 * muy antigua para que, si la cuenta ya tiene ese mismo registro, gane el de la cuenta.
 */
export function marcarTodoPendiente(modificado = 1) {
  return new Promise((resolver, rechazar) => {
    const tx = db.transaction(['socios', 'bonos', 'cobros', 'pendientes'], 'readwrite');
    const pendientes = tx.objectStore('pendientes');
    // Los cambios ya pendientes (hechos aquí sin sesión) son más recientes: se quedan como están.
    const yaPendientes = pendientes.getAllKeys();
    yaPendientes.onsuccess = () => {
      const ya = new Set(yaPendientes.result);
      for (const almacen of Object.keys(CLAVES)) {
        const p = tx.objectStore(almacen).getAllKeys();
        p.onsuccess = () => {
          for (const clave of p.result) {
            const id = idPendiente(almacen, clave);
            if (!ya.has(id)) pendientes.put({ id, almacen, clave, modificado });
          }
        };
      }
    };
    terminar(tx, resolver, rechazar);
  });
}

/** Al cerrar sesión: fuera todos los datos de este dispositivo. */
export function borrarTodo() {
  return new Promise((resolver, rechazar) => {
    const tx = db.transaction(['socios', 'bonos', 'cobros', 'pendientes'], 'readwrite');
    for (const almacen of ['socios', 'bonos', 'cobros', 'pendientes']) tx.objectStore(almacen).clear();
    terminar(tx, resolver, rechazar);
  });
}

// --- Preferencias (SharedPreferences "ajustes") ---

const PREFIJO = 'socios.';

/** Las preferencias que viajan con la cuenta (las demás, como el bloqueo, son de cada dispositivo). */
export const AJUSTES_CUENTA = ['tema', 'ultimaCuota', 'pendientesOcultos'];

export const prefs = {
  leer(clave, porDefecto = null) {
    try {
      const v = localStorage.getItem(PREFIJO + clave);
      return v == null ? porDefecto : JSON.parse(v);
    } catch {
      return porDefecto;
    }
  },
  guardar(clave, valor, { remoto = false } = {}) {
    try {
      if (valor == null) localStorage.removeItem(PREFIJO + clave);
      else localStorage.setItem(PREFIJO + clave, JSON.stringify(valor));
      if (!remoto && AJUSTES_CUENTA.includes(clave)) {
        prefs.guardar('ajustesPendientes', { ...prefs.leer('ajustesPendientes', {}), [clave]: Date.now() });
        avisarLocal();
      }
    } catch {
      // Sin almacenamiento (modo privado estricto): la preferencia dura lo que la sesión.
    }
  }
};

/** Lo que sobrevive a recargar la página pero no a cerrarla (el SavedStateHandle de la app). */
export const sesion = {
  leer(clave, porDefecto = null) {
    try {
      const v = sessionStorage.getItem(PREFIJO + clave);
      return v == null ? porDefecto : JSON.parse(v);
    } catch {
      return porDefecto;
    }
  },
  guardar(clave, valor) {
    try {
      if (valor == null) sessionStorage.removeItem(PREFIJO + clave);
      else sessionStorage.setItem(PREFIJO + clave, JSON.stringify(valor));
    } catch {
      // Igual que arriba.
    }
  }
};
