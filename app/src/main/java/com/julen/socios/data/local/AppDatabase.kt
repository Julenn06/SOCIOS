package com.julen.socios.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SocioEntity::class, BonoRegaloEntity::class, CobroEntity::class],
    version = 7,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun socioDao(): SocioDao
    abstract fun bonoRegaloDao(): BonoRegaloDao
    abstract fun cobroDao(): CobroDao

    companion object {
        const val NOMBRE = "socios_database"

        fun crear(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NOMBRE)
                .addMigrations(Migracion3a4(), Migracion4a5, Migracion5a6, Migracion6a7)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
