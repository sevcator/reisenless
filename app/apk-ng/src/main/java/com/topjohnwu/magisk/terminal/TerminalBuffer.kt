package com.topjohnwu.magisk.terminal

import java.util.Arrays







class TerminalBuffer(columns: Int, totalRows: Int, screenRows: Int) {

    var lines: Array<TerminalRow?>


    var totalRows: Int = totalRows
        private set


    var screenRows: Int = screenRows

    var columns: Int = columns


    var activeTranscriptRows: Int = 0
        private set


    private var screenFirstRow = 0

    init {
        lines = arrayOfNulls(totalRows)
        blockSet(0, 0, columns, screenRows, ' '.code, TextStyle.NORMAL)
    }

    val transcriptText: String
        get() = getSelectedText(0, -activeTranscriptRows, columns, screenRows).trim()

    val transcriptTextWithoutJoinedLines: String
        get() = getSelectedText(0, -activeTranscriptRows, columns, screenRows, false).trim()

    val transcriptTextWithFullLinesJoined: String
        get() = getSelectedText(0, -activeTranscriptRows, columns, screenRows, joinBackLines = true, joinFullLines = true).trim()

    fun getSelectedText(selX1: Int, selY1: Int, selX2: Int, selY2: Int, joinBackLines: Boolean = true, joinFullLines: Boolean = false): String {
        val builder = StringBuilder()

        var y1 = selY1
        var y2 = selY2
        if (y1 < -activeTranscriptRows) y1 = -activeTranscriptRows
        if (y2 >= screenRows) y2 = screenRows - 1

        for (row in y1..y2) {
            val x1 = if (row == y1) selX1 else 0
            var x2: Int
            if (row == y2) {
                x2 = selX2 + 1
                if (x2 > columns) x2 = columns
            } else {
                x2 = columns
            }
            val lineObject = lines[externalToInternalRow(row)]!!
            val x1Index = lineObject.findStartOfColumn(x1)
            var x2Index = if (x2 < columns) lineObject.findStartOfColumn(x2) else lineObject.spaceUsed
            if (x2Index == x1Index) {
                x2Index = lineObject.findStartOfColumn(x2 + 1)
            }
            val line = lineObject.text
            var lastPrintingCharIndex = -1
            val rowLineWrap = getLineWrap(row)
            if (rowLineWrap && x2 == columns) {
                lastPrintingCharIndex = x2Index - 1
            } else {
                for (i in x1Index until x2Index) {
                    val c = line[i]
                    if (c != ' ') lastPrintingCharIndex = i
                }
            }

            val len = lastPrintingCharIndex - x1Index + 1
            if (lastPrintingCharIndex != -1 && len > 0)
                builder.append(line, x1Index, len)

            val lineFillsWidth = lastPrintingCharIndex == x2Index - 1
            if ((!joinBackLines || !rowLineWrap) && (!joinFullLines || !lineFillsWidth)
                && row < y2 && row < screenRows - 1) builder.append('\n')
        }
        return builder.toString()
    }

    fun getWordAtLocation(x: Int, y: Int): String {
        var y1 = y
        var y2 = y
        while (y1 > 0 && !getSelectedText(0, y1 - 1, columns, y, joinBackLines = true, joinFullLines = true).contains("\n")) {
            y1--
        }
        while (y2 < screenRows && !getSelectedText(0, y, columns, y2 + 1, joinBackLines = true, joinFullLines = true).contains("\n")) {
            y2++
        }

        val text = getSelectedText(0, y1, columns, y2, joinBackLines = true, joinFullLines = true)
        val textOffset = (y - y1) * columns + x

        if (textOffset >= text.length) {
            return ""
        }

        val x1 = text.lastIndexOf(' ', textOffset)
        var x2 = text.indexOf(' ', textOffset)
        if (x2 == -1) {
            x2 = text.length
        }

        if (x1 == x2) {
            return ""
        }
        return text.substring(x1 + 1, x2)
    }

    val activeRows: Int get() = activeTranscriptRows + screenRows






















    fun externalToInternalRow(externalRow: Int): Int {
        if (externalRow < -activeTranscriptRows || externalRow > screenRows)
            throw IllegalArgumentException("extRow=$externalRow, screenRows=$screenRows, activeTranscriptRows=$activeTranscriptRows")
        val internalRow = screenFirstRow + externalRow
        return if (internalRow < 0) (totalRows + internalRow) else (internalRow % totalRows)
    }

    fun setLineWrap(row: Int) {
        lines[externalToInternalRow(row)]!!.lineWrap = true
    }

    fun getLineWrap(row: Int): Boolean {
        return lines[externalToInternalRow(row)]!!.lineWrap
    }

    fun clearLineWrap(row: Int) {
        lines[externalToInternalRow(row)]!!.lineWrap = false
    }









    fun resize(newColumns: Int, newRows: Int, newTotalRows: Int, cursor: IntArray, currentStyle: Long, altScreen: Boolean) {

        if (newColumns == columns && newRows <= totalRows) {

            var shiftDownOfTopRow = screenRows - newRows
            if (shiftDownOfTopRow > 0 && shiftDownOfTopRow < screenRows) {

                for (i in screenRows - 1 downTo 1) {
                    if (cursor[1] >= i) break
                    val r = externalToInternalRow(i)
                    if (lines[r] == null || lines[r]!!.isBlank()) {
                        if (--shiftDownOfTopRow == 0) break
                    }
                }
            } else if (shiftDownOfTopRow < 0) {

                val actualShift = maxOf(shiftDownOfTopRow, -activeTranscriptRows)
                if (shiftDownOfTopRow != actualShift) {
                    for (i in 0 until actualShift - shiftDownOfTopRow)
                        allocateFullLineIfNecessary((screenFirstRow + screenRows + i) % totalRows).clear(currentStyle)
                    shiftDownOfTopRow = actualShift
                }
            }
            screenFirstRow += shiftDownOfTopRow
            screenFirstRow = if (screenFirstRow < 0) (screenFirstRow + totalRows) else (screenFirstRow % totalRows)
            totalRows = newTotalRows
            activeTranscriptRows = if (altScreen) 0 else maxOf(0, activeTranscriptRows + shiftDownOfTopRow)
            cursor[1] -= shiftDownOfTopRow
            screenRows = newRows
        } else {

            val oldLines = lines
            lines = arrayOfNulls(newTotalRows)
            for (i in 0 until newTotalRows)
                lines[i] = TerminalRow(newColumns, currentStyle)

            val oldActiveTranscriptRows = activeTranscriptRows
            val oldScreenFirstRow = screenFirstRow
            val oldScreenRows = screenRows
            val oldTotalRows = totalRows
            totalRows = newTotalRows
            screenRows = newRows
            activeTranscriptRows = 0
            screenFirstRow = 0
            columns = newColumns

            var newCursorRow = -1
            var newCursorColumn = -1
            val oldCursorRow = cursor[1]
            val oldCursorColumn = cursor[0]
            var newCursorPlaced = false

            var currentOutputExternalRow = 0
            var currentOutputExternalColumn = 0

            var skippedBlankLines = 0
            for (externalOldRow in -oldActiveTranscriptRows until oldScreenRows) {
                var internalOldRow = oldScreenFirstRow + externalOldRow
                internalOldRow = if (internalOldRow < 0) (oldTotalRows + internalOldRow) else (internalOldRow % oldTotalRows)

                val oldLine = oldLines[internalOldRow]
                val cursorAtThisRow = externalOldRow == oldCursorRow
                if (oldLine == null || (!(!newCursorPlaced && cursorAtThisRow)) && oldLine.isBlank()) {
                    skippedBlankLines++
                    continue
                } else if (skippedBlankLines > 0) {
                    for (i in 0 until skippedBlankLines) {
                        if (currentOutputExternalRow == screenRows - 1) {
                            scrollDownOneLine(0, screenRows, currentStyle)
                        } else {
                            currentOutputExternalRow++
                        }
                        currentOutputExternalColumn = 0
                    }
                    skippedBlankLines = 0
                }

                var lastNonSpaceIndex = 0
                var justToCursor = false
                if (cursorAtThisRow || oldLine.lineWrap) {
                    lastNonSpaceIndex = oldLine.spaceUsed
                    if (cursorAtThisRow) justToCursor = true
                } else {
                    for (i in 0 until oldLine.spaceUsed)

                        if (oldLine.text[i] != ' '                                          )
                            lastNonSpaceIndex = i + 1
                }

                var currentOldCol = 0
                var styleAtCol = 0L
                var i = 0
                while (i < lastNonSpaceIndex) {
                    val c = oldLine.text[i]
                    val codePoint: Int
                    if (Character.isHighSurrogate(c)) {
                        i++
                        codePoint = Character.toCodePoint(c, oldLine.text[i])
                    } else {
                        codePoint = c.code
                    }
                    val displayWidth = WcWidth.width(codePoint)
                    if (displayWidth > 0) styleAtCol = oldLine.getStyle(currentOldCol)

                    if (currentOutputExternalColumn + displayWidth > columns) {
                        setLineWrap(currentOutputExternalRow)
                        if (currentOutputExternalRow == screenRows - 1) {
                            if (newCursorPlaced) newCursorRow--
                            scrollDownOneLine(0, screenRows, currentStyle)
                        } else {
                            currentOutputExternalRow++
                        }
                        currentOutputExternalColumn = 0
                    }

                    val offsetDueToCombiningChar = if (displayWidth <= 0 && currentOutputExternalColumn > 0) 1 else 0
                    val outputColumn = currentOutputExternalColumn - offsetDueToCombiningChar
                    setChar(outputColumn, currentOutputExternalRow, codePoint, styleAtCol)

                    if (displayWidth > 0) {
                        if (oldCursorRow == externalOldRow && oldCursorColumn == currentOldCol) {
                            newCursorColumn = currentOutputExternalColumn
                            newCursorRow = currentOutputExternalRow
                            newCursorPlaced = true
                        }
                        currentOldCol += displayWidth
                        currentOutputExternalColumn += displayWidth
                        if (justToCursor && newCursorPlaced) break
                    }
                    i++
                }
                if (externalOldRow != (oldScreenRows - 1) && !oldLine.lineWrap) {
                    if (currentOutputExternalRow == screenRows - 1) {
                        if (newCursorPlaced) newCursorRow--
                        scrollDownOneLine(0, screenRows, currentStyle)
                    } else {
                        currentOutputExternalRow++
                    }
                    currentOutputExternalColumn = 0
                }
            }

            cursor[0] = newCursorColumn
            cursor[1] = newCursorRow
        }


        if (cursor[0] < 0 || cursor[1] < 0) {
            cursor[0] = 0
            cursor[1] = 0
        }
    }








    private fun blockCopyLinesDown(srcInternal: Int, len: Int) {
        if (len == 0) return

        val start = len - 1
        val lineToBeOverWritten = lines[(srcInternal + start + 1) % totalRows]
        for (i in start downTo 0)
            lines[(srcInternal + i + 1) % totalRows] = lines[(srcInternal + i) % totalRows]
        lines[srcInternal % totalRows] = lineToBeOverWritten
    }








    fun scrollDownOneLine(topMargin: Int, bottomMargin: Int, style: Long) {
        if (topMargin > bottomMargin - 1 || topMargin < 0 || bottomMargin > screenRows)
            throw IllegalArgumentException("topMargin=$topMargin, bottomMargin=$bottomMargin, screenRows=$screenRows")

        blockCopyLinesDown(screenFirstRow, topMargin)
        blockCopyLinesDown(externalToInternalRow(bottomMargin), screenRows - bottomMargin)

        screenFirstRow = (screenFirstRow + 1) % totalRows
        if (activeTranscriptRows < totalRows - screenRows) activeTranscriptRows++

        val blankRow = externalToInternalRow(bottomMargin - 1)
        if (lines[blankRow] == null) {
            lines[blankRow] = TerminalRow(columns, style)
        } else {
            lines[blankRow]!!.clear(style)
        }
    }













    fun blockCopy(sx: Int, sy: Int, w: Int, h: Int, dx: Int, dy: Int) {
        if (w == 0) return
        if (sx < 0 || sx + w > columns || sy < 0 || sy + h > screenRows || dx < 0 || dx + w > columns || dy < 0 || dy + h > screenRows)
            throw IllegalArgumentException()
        val copyingUp = sy > dy
        for (y in 0 until h) {
            val y2 = if (copyingUp) y else (h - (y + 1))
            val sourceRow = allocateFullLineIfNecessary(externalToInternalRow(sy + y2))
            allocateFullLineIfNecessary(externalToInternalRow(dy + y2)).copyInterval(sourceRow, sx, sx + w, dx)
        }
    }






    fun blockSet(sx: Int, sy: Int, w: Int, h: Int, `val`: Int, style: Long) {
        if (sx < 0 || sx + w > columns || sy < 0 || sy + h > screenRows) {
            throw IllegalArgumentException(
                "Illegal arguments! blockSet($sx, $sy, $w, $h, $`val`, $columns, $screenRows)")
        }
        for (y in 0 until h)
            for (x in 0 until w)
                setChar(sx + x, sy + y, `val`, style)
    }

    fun allocateFullLineIfNecessary(row: Int): TerminalRow {
        return lines[row] ?: TerminalRow(columns, 0).also { lines[row] = it }
    }

    fun setChar(column: Int, row: Int, codePoint: Int, style: Long) {
        if (row < 0 || row >= screenRows || column < 0 || column >= columns)
            throw IllegalArgumentException("TerminalBuffer.setChar(): row=$row, column=$column, screenRows=$screenRows, columns=$columns")
        val internalRow = externalToInternalRow(row)
        allocateFullLineIfNecessary(internalRow).setChar(column, codePoint, style)
    }

    fun getStyleAt(externalRow: Int, column: Int): Long {
        return allocateFullLineIfNecessary(externalToInternalRow(externalRow)).getStyle(column)
    }


    fun setOrClearEffect(bits: Int, setOrClear: Boolean, reverse: Boolean, rectangular: Boolean, leftMargin: Int, rightMargin: Int, top: Int, left: Int,
                         bottom: Int, right: Int) {
        for (y in top until bottom) {
            val line = lines[externalToInternalRow(y)]!!
            val startOfLine = if (rectangular || y == top) left else leftMargin
            val endOfLine = if (rectangular || y + 1 == bottom) right else rightMargin
            for (x in startOfLine until endOfLine) {
                val currentStyle = line.getStyle(x)
                val foreColor = TextStyle.decodeForeColor(currentStyle)
                val backColor = TextStyle.decodeBackColor(currentStyle)
                var effect = TextStyle.decodeEffect(currentStyle)
                if (reverse) {
                    effect = (effect and bits.inv()) or (bits and effect.inv())
                } else if (setOrClear) {
                    effect = effect or bits
                } else {
                    effect = effect and bits.inv()
                }
                line.styles[x] = TextStyle.encode(foreColor, backColor, effect)
            }
        }
    }

    fun clearTranscript() {
        if (screenFirstRow < activeTranscriptRows) {
            Arrays.fill(lines, totalRows + screenFirstRow - activeTranscriptRows, totalRows, null)
            Arrays.fill(lines, 0, screenFirstRow, null)
        } else {
            Arrays.fill(lines, screenFirstRow - activeTranscriptRows, screenFirstRow, null)
        }
        activeTranscriptRows = 0
    }
}
