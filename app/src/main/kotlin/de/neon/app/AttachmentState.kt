package de.neon.app

/** Was gerade angehängt ist — für die Oberfläche. */
data class AttachmentState(
    val busy: Boolean = false,
    val files: List<String> = emptyList(),
    val chunkCount: Int = 0,
    /** Das Ergebnis der letzten Aufnahme, einschließlich dessen, was nicht ging. */
    val message: String? = null,
)
