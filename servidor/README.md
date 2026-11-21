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
- **Sincronización** (`sincronizacion.js`): cada registro lleva cuándo cambió (`modificado`) y si está borrado; gana
  el cambio más reciente. Cada cambio aceptado recibe una revisión creciente por cuenta y cada dispositivo pide lo
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
| `POST /api/sincronizar` | `{ desde, cambios }` → `{ rev, mas, cambios }` |

## Variables de entorno (hPanel → socios-api.jfl06studio.com → Node.js)

- `CLAVE_REGISTRO`: la contraseña de registro. Sin ella, el registro está cerrado.
- `ALLOWED_ORIGIN`: `https://socios.jfl06studio.com`.
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
los tests y copia el servidor a la rama `api`, que Hostinger despliega sola.
