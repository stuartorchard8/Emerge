package org.emerge.desktop

internal fun axis(neg: Boolean, pos: Boolean): Int =
    when {
        neg && !pos -> -Int.MAX_VALUE
        pos && !neg -> Int.MAX_VALUE
        else -> 0
    }

