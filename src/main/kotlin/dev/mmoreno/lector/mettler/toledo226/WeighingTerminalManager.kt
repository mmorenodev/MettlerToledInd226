package dev.mmoreno.dev.mmoreno.lector.mettler.toledo226

import com.fazecast.jSerialComm.SerialPort
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.coroutines.coroutineContext

class WeighingTerminalManager(
    private val portName: String,
    coroutineScope: CoroutineScope
) {
    private val _eventFlow = MutableSharedFlow<EventoBascula>(replay = 3)
    val eventFlow: SharedFlow<EventoBascula> = _eventFlow
    private val baudRate: Int = 9600

    private var comPort: SerialPort? = null

    init {
        coroutineScope.launch { startReading() }
    }

    private suspend fun startReading() {
        comPort = SerialPort.getCommPort(portName).apply {
            baudRate = this@WeighingTerminalManager.baudRate
        }

        if (comPort?.openPort() == true) {
            _eventFlow.emit(EventoBascula.PuertoSerialAbiertoCorrectamente)
            readData()
        } else {
            _eventFlow.emit(EventoBascula.ErrorAbriendoPuertoSerial(Exception("No se pudo abrir el puerto")))
        }
    }

    private suspend fun readData() = withContext(Dispatchers.IO) {
        val command = "P\r\n"
        comPort?.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 500, 0)

        while (comPort?.isOpen == true && isActive) {
            comPort?.outputStream?.apply {
                write(command.toByteArray())
                flush()
            }

            val completeBuffer = mutableListOf<Byte>()
            try {
                while (completeBuffer.size < 18 && isActive) {
                    val buffer = ByteArray(8)
                    val bytesRead = comPort?.inputStream?.read(buffer) ?: -1

                    if (bytesRead > 0) {
                        completeBuffer.addAll(buffer.slice(0 until bytesRead))
                    }
                }

                // Ya está el tamaño del buffer necesario para manejar la información
                processValidData(completeBuffer.take(18).toByteArray())
                completeBuffer.clear()

            } catch (e: Exception) {
                _eventFlow.emit(EventoBascula.ErrorLeyendoPropiedades(e))
            }
            delay(100)
        }
    }

    private suspend fun processValidData(data: ByteArray) {
        val stx = data[0]
        val cr = data[16]
        val chk = data[17]

        if (stx == 2.toByte() && cr == 13.toByte() && chk == 2.toByte()) {
            val df1 = String(data.sliceArray(4..9)).trim()
            val df2 = String(data.sliceArray(10..15)).trim()
            _eventFlow.emit(EventoBascula.LecturaPeso(df1))
        }
    }

    fun stop() {
        comPort?.closePort()
    }
}

sealed interface EventoBascula {
    data class ErrorLeyendoPropiedades(val exception: Exception) : EventoBascula
    data class ErrorAbriendoPuertoSerial(val exception: Exception) : EventoBascula
    object PuertoSerialAbiertoCorrectamente : EventoBascula
    data class LecturaPeso(val lectura: String) : EventoBascula
}
