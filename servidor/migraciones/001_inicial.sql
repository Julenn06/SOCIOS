-- Gestión de Socios (web): cuentas, sesiones y los datos de la app, con los mismos campos que las copias de la app
-- Android para que se entiendan igual (la app Android no usa este servidor).
-- Cada fila de datos lleva `modificado` (hora del cambio en el dispositivo), `borrado` y `rev` (ver sincronizacion.js).
-- Los importes van en céntimos, como en la app.

CREATE TABLE usuarios (
  id VARCHAR(32) NOT NULL PRIMARY KEY,
  nombre VARCHAR(80) NOT NULL,
  nombreClave VARCHAR(80) NOT NULL,
  contrasena VARCHAR(255) NOT NULL,
  rev BIGINT NOT NULL DEFAULT 0,
  creado BIGINT NOT NULL,
  ultimoAcceso BIGINT NOT NULL,
  UNIQUE KEY usuarios_nombreClave (nombreClave)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE sesiones (
  tokenHash CHAR(64) NOT NULL PRIMARY KEY,
  usuarioId VARCHAR(32) NOT NULL,
  dispositivo VARCHAR(80) NOT NULL DEFAULT '',
  creado BIGINT NOT NULL,
  visto BIGINT NOT NULL,
  caduca BIGINT NOT NULL,
  KEY sesiones_usuario (usuarioId),
  KEY sesiones_caduca (caduca),
  CONSTRAINT sesiones_usuario_fk FOREIGN KEY (usuarioId) REFERENCES usuarios (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE socios (
  usuarioId VARCHAR(32) NOT NULL,
  id VARCHAR(64) NOT NULL,
  colaboracionCentimos BIGINT NOT NULL,
  hecho TINYINT(1) NOT NULL,
  diaSemanaId TINYINT NOT NULL,
  semanaKey CHAR(8) NOT NULL,
  nombreSocio VARCHAR(200) NOT NULL,
  notas TEXT NOT NULL,
  `timestamp` BIGINT NOT NULL,
  modificado BIGINT NOT NULL,
  borrado TINYINT(1) NOT NULL DEFAULT 0,
  rev BIGINT NOT NULL,
  PRIMARY KEY (usuarioId, id),
  KEY socios_rev (usuarioId, rev),
  CONSTRAINT socios_usuario_fk FOREIGN KEY (usuarioId) REFERENCES usuarios (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE bonosRegalo (
  usuarioId VARCHAR(32) NOT NULL,
  semanaKey CHAR(8) NOT NULL,
  activo TINYINT(1) NOT NULL,
  esMontoFijo TINYINT(1) NOT NULL,
  montoCentimos BIGINT NOT NULL,
  nota VARCHAR(500) NOT NULL,
  modificado BIGINT NOT NULL,
  borrado TINYINT(1) NOT NULL DEFAULT 0,
  rev BIGINT NOT NULL,
  PRIMARY KEY (usuarioId, semanaKey),
  KEY bonos_rev (usuarioId, rev),
  CONSTRAINT bonos_usuario_fk FOREIGN KEY (usuarioId) REFERENCES usuarios (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE cobros (
  usuarioId VARCHAR(32) NOT NULL,
  semanaKey CHAR(8) NOT NULL,
  netoCobradoCentimos BIGINT NOT NULL,
  nota VARCHAR(500) NOT NULL,
  modificado BIGINT NOT NULL,
  borrado TINYINT(1) NOT NULL DEFAULT 0,
  rev BIGINT NOT NULL,
  PRIMARY KEY (usuarioId, semanaKey),
  KEY cobros_rev (usuarioId, rev),
  CONSTRAINT cobros_usuario_fk FOREIGN KEY (usuarioId) REFERENCES usuarios (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE ajustes (
  usuarioId VARCHAR(32) NOT NULL,
  clave VARCHAR(40) NOT NULL,
  valor MEDIUMTEXT NOT NULL,
  modificado BIGINT NOT NULL,
  borrado TINYINT(1) NOT NULL DEFAULT 0,
  rev BIGINT NOT NULL,
  PRIMARY KEY (usuarioId, clave),
  KEY ajustes_rev (usuarioId, rev),
  CONSTRAINT ajustes_usuario_fk FOREIGN KEY (usuarioId) REFERENCES usuarios (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
