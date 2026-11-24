package com.reianix.reianix_local.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.reianix.reianix_local.data.backup.ReiAnixBackupService
import com.reianix.reianix_local.data.backup.ReiAnixProtectedBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun ReiAnixProtectedBackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var encrypt by remember { mutableStateOf(true) }
    var source by remember { mutableStateOf<android.net.Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf<File?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        source = uri
        message = ""
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = prepared
        prepared = null
        if (file != null) {
            if (uri == null) {
                file.delete()
                busy = false
            } else scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val expected = java.security.MessageDigest.getInstance("SHA-256")
                        context.contentResolver.openOutputStream(uri, "w").use { out ->
                            requireNotNull(out)
                            file.inputStream().use { stream ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    val n = stream.read(buffer)
                                    if (n < 0) break
                                    expected.update(buffer, 0, n)
                                    out.write(buffer, 0, n)
                                }
                            }
                        }
                        val actual = java.security.MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        context.contentResolver.openInputStream(uri).use { stream ->
                            requireNotNull(stream)
                            val buffer = ByteArray(65536)
                            while (true) {
                                val n = stream.read(buffer)
                                if (n < 0) break
                                count += n
                                require(count <= file.length())
                                actual.update(buffer, 0, n)
                            }
                        }
                        require(count == file.length() && java.security.MessageDigest.isEqual(expected.digest(), actual.digest()))
                    }
                    message = if (encrypt) "Backup protegido salvo." else "ZIP recuperado. Use Restaurar backup para importá-lo."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "Não foi possível salvar ou verificar o arquivo." }
                finally { file.delete(); busy = false }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { prepared?.delete() } }
    ReiAnixSettingsRoot {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsHeader(title = "Backup protegido", subtitle = "", onBack = onBack)
                if (Build.VERSION.SDK_INT < 26) {
                    Text("A criptografia de backup requer Android 8 ou superior.")
                    return@Column
                }
                Text("Proteja um ZIP criado em Fazer backup com uma senha. Para restaurar um arquivo protegido, recupere primeiro o ZIP e use Restaurar backup. O original não é apagado. Guarde a senha: não há recuperação.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = encrypt, enabled = !busy, onClick = { encrypt = true; source = null; message = "" }, label = { Text("Proteger ZIP") })
                    FilterChip(selected = !encrypt, enabled = !busy, onClick = { encrypt = false; source = null; message = "" }, label = { Text("Recuperar ZIP") })
                }
                OutlinedButton(enabled = !busy, onClick = { open.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                    Text(if (source == null) "Selecionar arquivo" else "Arquivo selecionado — trocar")
                }
                OutlinedTextField(value = password, onValueChange = { password = it.take(256) }, label = { Text("Senha") },
                    singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation())
                if (encrypt) OutlinedTextField(value = confirmation, onValueChange = { confirmation = it.take(256) },
                    label = { Text("Confirmar senha (mínimo 12 caracteres)") }, singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation())
                Button(enabled = !busy && source != null && password.isNotEmpty() &&
                    (!encrypt || (password.length >= 12 && confirmation == password)), onClick = {
                    val uri = source ?: return@Button
                    val secret = password.toCharArray()
                    password = ""; confirmation = ""; busy = true; message = ""
                    scope.launch {
                        var result: File? = null
                        try {
                            withContext(Dispatchers.IO) {
                                val directory = File(context.cacheDir, "protected_backup")
                                check(directory.isDirectory || directory.mkdirs())
                                val input = File.createTempFile("input-", ".tmp", directory)
                                try {
                                    context.contentResolver.openInputStream(uri).use { stream ->
                                        requireNotNull(stream)
                                        input.outputStream().use { out ->
                                            val buffer = ByteArray(65536)
                                            var total = 0L
                                            while (true) {
                                                val count = stream.read(buffer)
                                                if (count < 0) break
                                                total += count
                                                require(total <= ReiAnixProtectedBackup.MAX_INPUT_BYTES)
                                                out.write(buffer, 0, count)
                                            }
                                        }
                                    }
                                    if (encrypt) ReiAnixBackupService(context).inspectBackup(input)
                                    val output = File.createTempFile("output-", ".tmp", directory)
                                    result = output
                                    ReiAnixProtectedBackup.transform(input, output, secret, encrypt)
                                    // Validate the recovered ZIP before allowing it to leave private cache.
                                    if (!encrypt) ReiAnixBackupService(context).inspectBackup(output)
                                } finally { input.delete() }
                            }
                            prepared = requireNotNull(result)
                            save.launch(if (encrypt) "reianix-backup.rxb" else "reianix-backup.zip")
                            result = null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { message = "Arquivo inválido, senha incorreta ou falha de leitura."; busy = false }
                        finally { secret.fill('\u0000'); result?.delete() }
                    }
                }) { Text(if (encrypt) "Criptografar" else "Descriptografar") }
                if (busy) CircularProgressIndicator()
                if (message.isNotEmpty()) Text(message)
            }
        }
    }
}
