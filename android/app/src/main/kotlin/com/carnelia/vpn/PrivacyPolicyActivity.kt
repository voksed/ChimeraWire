package com.carnelia.vpn

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carnelia.vpn.ui.theme.CarheliaTheme
import com.carnelia.vpn.utils.PrefsManager

class PrivacyPolicyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CarheliaTheme(themeIndex = PrefsManager.getThemeIndex(this)) {
                PrivacyPolicyScreen(onBack = { finish() })
            }
        }
    }
}

// Loads and renders privacy_policy.md from assets with UTF-8 encoding
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // Read the UTF-8 asset for the current system language, falling back to English.
    val policyText = remember {
        val lang = java.util.Locale.getDefault().language  // en, ru, es, zh, ar, fr
        fun load(name: String) =
            context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        try {
            try { load("privacy_policy-$lang.md") } catch (e: Exception) { load("privacy_policy-en.md") }
        } catch (e: Exception) {
            "Privacy policy unavailable.\n\nError: ${e.message}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Политика конфиденциальности",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            MarkdownText(text = policyText)
            Spacer(Modifier.height(32.dp))
        }
    }
}

// Minimal markdown renderer — handles # headers, **bold**, `code`, - lists
@Composable
private fun MarkdownText(text: String) {
    val accentColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (rawLine in text.lines()) {
            val line = rawLine.trimEnd()
            when {
                line.startsWith("# ") -> {
                    Text(
                        text = line.removePrefix("# "),
                        style = TextStyle(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = accentColor,
                            letterSpacing = 0.sp
                        )
                    )
                }
                line.startsWith("## ") -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = line.removePrefix("## "),
                        style = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                    )
                }
                line.startsWith("### ") -> {
                    Text(
                        text = line.removePrefix("### "),
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = onSurface
                        )
                    )
                }
                line == "---" -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                }
                line.startsWith("| ") -> {
                    // Table row — simple monospace display
                    val cells = line.split("|")
                        .drop(1).dropLast(1)
                        .map { it.trim() }
                        .filter { it.isNotBlank() && it != "---" && !it.all { c -> c == '-' } }
                    if (cells.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = surfaceColor.copy(alpha = 0.4f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = cells.joinToString("  │  "),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = onSurface
                                )
                            )
                        }
                    }
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    Row(modifier = Modifier.padding(start = 8.dp)) {
                        Text("•  ", color = accentColor, fontSize = 13.sp)
                        Text(
                            buildInlineMarkdown(line.drop(2), accentColor),
                            style = TextStyle(fontSize = 13.sp, color = onSurface),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                else -> {
                    Text(
                        buildInlineMarkdown(line, accentColor),
                        style = TextStyle(fontSize = 13.sp, color = secondary, lineHeight = 20.sp)
                    )
                }
            }
        }
    }
}

// Parses **bold**, `code`, and [link](url) inline
private fun buildInlineMarkdown(
    text: String,
    accentColor: Color
) = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            // **bold**
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            // `code`
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = accentColor
                    )) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            // [label](url) — show label only
            text[i] == '[' -> {
                val labelEnd = text.indexOf(']', i + 1)
                val urlStart = if (labelEnd != -1 && labelEnd + 1 < text.length && text[labelEnd + 1] == '(') labelEnd + 2 else -1
                val urlEnd = if (urlStart != -1) text.indexOf(')', urlStart) else -1
                if (labelEnd != -1 && urlEnd != -1) {
                    val label = text.substring(i + 1, labelEnd)
                    withStyle(SpanStyle(color = accentColor, fontWeight = FontWeight.Medium)) {
                        append(label)
                    }
                    i = urlEnd + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}
