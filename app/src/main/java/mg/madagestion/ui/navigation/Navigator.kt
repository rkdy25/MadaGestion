package mg.madagestion.ui.navigation

import androidx.compose.runtime.mutableStateListOf

class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    val atRoot: Boolean get() = stack.size <= 1

    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replace(s: Screen) { stack[stack.lastIndex] = s }
    fun root(s: Screen) {
        stack.clear()
        stack.add(s)
    }
}
