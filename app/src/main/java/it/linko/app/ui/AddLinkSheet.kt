package it.linko.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.TipsAndUpdates
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import it.linko.app.data.LibraryState
import it.linko.app.data.CategoryRules
import it.linko.app.data.LinkMetadata
import it.linko.app.data.MetadataExtractor
import it.linko.app.data.VideoLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The same review flow is used for pasted links and Android's ACTION_SEND. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddLinkSheet(
    initialUrl: String = "",
    initialLink: VideoLink? = null,
    library: LibraryState,
    onDismiss: () -> Unit,
    onSave: (VideoLink) -> Unit,
    onCreateCategory: (String) -> Unit,
    onCreateKey: (String) -> Unit,
    isSaving: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val stringListSaver = listSaver<List<String>, String>(save = { it }, restore = { it })
    var url by rememberSaveable { mutableStateOf(initialLink?.url ?: initialUrl) }
    var title by rememberSaveable { mutableStateOf(initialLink?.title.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(initialLink?.description.orEmpty()) }
    var thumbnail by rememberSaveable { mutableStateOf(initialLink?.thumbnail) }
    var quality by rememberSaveable { mutableStateOf(initialLink?.quality) }
    var originalQuality by rememberSaveable { mutableStateOf(initialLink?.quality) }
    var qualityExpanded by rememberSaveable { mutableStateOf(false) }
    var duration by rememberSaveable { mutableStateOf(initialLink?.duration) }
    var categoryId by rememberSaveable { mutableStateOf(initialLink?.categoryId ?: 1L) }
    var selectedKeys by rememberSaveable(stateSaver = stringListSaver) { mutableStateOf(initialLink?.keys.orEmpty()) }
    var suggestedKeys by rememberSaveable(stateSaver = stringListSaver) { mutableStateOf(emptyList<String>()) }
    var suggestedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var matchedRuleName by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryManuallyChosen by rememberSaveable { mutableStateOf(initialLink != null) }
    var metadataSource by rememberSaveable { mutableStateOf<String?>(null) }
    var metadataWarning by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewing by rememberSaveable { mutableStateOf(initialLink != null) }
    var initialAnalysisStarted by rememberSaveable { mutableStateOf(initialLink != null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var fetchJob by remember { mutableStateOf<Job?>(null) }
    var showNewCategory by rememberSaveable { mutableStateOf(false) }
    var newCategory by rememberSaveable { mutableStateOf("") }
    var pendingCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var newKey by rememberSaveable { mutableStateOf("") }

    fun applyMetadata(result: LinkMetadata) {
        metadataSource = result.source
        metadataWarning = result.warning
        suggestedKeys = result.keywords
        val matchingRule = CategoryRules.suggest(result.url, library.categories,
            listOf(result.title, result.description, result.keywords.joinToString(" "), result.suggestedCategory.orEmpty()).joinToString(" "))
        matchedRuleName = matchingRule?.name
        suggestedCategory = matchingRule?.name ?: result.suggestedCategory?.trim()?.takeIf { it.isNotBlank() }
        url = result.url
        title = result.title
        description = result.description
        thumbnail = result.thumbnail
        quality = result.quality
        originalQuality = result.quality
        duration = result.duration
        selectedKeys = result.keywords.take(12)
        if (!categoryManuallyChosen) {
            categoryId = matchingRule?.id ?: library.categories.firstOrNull {
                it.name.equals(result.suggestedCategory, ignoreCase = true)
            }?.id ?: library.categories.firstOrNull { it.id == 1L }?.id ?: library.categories.firstOrNull()?.id ?: 1L
        }
        reviewing = true
    }

    fun analyze(manual: Boolean = false) {
        val extracted = MetadataExtractor.extractUrl(url)
        if (extracted == null) {
            error = "Inserisci un link completo che inizi con https:// o http://."
            return
        }
        error = null
        fetchJob?.cancel()
        fetchJob = scope.launch {
            loading = true
            try {
                applyMetadata(if (manual) MetadataExtractor.fromUrl(extracted) else MetadataExtractor.extract(extracted))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                applyMetadata(MetadataExtractor.fromUrl(extracted).copy(
                    warning = "Anteprima non disponibile. Puoi comunque organizzare e salvare il link."
                ))
            } finally {
                loading = false
            }
        }
    }

    fun createCategory() {
        val name = newCategory.trim().take(60)
        if (name.isBlank()) return
        library.categories.firstOrNull { it.name.equals(name, true) }?.let {
            categoryId = it.id
            categoryManuallyChosen = true
            showNewCategory = false
            newCategory = ""
            return
        }
        pendingCategory = name
        onCreateCategory(name)
        newCategory = ""
        showNewCategory = false
    }

    fun createKey() {
        val value = newKey.trim().removePrefix("#").trim().take(40)
        if (value.isBlank()) return
        val canonical = library.keys.firstOrNull { it.equals(value, true) } ?: value
        if (selectedKeys.none { it.equals(canonical, true) }) selectedKeys = selectedKeys + canonical
        onCreateKey(canonical)
        newKey = ""
    }

    LaunchedEffect(initialUrl) {
        if (!initialAnalysisStarted && initialLink == null && initialUrl.isNotBlank()) {
            initialAnalysisStarted = true
            analyze()
        }
    }
    LaunchedEffect(library.categories, pendingCategory) {
        pendingCategory?.let { name ->
            library.categories.firstOrNull { it.name.equals(name, true) }?.let {
                categoryId = it.id
                categoryManuallyChosen = true
                pendingCategory = null
            }
        }
        if (library.categories.none { it.id == categoryId }) categoryId = library.categories.firstOrNull()?.id ?: 1L
    }
    DisposableEffect(Unit) { onDispose { fetchJob?.cancel() } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        DarkSheetBars()
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(15.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Link, null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (initialLink == null) "Un bel link da tenere." else "Ogni link, al suo posto.",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (initialLink == null) "Salvalo oggi. Ritrovalo quando vuoi." else "Modifica i dettagli della raccolta.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Chiudi") }
            }

            OutlinedTextField(
                value = url,
                onValueChange = {
                    fetchJob?.cancel()
                    loading = false
                    url = it
                    reviewing = false
                    error = null
                    metadataSource = null
                    metadataWarning = null
                    suggestedKeys = emptyList()
                    suggestedCategory = null
                    matchedRuleName = null
                },
                label = { Text("Link del video") },
                placeholder = { Text("https://…") },
                leadingIcon = { Icon(Icons.Rounded.Link, null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                isError = error != null,
                supportingText = { error?.let { Text(it) } },
                maxLines = 3,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (!loading) analyze() })
            )

            if (loading) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Column(Modifier.weight(1f)) {
                            Text("Cerco i dettagli…", style = MaterialTheme.typography.titleSmall)
                            Text("Titolo, anteprima e parole chiave", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { fetchJob?.cancel(); loading = false }) { Text("Annulla") }
                    }
                }
            } else if (!reviewing) {
                Button(
                    onClick = { analyze() },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Continua", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                }
                TextButton(onClick = { analyze(manual = true) }, enabled = url.isNotBlank(), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Inserisci i dettagli manualmente")
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.TipsAndUpdates, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            "Da Chrome puoi anche toccare Condividi → Linko: il link arriverà direttamente qui.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (reviewing && !loading) {
                metadataSource?.let { source ->
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = .08f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.TipsAndUpdates, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    metadataWarning ?: "Controlla i suggerimenti e scegli cosa tenere.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { analyze() }) { Icon(Icons.Rounded.Refresh, "Riprova a leggere i dettagli") }
                        }
                    }
                }

                thumbnail?.takeIf { it.isNotBlank() }?.let { image ->
                    AsyncImage(
                        model = image,
                        contentDescription = "Anteprima del video",
                        modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentScale = ContentScale.Crop
                    )
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(500) },
                    label = { Text("Titolo") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    maxLines = 3,
                    supportingText = if (title.isBlank()) ({ Text("Dai un nome al link prima di salvarlo.") }) else null
                )

                duration?.let {
                    AssistChip(onClick = {}, label = { Text(it) }, leadingIcon = { Icon(Icons.Rounded.Schedule, null, Modifier.size(16.dp)) })
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Movie, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("Qualità", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { qualityExpanded = !qualityExpanded }) {
                            Text(if (qualityExpanded) "Chiudi" else quality?.let { "$it · Modifica" } ?: "Imposta qualità")
                        }
                    }
                    if (qualityExpanded) {
                        Text("Se il sito non la dichiara, puoi indicarla tu.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val qualityChoices = (listOf("360p", "480p", "720p", "1080p", "1440p", "4K") + listOfNotNull(originalQuality, quality))
                            .distinctBy { it.lowercase() }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            FilterChip(selected = quality == null, onClick = { quality = null }, label = { Text("Non indicata") },
                                leadingIcon = if (quality == null) ({ Icon(Icons.Rounded.Check, null, Modifier.size(14.dp)) }) else null)
                            qualityChoices.forEach { option ->
                                val selected = quality.equals(option, ignoreCase = true)
                                FilterChip(selected = selected, onClick = { quality = option }, label = { Text(option) },
                                    leadingIcon = if (selected) ({ Icon(Icons.Rounded.Check, null, Modifier.size(14.dp)) }) else null)
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Dove lo mettiamo?", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { showNewCategory = !showNewCategory }) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Categoria")
                        }
                    }
                    suggestedCategory?.let { suggestion ->
                        val existing = library.categories.firstOrNull { it.name.equals(suggestion, true) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (matchedRuleName != null) "Regola della categoria: $suggestion"
                                else if (existing == null) "Categoria suggerita: $suggestion" else "Suggerita: ${existing.name}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (existing == null) {
                                TextButton(onClick = { newCategory = suggestion.take(60); showNewCategory = true }) {
                                    Text("Crea categoria")
                                }
                            } else if (categoryId != existing.id) {
                                TextButton(onClick = { categoryId = existing.id; pendingCategory = null; categoryManuallyChosen = true }) {
                                    Text("Seleziona")
                                }
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        library.categories.forEach { category ->
                            FilterChip(
                                selected = categoryId == category.id,
                                onClick = { categoryId = category.id; pendingCategory = null; categoryManuallyChosen = true },
                                label = { Text(category.name) },
                                leadingIcon = if (categoryId == category.id) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }) else null
                            )
                        }
                    }
                    if (showNewCategory) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = newCategory,
                                onValueChange = { newCategory = it.take(60) },
                                label = { Text("Nuova categoria") },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { createCategory() })
                            )
                            IconButton(onClick = { createCategory() }, enabled = newCategory.isNotBlank()) {
                                Icon(Icons.Rounded.Check, "Crea categoria", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Le parole per ritrovarlo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Tocca le keys che vuoi associare. Sono facoltative.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val options = (selectedKeys + suggestedKeys + library.keys).distinctBy { it.lowercase() }.take(80)
                    if (options.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            options.forEach { key ->
                                val selected = selectedKeys.any { it.equals(key, true) }
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedKeys = if (selected) selectedKeys.filterNot { it.equals(key, true) } else selectedKeys + key },
                                    label = { Text("#${key}") },
                                    leadingIcon = if (selected) ({ Icon(Icons.Rounded.Check, null, Modifier.size(14.dp)) }) else null
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newKey,
                            onValueChange = { newKey = it.take(40) },
                            label = { Text("Aggiungi una key") },
                            placeholder = { Text("es. street-food") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { createKey() })
                        )
                        IconButton(onClick = { createKey() }, enabled = newKey.isNotBlank()) {
                            Icon(Icons.Rounded.Add, "Crea e seleziona key", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(5000) },
                    label = { Text("Descrizione o note · facoltative") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    maxLines = 4
                )
                Button(
                    onClick = {
                        val normalized = MetadataExtractor.extractUrl(url)
                        if (normalized == null) {
                            error = "Controlla l’indirizzo del link."
                        } else if (title.isNotBlank()) {
                            onSave(
                                VideoLink(
                                    id = initialLink?.id ?: 0L,
                                    url = normalized,
                                    title = title.trim(),
                                    description = description.trim(),
                                    thumbnail = thumbnail,
                                    categoryId = categoryId,
                                    keys = selectedKeys,
                                    quality = quality,
                                    duration = duration,
                                    favorite = initialLink?.favorite ?: false,
                                    createdAt = initialLink?.createdAt ?: System.currentTimeMillis()
                                )
                            )
                        }
                    },
                    enabled = title.isNotBlank() && !isSaving && pendingCategory == null && library.categories.any { it.id == categoryId },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (isSaving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.TaskAlt, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (initialLink == null) "Salva nella raccolta" else "Salva le modifiche", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
