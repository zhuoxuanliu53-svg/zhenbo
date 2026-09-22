package app.jingting.localplayer.core

/** Selection is an ordered sequence, independent of display order and filtering. */
object SelectionOrder {
    fun toggle(selected: List<String>, id: String): List<String> =
        if (id in selected) selected.filterNot { it == id } else selected + id
    fun selectAll(selected: List<String>, visible: List<String>): List<String> =
        selected + visible.filterNot { it in selected }.distinct()
    fun moveFirst(existing: List<String>, selected: List<String>): List<String> =
        selected.filter { it in existing }.distinct() + existing.filterNot { it in selected }
}
