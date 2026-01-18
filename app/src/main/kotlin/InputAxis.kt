package org.example.app

internal fun axis(neg: Boolean, pos: Boolean): Int =
    when {
        neg && !pos -> -1
        pos && !neg -> 1
        else -> 0
    }

