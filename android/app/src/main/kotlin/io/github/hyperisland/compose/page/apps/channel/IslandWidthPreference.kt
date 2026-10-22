package io.github.hyperisland.compose.page.apps.channel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.hyperisland.R
import io.github.hyperisland.compose.data.channel.OPTION_DEFAULT
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun IslandWidthPreference(
    value: String?,
    appLevel: Boolean = false,
    batch: Boolean = false,
    onChange: (String?) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val title = stringResource(islandWidthTitleRes(appLevel))
    val inherit = stringResource(if (appLevel) R.string.island_width_follow_global else R.string.island_width_follow_app)
    val summary = when (value) {
        null -> stringResource(R.string.no_change)
        OPTION_DEFAULT -> inherit
        "0" -> stringResource(R.string.island_width_system)
        else -> "$value dp"
    }
    ArrowPreference(
        title = title,
        summary = summary,
        onClick = { draft = value?.takeIf { it.toIntOrNull() != null }.orEmpty(); show = true },
    )
    fun save(next: String?) { onChange(next); show = false }
    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = { show = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.island_width_help))
            TextField(
                value = draft,
                onValueChange = { draft = it.filter(Char::isDigit).take(4) },
                modifier = Modifier.fillMaxWidth(),
                label = "1–1000 dp",
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(text = inherit, onClick = { save(OPTION_DEFAULT) }, modifier = Modifier.weight(1f))
                TextButton(text = stringResource(R.string.island_width_system), onClick = { save("0") }, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (batch) TextButton(text = stringResource(R.string.no_change), onClick = { save(null) }, modifier = Modifier.weight(1f))
                Button(
                    onClick = { draft.toIntOrNull()?.takeIf { it in 1..1000 }?.let { save(it.toString()) } },
                    enabled = draft.toIntOrNull() in 1..1000,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(stringResource(R.string.save)) }
            }
        }
    }
}
