package mg.madagestion.core

import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
