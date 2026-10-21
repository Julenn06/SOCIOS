package com.julen.socios.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SocioEntity::class, BonoRegaloEntity::class, IrpfVigenciaEntity::class, DevolucionEntity::class],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun socioDao(): SocioDao
    abstract fun bonoRegaloDao(): BonoRegaloDao
    abstract fun irpfVigenciaDao(): IrpfVigenciaDao
    abstract fun devolucionDao(): DevolucionDao

    companion object {
        const val NOMBRE = "socios_database"

        fun crear(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NOMBRE)
                .addMigrations(Migracion3a4(), Migracion4a5)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
