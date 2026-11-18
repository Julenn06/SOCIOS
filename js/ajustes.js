// ajustes/Tema.kt, ajustes/Bloqueo.kt y ActualizacionesViewModel.kt, adaptados a la web: el tema de la app, el
// bloqueo con la huella, la cara o el PIN del dispositivo (WebAuthn) y las versiones nuevas (service worker).
import { prefs } from './db.js';
import { VERSION } from './version.js';

// --- Tema ---

export const TEMAS = [
  { id: 'SISTEMA', nombre: 'Sistema', icono: 'contrast' },
  { id: 'CLARO', nombre: 'Claro', icono: 'light_mode' },
  { id: 'OSCURO', nombre: 'Oscuro', icono: 'dark_mode' }
];

const oscuroSistema = matchMedia('(prefers-color-scheme: dark)');
export const temaGuardado = () => prefs.leer('tema', null) ?? 'SISTEMA';

/** Si se ve en oscuro: el tema forzado o, con "Sistema", el del dispositivo. */
export const esOscuro = (tema = temaGuardado()) => tema === 'OSCURO' || (tema === 'SISTEMA' && oscuroSistema.matches);
let oscuroPintado = esOscuro();

/**
 * Refleja el tema en el <meta name="color-scheme"> (la hoja de estilos sigue a ese <meta>: con "light dark", al
 * sistema) y en el color de la barra del navegador; las vistas dibujadas a mano se repintan.
 */
export function aplicarTema(tema = temaGuardado()) {
  const forzado = tema === 'OSCURO' ? 'dark' : tema === 'CLARO' ? 'light' : null;
  document.querySelector('meta[name="color-scheme"]').content = forzado ?? 'light dark';
  document.querySelectorAll('meta[name="theme-color"]').forEach((m) => {
    const delSistema = m.media.includes('dark') ? '#0B100E' : '#F2F4F0';
    m.content = forzado ? (forzado === 'dark' ? '#0B100E' : '#F2F4F0') : delSistema;
  });
  const oscuro = esOscuro(tema);
  if (oscuro !== oscuroPintado) {
    oscuroPintado = oscuro;
    document.dispatchEvent(new Event('socios:tema'));
  }
}

/** "Sistema" no se guarda: así la app vuelve a seguir al dispositivo. */
export function elegirTema(tema) {
  prefs.guardar('tema', tema === 'SISTEMA' ? null : tema);
  aplicarTema(tema);
}

oscuroSistema.addEventListener('change', () => aplicarTema());
// Cambiado en otra pestaña o en la app instalada.
addEventListener('storage', (e) => e.key === 'socios.tema' && aplicarTema());

// --- Bloqueo ---

/** Tras más de esto fuera de la app, al volver se vuelve a pedir la huella o el PIN. */
const ESPERA_MS = 60_000;
const aBase64 = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf)));
const deBase64 = (t) => Uint8Array.from(atob(t), (c) => c.charCodeAt(0));
const aleatorio = (n) => crypto.getRandomValues(new Uint8Array(n));

export const bloqueo = {
  desbloqueada: false,
  fueraDesde: null,
  oyentes: new Set(),
  get activo() {
    return prefs.leer('bloqueo', false) === true && !!prefs.leer('bloqueoCredencial', null);
  },
  get bloqueada() {
    return this.activo && !this.desbloqueada;
  },
  alCambiar(f) {
    this.oyentes.add(f);
    return () => this.oyentes.delete(f);
  },
  avisar() {
    this.oyentes.forEach((f) => f(this.bloqueada));
  },
  desbloquear() {
    this.desbloqueada = true;
    this.fueraDesde = null;
    this.avisar();
  },
  alSalir() {
    this.fueraDesde = Date.now();
  },
  alVolver() {
    const desde = this.fueraDesde;
    if (desde == null) return;
    this.fueraDesde = null;
    if (Date.now() - desde >= ESPERA_MS) this.desbloqueada = false;
    this.avisar();
  }
};

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'hidden') bloqueo.alSalir();
  else bloqueo.alVolver();
});

/** 'OK', 'SIN_BLOQUEO' (el dispositivo no tiene con qué identificarse) o 'NO_DISPONIBLE'. */
export async function disponibilidadBloqueo() {
  if (!window.isSecureContext || !window.PublicKeyCredential || !navigator.credentials) return 'NO_DISPONIBLE';
  try {
    return (await PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable()) ? 'OK' : 'SIN_BLOQUEO';
  } catch {
    return 'NO_DISPONIBLE';
  }
}

/** Crea la llave del dispositivo para la app (pide la huella o el PIN). Devuelve true si el usuario la confirma. */
export async function crearLlave() {
  try {
    const credencial = await navigator.credentials.create({
      publicKey: {
        challenge: aleatorio(32),
        rp: { name: 'Gestión de Socios' },
        user: { id: aleatorio(16), name: 'socios', displayName: 'Gestión de Socios' },
        pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
        authenticatorSelection: { authenticatorAttachment: 'platform', userVerification: 'required', residentKey: 'discouraged' },
        timeout: 60_000,
        attestation: 'none'
      }
    });
    if (!credencial) return false;
    prefs.guardar('bloqueoCredencial', aBase64(credencial.rawId));
    return true;
  } catch {
    return false;
  }
}

/** Pide la huella, la cara o el PIN del dispositivo. Devuelve true si es el dueño. */
export async function identificar() {
  const id = prefs.leer('bloqueoCredencial', null);
  if (!id) return false;
  try {
    const r = await navigator.credentials.get({
      publicKey: {
        challenge: aleatorio(32),
        allowCredentials: [{ type: 'public-key', id: deBase64(id), transports: ['internal'] }],
        userVerification: 'required',
        timeout: 60_000
      }
    });
    return !!r;
  } catch {
    return false;
  }
}

export function guardarBloqueo(activo) {
  prefs.guardar('bloqueo', activo);
  if (!activo) prefs.guardar('bloqueoCredencial', null);
  // Quien lo activa ya ha confirmado que es él: no se le pide otra vez enseguida.
  bloqueo.desbloquear();
}

// --- Versiones nuevas ---

/** Compara "1.3" con "1.3.0" (iguales) o "1.10.0" (mayor). */
export function compararVersiones(a, b) {
  const pa = String(a).replace(/^v/, '').split('.').map(Number);
  const pb = String(b).replace(/^v/, '').split('.').map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d) return Math.sign(d);
  }
  return 0;
}

export const actualizaciones = {
  versionInstalada: VERSION,
  /** 'SIN_COMPROBAR' | 'BUSCANDO' | 'AL_DIA' | 'DISPONIBLE' | 'ERROR', con [publicada] si hay una. */
  estado: { tipo: 'SIN_COMPROBAR' },
  /** 'INACTIVA' | 'EN_CURSO' | 'COMPLETADA' | 'FALLIDA', con su progreso de 0 a 1. */
  descarga: { tipo: 'INACTIVA' },
  oyentes: new Set(),
  get ultimaComprobacion() {
    return prefs.leer('ultimaComprobacion', 0);
  },
  suscribir(f) {
    this.oyentes.add(f);
    return () => this.oyentes.delete(f);
  },
  cambiar(parcial) {
    Object.assign(this, parcial);
    this.oyentes.forEach((f) => f());
  },

  async consultar() {
    // Con una conexión que no responde, "Buscando…" no se queda colgado: a los 15 s pasa a "No se ha podido comprobar".
    const r = await fetch(`version.json?t=${Date.now()}`, { cache: 'no-store', signal: AbortSignal.timeout(15_000) });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    const publicada = await r.json();
    if (!publicada?.version) throw new Error('version.json sin versión');
    prefs.guardar('ultimaComprobacion', Date.now());
    return publicada;
  },

  /** Al abrir la app, como mucho una vez al día: devuelve la versión nueva si la hay. */
  async comprobarSiToca() {
    if (!navigator.onLine || Date.now() - this.ultimaComprobacion < 24 * 3600_000) return null;
    try {
      const publicada = await this.consultar();
      if (compararVersiones(publicada.version, VERSION) > 0) {
        this.cambiar({ estado: { tipo: 'DISPONIBLE', publicada } });
        return publicada;
      }
    } catch {
      // Sin conexión o sin servidor: ya se mirará desde "Más".
    }
    return null;
  },

  async buscar() {
    if (this.estado.tipo === 'BUSCANDO') return;
    this.cambiar({ estado: { tipo: 'BUSCANDO' } });
    try {
      const publicada = await this.consultar();
      this.cambiar({ estado: compararVersiones(publicada.version, VERSION) > 0 ? { tipo: 'DISPONIBLE', publicada } : { tipo: 'AL_DIA' } });
    } catch {
      this.cambiar({ estado: { tipo: 'ERROR' } });
    }
  },

  /** Descarga la versión nueva: el service worker la guarda entera antes de poder usarla. */
  async descargar(publicada) {
    if (this.descarga.tipo === 'EN_CURSO') return;
    this.cambiar({ descarga: { tipo: 'EN_CURSO', publicada, progreso: 0, bytes: 0 } });
    try {
      const registro = await navigator.serviceWorker?.getRegistration();
      if (!registro) throw new Error('Sin service worker');
      const yaEsperando = registro.waiting;
      if (!yaEsperando) {
        const instalado = new Promise((resolver, rechazar) => {
          const vigilar = (sw) => {
            if (!sw) return;
            sw.addEventListener('statechange', () => {
              if (sw.state === 'installed') resolver();
              if (sw.state === 'redundant') rechazar(new Error('La versión nueva no se ha podido instalar'));
            });
          };
          vigilar(registro.installing);
          registro.addEventListener('updatefound', () => vigilar(registro.installing), { once: true });
          setTimeout(() => rechazar(new Error('Tiempo agotado')), 120_000);
        });
        // Si no llega a esperarse (no había versión nueva), que su tiempo agotado no quede como error suelto.
        instalado.catch(() => {});
        await registro.update();
        if (!registro.installing && !registro.waiting) throw new Error('No hay ninguna versión nueva en el servidor');
        if (!registro.waiting) await instalado;
      }
      if (this.descarga.tipo !== 'EN_CURSO') return;
      this.cambiar({ descarga: { tipo: 'COMPLETADA', publicada, progreso: 1 } });
    } catch {
      if (this.descarga.tipo !== 'EN_CURSO') return;
      this.cambiar({ descarga: { tipo: 'FALLIDA', publicada, progreso: this.descarga.progreso ?? 0, motivo: navigator.onLine ? 'La descarga no ha llegado completa. Vuelve a intentarlo.' : 'No se ha podido descargar la actualización. Revisa la conexión a internet.' } });
    }
  },

  cancelar() {
    this.cambiar({ descarga: { tipo: 'INACTIVA' } });
  },

  /** Usa la versión descargada: la página se recarga con ella. Tus socios y ajustes se conservan. */
  async instalar() {
    const registro = await navigator.serviceWorker?.getRegistration();
    if (!registro?.waiting) return false;
    navigator.serviceWorker.addEventListener('controllerchange', () => location.reload(), { once: true });
    registro.waiting.postMessage({ tipo: 'activar' });
    return true;
  }
};

// El service worker cuenta lo que lleva guardado de la versión nueva.
navigator.serviceWorker?.addEventListener('message', (e) => {
  const m = e.data;
  if (m?.tipo !== 'progreso' || actualizaciones.descarga.tipo !== 'EN_CURSO') return;
  actualizaciones.cambiar({ descarga: { ...actualizaciones.descarga, progreso: m.total ? m.hechos / m.total : 0, bytes: m.bytes } });
});
