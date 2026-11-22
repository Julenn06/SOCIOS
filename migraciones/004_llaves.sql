-- Llaves de acceso (passkeys, ver passkeys.js): solo la clave pública de cada una, en SPKI (base64url). Se borran con
-- la cuenta.

CREATE TABLE llaves (
  id VARCHAR(1400) NOT NULL,
  idHash CHAR(64) NOT NULL PRIMARY KEY,
  usuarioId VARCHAR(32) NOT NULL,
  clavePublica TEXT NOT NULL,
  alg INT NOT NULL,
  contador BIGINT NOT NULL DEFAULT 0,
  transportes VARCHAR(200) NOT NULL DEFAULT '',
  nombre VARCHAR(80) NOT NULL DEFAULT '',
  creada BIGINT NOT NULL,
  usada BIGINT NOT NULL DEFAULT 0,
  KEY llaves_usuario (usuarioId),
  CONSTRAINT llaves_usuario_fk FOREIGN KEY (usuarioId) REFERENCES usuarios (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
