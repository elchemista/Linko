package it.linko.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.linko.app.data.Category
import java.util.Locale

/** Category appearance and explicit URL/metadata aliases are edited together. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryEditor(
    category: Category?,
    onDismiss: () -> Unit,
    existingCategories: List<Category> = emptyList(),
    onSave: (String, String, Long, List<String>) -> Unit
) {
    var name by rememberSaveable(category?.id) { mutableStateOf(category?.name.orEmpty()) }
    var icon by rememberSaveable(category?.id) { mutableStateOf(category?.icon ?: "folder") }
    var color by rememberSaveable(category?.id) { mutableStateOf(category?.color ?: 0xFFB9A7FF) }
    var aliasesText by rememberSaveable(category?.id) { mutableStateOf(category?.matchKeywords.orEmpty().joinToString(", ")) }
    val duplicateName = name.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotBlank() }?.let { proposed ->
        existingCategories.any { it.id != category?.id && it.name.trim().replace(Regex("\\s+"), " ").equals(proposed, ignoreCase = true) }
    } == true
    val aliases = aliasesText.split(Regex("[,;\\r\\n]+"))
        .map { it.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) }
        .filter { it.isNotBlank() }.distinct()
    val aliasesError = when {
        aliases.size > 100 -> "Puoi indicare al massimo 100 parole."
        aliases.any { it.length > 60 } -> "Ogni parola o frase può avere al massimo 60 caratteri."
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (category == null) "Una nuova raccolta" else "Modifica raccolta") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Nome") }, singleLine = true,
                    shape = RoundedCornerShape(12.dp), isError = duplicateName,
                    supportingText = { if (duplicateName) Text("Esiste già una categoria con questo nome: aprila per modificarla.") })

                Text("Icona", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 18.dp, bottom = 9.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("folder", "food", "code", "travel", "spark", "music", "sport", "learn").forEach { key ->
                        IconButton(
                            onClick = { icon = key },
                            modifier = Modifier.size(45.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (icon == key) Lavender.copy(alpha = .18f) else Panel)
                        ) { Icon(categoryIcon(key), "Icona $key", tint = if (icon == key) Lavender else Muted) }
                    }
                }

                Text("Colore", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 18.dp, bottom = 9.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0xFFB9A7FF to "lavanda", 0xFFF0BE91 to "pesca", 0xFFA4D5BF to "menta", 0xFF8FC7EA to "azzurro", 0xFFE9A3B7 to "rosa").forEach { (value, label) ->
                        Box(
                            Modifier.size(39.dp).clip(CircleShape).background(Color(value)).clickable { color = value }
                                .semantics { contentDescription = "Colore $label" },
                            contentAlignment = Alignment.Center
                        ) { if (color == value) Icon(Icons.Rounded.Check, null, tint = Ink, modifier = Modifier.size(20.dp)) }
                    }
                }

                Text("Riconoscila dai link", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 22.dp, bottom = 9.dp))
                OutlinedTextField(
                    value = aliasesText,
                    onValueChange = { aliasesText = it.take(10000) },
                    label = { Text("Parole per assegnare la categoria") },
                    placeholder = { Text("black, ebony") },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                    isError = aliasesError != null,
                    supportingText = { Text(aliasesError ?: "Separa con virgole, punto e virgola o una nuova riga.") }
                )
                Surface(color = Lavender.copy(alpha = .08f), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        "Cercate nell’URL e nei metadati: basta una parola. Trattini, underscore e simboli separano le parole. Es.: black, ebony → questa categoria. Maiuscole e minuscole non fanno differenza. Puoi lasciare vuoto.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), icon, color, aliases) }, enabled = name.isNotBlank() && !duplicateName && aliasesError == null) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}
