package com.julen.socios.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.julen.socios.model.IrpfVigencia

@Entity(tableName = "irpf_vigencias")
data class IrpfVigenciaEntity(
    @PrimaryKey val desdeSemanaKey: String,
    val porcentaje: Double
) {
    fun toIrpfVigencia(): IrpfVigencia = IrpfVigencia(desdeSemanaKey, porcentaje)

    companion object {
        fun fromIrpfVigencia(vigencia: IrpfVigencia): IrpfVigenciaEntity =
            IrpfVigenciaEntity(vigencia.desdeSemanaKey, vigencia.porcentaje)
    }
}
