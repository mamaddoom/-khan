package com.mozkhan.shop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mozkhan.shop.util.Currency
import com.mozkhan.shop.util.Fmt

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = container)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) Card(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.large, colors = colors, border = border) { content() }
    else Card(modifier = modifier, shape = MaterialTheme.shapes.large, colors = colors, border = border) { content() }
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = CircleShape, color = color.copy(alpha = 0.14f)) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Int = 44) {
    Box(modifier.size(size.dp).background(tint.copy(alpha = 0.14f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.5).dp))
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IconBadge(icon, MaterialTheme.colorScheme.primary, size = 72)
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

/** Text field that formats a TOMAN value in the chosen currency with thousand separators while typing. */
@Composable
fun MoneyField(
    label: String,
    toman: Long,
    currency: Currency,
    onChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    suffix: Boolean = true,
) {
    // what the user typed is kept (see Fmt.moneyInput): in RIAL mode the last digit would otherwise vanish
    var typed by remember { mutableStateOf("") }
    OutlinedTextField(
        value = Fmt.moneyInput(typed, toman, currency),
        onValueChange = {
            typed = Fmt.normalize(it).filter { c -> c.isDigit() }.take(Fmt.MONEY_DIGITS)
            onChange(Fmt.parseMoney(typed, currency))
        },
        label = { Text(label) },
        suffix = if (suffix) ({ Text(currency.fa) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    )
}

@Composable
fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    decimal: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(Fmt.normalize(it).filter { c -> c.isDigit() || (decimal && c == '.') }) },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    )
}

@Composable
fun AppField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: @Composable (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = trailing, leadingIcon = leading,
        shape = MaterialTheme.shapes.medium,
        colors = TextFieldDefaults.colors(),
        visualTransformation = VisualTransformation.None,
        modifier = modifier,
    )
}

@Composable
fun QtyStepper(qty: Double, onChange: (Double) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(qty + 1) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Add, "افزودن", modifier = Modifier.size(18.dp)) }
        Text(Fmt.qty(qty, false), modifier = Modifier.width(40.dp), maxLines = 1, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
        IconButton(onClick = { onChange(qty - 1) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Remove, "کم کردن", modifier = Modifier.size(18.dp)) }
    }
}

@Composable
fun ClickableRow(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}
