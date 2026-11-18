// La base de datos (Room en la app): socios, bonos ajustados a mano y lo cobrado, en IndexedDB. Todo se carga en
// memoria al abrir (son pocos miles de registros como mucho) y cada cambio se escribe en una transacción.

const NOMBRE = 'socios';
const VERSION = 1;

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
  const [socios, bonos, cobros] = await Promise.all([leerTodo('socios'), leerTodo('bonos'), leerTodo('cobros')]);
  return { socios, bonos, cobros };
}

/**
 * Aplica [cambios] en una sola transacción: o se guarda todo o nada. Cada cambio: { almacen, poner } o
 * { almacen, borrar: clave }.
 */
export function escribir(cambios) {
  return new Promise((resolver, rechazar) => {
    const almacenes = [...new Set(cambios.map((c) => c.almacen))];
    const tx = db.transaction(almacenes, 'readwrite');
    for (const c of cambios) {
      const almacen = tx.objectStore(c.almacen);
      if ('borrar' in c) almacen.delete(c.borrar);
      else almacen.put(c.poner);
    }
    tx.oncomplete = () => resolver();
    tx.onerror = () => rechazar(tx.error);
    tx.onabort = () => rechazar(tx.error ?? new Error('Transacción cancelada'));
  });
}

// --- Preferencias (SharedPreferences "ajustes") ---

const PREFIJO = 'socios.';

export const prefs = {
  leer(clave, porDefecto = null) {
    try {
      const v = localStorage.getItem(PREFIJO + clave);
      return v == null ? porDefecto : JSON.parse(v);
    } catch {
      return porDefecto;
    }
  },
  guardar(clave, valor) {
    try {
      if (valor == null) localStorage.removeItem(PREFIJO + clave);
      else localStorage.setItem(PREFIJO + clave, JSON.stringify(valor));
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
