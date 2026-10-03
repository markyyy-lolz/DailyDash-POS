package com.dailydash.pos

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale

private val V21Blue = Color(0xFF0866D7)

private fun v21Peso(value: Int): String =
    NumberFormat.getCurrencyInstance(Locale("en", "PH")).format(value)

@Composable
fun ProductCustomizerDialog(
    product: Product,
    options: List<ModifierOption>,
    onAdd: (CartLine) -> Unit,
    onDismiss: () -> Unit
) {
    val groups = remember(options) {
        options
            .groupBy { it.groupName }
            .toList()
            .sortedBy { (_, rows) -> rows.minOfOrNull { it.sortOrder } ?: 0 }
    }

    var upsized by remember(product.id) { mutableStateOf(false) }
    var quantity by remember(product.id) { mutableIntStateOf(1) }

    val selected = remember(product.id, options) {
        mutableStateMapOf<String, Set<String>>().apply {
            groups.forEach { (groupName, rows) ->
                val defaults = rows.filter { it.isDefault }.map { it.id }.toSet()
                if (defaults.isNotEmpty()) put(groupName, defaults)
            }
        }
    }

    val requiredValid = groups.all { (groupName, rows) ->
        !rows.any { it.required } || !selected[groupName].isNullOrEmpty()
    }

    val selectedOptions = groups.flatMap { (groupName, rows) ->
        val ids = selected[groupName].orEmpty()
        rows.filter { it.id in ids }.map { SelectedModifier(it) }
    }

    val unitTotal =
        product.price +
            (if (upsized) product.upsizePrice else 0) +
            selectedOptions.sumOf { it.lineDelta }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = Color.White,
        title = {
            Column {
                Text(
                    product.name,
                    fontWeight = FontWeight.Black,
                    fontSize = 23.sp,
                    color = Color(0xFF102A56)
                )
                Text(
                    product.category + " • Customize order",
                    color = Color(0xFF718096),
                    fontSize = 11.sp
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (product.allowUpsize) {
                    item {
                        Surface(
                            color = if (upsized) Color(0xFFEAF3FF) else Color(0xFFF8FAFD),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(
                                if (upsized) 2.dp else 1.dp,
                                if (upsized) V21Blue else Color(0xFFE3E9F1)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { upsized = !upsized }
                        ) {
                            Row(
                                Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Upsize", fontWeight = FontWeight.Bold)
                                    Text(
                                        "+" + v21Peso(product.upsizePrice),
                                        color = Color(0xFF718096),
                                        fontSize = 11.sp
                                    )
                                }
                                Checkbox(
                                    checked = upsized,
                                    onCheckedChange = { upsized = it }
                                )
                            }
                        }
                    }
                }

                groups.forEach { (groupName, rows) ->
                    item(key = "title-" + groupName) {
                        val required = rows.any { it.required }
                        Column {
                            Text(
                                groupName,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF243B64)
                            )
                            Text(
                                if (rows.firstOrNull()?.groupType == "single")
                                    "Choose one" + if (required) " • Required" else ""
                                else
                                    "Choose up to " +
                                        (rows.maxOfOrNull { it.maxSelect } ?: 1) +
                                        if (required) " • Required" else "",
                                fontSize = 10.sp,
                                color = Color(0xFF7B8798)
                            )
                        }
                    }

                    rows.sortedBy { it.sortOrder }.forEach { option ->
                        item(key = option.id) {
                            val current = selected[groupName].orEmpty()
                            val checked = option.id in current
                            val isSingle = option.groupType == "single"
                            val maxSelect = rows.maxOfOrNull { it.maxSelect } ?: 1

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected[groupName] =
                                            if (isSingle) {
                                                setOf(option.id)
                                            } else if (checked) {
                                                current - option.id
                                            } else if (current.size < maxSelect) {
                                                current + option.id
                                            } else {
                                                current
                                            }
                                    },
                                shape = RoundedCornerShape(14.dp),
                                color = if (checked) Color(0xFFEAF3FF) else Color(0xFFF9FBFD),
                                border = BorderStroke(
                                    if (checked) 2.dp else 1.dp,
                                    if (checked) V21Blue else Color(0xFFE5EAF1)
                                )
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .background(
                                                if (checked) V21Blue else Color(0xFFE9EEF5),
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (checked) {
                                            Icon(
                                                Icons.Default.Check,
                                                null,
                                                tint = Color.White,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        option.name,
                                        modifier = Modifier.weight(1f),
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF263D60)
                                    )
                                    Text(
                                        if (option.priceDelta == 0) "Included"
                                        else "+" + v21Peso(option.priceDelta),
                                        color = if (option.priceDelta == 0)
                                            Color(0xFF7B8798) else V21Blue,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Surface(
                        color = Color(0xFFF4F8FE),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Quantity",
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { quantity = (quantity - 1).coerceAtLeast(1) }
                            ) {
                                Icon(Icons.Default.Remove, "Decrease")
                            }
                            Text(
                                quantity.toString(),
                                fontWeight = FontWeight.Black,
                                fontSize = 18.sp
                            )
                            IconButton(
                                onClick = { quantity = (quantity + 1).coerceAtMost(50) }
                            ) {
                                Icon(Icons.Default.Add, "Increase")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = requiredValid,
                onClick = {
                    onAdd(
                        CartLine(
                            product = product,
                            quantity = quantity,
                            upsized = upsized,
                            modifiers = selectedOptions
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = V21Blue),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    "Add • " + v21Peso(unitTotal * quantity),
                    fontWeight = FontWeight.Black
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
