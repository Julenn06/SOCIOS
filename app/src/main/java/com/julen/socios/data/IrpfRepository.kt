package com.julen.socios.data

import com.julen.socios.data.local.IrpfVigenciaDao
import com.julen.socios.model.IrpfVigencia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Los cambios del porcentaje de IRPF que llegan con las copias de seguridad; sin ninguno se usa el de por defecto. */
class IrpfRepository(private val irpfDao: IrpfVigenciaDao) {

    fun getVigenciasFlow(): Flow<List<IrpfVigencia>> =
        irpfDao.getAllFlow().map { list -> list.map { it.toIrpfVigencia() } }
}
