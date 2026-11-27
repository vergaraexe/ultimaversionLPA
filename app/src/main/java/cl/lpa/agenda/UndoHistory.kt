package cl.lpa.agenda

/**
 * Historial para DESHACER: guarda una copia de los trazos justo antes de cada cambio
 * (dibujar, borrar con la goma, limpiar). Asi "Deshacer" tambien devuelve lo que se borro con la goma.
 * [owner] identifica a quien pertenecen los trazos (la hoja del acta, o Unit en la agenda).
 */
class UndoHistory {
    private val stack = ArrayList<Pair<Any, List<InkStroke>>>()

    fun push(owner: Any, strokes: List<InkStroke>) {
        stack.add(Pair(owner, strokes.toList()))
        if (stack.size > 80) stack.removeAt(0)
    }

    fun pop(): Pair<Any, List<InkStroke>>? = if (stack.isEmpty()) null else stack.removeAt(stack.lastIndex)
}
