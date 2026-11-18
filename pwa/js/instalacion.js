// Instalar la app desde la propia página (solo en la web; la app Android ya está instalada). El navegador siempre pide
// confirmación con su propio cuadro: la página solo ofrece el botón, y solo cuando el navegador puede instalarla.
//
// navigator.install() es la forma nueva; donde no existe, se usa el evento beforeinstallprompt de Chrome, que hay que
// guardar en cuanto llega (por eso este módulo se carga al arrancar). Sin ninguna de las dos (Safari, Firefox), no hay
// botón: el navegador ya ofrece su propia forma de instalarla.

const soportaInstall = typeof Navigator.prototype.install === 'function';
let aviso = null;
const oyentes = new Set();
const avisar = () => oyentes.forEach((f) => f());

addEventListener('beforeinstallprompt', (e) => {
  // Sin la barra que Chrome pone por su cuenta: se ofrece desde "Más".
  e.preventDefault();
  aviso = e;
  avisar();
});
addEventListener('appinstalled', () => {
  aviso = null;
  avisar();
});

/** Si ya se está usando como app instalada (y no en una pestaña del navegador). */
const instalada = () => matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;

export const sePuedeInstalar = () => !instalada() && (soportaInstall || aviso != null);

export const alCambiarInstalacion = (f) => (oyentes.add(f), () => oyentes.delete(f));

/**
 * Pide al navegador que la instale. Hay que llamarlo directamente desde el toque (la instalación necesita que lo haya
 * pedido el usuario). Devuelve 'instalada', 'cancelada' o 'error'.
 */
export async function instalar() {
  try {
    if (soportaInstall) {
      await navigator.install();
      return 'instalada';
    }
    if (!aviso) return 'error';
    // El evento solo sirve una vez: se suelta antes de esperar la respuesta.
    const evento = aviso;
    aviso = null;
    avisar();
    await evento.prompt();
    const { outcome } = await evento.userChoice;
    return outcome === 'accepted' ? 'instalada' : 'cancelada';
  } catch (e) {
    if (e?.name === 'AbortError') return 'cancelada';
    console.error(e);
    return 'error';
  }
}
