package com.saurabhtiwari.voicetranscriber

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Everything the History screen needs for the Phase 2 features, bundled so `HistoryContent` keeps one
 * extra parameter instead of twenty. Built from [MeetingViewModel] in one place.
 */
internal class HistoryTools(
    val query: String,
    val onQueryChange: (String) -> Unit,
    val searching: Boolean,
    val deviceText: Map<String, String>,
    val remoteMatchIds: Set<String>,
    val player: PlayerState,
    val onTogglePlay: () -> Unit,
    val onSeekMs: (Int) -> Unit,
    val onPlayFrom: (Double) -> Unit,
    val speakerAliases: Map<String, String>,
    val onRenameSpeaker: (speakerId: String, name: String) -> Unit,
    val onRenameTitle: (String) -> Unit,
    val onEditLocalText: (String) -> Unit,
    val onDeleteCurrent: () -> Unit,
    val onClose: () -> Unit,
    val exportDocument: () -> ExportDocument?,
    val onCancelJob: (RecordingItem) -> Unit,
    val onRemoveItem: (RecordingItem) -> Unit,
    val onLeave: () -> Unit
)

private val DangerRed = Color(0xFFB33C46)

/** The database limits meeting titles to 500 characters. */
private const val MAX_TITLE_CHARS = 500

// ---- search ------------------------------------------------------------------------------------

@Composable
internal fun HistorySearchField(query: String, searching: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Search titles and transcripts") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (searching) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        }
    )
}

// ---- playback ----------------------------------------------------------------------------------

/** Play/pause and a seek bar for the open recording's audio; nothing is drawn when there is no audio. */
@Composable
internal fun PlayerBar(state: PlayerState, onToggle: () -> Unit, onSeekMs: (Int) -> Unit) {
    if (state.error.isNotEmpty()) {
        Text(state.error, color = Amber, style = MaterialTheme.typography.labelSmall)
        return
    }
    if (!state.loaded) return
    var dragging by remember { mutableStateOf<Float?>(null) }
    val position = dragging?.toInt() ?: state.positionMs
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onToggle) {
            Icon(
                if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (state.playing) "Pause audio" else "Play audio",
                tint = Blue
            )
        }
        Slider(
            value = (dragging ?: state.positionMs.toFloat()).coerceIn(0f, state.durationMs.coerceAtLeast(1).toFloat()),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeekMs(it.toInt()) }
                dragging = null
            },
            valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(),
            modifier = Modifier.weight(1f)
        )
        Text(
            clock(position) + " / " + clock(state.durationMs),
            color = Muted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

private fun clock(milliseconds: Int): String = SpeakerTranscripts.formatTimestamp(milliseconds / 1000.0)

// ---- transcript --------------------------------------------------------------------------------

/**
 * Timestamped transcript. Lines are tappable when [canSeek] (the audio is on this phone); the line
 * being played is highlighted. With speaker labels, a speaker summary is shown and each speaker can be renamed.
 */
@Composable
internal fun TimedTranscript(
    transcript: DisplayTranscript,
    activeIndex: Int,
    canSeek: Boolean,
    onPlayFrom: (Double) -> Unit,
    onSpeakerClick: (SpeakerStat) -> Unit,
    query: String = "",
    matchesOnly: Boolean = false
) {
    val filtering = matchesOnly && query.isNotBlank()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (transcript.hasSpeakers) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SPEAKERS", color = Muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                transcript.speakers.forEach { speaker ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSpeakerClick(speaker) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(speakerColor(speaker.colorIndex)))
                        Text(speaker.label, color = Ink, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text("${(speaker.share * 100).toInt()}%", color = Muted, style = MaterialTheme.typography.labelSmall)
                        Icon(Icons.Default.Edit, contentDescription = "Rename ${speaker.label}", tint = Muted, modifier = Modifier.size(14.dp))
                    }
                    LinearProgressIndicator(
                        progress = { speaker.share.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = speakerColor(speaker.colorIndex),
                        trackColor = Border
                    )
                }
            }
        }
        transcript.turns.forEachIndexed { index, turn ->
            if (filtering && !TranscriptFind.contains(turn.text, query)) return@forEachIndexed
            val rowModifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (index == activeIndex) BlueTint else Color.Transparent)
                .let { if (canSeek) it.clickable { onPlayFrom(turn.startSeconds) } else it }
                .padding(horizontal = 6.dp, vertical = 4.dp)
            Column(rowModifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (transcript.hasSpeakers) {
                        Text(
                            turn.label ?: "Unlabeled",
                            color = speakerColor(turn.colorIndex),
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        SpeakerTranscripts.formatTimestamp(turn.startSeconds),
                        color = Muted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                HighlightedText(turn.text, query, Ink, MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** [text] with every match of [query] highlighted; plain text when the query is blank. */
@Composable
internal fun HighlightedText(text: String, query: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    val annotated = remember(text, query) { highlight(text, query) }
    Text(annotated, color = color, style = style, modifier = modifier)
}

private fun highlight(text: String, query: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (range in TranscriptFind.ranges(text, query)) {
        append(text.substring(cursor, range.first))
        withStyle(SpanStyle(background = AmberTint, color = Ink, fontWeight = FontWeight.SemiBold)) {
            append(text.substring(range.first, range.last + 1))
        }
        cursor = range.last + 1
    }
    append(text.substring(cursor))
}

/** Find box with a match count and a switch between highlighting matches and showing only them. */
@Composable
internal fun FindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    matchesOnly: Boolean,
    onToggleMatchesOnly: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Find in this transcript") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear find")
                    }
                }
            }
        )
        if (query.isNotBlank()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    when (matchCount) {
                        0 -> "No matches"
                        1 -> "1 match"
                        else -> "$matchCount matches"
                    },
                    color = Muted,
                    style = MaterialTheme.typography.labelSmall
                )
                if (matchCount > 0) {
                    TextButton(onClick = onToggleMatchesOnly) {
                        Text(if (matchesOnly) "Show full transcript" else "Show only matches")
                    }
                }
            }
        }
    }
}

/** A transcript without timestamps (an on-device transcript): find, highlight and filter by sentence. */
@Composable
internal fun PlainTranscriptView(text: String, resetKey: Any) {
    var query by remember(resetKey) { mutableStateOf("") }
    var matchesOnly by remember(resetKey) { mutableStateOf(false) }
    val matchCount = remember(text, query) { TranscriptFind.ranges(text, query).size }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FindBar(query, { query = it }, matchCount, matchesOnly) { matchesOnly = !matchesOnly }
        val shown = if (matchesOnly && query.isNotBlank()) {
            TranscriptFind.matchingSentences(text, query).joinToString("\n\n")
        } else {
            text
        }
        HighlightedText(shown, query, Ink, MaterialTheme.typography.bodyMedium)
    }
}

/** The open meeting's transcript: timestamped (tap to play), speaker names editable, current line highlighted. */
@Composable
internal fun MeetingTranscriptView(meeting: MeetingDetail, tools: HistoryTools) {
    val display = remember(meeting.meetingId, meeting.segments, meeting.speakers, tools.speakerAliases) {
        DisplayTranscripts.build(meeting.segments, meeting.speakers, tools.speakerAliases)
    }
    if (display.isEmpty) {
        PlainTranscriptView(meeting.transcript.ifBlank { "Transcript unavailable." }, meeting.meetingId)
        return
    }
    var query by remember(meeting.meetingId) { mutableStateOf("") }
    var matchesOnly by remember(meeting.meetingId) { mutableStateOf(false) }
    val matchCount = remember(display, query) { TranscriptFind.count(display.turns, query) }
    FindBar(query, { query = it }, matchCount, matchesOnly) { matchesOnly = !matchesOnly }
    val starts = remember(display) { display.turns.map { it.startSeconds } }
    val player = tools.player
    val active = if (player.loaded && (player.playing || player.positionMs > 0)) Playback.activeIndex(starts, player.positionMs) else -1
    var renaming by remember { mutableStateOf<SpeakerStat?>(null) }
    TimedTranscript(
        transcript = display,
        activeIndex = active,
        canSeek = player.loaded,
        onPlayFrom = tools.onPlayFrom,
        onSpeakerClick = { renaming = it },
        query = query,
        matchesOnly = matchesOnly
    )
    renaming?.let { speaker ->
        TextInputDialog(
            title = "Rename speaker", label = "Name (leave empty to reset)", initial = speaker.label,
            confirmLabel = "Save", singleLine = true, allowBlank = true,
            onConfirm = { tools.onRenameSpeaker(speaker.speakerId, it); renaming = null },
            onDismiss = { renaming = null }
        )
    }
}

// ---- dialogs -----------------------------------------------------------------------------------

@Composable
internal fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    singleLine: Boolean,
    allowBlank: Boolean,
    maxLength: Int = Int.MAX_VALUE,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(maxLength) },
                label = { Text(label) },
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 12,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = allowBlank || text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = DangerRed)) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---- actions on the open recording -------------------------------------------------------------

private enum class ToolDialog { RENAME, EDIT_TEXT, DELETE }

/**
 * Share, copy, save, rename, edit and delete for the open recording, with the dialogs they need.
 * Sharing always sends plain text (JSON included) so every app offers itself as a target; copying and saving
 * use Markdown or JSON. Very long transcripts are not pushed through the share sheet (the system limits how
 * much data an intent can carry); they can be saved instead.
 */
@Composable
internal fun MeetingActionBar(
    title: String,
    tools: HistoryTools,
    editableText: String?,
    isLocalOnly: Boolean
) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<ToolDialog?>(null) }
    var pendingSave by remember { mutableStateOf<String?>(null) }
    var pendingPdf by remember { mutableStateOf<ExportDocument?>(null) }
    val saveSaved: (Uri?) -> Unit = { uri ->
        val text = pendingSave
        pendingSave = null
        if (uri != null && text != null) writeExport(context, uri, text)
    }
    val pdfSaved: (Uri?) -> Unit = { uri ->
        val document = pendingPdf
        pendingPdf = null
        if (uri != null && document != null) {
            try {
                TranscriptPdfExport.write(context, uri, document)
                toast(context, "PDF saved")
            } catch (_: Exception) {
                toast(context, "Could not create the PDF.")
            }
        }
    }
    val markdownSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown"), saveSaved)
    val jsonSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), saveSaved)
    val pdfSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf"), pdfSaved)
    var menuOpen by remember { mutableStateOf(false) }

    // Closes the menu, then runs [block] with the open recording's export document (nothing if it vanished).
    fun export(block: (ExportDocument) -> Unit) {
        menuOpen = false
        tools.exportDocument()?.let(block)
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            ActionIcon(Icons.Default.Share, "Share or export") { menuOpen = true }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Share as text") },
                    onClick = { export { shareText(context, it.title, TranscriptExport.plainText(it)) } }
                )
                DropdownMenuItem(
                    text = { Text("Copy as Markdown") },
                    onClick = { export { copyText(context, it.title, TranscriptExport.markdown(it)) } }
                )
                DropdownMenuItem(
                    text = { Text("Save Markdown file") },
                    onClick = {
                        export {
                            pendingSave = TranscriptExport.markdown(it)
                            markdownSaver.launch(TranscriptExport.fileName(it.title, "md"))
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Share as JSON") },
                    onClick = { export { shareText(context, it.title + " (JSON)", TranscriptExport.json(it)) } }
                )
                DropdownMenuItem(
                    text = { Text("Save JSON file") },
                    onClick = {
                        export {
                            pendingSave = TranscriptExport.json(it)
                            jsonSaver.launch(TranscriptExport.fileName(it.title, "json"))
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Save PDF file") },
                    onClick = {
                        export {
                            pendingPdf = it
                            pdfSaver.launch(TranscriptExport.fileName(it.title, "pdf"))
                        }
                    }
                )
            }
        }
        ActionIcon(Icons.Default.Edit, "Rename") { dialog = ToolDialog.RENAME }
        if (editableText != null) ActionIcon(Icons.Default.EditNote, "Edit transcript text") { dialog = ToolDialog.EDIT_TEXT }
        ActionIcon(Icons.Default.Delete, "Delete", tint = DangerRed) { dialog = ToolDialog.DELETE }
        Box(Modifier.weight(1f))
        ActionIcon(Icons.Default.Close, "Close", onClick = tools.onClose)
    }

    when (dialog) {
        ToolDialog.RENAME -> TextInputDialog(
            title = "Rename recording", label = "Title", initial = title, confirmLabel = "Rename",
            singleLine = true, allowBlank = false, maxLength = MAX_TITLE_CHARS,
            onConfirm = { tools.onRenameTitle(it); dialog = null },
            onDismiss = { dialog = null }
        )
        ToolDialog.EDIT_TEXT -> TextInputDialog(
            title = "Edit transcript", label = "Transcript", initial = editableText.orEmpty(), confirmLabel = "Save",
            singleLine = false, allowBlank = false,
            onConfirm = { tools.onEditLocalText(it); dialog = null },
            onDismiss = { dialog = null }
        )
        ToolDialog.DELETE -> ConfirmDialog(
            title = "Delete this recording?",
            message = if (isLocalOnly) {
                "The transcript on this phone and its audio will be deleted. This cannot be undone."
            } else {
                "The transcript and notes in your workspace will be deleted, along with any audio saved on this phone. " +
                    "This cannot be undone."
            },
            confirmLabel = "Delete",
            onConfirm = { tools.onDeleteCurrent(); dialog = null },
            onDismiss = { dialog = null }
        )
        null -> Unit
    }
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color = Muted,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = description, tint = tint)
    }
}

// ---- export plumbing ---------------------------------------------------------------------------

private const val SHARE_TEXT_LIMIT = 150_000
private const val CLIPBOARD_TEXT_LIMIT = 400_000

private fun shareText(context: Context, subject: String, text: String) {
    if (text.length > SHARE_TEXT_LIMIT) {
        toast(context, "Too long to share as text. Use one of the Save file options instead.")
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Share transcript"))
    } catch (_: Exception) {
        toast(context, "No app available to share with.")
    }
}

private fun copyText(context: Context, label: String, text: String) {
    if (text.length > CLIPBOARD_TEXT_LIMIT) {
        toast(context, "Too long to copy. Use one of the Save file options instead.")
        return
    }
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(context, "Copied as Markdown")
    } catch (_: Exception) {
        toast(context, "Could not copy this transcript.")
    }
}

private fun writeExport(context: Context, uri: Uri, text: String) {
    try {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw java.io.IOException("No output stream")
        toast(context, "Saved")
    } catch (_: Exception) {
        toast(context, "Could not save the file.")
    }
}

private fun toast(context: Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
