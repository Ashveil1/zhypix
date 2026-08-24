package com.example.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration

fun parseAnsiToAnnotatedString(text: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var i = 0
    val n = text.length
    
    var isBold = false
    var isItalic = false
    var isUnderline = false
    var isInverse = false
    var fgColor: Color? = null
    var bgColor: Color? = null
    
    val ansiColors = mapOf(
        0 to Color(0xFF000000),   // Black
        1 to Color(0xFFEF4444),   // Red
        2 to Color(0xFF22C55E),   // Green
        3 to Color(0xFFEAB308),   // Yellow
        4 to Color(0xFF3B82F6),   // Blue
        5 to Color(0xFFD946EF),   // Magenta
        6 to Color(0xFF06B6D4),   // Cyan
        7 to Color(0xFFF4F4F5),   // White (zinc-100)
        
        8 to Color(0xFF71717A),   // Bright Black (Gray)
        9 to Color(0xFFF87171),   // Bright Red
        10 to Color(0xFF4ADE80),  // Bright Green
        11 to Color(0xFFFACC15),  // Bright Yellow
        12 to Color(0xFF60A5FA),  // Bright Blue
        13 to Color(0xFFF472B6),  // Bright Magenta
        14 to Color(0xFF22D3EE),  // Bright Cyan
        15 to Color(0xFFFFFFFF)   // Bright White
    )
    
    val currentSegment = java.lang.StringBuilder()
    
    fun flushSegment() {
        if (currentSegment.isEmpty()) return
        val segmentText = currentSegment.toString()
        
        val style = SpanStyle(
            color = if (isInverse) (bgColor ?: Color.Black) else (fgColor ?: Color.White),
            background = if (isInverse) (fgColor ?: Color.White) else (bgColor ?: Color.Transparent),
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (isUnderline) TextDecoration.Underline else TextDecoration.None
        )
        
        val start = builder.length
        builder.append(segmentText)
        builder.addStyle(style, start, builder.length)
        currentSegment.setLength(0)
    }
    
    while (i < n) {
        val c = text[i]
        if (c == '\u001B' || c == '\u001b') {
            if (i + 1 < n && text[i + 1] == '[') {
                flushSegment()
                var j = i + 2
                while (j < n && text[j].code in 32..63) {
                    j++
                }
                if (j < n) {
                    val endChar = text[j]
                    val seq = text.substring(i + 2, j)
                    i = j
                    
                    if (endChar == 'm') {
                        val parts = seq.split(';', ':')
                        var pIdx = 0
                        while (pIdx < parts.size) {
                            val partStr = parts[pIdx]
                            if (partStr.isEmpty()) {
                                pIdx++
                                continue
                            }
                            val code = partStr.toIntOrNull() ?: 0
                            when (code) {
                                0 -> {
                                    isBold = false
                                    isItalic = false
                                    isUnderline = false
                                    isInverse = false
                                    fgColor = null
                                    bgColor = null
                                }
                                1 -> isBold = true
                                2 -> isBold = false
                                3 -> isItalic = true
                                4 -> isUnderline = true
                                7 -> isInverse = true
                                22 -> isBold = false
                                23 -> isItalic = false
                                24 -> isUnderline = false
                                27 -> isInverse = false
                                in 30..37 -> fgColor = ansiColors[code - 30]
                                38 -> {
                                    if (pIdx + 1 < parts.size) {
                                        val type = parts[pIdx + 1].toIntOrNull()
                                        if (type == 5 && pIdx + 2 < parts.size) {
                                            val colorIdx = parts[pIdx + 2].toIntOrNull() ?: 0
                                            fgColor = ansiColors[colorIdx % 16]
                                            pIdx += 2
                                        } else if (type == 2 && pIdx + 4 < parts.size) {
                                            val r = parts[pIdx + 2].toIntOrNull() ?: 0
                                            val g = parts[pIdx + 3].toIntOrNull() ?: 0
                                            val b = parts[pIdx + 4].toIntOrNull() ?: 0
                                            fgColor = Color(r, g, b)
                                            pIdx += 4
                                        }
                                    }
                                }
                                39 -> fgColor = null
                                in 40..47 -> bgColor = ansiColors[code - 40]
                                48 -> {
                                    if (pIdx + 1 < parts.size) {
                                        val type = parts[pIdx + 1].toIntOrNull()
                                        if (type == 5 && pIdx + 2 < parts.size) {
                                            val colorIdx = parts[pIdx + 2].toIntOrNull() ?: 0
                                            bgColor = ansiColors[colorIdx % 16]
                                            pIdx += 2
                                        } else if (type == 2 && pIdx + 4 < parts.size) {
                                            val r = parts[pIdx + 2].toIntOrNull() ?: 0
                                            val g = parts[pIdx + 3].toIntOrNull() ?: 0
                                            val b = parts[pIdx + 4].toIntOrNull() ?: 0
                                            bgColor = Color(r, g, b)
                                            pIdx += 4
                                        }
                                    }
                                }
                                49 -> bgColor = null
                                in 90..97 -> fgColor = ansiColors[code - 90 + 8]
                                in 100..107 -> bgColor = ansiColors[code - 100 + 8]
                            }
                            pIdx++
                        }
                    }
                } else {
                    currentSegment.append(c)
                    currentSegment.append('[')
                    i += 1
                }
            } else {
                currentSegment.append(c)
            }
        } else {
            currentSegment.append(c)
        }
        i++
    }
    
    flushSegment()
    if (builder.length == 0) {
        return AnnotatedString("")
    }
    return builder.toAnnotatedString()
}
