package com.ftapps.remft.domain.notes.export

import com.ftapps.remft.domain.notes.markdown.AlertKind

data class NoteExportLabels(
    val alertNote: String,
    val alertTip: String,
    val alertImportant: String,
    val alertWarning: String,
    val alertCaution: String,
    val mediaMissing: String,
    val unsupportedBlock: String,
    val frontMatterTitle: String = "",
) {
    fun alert(kind: AlertKind): String = when (kind) {
        AlertKind.Note -> alertNote
        AlertKind.Tip -> alertTip
        AlertKind.Important -> alertImportant
        AlertKind.Warning -> alertWarning
        AlertKind.Caution -> alertCaution
    }
}
