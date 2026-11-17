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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import it.linko.app.data.LibraryState
import it.linko.app.data.CategoryRules
import it.linko.app.data.LinkMatcher
import it.linko.app.data.MetadataExtractor
import it.linko.app.data.VideoLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Review multiple pasted URLs without requiring metadata requests to finish. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BulkImportSheet(
    urls: List<String>,
    library: LibraryState,
    isSaving: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (List<VideoLink>) -> Unit,
    onCreateCategory: (String) -> Unit
) {
    // Save only small user choices; metadata and image URLs do not enter the activity Bundle.
    val categoriesSaver = listSaver<Map<Int, Long>, Long>(
        save = { map -> map.entries.flatMap { listOf(it.key.toLong(), it.value) } },
        restore = { values -> values.chunked(2).associate { it[0].toInt() to it[1] } }
    )
    val titlesSaver = listSaver<Map<Int, String>, String>(
        save = { map -> map.entries.flatMap { listOf(it.key.toString(), it.value) } },
        restore = { values -> values.chunked(2).associate { it[0].toInt() to it[1] } }
    )
    val removedSaver = listSaver<Set<Int>, Int>(save = { it.toList() }, restore = { it.toSet() })
    var metadata by remember(urls) { mutableStateOf(urls.map { MetadataExtractor.fromUrl(it) }) }
    var categoryOverrides by rememberSaveable(urls, stateSaver = categoriesSaver) { mutableStateOf(emptyMap<Int, Long>()) }
    var titleOverrides by rememberSaveable(urls, stateSaver = titlesSaver) { mutableStateOf(emptyMap<Int, String>()) }
    var removed by rememberSaveable(urls, stateSaver = removedSaver) { mutableStateOf(emptySet<Int>()) }
    var globalMode by rememberSaveable(urls) { mutableStateOf(false) }
    var globalCategoryId by rememberSaveable(urls) { mutableStateOf(1L) }
    var analysisStopped by rememberSaveable(urls) { mutableStateOf(false) }
    var showNewCategory by rememberSaveable(urls) { mutableStateOf(false) }
    var newCategory by rememberSaveable(urls) { mutableStateOf("") }
    var pendingCategory by rememberSaveable(urls) { mutableStateOf<String?>(null) }
    var completed by remember(urls) { mutableStateOf(emptySet<Int>()) }
    var isAnalyzing by remember(urls) { mutableStateOf(!analysisStopped && urls.isNotEmpty()) }
    var analyzerJob by remember { mutableStateOf<Job?>(null) }
    var editingTitleIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var editingTitle by rememberSaveable { mutableStateOf("") }

    val activeIndices = urls.indices.filterNot { it in removed }
    val defaultCategoryId = library.categories.firstOrNull { it.id == 1L }?.id ?: library.categories.firstOrNull()?.id ?: 1L
    val effectiveGlobalCategory = globalCategoryId.takeIf { id -> library.categories.any { it.id == id } } ?: defaultCategoryId
    fun individualCategory(index: Int): Long {
        categoryOverrides[index]?.takeIf { id -> library.categories.any { it.id == id } }?.let { return it }
        val item = metadata[index]
        CategoryRules.suggest(item.url, library.categories,
            listOf(item.title, item.description, item.keywords.joinToString(" "), item.suggestedCategory.orEmpty()).joinToString(" "))?.let { return it.id }
        return library.categories.firstOrNull { it.name.equals(metadata[index].suggestedCategory, true) }?.id ?: defaultCategoryId
    }
    fun stopAnalysis() {
        analysisStopped = true
        analyzerJob?.cancel()
        isAnalyzing = false
    }
    fun createCategory() {
        val name = newCategory.trim().take(60)
        if (name.isBlank()) return
        val existing = library.categories.firstOrNull { it.name.equals(name, true) }
        if (existing != null) {
            globalCategoryId = existing.id
            pendingCategory = null
        } else {
            pendingCategory = name
            onCreateCategory(name)
        }
        newCategory = ""
        showNewCategory = false
    }

    LaunchedEffect(urls) {
        if (analysisStopped) return@LaunchedEffect
        val semaphore = Semaphore(3)
        analyzerJob = launch {
            isAnalyzing = true
            try {
                supervisorScope {
                    urls.indices.map { index ->
                        launch {
                            semaphore.withPermit {
                                if (index in removed) return@withPermit
                                val result = try {
                                    MetadataExtractor.extract(urls[index])
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    MetadataExtractor.fromUrl(urls[index])
                                }
                                metadata = metadata.mapIndexed { current, item -> if (current == index) result else item }
                                completed = completed + index
                            }
                        }
                    }.joinAll()
                }
            } finally {
                isAnalyzing = false
            }
        }
    }
    LaunchedEffect(library.categories, pendingCategory) {
        pendingCategory?.let { name ->
            library.categories.firstOrNull { it.name.equals(name, true) }?.let {
                globalCategoryId = it.id
                pendingCategory = null
            }
        }
    }
    DisposableEffect(Unit) { onDispose { analyzerJob?.cancel() } }

    ModalBottomSheet(
        onDismissRequest = { stopAnalysis(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        DarkSheetBars()
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Link, null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Tutti i tuoi link, insieme.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${activeIndices.size} link pronti da organizzare", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { stopAnalysis(); onDismiss() }, enabled = !isSaving) { Icon(Icons.Rounded.Close, "Chiudi importazione") }
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = .08f), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (isAnalyzing) "Cerco titoli e anteprime…" else if (analysisStopped) "Puoi salvare subito" else "Dettagli pronti",
                                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                Text("${activeIndices.count { it in completed }} di ${activeIndices.size} link analizzati", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (isAnalyzing) TextButton(onClick = { stopAnalysis() }) { Text("Salta analisi") }
                        }
                        if (isAnalyzing) LinearProgressIndicator(
                            progress = { if (activeIndices.isEmpty()) 0f else activeIndices.count { it in completed }.toFloat() / activeIndices.size },
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                        )
                        Text("Puoi salvare anche senza attendere le anteprime.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 7.dp))
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Dove li mettiamo?", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !globalMode, onClick = { globalMode = false; pendingCategory = null },
                            label = { Text("Categorie suggerite") }, leadingIcon = if (!globalMode) ({ Icon(Icons.Rounded.Check, null, Modifier.size(15.dp)) }) else null)
                        FilterChip(selected = globalMode, onClick = { globalMode = true },
                            label = { Text("Una categoria per tutti") }, leadingIcon = if (globalMode) ({ Icon(Icons.Rounded.Check, null, Modifier.size(15.dp)) }) else null)
                    }
                    if (globalMode) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            library.categories.forEach { category ->
                                FilterChip(selected = effectiveGlobalCategory == category.id, onClick = { globalCategoryId = category.id; pendingCategory = null },
                                    label = { Text(category.name) }, leadingIcon = if (effectiveGlobalCategory == category.id) ({ Icon(Icons.Rounded.Check, null, Modifier.size(14.dp)) }) else null)
                            }
                        }
                        TextButton(onClick = { showNewCategory = !showNewCategory }) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Nuova categoria")
                        }
                        if (showNewCategory) Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(newCategory, { newCategory = it.take(60) }, label = { Text("Nome categoria") }, singleLine = true,
                                modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { createCategory() }))
                            IconButton(onClick = { createCategory() }, enabled = newCategory.isNotBlank()) { Icon(Icons.Rounded.Check, "Crea categoria") }
                        }
                        pendingCategory?.let { Text("Creazione di $it…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Text("Tocca la categoria sotto un link per cambiarla singolarmente. Keys e dettagli restano modificabili dopo il salvataggio.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                activeIndices.forEach { index ->
                    val item = metadata[index]
                    val categoryId = if (globalMode) effectiveGlobalCategory else individualCategory(index)
                    val category = library.categories.firstOrNull { it.id == categoryId }
                    var menuExpanded by remember(index) { mutableStateOf(false) }
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(17.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                                    item.thumbnail?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp)) }
                                }
                                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                                    Text(titleOverrides[index] ?: item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(listOfNotNull(LinkMatcher.domainOf(item.url), item.quality).joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                                }
                                IconButton(onClick = { removed = removed + index }, enabled = !isSaving, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Rounded.Close, "Rimuovi ${titleOverrides[index] ?: item.title}", Modifier.size(17.dp))
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    TextButton(onClick = { menuExpanded = true }, enabled = !isSaving) {
                                        Icon(Icons.Rounded.Folder, null, Modifier.size(15.dp)); Spacer(Modifier.width(5.dp))
                                        Text(category?.name ?: "Da organizzare", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp))
                                    }
                                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                        library.categories.forEach { option ->
                                            DropdownMenuItem(text = { Text(option.name) }, onClick = {
                                                if (globalMode) {
                                                    categoryOverrides = categoryOverrides + activeIndices.associateWith { effectiveGlobalCategory }
                                                    globalMode = false
                                                }
                                                categoryOverrides = categoryOverrides + (index to option.id)
                                                menuExpanded = false
                                            }, leadingIcon = { if (categoryId == option.id) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) })
                                        }
                                    }
                                }
                                IconButton(onClick = { editingTitleIndex = index; editingTitle = titleOverrides[index] ?: item.title }, enabled = !isSaving, modifier = Modifier.size(34.dp)) {
                                    Icon(Icons.Rounded.Edit, "Modifica titolo", Modifier.size(16.dp))
                                }
                            }
                            if (!globalMode && categoryOverrides[index] == null) {
                                val matchingRule = CategoryRules.suggest(item.url, library.categories,
                                    listOf(item.title, item.description, item.keywords.joinToString(" "), item.suggestedCategory.orEmpty()).joinToString(" "))
                                if (matchingRule != null) {
                                    Text("Regola della categoria: ${matchingRule.name}", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                } else item.suggestedCategory?.takeIf { suggestion -> library.categories.none { it.name.equals(suggestion, true) } }?.let { suggestion ->
                                    Text("Suggerita: $suggestion · per ora in Da organizzare", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (item.keywords.isNotEmpty()) Text(item.keywords.take(5).joinToString("  ") { "#$it" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (activeIndices.isEmpty()) Text("Hai rimosso tutti i link. Chiudi questa scheda per incollarne altri.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
            }

            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
                    Button(onClick = {
                        stopAnalysis()
                        onSave(activeIndices.map { index ->
                            val item = metadata[index]
                            VideoLink(url = item.url, title = (titleOverrides[index] ?: item.title).trim(), description = item.description,
                                thumbnail = item.thumbnail, categoryId = if (globalMode) effectiveGlobalCategory else individualCategory(index),
                                keys = item.keywords.take(24), quality = item.quality, duration = item.duration)
                        })
                    }, enabled = activeIndices.isNotEmpty() && !isSaving && (!globalMode || pendingCategory == null) && library.categories.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                        if (isSaving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Check, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Salva ${activeIndices.size} link", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    editingTitleIndex?.let { index ->
        AlertDialog(onDismissRequest = { editingTitleIndex = null }, title = { Text("Un titolo per ritrovarlo") },
            text = { OutlinedTextField(editingTitle, { editingTitle = it.take(500) }, label = { Text("Titolo") }, maxLines = 4,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) },
            confirmButton = { TextButton(onClick = { titleOverrides = titleOverrides + (index to editingTitle.trim()); editingTitleIndex = null }, enabled = editingTitle.isNotBlank()) { Text("Salva titolo") } },
            dismissButton = { TextButton(onClick = { editingTitleIndex = null }) { Text("Annulla") } })
    }
}
