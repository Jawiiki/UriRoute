package com.uriroute.ui.component

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val JS_KEYWORDS = setOf(
    "function", "var", "let", "const", "if", "else", "for", "while", "do",
    "switch", "case", "break", "continue", "return", "throw", "try", "catch",
    "finally", "new", "delete", "typeof", "instanceof", "class", "extends",
    "import", "export", "default", "async", "await", "yield", "in", "of",
    "this", "super", "null", "undefined", "true", "false"
)

private val JS_BUILTINS = setOf(
    "console", "JSON", "Math", "Array", "Object", "String", "Number",
    "Boolean", "Date", "RegExp", "Map", "Set", "Promise", "Error",
    "parseInt", "parseFloat", "isNaN", "Symbol", "WeakMap", "WeakSet",
    "Proxy", "Reflect", "Intl", "BigInt", "BigInt64Array", "BigUint64Array",
    "Float32Array", "Float64Array", "Int8Array", "Int16Array", "Int32Array",
    "Uint8Array", "Uint8ClampedArray", "Uint16Array", "Uint32Array"
)

/**
 * Visual transformation that applies JS syntax highlighting to editor text.
 */
private class JsSyntaxHighlight(
    private val defaultColor: Color,
    private val keywordColor: Color,
    private val stringColor: Color,
    private val numberColor: Color,
    private val commentColor: Color,
    private val builtinColor: Color
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val highlighted = highlightText(text.text, keywordColor, stringColor, numberColor, commentColor, builtinColor)
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}

private fun highlightText(
    text: String,
    keywordColor: Color,
    stringColor: Color,
    numberColor: Color,
    commentColor: Color,
    builtinColor: Color
): AnnotatedString = buildAnnotatedString {
    var i = 0
    val len = text.length

    val keywordStyle = SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold)
    val stringStyle = SpanStyle(color = stringColor)
    val numberStyle = SpanStyle(color = numberColor)
    val commentStyle = SpanStyle(color = commentColor)
    val builtinStyle = SpanStyle(color = builtinColor)

    while (i < len) {
        val ch = text[i]

        when {
            // Single-line comment: //
            ch == '/' && i + 1 < len && text[i + 1] == '/' -> {
                val end = text.indexOf('\n', i).let { if (it == -1) len else it }
                withStyle(commentStyle) { append(text.substring(i, end)) }
                i = end
            }
            // Multi-line comment: /* ... */
            ch == '/' && i + 1 < len && text[i + 1] == '*' -> {
                val end = text.indexOf("*/", i + 2).let { if (it == -1) len else it + 2 }
                withStyle(commentStyle) { append(text.substring(i, end)) }
                i = end
            }
            // Template literal: `...`
            ch == '`' -> {
                val start = i
                i++
                while (i < len) {
                    if (text[i] == '\\' && i + 1 < len) i += 2
                    else if (text[i] == '`') { i++; break }
                    else i++
                }
                withStyle(stringStyle) { append(text.substring(start, i)) }
            }
            // Double-quoted string: "..."
            ch == '"' -> {
                val start = i
                i++
                while (i < len) {
                    if (text[i] == '\\' && i + 1 < len) i += 2
                    else if (text[i] == '"') { i++; break }
                    else i++
                }
                withStyle(stringStyle) { append(text.substring(start, i)) }
            }
            // Single-quoted string: '...'
            ch == '\'' -> {
                val start = i
                i++
                while (i < len) {
                    if (text[i] == '\\' && i + 1 < len) i += 2
                    else if (text[i] == '\'') { i++; break }
                    else i++
                }
                withStyle(stringStyle) { append(text.substring(start, i)) }
            }
            // Digit → number literal
            ch.isDigit() || (ch == '.' && i + 1 < len && text[i + 1].isDigit()) -> {
                val start = i
                // Handle 0x, 0b, 0o prefixes
                if (ch == '0' && i + 1 < len && text[i + 1] in "xXbBoO") i += 2
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '.')) i++
                withStyle(numberStyle) { append(text.substring(start, i)) }
            }
            // Letter, underscore, dollar → identifier or keyword
            ch.isLetter() || ch == '_' || ch == '$' -> {
                val start = i
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$')) i++
                val word = text.substring(start, i)
                when {
                    word in JS_KEYWORDS -> withStyle(keywordStyle) { append(word) }
                    word in JS_BUILTINS -> withStyle(builtinStyle) { append(word) }
                    else -> append(word)
                }
            }
            // Regex literal: /.../
            ch == '/' -> {
                val start = i
                i++
                while (i < len) {
                    if (text[i] == '\\' && i + 1 < len) i += 2
                    else if (text[i] == '/') { i++; break }
                    else i++
                }
                if (i < len) i++ // skip trailing /
                while (i < len && text[i] in "gimsuy") i++
                withStyle(stringStyle) { append(text.substring(start, i)) }
            }
            else -> {
                append(ch)
                i++
            }
        }
    }
}

/**
 * A JS code editor with monospace font, scroll support, and basic syntax highlighting.
 */
@Composable
fun JsCodeEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false
) {
    val backgroundColor = MaterialTheme.colorScheme.surface
    val textColor = MaterialTheme.colorScheme.onSurface
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Syntax highlighting colors (VS Code-inspired dark theme)
    val keywordColor = Color(0xFF569CD6)
    val stringColor = Color(0xFFCE9178)
    val numberColor = Color(0xFFB5CEA8)
    val commentColor = Color(0xFF6A9955)
    val builtinColor = Color(0xFFDCDCAA)

    val syntaxHighlight = remember(keywordColor, stringColor, numberColor, commentColor, builtinColor, textColor) {
        JsSyntaxHighlight(
            defaultColor = textColor,
            keywordColor = keywordColor,
            stringColor = stringColor,
            numberColor = numberColor,
            commentColor = commentColor,
            builtinColor = builtinColor
        )
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Surface(
        modifier = modifier,
        color = backgroundColor,
        shape = MaterialTheme.shapes.small
    ) {
        val verticalScroll = rememberScrollState()

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = readOnly,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = textColor
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = syntaxHighlight,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .padding(8.dp)
                .verticalScroll(verticalScroll)
        )
    }
}
