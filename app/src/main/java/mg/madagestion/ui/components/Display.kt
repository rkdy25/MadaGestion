package mg.madagestion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import mg.madagestion.core.fmt
import mg.madagestion.ui.theme.Muted
import mg.madagestion.ui.theme.Navy

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontWeight = FontWeight.Bold, color = Navy, fontSize = 16.sp, modifier = modifier.padding(top = 8.dp))
}

@Composable
fun EmptyHint(text: String) {
    Text(text, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
}

@Composable
fun StatusPill(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun MoneyText(value: BigDecimal, color: Color = Navy, bold: Boolean = false, size: Int = 14) {
    Text(
        "${value.fmt()} Ar",
        color = color,
        fontSize = size.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
    )
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, color = Muted, fontSize = 13.sp)
        Text(value, color = Navy, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SurfaceCard(onClick: (() -> Unit)? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = Color.White)
    val shape = RoundedCornerShape(14.dp)
    if (onClick != null) {
        Card(onClick = onClick, colors = colors, shape = shape, modifier = modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), content = content)
        }
    } else {
        Card(colors = colors, shape = shape, modifier = modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), content = content)
        }
    }
}

@Composable
fun TotalRow(label: String, value: BigDecimal, bold: Boolean = false, color: Color = Navy) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 13.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        MoneyText(value, color = color, bold = bold, size = 14)
    }
}

@Composable
fun MetricTile(title: String, value: BigDecimal, modifier: Modifier = Modifier, color: Color = Navy) {
    SurfaceCard(modifier = modifier) {
        Text(title, color = Muted, fontSize = 11.sp)
        MoneyText(value, color = color, bold = true, size = 15)
    }
}
