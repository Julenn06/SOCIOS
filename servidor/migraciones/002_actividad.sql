-- Actividad de cada cuenta (Más → Cuenta → Dispositivos y actividad): entradas, intentos fallidos, cambios de nombre y
-- de contraseña y sesiones cerradas desde otro dispositivo. Sin IP ni nada que no se vea ya en la web. Se guardan las
-- 100 últimas de cada cuenta y se borran con ella.

CREATE TABLE actividad (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  usuarioId VARCHAR(32) NOT NULL,
  momento BIGINT NOT NULL,
  tipo VARCHAR(24) NOT NULL,
  dispositivo VARCHAR(80) NOT NULL DEFAULT '',
  KEY actividad_usuario (usuarioId, id),
  CONSTRAINT actividad_usuario_fk FOREIGN KEY (usuarioId) REFERENCES usuarios (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
