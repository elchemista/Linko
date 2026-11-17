package it.linko.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.linko.app.data.LibraryState
import it.linko.app.data.LinkFilters
import it.linko.app.data.LinkMatcher
import java.util.Locale

/** Filters update live; all choices are touch controls and the result button stays visible. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    library: LibraryState,
    filters: LinkFilters,
    onChange: (LinkFilters) -> Unit,
    onDismiss: () -> Unit
) {
    var allKeysVisible by rememberSaveable { mutableStateOf(false) }
    var allDomainsVisible by rememberSaveable { mutableStateOf(false) }
    val categoryNames = remember(library.categories) { library.categories.associate { it.id to it.name } }
    val resultCount = remember(library.links, filters, categoryNames) {
        library.links.count { LinkMatcher.matches(it, filters, categoryNames[it.categoryId].orEmpty()) }
    }
    val allKeys = remember(library.keys, library.links, filters.keys) {
        (library.keys + library.links.flatMap { it.keys } + filters.keys)
            .map { it.trim().trimStart('#').trim() }.filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }.sortedBy { it.lowercase(Locale.ROOT) }
    }
    val qualityOptions = remember(library.links) {
        library.links.mapNotNull { it.quality?.trim()?.takeIf(String::isNotBlank) }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedWith(compareBy<String> { quality ->
                Regex("\\d+").find(quality)?.value?.toIntOrNull() ?: Int.MAX_VALUE
            }.thenBy { it.lowercase(Locale.ROOT) })
    }
    val domainOptions = remember(library.links) {
        library.links.mapNotNull { LinkMatcher.domainOf(it.url) }.distinct().sorted()
    }
    val suffixOptions = remember(domainOptions) {
        domainOptions.mapNotNull { host ->
            host.substringAfterLast('.', "").takeIf { part -> part.length >= 2 && part.all(Char::isLetter) }?.let { ".$it" }
        }.distinct().sorted()
    }
    val hasFilters = filters.keys.isNotEmpty() || filters.qualities.isNotEmpty() || filters.domains.isNotEmpty()

    fun keySelected(value: String): Boolean = filters.keys.any { it.trim().trimStart('#').trim().equals(value, true) }
    fun toggleKey(value: String) {
        onChange(filters.copy(keys = if (keySelected(value)) {
            filters.keys.filterNot { it.trim().trimStart('#').trim().equals(value, true) }.toSet()
        } else filters.keys + value))
    }
    fun qualitySelected(value: String): Boolean = filters.qualities.any { it.trim().equals(value, true) }
    fun toggleQuality(value: String) {
        onChange(filters.copy(qualities = if (qualitySelected(value)) {
            filters.qualities.filterNot { it.trim().equals(value, true) }.toSet()
        } else filters.qualities + value))
    }
    fun domainSelected(value: String): Boolean = filters.domains.any { it.trim().equals(value, true) }
    fun toggleDomain(value: String) {
        onChange(filters.copy(domains = if (domainSelected(value)) {
            filters.domains.filterNot { it.trim().equals(value, true) }.toSet()
        } else filters.domains + value))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        DarkSheetBars()
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.FilterAlt, null, tint = MaterialTheme.colorScheme.primary) }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Trova il video giusto", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Scegli con un tocco. Combina come vuoi.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Chiudi filtri") }
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = .08f), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(15.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("$resultCount video trovati", style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary)
                                val context = listOfNotNull(
                                    filters.categoryId?.let { categoryNames[it] },
                                    "Preferiti".takeIf { filters.favoritesOnly },
                                    filters.query.takeIf { it.isNotBlank() }?.let { "Ricerca: $it" }
                                ).joinToString(" · ")
                                Text(context.ifBlank { "In tutta la tua raccolta" }, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (hasFilters) TextButton(onClick = { onChange(filters.copy(keys = emptySet(), qualities = emptySet(), domains = emptySet())) }) {
                                Text("Azzera")
                            }
                        }
                        if (hasFilters) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.padding(top = 6.dp)) {
                                filters.keys.forEach { key ->
                                    RemovableFilterChip("#${key.trim().trimStart('#')}") { toggleKey(key.trim().trimStart('#').trim()) }
                                }
                                filters.qualities.forEach { quality -> RemovableFilterChip(quality) { toggleQuality(quality) } }
                                filters.domains.forEach { domain -> RemovableFilterChip(domain) { toggleDomain(domain) } }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilterSectionHeading(Icons.Rounded.Tag, "Parole chiave", "Devono esserci tutte quelle selezionate.")
                    if (allKeys.isEmpty()) {
                        Text("Le keys salvate nei tuoi link compariranno qui.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val visibleKeys = if (allKeysVisible) allKeys else (allKeys.filter { keySelected(it) } + allKeys.take(24)).distinctBy { it.lowercase(Locale.ROOT) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            visibleKeys.forEach { key ->
                                TouchFilterChip("#$key", keySelected(key)) { toggleKey(key) }
                            }
                        }
                        if (allKeys.size > 24) TextButton(onClick = { allKeysVisible = !allKeysVisible }) {
                            Text(if (allKeysVisible) "Mostra meno" else "Mostra tutte le ${allKeys.size} keys")
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .65f))
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilterSectionHeading(Icons.Rounded.Movie, "Qualità video", "Basta una delle qualità selezionate.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        TouchFilterChip("Tutte", filters.qualities.isEmpty()) { onChange(filters.copy(qualities = emptySet())) }
                        qualityOptions.forEach { quality ->
                            TouchFilterChip(quality, qualitySelected(quality)) { toggleQuality(quality) }
                        }
                    }
                    if (qualityOptions.isEmpty()) Text("Nessun link ha ancora una qualità dichiarata.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .65f))
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilterSectionHeading(Icons.Rounded.Language, "Da quali siti?", "Basta uno dei siti o delle estensioni selezionati.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        TouchFilterChip("Tutti i siti", filters.domains.isEmpty()) { onChange(filters.copy(domains = emptySet())) }
                        suffixOptions.forEach { suffix ->
                            TouchFilterChip(suffix, domainSelected(suffix)) { toggleDomain(suffix) }
                        }
                    }
                    if (domainOptions.isEmpty()) {
                        Text("I siti dei link salvati compariranno qui.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val visibleDomains = if (allDomainsVisible) domainOptions else (domainOptions.filter { domainSelected(it) } + domainOptions.take(12)).distinct()
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            visibleDomains.forEach { domain ->
                                TouchFilterChip(domain, domainSelected(domain)) { toggleDomain(domain) }
                            }
                        }
                        if (domainOptions.size > 12) TextButton(onClick = { allDomainsVisible = !allDomainsVisible }) {
                            Text(if (allDomainsVisible) "Mostra meno" else "Mostra tutti i ${domainOptions.size} siti")
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp)) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(9.dp))
                        Text("Mostra $resultCount video", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSectionHeading(icon: ImageVector, title: String, description: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TouchFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.widthIn(max = 300.dp),
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = if (selected) ({ Icon(Icons.Rounded.Check, null, Modifier.size(15.dp)) }) else null
    )
}

@Composable
private fun RemovableFilterChip(label: String, onRemove: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onRemove,
        modifier = Modifier.widthIn(max = 300.dp),
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = { Icon(Icons.Rounded.Close, "Rimuovi $label", Modifier.size(14.dp)) }
    )
}
