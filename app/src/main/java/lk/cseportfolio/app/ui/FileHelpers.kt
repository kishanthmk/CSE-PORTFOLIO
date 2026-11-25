package lk.cseportfolio.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import lk.cseportfolio.app.MainViewModel
import lk.cseportfolio.core.Fmt
import lk.cseportfolio.core.parseDecimal

fun String.dec(): BigDecimal? = parseDecimal(this)
fun String.decOrZero(): BigDecimal = parseDecimal(this) ?: BigDecimal.ZERO
fun BigDecimal?.field(): String = if (this == null || this.signum() == 0) "" else Fmt.plain(this)

class Saver(val save: (name: String, bytes: suspend () -> ByteArray) -> Unit)

/** "Save as…" using the system file picker, so the user decides where every export goes. */
@Composable
fun rememberSaver(vm: MainViewModel): Saver {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<(suspend () -> ByteArray)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val producer = pending
        pending = null
        if (uri != null && producer != null) scope.launch(Dispatchers.IO) {
            try {
                val bytes = producer()
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                vm.say("File saved.")
            } catch (e: Exception) { vm.say("Could not save the file: ${e.message}") }
        }
    }
    return remember(launcher) { Saver { name, bytes -> pending = bytes; launcher.launch(name) } }
}

class Opener(val open: () -> Unit)

/** Lets the user pick a file and hands back its name and bytes. */
@Composable
fun rememberOpener(vm: MainViewModel, onFile: (name: String, bytes: ByteArray) -> Unit): Opener {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            try {
                var name = uri.lastPathSegment ?: "file"
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                launch(Dispatchers.Main) { onFile(name, bytes) }
            } catch (e: Exception) { vm.say("Could not read the file: ${e.message}") }
        }
    }
    return remember(launcher) { Opener { launcher.launch(arrayOf("*/*")) } }
}

fun shareFile(context: Context, name: String, mime: String, bytes: ByteArray) {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(dir, name)
    file.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    context.startActivity(Intent.createChooser(send, "Share $name").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
