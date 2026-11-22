# Servidor de la cuenta (web)

Cuentas y sincronización de la web de Gestión de Socios (`pwa/`) entre los dispositivos donde se abre. **La app
Android no lo usa**: sigue con sus datos en el móvil y sus copias.

Node 22 con `http` y `mysql2`, sin más dependencias, como el servidor de Marea. Está en
**https://socios-api.jfl06studio.com**, un sitio Node.js propio en Hostinger con la base de datos MySQL
`u458712169_socios`.

## Cómo funciona

- **Cuentas:** "¿Cómo te llamas?" y una contraseña (scrypt con sal). El nombre vale escrito de cualquier forma:
  mayúsculas, minúsculas, tildes o espacios de más. Para crear una cuenta hace falta la contraseña de registro
  (`CLAVE_REGISTRO`). Las sesiones duran 180 días desde el último uso y se guardan solo por el SHA-256 de su token.
- **Sesión en cookie:** la web pide `modo: 'cookie'` al entrar o registrarse y el token va solo en la cookie
  `__Host-socios_sesion` (`HttpOnly`, `Secure`, `SameSite=Strict`, sin `Domain`): el código de la web no puede leerla.
  Sin ese modo (tests, otros clientes) se devuelve el token para `Authorization: Bearer`. Las sesiones de antes pasan a
  la cookie con `POST /api/sesion/cookie`.
- **Peticiones falsificadas (CSRF):** además de `SameSite=Strict`, toda petición que no sea GET y traiga `Origin` tiene
  que venir de `ALLOWED_ORIGIN` (403 si no).
- **Cabeceras:** HSTS, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `X-Frame-Options`,
  `Cross-Origin-Resource-Policy: same-origin`, `Referrer-Policy: no-referrer` y `nosniff` en todas las respuestas.
- **Dispositivos y actividad:** la lista de sesiones abiertas (cada una con un id que es un trozo de su SHA-256, nunca
  el token) y las 100 últimas cosas de la cuenta (`actividad`: entradas, intentos fallidos, cambios de nombre y de
  contraseña, sesiones cerradas), sin IP. Al entrar, la respuesta dice cuántos intentos fallidos ha habido desde la
  última entrada.
- **Llaves de acceso** (`passkeys.js`, WebAuthn sin dependencias): entrar con la huella, la cara o el PIN, sin
  contraseña. Solo se guarda la clave pública (SPKI); se comprueban el reto (un solo uso, 5 minutos, en memoria), el
  origen, el dominio, que el usuario se verificó (UV) y la firma (ES256, EdDSA o RS256), y un contador que no avanza
  delata una llave copiada. Hasta 20 por cuenta; se ven y se borran en la web (Más → Cuenta → Seguridad).
- **Errores de la web** (`errores.js`): la web cuenta sus fallos (y el navegador, con `report-uri` de la CSP, lo que
  bloquea) sin nada de la persona: tipo, mensaje sin lo que va entre comillas, archivo y línea, versión y navegador.
  Los iguales se cuentan juntos; quedan los 500 últimos. Los lee `GET /api/errores` con la clave, y la vigilancia
  (`.github/workflows/vigilancia.yml`) abre un issue cuando hay nuevos.
- **Sincronización** (`sincronizacion.js`): cada registro lleva cuándo cambió (`modificado`), si está borrado y cuándo
  cambió cada campo (`campos`, columna JSON). Si dos dispositivos cambian el mismo registro, se juntan campo a campo: de
  cada campo gana el cambio más reciente, y existir o no es un campo más. Lo de las versiones de la web anteriores a la
  1.0.17 (sin `campos`) cuenta como cambiado entero. Cada cambio aceptado recibe una revisión creciente por cuenta y cada dispositivo pide lo
  posterior a la suya. Se guardan socios, bonus, lo cobrado y los ajustes (tema, última cuota y pendientes ocultos).
- **Límites:** 240 peticiones por minuto e IP (la IP es la última de `X-Forwarded-For`, que pone el proxy de Hostinger:
  comprobado que no se puede falsificar); 20 intentos de entrar o registrarse por IP y 10 fallos por cuenta cada
  15 minutos. CORS solo para `https://socios.jfl06studio.com`.
- Las tablas se crean solas al arrancar con las migraciones de `migraciones/` (quedan en `schema_migrations`).

## API

Las rutas con sesión la toman de la cookie o, si no hay, de `Authorization: Bearer <token>`.

| Ruta | |
|---|---|
| `GET /api/salud` | `{ ok: true }` |
| `POST /api/registro/comprobar` | `{ claveRegistro }` → 204 si es la correcta |
| `POST /api/registro` | `{ claveRegistro, nombre, contrasena, dispositivo, modo? }` → `{ token?, nombre }` |
| `POST /api/sesion` | `{ nombre, contrasena, dispositivo, modo? }` → `{ token?, nombre, fallos }` |
| `POST /api/sesion/cookie` | con `Bearer`: pasa esa sesión a la cookie |
| `DELETE /api/sesion` | cerrar sesión |
| `GET /api/cuenta` | `{ nombre, creado }` |
| `DELETE /api/cuenta` | `{ contrasena }`: borra la cuenta y todos sus datos |
| `POST /api/cuenta/nombre` | `{ nombre }` → `{ nombre }` (409 si lo usa otra cuenta) |
| `POST /api/cuenta/contrasena` | `{ actual, nueva }` o, para restablecerla, `{ claveRegistro, nueva }`; cierra las demás sesiones |
| `GET /api/sesiones` | `{ sesiones: [{ id, dispositivo, creado, visto, actual }] }` |
| `POST /api/sesiones/cerrar` | `{ id }` o `{ otras: true }` → 204 |
| `GET /api/actividad` | `{ actividad: [{ tipo, momento, dispositivo }] }` (las 50 últimas) |
| `POST /api/llaves/opciones` | → `{ reto, usuario, nombre, rp, excluir, algoritmos }` para crear una llave |
| `POST /api/llaves` | `{ id, clientDataJSON, authenticatorData, publicKey, alg, transports, dispositivo }` → `{ id, nombre, creada }` |
| `GET /api/llaves` | `{ llaves: [{ id, nombre, creada, usada }] }` |
| `POST /api/llaves/borrar` | `{ id }` → 204 |
| `POST /api/sesion/llave/opciones` | sin sesión: `{ reto }` |
| `POST /api/sesion/llave` | `{ id, clientDataJSON, authenticatorData, signature, userHandle, dispositivo, modo? }` → como `/api/sesion` |
| `POST /api/errores` | `{ errores: [{ tipo, mensaje, fuente, linea, columna, version, navegador }] }` o un informe CSP → 204 |
| `GET /api/errores?desde=ms` | con `Authorization: Clave <CLAVE_ERRORES o CLAVE_REGISTRO>`: `{ errores: [...] }` |
| `POST /api/sincronizar` | `{ desde, cambios }` → `{ rev, mas, cambios }` (cada registro con `campos`) |

## Variables de entorno (hPanel → socios-api.jfl06studio.com → Node.js)

- `CLAVE_REGISTRO`: la contraseña de registro. Sin ella, el registro está cerrado.
- `ALLOWED_ORIGIN`: `https://socios.jfl06studio.com`.
- `CLAVE_ERRORES` (opcional): la clave para leer los errores de la web. Sin ella vale la de registro. Si se pone, hay
  que poner la misma en el secreto `CLAVE_ERRORES` del repositorio (la usa la vigilancia).
- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`: los puso Hostinger al crear la base de datos del sitio.
  **Para cambiar `CLAVE_REGISTRO`, hazlo desde hPanel**: la operación de la API que cambia las variables las sustituye
  todas, y la contraseña de la base de datos no se puede volver a leer.

## Probar y publicar

```bash
cd servidor
npm ci
npm test
PORT=3000 CLAVE_REGISTRO=prueba ALLOWED_ORIGIN=http://localhost:8123 node server.js   # en memoria, sin MySQL
```

Cada push a `main` que toca `servidor/` lanza el workflow `Publicar el servidor` (`.github/workflows/api.yml`): pasa
los tests y los recorridos de la web en Chrome contra este servidor (`pwa/test/e2e/`), y copia el servidor a la rama `api`, que Hostinger despliega sola.
