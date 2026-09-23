// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.ui.unit.TextUnit

/** Limit unit in [low..high] bounds */
fun TextUnit.coerceIn(low: TextUnit, high: TextUnit): TextUnit = if (this < low) low else if (this > high) high else this

/** Returns a copy with the element at index replaced; an out-of-range index leaves the list unchanged. */
fun <T> List<T>.replaced(index: Int, value: T): List<T> = mapIndexed { i, item -> if (i == index) value else item }
