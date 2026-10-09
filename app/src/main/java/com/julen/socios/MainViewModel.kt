package com.julen.socios

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.julen.socios.data.BonoRegaloRepository
import com.julen.socios.data.SocioRepository
import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.Socio
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DateUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val weekOffset: Int = 0,
    val semanaKey: String = DateUtils.getSemanaKey(0),
    val selectedDiaFiltroId: Int? = null,
    val todosSociosSemana: List<Socio> = emptyList(),
    val sociosFiltrados: List<Socio> = emptyList(),
    val bonoInfoSemana: BonoRegaloInfo? = null,
    val resumenSemana: CalculoComisiones.ResumenCalculo? = null,
    val rangoTexto: String = DateUtils.getRangoSemanaTexto(0),
    val messageEvent: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val socioRepository: SocioRepository,
    private val bonoRegaloRepository: BonoRegaloRepository
) : ViewModel() {

    private val _weekOffset = MutableStateFlow(0)
    private val _selectedDiaFiltroId = MutableStateFlow<Int?>(null)
    private val _messageEvent = MutableStateFlow<String?>(null)

    fun clearMessageEvent() {
        _messageEvent.value = null
    }

    val uiState: StateFlow<MainUiState> =
        combine(_weekOffset, _selectedDiaFiltroId, _weekOffset.flatMapLatest { offset ->
            val semanaKey = DateUtils.getSemanaKey(offset)
            socioRepository.getSociosPorSemanaFlow(semanaKey)
        }, _weekOffset.flatMapLatest { offset ->
            val semanaKey = DateUtils.getSemanaKey(offset)
            bonoRegaloRepository.getBonoRegaloFlow(semanaKey)
        }) { offset, diaFiltroId, socios, bonoInfo ->
            val semanaKey = DateUtils.getSemanaKey(offset)
            val rangoTexto = DateUtils.getRangoSemanaTexto(offset)
            val resumen = CalculoComisiones.calcularResumenSemana(socios, bonoInfo)

            val filtrados = if (diaFiltroId != null) {
                socios.filter { it.diaSemanaId == diaFiltroId }
            } else {
                socios
            }

            MainUiState(
                weekOffset = offset,
                semanaKey = semanaKey,
                selectedDiaFiltroId = diaFiltroId,
                todosSociosSemana = socios,
                sociosFiltrados = filtrados,
                bonoInfoSemana = bonoInfo,
                resumenSemana = resumen,
                rangoTexto = rangoTexto,
                messageEvent = _messageEvent.value,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = MainUiState()
        )

    fun changeWeekOffset(offsetDelta: Int) {
        _selectedDiaFiltroId.value = null
        _weekOffset.value += offsetDelta
    }

    fun setWeekOffset(absoluteOffset: Int) {
        _selectedDiaFiltroId.value = null
        _weekOffset.value = absoluteOffset
    }

    fun setSelectedDiaFiltro(diaId: Int?) {
        _selectedDiaFiltroId.value = diaId
    }

    fun toggleSocioHecho(socioId: String) {
        viewModelScope.launch {
            val nuevoEstado = socioRepository.toggleSocioHecho(socioId)
            _messageEvent.value =
                if (nuevoEstado) "✓ Socio marcado como HECHO" else "⏳ Socio marcado como NO HECHO"
        }
    }

    fun addSocio(socio: Socio) {
        viewModelScope.launch {
            socioRepository.addSocio(socio)
            _messageEvent.value = "¡Socio registrado con éxito!"
        }
    }

    fun updateSocio(socio: Socio) {
        viewModelScope.launch {
            socioRepository.updateSocio(socio)
            _messageEvent.value = "Socio actualizado"
        }
    }

    fun deleteSocio(socioId: String) {
        viewModelScope.launch {
            socioRepository.deleteSocio(socioId)
            _messageEvent.value = "Socio eliminado"
        }
    }

    fun saveBonoRegalo(bonoInfo: BonoRegaloInfo) {
        viewModelScope.launch {
            bonoRegaloRepository.saveBonoRegalo(bonoInfo)
            _messageEvent.value = "¡Bonus actualizado!"
        }
    }

    fun deleteBonoRegalo(semanaKey: String) {
        viewModelScope.launch {
            bonoRegaloRepository.deleteBonoRegalo(semanaKey)
            _messageEvent.value = "Bonus restablecido a automático"
        }
    }

    fun importBackup(socios: List<Socio>, bonosRegalo: List<BonoRegaloInfo>) {
        viewModelScope.launch {
            val countSocios = if (socios.isNotEmpty()) socioRepository.importSocios(socios) else 0
            bonosRegalo.forEach { bono ->
                bonoRegaloRepository.saveBonoRegalo(bono)
            }
            val countBonos = bonosRegalo.size
            _messageEvent.value = if (countBonos > 0) {
                "✓ Se han importado $countSocios socios y $countBonos bonos de regalo"
            } else {
                "✓ Se han importado $countSocios socios correctamente"
            }
        }
    }

    suspend fun getAllSocios(): List<Socio> {
        return socioRepository.getAllSocios()
    }

    suspend fun getSociosPorSemana(semanaKey: String): List<Socio> {
        return socioRepository.getSociosPorSemana(semanaKey)
    }

    suspend fun getBonoRegalo(semanaKey: String): BonoRegaloInfo {
        return bonoRegaloRepository.getBonoRegalo(semanaKey)
    }

    suspend fun getAllBonosRegalo(): Map<String, BonoRegaloInfo> {
        return bonoRegaloRepository.getAllBonosRegalo()
    }
}

class MainViewModelFactory(
    private val socioRepository: SocioRepository,
    private val bonoRegaloRepository: BonoRegaloRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(socioRepository, bonoRegaloRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
