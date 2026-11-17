package it.linko.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun QuickPasteBar(
    value: String,
    onValueChange: (String) -> Unit,
    linkCount: Int,
    error: String?,
    enabled: Boolean = true,
    onSend: () -> Unit
) {
    val canSend = enabled && error == null && linkCount in 1..100
    Column(Modifier.fillMaxWidth().background(Ink).padding(horizontal = 16.dp, vertical = 7.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Incolla link o un testo…", fontSize = 13.sp) },
            leadingIcon = {
                if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }, enabled = enabled) {
                    Icon(Icons.Rounded.Close, "Cancella testo", Modifier.size(20.dp), tint = Muted)
                } else Icon(Icons.Rounded.Link, null, Modifier.size(21.dp), tint = Lavender)
            },
            trailingIcon = {
                FilledIconButton(onClick = onSend, enabled = canSend, modifier = Modifier.padding(end = 5.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.Send, "Organizza $linkCount link", Modifier.size(19.dp))
                }
            },
            supportingText = if (error != null || value.isNotBlank()) ({
                Text(error ?: when {
                    linkCount > 100 -> "Più di 100 link: dividili in gruppi più piccoli."
                    linkCount > 1 -> "$linkCount link trovati · Organizzali insieme o uno alla volta"
                    linkCount == 1 -> "1 link trovato · Tocca la freccia per organizzarlo"
                    else -> "Incolla un indirizzo o un testo che contenga link"
                }, fontSize = 10.sp)
            }) else null,
            isError = error != null || linkCount > 100,
            minLines = 1,
            maxLines = 3,
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Line, unfocusedContainerColor = Panel, focusedContainerColor = Panel),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() })
        )
    }
}
