// Que el navegador no borre los datos (solo en la web; en Android son de la app y nadie los toca).
//
// Sin pedirlo, los datos de una web son "provisionales": Chrome puede borrarlos si el móvil se queda sin espacio y
// Safari los borra tras 7 días sin abrir la web si no está en la pantalla de inicio. navigator.storage.persist() pide
// que sean permanentes. Chrome lo concede sin preguntar si la app está instalada o se usa a menudo; Firefox pregunta.

const almacen = navigator.storage;
export const sePuedeProteger = typeof almacen?.persist === 'function' && typeof almacen?.persisted === 'function';

const oyentes = new Set();
export const alCambiarProteccion = (f) => (oyentes.add(f), () => oyentes.delete(f));

/** { protegidos, ocupado (bytes o null) }. */
export async function estadoDatos() {
  const [protegidos, estimacion] = await Promise.all([
    almacen.persisted().catch(() => false),
    almacen.estimate?.().catch(() => null) ?? null
  ]);
  return { protegidos, ocupado: estimacion?.usage ?? null };
}

/** Pide que sean permanentes. Devuelve si lo son. */
export async function protegerDatos() {
  if (!sePuedeProteger) return false;
  try {
    const protegidos = (await almacen.persisted()) || (await almacen.persist());
    oyentes.forEach((f) => f(protegidos));
    return protegidos;
  } catch (e) {
    console.warn('No se ha podido pedir almacenamiento permanente:', e);
    return false;
  }
}
