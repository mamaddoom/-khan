package com.mozkhan.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozkhan.shop.data.CAT_DRINK
import com.mozkhan.shop.data.CAT_INGREDIENT
import com.mozkhan.shop.data.KIND_DRINK
import com.mozkhan.shop.data.KIND_INGREDIENT
import com.mozkhan.shop.data.Measure
import com.mozkhan.shop.data.Product
import com.mozkhan.shop.data.ProductWithStock
import com.mozkhan.shop.data.newId
import com.mozkhan.shop.util.Currency
import com.mozkhan.shop.util.Fmt
import kotlinx.coroutines.launch

/**
 * کافه: a separate warehouse of ingredients (cups, lids, milk, espresso beans, syrups...) and drink recipes.
 * A drink's cost price is computed live from its recipe; selling a drink consumes its ingredients.
 */
@Composable
fun CafeScreen(vm: MainVm, onBack: () -> Unit) {
    val cur by vm.currency.collectAsState()
    val ingredients by vm.ingredients.collectAsState()
    val products by vm.products.collectAsState()
    val costs by vm.recipeCosts.collectAsState()
    val recipes by vm.recipes.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var ingredientDialog by remember { mutableStateOf<Pair<ProductWithStock?, Boolean>?>(null) } // (item, open)
    var restock by remember { mutableStateOf<ProductWithStock?>(null) }
    var editingDrink by remember { mutableStateOf<ProductWithStock?>(null) }
    var newDrink by remember { mutableStateOf(false) }

    val drinks = products.filter { it.kind == KIND_DRINK }

    if (newDrink || editingDrink != null) {
        DrinkEditor(vm, editingDrink, cur, ingredients) { newDrink = false; editingDrink = null }
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") }
                Text("کافه", style = MaterialTheme.typography.headlineSmall)
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tab == 0, { tab = 0 }, label = { Text("انبار کافه") }, leadingIcon = { Icon(Icons.Filled.Kitchen, null, Modifier.padding(2.dp)) })
                FilterChip(tab == 1, { tab = 1 }, label = { Text("منو و دستور ساخت") }, leadingIcon = { Icon(Icons.Filled.Coffee, null, Modifier.padding(2.dp)) })
            }
            if (tab == 0) {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp, 6.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Kpi("ارزش مواد موجود", Fmt.money(ingredients.sumOf { it.stockValue }, cur), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                            Kpi("کمبود / تمام‌شده", Fmt.digits("${ingredients.count { it.low || it.stock <= 0 }} از ${ingredients.size}"), MaterialTheme.extra.warn, Modifier.weight(1f))
                        }
                    }
                    if (ingredients.isEmpty()) item {
                        EmptyState(Icons.Filled.Kitchen, "هنوز ماده‌ای تعریف نشده", "لیوان، درب، شیر، شات اسپرسو، سیروپ و … را اضافه کنید تا قیمت تمام‌شدهٔ نوشیدنی‌ها حساب شود.")
                    }
                    items(ingredients, key = { it.id }) { p ->
                        AppCard {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "کد " + Fmt.digits(p.code) + " • خرید " + Fmt.money(p.buyPrice, cur, false) + " " + cur.fa + " " + perUnit(p),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Pill("موجودی " + Fmt.qtyOf(p.stock, p.unit), if (p.stock <= 0 || p.low) MaterialTheme.extra.warn else MaterialTheme.colorScheme.primary)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { restock = p }) { Text("خرید جدید") }
                                    OutlinedButton(onClick = { ingredientDialog = p to true }) { Text("ویرایش") }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp, 6.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (drinks.isEmpty()) item {
                        EmptyState(Icons.Filled.Coffee, "منوی کافه خالی است", "نوشیدنی‌ها (لاته، کاپوچینو، ماکیاتو…) را با دستور ساخت تعریف کنید.")
                    }
                    items(drinks, key = { it.id }) { d ->
                        val hasRecipe = d.id in costs
                        val cost = if (hasRecipe) costs[d.id] ?: 0.0 else d.buyPrice.toDouble()
                        val margin = if (d.sellPrice > 0) (d.sellPrice - cost) * 100.0 / d.sellPrice else 0.0
                        AppCard(onClick = { editingDrink = d }) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (hasRecipe) "قیمت تمام‌شده " + Fmt.money(Math.round(cost), cur) else "دستور ساخت ندارد (هزینهٔ ثابت " + Fmt.money(d.buyPrice, cur) + ")",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text("فروش " + Fmt.money(d.sellPrice, cur), style = MaterialTheme.typography.bodySmall)
                                }
                                if (d.sellPrice > 0) Pill("سود " + Fmt.percent(margin), if (margin >= 50) MaterialTheme.extra.profit else MaterialTheme.extra.warn)
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { if (tab == 0) ingredientDialog = null to true else newDrink = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text(if (tab == 0) "ماده جدید" else "نوشیدنی جدید") },
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
        )
    }

    ingredientDialog?.let { (item, _) -> IngredientDialog(vm, item, cur) { ingredientDialog = null } }
    restock?.let { p ->
        RestockDialog(p, cur, askSell = false, onDismiss = { restock = null }) { qty, unitCost, newBuy, _, _, _, supplier ->
            vm.restock(p.id, qty, unitCost, newBuy, null, null, null, supplier)
        }
    }
}

private fun perUnit(p: ProductWithStock) = when (p.unit) { Measure.WEIGHT -> "(هر کیلو)"; Measure.VOLUME -> "(هر لیتر)"; else -> "(هر عدد)" }

@Composable
private fun IngredientDialog(vm: MainVm, item: ProductWithStock?, cur: Currency, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(item?.name.orEmpty()) }
    var unit by remember { mutableStateOf(item?.unit ?: Measure.PIECE) }
    var buy by remember { mutableStateOf(item?.buyPrice ?: 0L) }
    var min by remember { mutableStateOf(item?.let { if (it.minStock > 0) (if (it.isMeasured) it.minStock / 1000 else it.minStock).toString().removeSuffix(".0") else "" }.orEmpty()) }
    var opening by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val unitName = when (unit) { Measure.WEIGHT -> "کیلوگرم"; Measure.VOLUME -> "لیتر"; else -> "عدد" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "ماده جدید کافه" else "ویرایش ماده") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppField(name, { name = it }, "نام (لیوان، شیر، شات اسپرسو…)", Modifier.fillMaxWidth())
                Text("واحد", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(unit == Measure.PIECE, { unit = Measure.PIECE }, label = { Text("عدد") })
                    FilterChip(unit == Measure.WEIGHT, { unit = Measure.WEIGHT }, label = { Text("وزنی") })
                    FilterChip(unit == Measure.VOLUME, { unit = Measure.VOLUME }, label = { Text("حجمی") })
                }
                MoneyField("قیمت خرید هر $unitName", buy, cur, { buy = it }, Modifier.fillMaxWidth())
                if (item == null) NumberField("موجودی اولیه", opening, { opening = it }, Modifier.fillMaxWidth(), suffix = unitName)
                NumberField("هشدار کمبود در", min, { min = it }, Modifier.fillMaxWidth(), suffix = unitName)
                Text("در دستور ساخت، مقدار مصرف را به " + Fmt.unitLabel(unit) + " وارد می‌کنید.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && buy > 0 && !saving, onClick = {
                if (saving) return@TextButton
                saving = true
                scope.launch {
                    try {
                        val base = item?.let { vm.repo.raw(it.id) }
                        // stock is kept in base units: changing the unit of a used ingredient would rescale it
                        if (base != null && base.unit != unit && vm.repo.hasHistory(base.id)) {
                            error = "واحد این ماده قابل تغییر نیست چون موجودی یا مصرف ثبت‌شده دارد."
                            unit = base.unit
                            return@launch
                        }
                        val factor = if (unit != Measure.PIECE) 1000.0 else 1.0
                        val p = (base ?: Product(newId(), name.trim(), category = CAT_INGREDIENT, kind = KIND_INGREDIENT, code = vm.repo.nextCode())).copy(
                            name = name.trim(), unit = unit, buyPrice = buy, minStock = Fmt.parseDouble(min) * factor,
                            category = CAT_INGREDIENT, kind = KIND_INGREDIENT, trackStock = true,
                        )
                        val op = Fmt.parseDouble(opening) * factor
                        vm.repo.saveProduct(p, if (item == null && op > 0) op else null)
                        onDismiss()
                    } catch (e: Exception) {
                        error = "ذخیره نشد: ${e.message}"
                    } finally {
                        saving = false
                    }
                }
            }) { Text("ذخیره") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}

private data class RecipeLineState(val ingredientId: String, val qty: String)

@Composable
private fun DrinkEditor(vm: MainVm, drink: ProductWithStock?, cur: Currency, ingredients: List<ProductWithStock>, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val recipes by vm.recipes.collectAsState()
    var name by remember { mutableStateOf(drink?.name.orEmpty()) }
    var sell by remember { mutableStateOf(drink?.sellPrice ?: 0L) }
    var manualCost by remember { mutableStateOf(drink?.buyPrice ?: 0L) }
    var category by remember { mutableStateOf(drink?.category ?: CAT_DRINK) }
    val lines = remember { mutableStateListOf<RecipeLineState>() }
    var menu by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val byId = ingredients.associateBy { it.id }

    LaunchedEffect(drink?.id) {
        lines.clear()
        if (drink != null) recipes.filter { it.drinkId == drink.id }.forEach { lines.add(RecipeLineState(it.ingredientId, it.qty.toString().removeSuffix(".0"))) }
    }

    val cost = lines.sumOf { l -> (byId[l.ingredientId]?.baseCost ?: 0.0) * Fmt.parseDouble(l.qty) }
    val effective = if (lines.isEmpty()) manualCost.toDouble() else cost
    val margin = if (sell > 0) (sell - effective) * 100.0 / sell else 0.0

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") }
            Text(if (drink == null) "نوشیدنی جدید" else "ویرایش نوشیدنی", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (drink != null) IconButton(onClick = { askDelete = true }) { Icon(Icons.Filled.DeleteOutline, "حذف نوشیدنی", tint = MaterialTheme.colorScheme.error) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppField(name, { name = it }, "نام نوشیدنی (لاته، کاپوچینو…)", Modifier.fillMaxWidth())
            MoneyField("قیمت فروش", sell, cur, { sell = it }, Modifier.fillMaxWidth())

            AppCard {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("دستور ساخت (مواد مصرفی برای یک نوشیدنی)", style = MaterialTheme.typography.titleSmall)
                    if (ingredients.isEmpty()) Text("ابتدا از تب «انبار کافه» مواد را تعریف کنید.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.warn)
                    lines.forEachIndexed { i, l ->
                        val ing = byId[l.ingredientId]
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1.3f)) {
                                Text(ing?.name ?: "؟ (حذف‌شده)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                ing?.let { Text("هزینه: " + Fmt.money(Math.round(it.baseCost * Fmt.parseDouble(l.qty)), cur, false), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            NumberField("مقدار", l.qty, { v -> lines[i] = l.copy(qty = v) }, Modifier.weight(1f), suffix = ing?.let { Fmt.unitLabel(it.unit) })
                            IconButton(onClick = { lines.removeAt(i) }) { Icon(Icons.Filled.DeleteOutline, "حذف ماده", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { menu = true }, enabled = ingredients.isNotEmpty()) { Icon(Icons.Filled.Add, null); Text("  افزودن ماده") }
                        DropdownMenu(menu, { menu = false }) {
                            ingredients.filter { ing -> lines.none { it.ingredientId == ing.id } }.forEach { ing ->
                                DropdownMenuItem(text = { Text(ing.name + " (" + Fmt.unitLabel(ing.unit) + ")") }, onClick = { lines.add(RecipeLineState(ing.id, "")); menu = false })
                            }
                        }
                    }
                    if (lines.isEmpty()) MoneyField("هزینهٔ ثابت (اگر دستور ساخت ندارد)", manualCost, cur, { manualCost = it }, Modifier.fillMaxWidth())
                }
            }

            val col = if (margin >= 50) MaterialTheme.extra.profit else MaterialTheme.extra.warn
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi("قیمت تمام‌شده", Fmt.money(Math.round(effective), cur), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                Kpi("سود هر نوشیدنی", Fmt.money(sell - Math.round(effective), cur), col, Modifier.weight(1f))
                Kpi("درصد سود", Fmt.percent(margin), col, Modifier.weight(1f))
            }
            if (effective > 0) {
                Text("قیمت پیشنهادی برای درصد سود هدف:", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf(50, 60, 65, 70, 75)) { m ->
                        FilterChip(false, { sell = Math.round(effective / (1 - m / 100.0) / 1000.0) * 1000 }, label = { Text(Fmt.digits("$m٪")) })
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Button(
            onClick = {
                if (name.isBlank()) { error = "نام نوشیدنی را وارد کنید"; return@Button }
                if (sell <= 0) { error = "قیمت فروش را وارد کنید"; return@Button }
                if (saving) return@Button // a double tap must not create the drink twice
                saving = true
                scope.launch {
                    try {
                        val base = drink?.let { vm.repo.raw(it.id) }
                        val p = (base ?: Product(newId(), name.trim(), code = vm.repo.nextCode())).copy(
                            name = name.trim(), category = category, sellPrice = sell, buyPrice = if (lines.isEmpty()) manualCost else 0,
                            unit = Measure.PIECE, trackStock = false, kind = KIND_DRINK, favorite = true,
                        )
                        vm.repo.saveProduct(p)
                        vm.repo.saveRecipe(p.id, lines.filter { Fmt.parseDouble(it.qty) > 0 }.map { it.ingredientId to Fmt.parseDouble(it.qty) })
                        onClose()
                    } catch (e: Exception) {
                        error = "ذخیره نشد: ${e.message}"
                    } finally {
                        saving = false
                    }
                }
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(), shape = RoundedCornerShape(16.dp),
        ) { Text("ذخیرهٔ نوشیدنی", style = MaterialTheme.typography.titleSmall) }
    }
    if (askDelete) AlertDialog(
        onDismissRequest = { askDelete = false },
        title = { Text("حذف نوشیدنی") },
        text = { Text("«$name» از منو حذف شود؟ سابقهٔ فروش حفظ می‌شود.") },
        confirmButton = { TextButton(onClick = { drink?.let { vm.deleteProduct(it.id) }; askDelete = false; onClose() }) { Text("حذف", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text("انصراف") } },
    )
}
