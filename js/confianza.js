// Trusted Types (la CSP de .htaccess: require-trusted-types-for 'script'; trusted-types socios). Con ella, el navegador
// no deja meter HTML en la página (innerHTML y demás) ni cargar scripts por URL si no pasan por esta política, la
// única que puede existir. Así, aunque algún día se colara código, no tendría cómo inyectar HTML ni scripts.
//
// El HTML de la app sale de plantillas del propio código; lo que escribe el usuario (nombres, notas…) va siempre
// escapado con esc() (formato.js) antes de entrar en ellas, así que la política lo deja pasar tal cual. Regla: nunca
// asignar una cadena a innerHTML directamente; usar html() de componentes.js o htmlConfiable().
//
// En los navegadores sin Trusted Types (Firefox y Safari antiguos), las funciones devuelven la cadena tal cual.
const politica = globalThis.trustedTypes?.createPolicy('socios', {
  createHTML: (texto) => texto,
  // Solo scripts de la propia web (el service worker).
  createScriptURL: (direccion) => {
    const url = new URL(direccion, location.href);
    if (url.origin !== location.origin) throw new TypeError(`Script de otro origen bloqueado: ${url.origin}`);
    return url.href;
  }
});

/** Para innerHTML: una plantilla del código, con lo del usuario ya escapado con esc(). */
export const htmlConfiable = (texto) => (politica ? politica.createHTML(texto) : texto);

/** Para registrar el service worker. */
export const urlScriptConfiable = (direccion) => (politica ? politica.createScriptURL(direccion) : direccion);
