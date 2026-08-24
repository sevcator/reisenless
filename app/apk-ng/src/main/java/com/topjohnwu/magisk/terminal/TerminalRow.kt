package com.topjohnwu.magisk.terminal

import java.util.Arrays






class TerminalRow(private val columns: Int, style: Long) {

























    companion object {
        private const val SPARE_CAPACITY_FACTOR = 1.5f
        private const val MAX_COMBINING_CHARACTERS_PER_COLUMN = 15
    }


    var text: CharArray = CharArray((SPARE_CAPACITY_FACTOR * columns).toInt())


    private var _spaceUsed: Short = 0


    var lineWrap: Boolean = false


    val styles: LongArray = LongArray(columns)


    var hasNonOneWidthOrSurrogateChars: Boolean = false

    init {
        clear(style)
    }


    fun copyInterval(line: TerminalRow, sourceX1: Int, sourceX2: Int, destinationX: Int) {
        hasNonOneWidthOrSurrogateChars = hasNonOneWidthOrSurrogateChars or line.hasNonOneWidthOrSurrogateChars
        val x1 = line.findStartOfColumn(sourceX1)
        val x2 = line.findStartOfColumn(sourceX2)
        var startingFromSecondHalfOfWideChar = sourceX1 > 0 && line.wideDisplayCharacterStartingAt(sourceX1 - 1)
        val sourceChars = if (this === line) line.text.copyOf() else line.text
        var latestNonCombiningWidth = 0
        var destX = destinationX
        var srcX1 = sourceX1
        var i = x1
        while (i < x2) {
            val sourceChar = sourceChars[i]
            var codePoint: Int
            if (Character.isHighSurrogate(sourceChar)) {
                i++
                codePoint = Character.toCodePoint(sourceChar, sourceChars[i])
            } else {
                codePoint = sourceChar.code
            }
            if (startingFromSecondHalfOfWideChar) {
                codePoint = ' '.code
                startingFromSecondHalfOfWideChar = false
            }
            val w = WcWidth.width(codePoint)
            if (w > 0) {
                destX += latestNonCombiningWidth
                srcX1 += latestNonCombiningWidth
                latestNonCombiningWidth = w
            }
            setChar(destX, codePoint, line.getStyle(srcX1))
            i++
        }
    }

    val spaceUsed: Int get() = _spaceUsed.toInt()


    fun findStartOfColumn(column: Int): Int {
        if (column == columns) return spaceUsed

        var currentColumn = 0
        var currentCharIndex = 0
        while (true) {
            var newCharIndex = currentCharIndex
            val c = text[newCharIndex++]
            val isHigh = Character.isHighSurrogate(c)
            val codePoint = if (isHigh) Character.toCodePoint(c, text[newCharIndex++]) else c.code
            val wcwidth = WcWidth.width(codePoint)
            if (wcwidth > 0) {
                currentColumn += wcwidth
                if (currentColumn == column) {
                    while (newCharIndex < _spaceUsed) {
                        if (Character.isHighSurrogate(text[newCharIndex])) {
                            if (WcWidth.width(Character.toCodePoint(text[newCharIndex], text[newCharIndex + 1])) <= 0) {
                                newCharIndex += 2
                            } else {
                                break
                            }
                        } else if (WcWidth.width(text[newCharIndex].code) <= 0) {
                            newCharIndex++
                        } else {
                            break
                        }
                    }
                    return newCharIndex
                } else if (currentColumn > column) {
                    return currentCharIndex
                }
            }
            currentCharIndex = newCharIndex
        }
    }

    private fun wideDisplayCharacterStartingAt(column: Int): Boolean {
        var currentCharIndex = 0
        var currentColumn = 0
        while (currentCharIndex < _spaceUsed) {
            val c = text[currentCharIndex++]
            val codePoint = if (Character.isHighSurrogate(c)) Character.toCodePoint(c, text[currentCharIndex++]) else c.code
            val wcwidth = WcWidth.width(codePoint)
            if (wcwidth > 0) {
                if (currentColumn == column && wcwidth == 2) return true
                currentColumn += wcwidth
                if (currentColumn > column) return false
            }
        }
        return false
    }

    fun clear(style: Long) {
        Arrays.fill(text, ' ')
        Arrays.fill(styles, style)
        _spaceUsed = columns.toShort()
        hasNonOneWidthOrSurrogateChars = false
    }


    fun setChar(columnToSet: Int, codePoint: Int, style: Long) {
        if (columnToSet < 0 || columnToSet >= styles.size)
            throw IllegalArgumentException("TerminalRow.setChar(): columnToSet=$columnToSet, codePoint=$codePoint, style=$style")

        styles[columnToSet] = style

        val newCodePointDisplayWidth = WcWidth.width(codePoint)


        if (!hasNonOneWidthOrSurrogateChars) {
            if (codePoint >= Character.MIN_SUPPLEMENTARY_CODE_POINT || newCodePointDisplayWidth != 1) {
                hasNonOneWidthOrSurrogateChars = true
            } else {
                text[columnToSet] = codePoint.toChar()
                return
            }
        }

        val newIsCombining = newCodePointDisplayWidth <= 0

        val wasExtraColForWideChar = columnToSet > 0 && wideDisplayCharacterStartingAt(columnToSet - 1)

        var col = columnToSet
        if (newIsCombining) {
            if (wasExtraColForWideChar) col--
        } else {
            if (wasExtraColForWideChar) setChar(col - 1, ' '.code, style)
            val overwritingWideCharInNextColumn = newCodePointDisplayWidth == 2 && wideDisplayCharacterStartingAt(col + 1)
            if (overwritingWideCharInNextColumn) setChar(col + 1, ' '.code, style)
        }

        var textArray = text
        val oldStartOfColumnIndex = findStartOfColumn(col)
        val oldCodePointDisplayWidth = WcWidth.width(textArray, oldStartOfColumnIndex)

        val oldCharactersUsedForColumn: Int
        if (col + oldCodePointDisplayWidth < columns) {
            val oldEndOfColumnIndex = findStartOfColumn(col + oldCodePointDisplayWidth)
            oldCharactersUsedForColumn = oldEndOfColumnIndex - oldStartOfColumnIndex
        } else {
            oldCharactersUsedForColumn = _spaceUsed - oldStartOfColumnIndex
        }

        if (newIsCombining) {
            val combiningCharsCount = WcWidth.zeroWidthCharsCount(textArray, oldStartOfColumnIndex, oldStartOfColumnIndex + oldCharactersUsedForColumn)
            if (combiningCharsCount >= MAX_COMBINING_CHARACTERS_PER_COLUMN)
                return
        }

        var newCharactersUsedForColumn = Character.charCount(codePoint)
        if (newIsCombining) {
            newCharactersUsedForColumn += oldCharactersUsedForColumn
        }

        val oldNextColumnIndex = oldStartOfColumnIndex + oldCharactersUsedForColumn
        val newNextColumnIndex = oldStartOfColumnIndex + newCharactersUsedForColumn

        val javaCharDifference = newCharactersUsedForColumn - oldCharactersUsedForColumn
        if (javaCharDifference > 0) {
            val oldCharactersAfterColumn = _spaceUsed - oldNextColumnIndex
            if (_spaceUsed + javaCharDifference > textArray.size) {
                val newText = CharArray(textArray.size + columns)
                System.arraycopy(textArray, 0, newText, 0, oldNextColumnIndex)
                System.arraycopy(textArray, oldNextColumnIndex, newText, newNextColumnIndex, oldCharactersAfterColumn)
                text = newText
                textArray = newText
            } else {
                System.arraycopy(textArray, oldNextColumnIndex, textArray, newNextColumnIndex, oldCharactersAfterColumn)
            }
        } else if (javaCharDifference < 0) {
            System.arraycopy(textArray, oldNextColumnIndex, textArray, newNextColumnIndex, _spaceUsed - oldNextColumnIndex)
        }
        _spaceUsed = (_spaceUsed + javaCharDifference).toShort()

        Character.toChars(codePoint, textArray, oldStartOfColumnIndex + if (newIsCombining) oldCharactersUsedForColumn else 0)

        if (oldCodePointDisplayWidth == 2 && newCodePointDisplayWidth == 1) {
            if (_spaceUsed + 1 > textArray.size) {
                val newText = CharArray(textArray.size + columns)
                System.arraycopy(textArray, 0, newText, 0, newNextColumnIndex)
                System.arraycopy(textArray, newNextColumnIndex, newText, newNextColumnIndex + 1, _spaceUsed - newNextColumnIndex)
                text = newText
                textArray = newText
            } else {
                System.arraycopy(textArray, newNextColumnIndex, textArray, newNextColumnIndex + 1, _spaceUsed - newNextColumnIndex)
            }
            textArray[newNextColumnIndex] = ' '
            ++_spaceUsed
        } else if (oldCodePointDisplayWidth == 1 && newCodePointDisplayWidth == 2) {
            if (col == columns - 1) {
                throw IllegalArgumentException("Cannot put wide character in last column")
            } else if (col == columns - 2) {
                _spaceUsed = newNextColumnIndex.toShort()
            } else {
                val newNextNextColumnIndex = newNextColumnIndex + if (Character.isHighSurrogate(textArray[newNextColumnIndex])) 2 else 1
                val nextLen = newNextNextColumnIndex - newNextColumnIndex
                System.arraycopy(textArray, newNextNextColumnIndex, textArray, newNextColumnIndex, _spaceUsed - newNextNextColumnIndex)
                _spaceUsed = (_spaceUsed - nextLen).toShort()
            }
        }
    }

    internal fun isBlank(): Boolean {
        for (charIndex in 0 until spaceUsed)
            if (text[charIndex] != ' ') return false
        return true
    }

    fun getStyle(column: Int): Long = styles[column]
}
