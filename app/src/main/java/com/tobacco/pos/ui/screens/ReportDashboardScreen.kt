package com.tobacco.pos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tobacco.pos.domain.ShopFinancialReport
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDashboardScreen(
    report: ShopFinancialReport,
    selectedPeriod: String,
    onPeriodSelected: (String) -> Unit
) {
    val persianFormat = remember { NumberFormat.getNumberInstance(Locale("fa", "IR")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("داشبورد سود و زیان و گزارشات", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // کلیدهای انتخاب بازه زمانی
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("امروز", "هفته جاری", "ماه جاری").forEach { period ->
                    FilterChip(
                        selected = selectedPeriod == period,
                        onClick = { onPeriodSelected(period) },
                        label = { Text(period, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ردیف اول: فروش کل و سود ناخالص
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    title = "فروش کل",
                    value = "${persianFormat.format(report.totalRevenue)} ت",
                    subtitle = "${persianFormat.format(report.totalInvoicesCount)} فاکتور",
                    valueColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "سود ناخالص",
                    value = "${persianFormat.format(report.grossProfit)} ت",
                    subtitle = "مارجین: ${String.format(Locale.US, "%.1f", report.profitMarginPercentage)}٪",
                    valueColor = Color(0xFF4CAF50),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ردیف دوم: هزینه‌ها و سود خالص نهایی
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    title = "هزینه‌های جاری",
                    value = "${persianFormat.format(report.totalExpenses)} ت",
                    subtitle = "اجاره، قبوض، پرسنل",
                    valueColor = Color(0xFFEF5350),
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "سود خالص نهایی",
                    value = "${persianFormat.format(report.netProfit)} ت",
                    subtitle = "بعد از کسر کل هزینه‌ها",
                    valueColor = if (report.netProfit >= 0) Color(0xFF4CAF50) else Color(0xFFEF5350),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text("پرفروش‌ترین‌های این دوره:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(report.topSellingProducts) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(item.productName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("تعداد فروش: ${persianFormat.format(item.totalQuantitySold)} عدد/بسته", fontSize = 12.sp, color = Color.Gray)
                            }
                            Text(
                                "سود: ${persianFormat.format(item.totalProfitGenerated)} ت",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF4CAF50),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontSize = 12.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(6.dp))
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = valueColor)
            Spacer(modifier = Modifier.height(4.dp))
            Text(subtitle, fontSize = 11.sp, color = Color.Gray)
        }
    }
}
