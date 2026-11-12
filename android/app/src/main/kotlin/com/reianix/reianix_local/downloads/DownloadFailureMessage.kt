package com.reianix.reianix_local.downloads

import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Honest, non-sensitive connection diagnostics for a queued transfer. */
internal object DownloadFailureMessage {
    enum class Phase { DESTINATION, CONNECTION, TRANSFER, FINISHING }

    fun pending(error:Exception,phase:Phase,attempts:Int,received:Long):String {
        val detail=when {
            phase==Phase.DESTINATION ->
                "O destino não respondeu à gravação. Verifique o espaço e a permissão da pasta."
            error is UnknownHostException ->
                "Não foi possível encontrar o servidor (DNS). Verifique sua conexão."
            error is SocketTimeoutException ->
                "O servidor demorou demais para responder."
            error is ConnectException ->
                "O servidor recusou a conexão."
            error is SSLException ->
                "Falha na conexão HTTPS. Verifique data, rede ou certificado."
            error is EOFException ->
                "O servidor encerrou a conexão antes do esperado."
            error is SocketException ->
                "A conexão com o servidor foi interrompida."
            phase==Phase.CONNECTION ->
                "O servidor ainda não aceitou a conexão do arquivo."
            phase==Phase.TRANSFER ->
                "O download foi interrompido durante a transferência."
            else ->
                "Não foi possível concluir esta etapa do download."
        }
        val prefix=if(received==0L) "Ainda não recebi dados. " else ""
        val retry="A nova tentativa é automática."
        val source=if(received==0L && attempts>=3)
            " Se persistir, o link ou a sessão pode ter expirado." else ""
        return prefix+detail+" "+retry+source
    }
}
