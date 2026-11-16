package com.mozkhan.shop

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mozkhan.shop.data.AppDb
import com.mozkhan.shop.data.BackupParser
import com.mozkhan.shop.data.CartLine
import com.mozkhan.shop.data.KIND_DRINK
import com.mozkhan.shop.data.KIND_INGREDIENT
import com.mozkhan.shop.data.Measure
import com.mozkhan.shop.data.Product
import com.mozkhan.shop.data.Repo
import com.mozkhan.shop.sync.CloudSync
import com.mozkhan.shop.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Repository behaviour on a real (in-memory) Room database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RepoTest {
    private lateinit var db: AppDb
    private lateinit var repo: Repo

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
        val prefs = Prefs(ctx)
        repo = Repo(db, prefs, CloudSync(ctx, db, CoroutineScope(Dispatchers.Unconfined), prefs))
    }

    @After fun tearDown() = db.close()

    private suspend fun stock(id: String) = repo.byId(id)?.stock ?: db.stock().all().filter { it.productId == id }.sumOf { it.qty }

    @Test fun deletedIngredientIsCostedButNotConsumed() = runBlocking {
        val milk = Product("milk", "شیر", unit = Measure.VOLUME, buyPrice = 40_000, kind = KIND_INGREDIENT, code = "1")
        val cup = Product("cup", "لیوان", buyPrice = 1_500, kind = KIND_INGREDIENT, code = "2")
        val latte = Product("latte", "لاته", sellPrice = 60_000, trackStock = false, kind = KIND_DRINK, code = "3")
        repo.saveProduct(milk, 5_000.0)
        repo.saveProduct(cup, 100.0)
        repo.saveProduct(latte)
        repo.saveRecipe("latte", listOf("milk" to 200.0, "cup" to 1.0))
        repo.deleteProduct("cup")

        val sale = repo.checkout(listOf(CartLine(repo.byId("latte")!!, 2.0, 60_000)), 0, "CASH")

        assertEquals(2 * 9_500L, sale.cost)                    // 200 ml milk (8,000) + cup (1,500), twice
        assertEquals(4_600.0, stock("milk"), 0.001)           // milk was consumed
        val cupMoves = db.stock().all().filter { it.productId == "cup" }
        assertEquals(listOf("OPENING"), cupMoves.map { it.reason }) // the deleted cup was not touched
    }

    @Test fun voidedSaleStaysVoidedAfterRestoringAnOlderBackup() = runBlocking {
        repo.saveProduct(Product("p", "بهمن", buyPrice = 90_000, sellPrice = 100_000, code = "10"), 10.0)
        val sale = repo.checkout(listOf(CartLine(repo.byId("p")!!, 3.0, 100_000)), 0, "CASH")
        val backup = repo.exportJson()                        // taken BEFORE the void
        Thread.sleep(5)
        repo.voidSale(sale.id)
        assertEquals(10.0, stock("p"), 0.001)

        repo.importJson(backup)

        assertTrue(db.sales().sale(sale.id)!!.voided)           // not resurrected as a valid sale
        assertEquals(10.0, stock("p"), 0.001)                   // stock still consistent (moves are append-only)
    }

    // org.json is only real under Robolectric, so the parser tests live here
    @Test fun backupWithoutTimestampsDoesNotLandIn1970() {
        val json = """{"version":3,"exportedAt":1700000000000,
            "moves":[{"id":"m","productId":"p","qty":5,"reason":"OPENING"}],
            "sales":[{"id":"s","subtotal":10,"total":10,"cost":5,"updatedAt":1690000000000}],
            "expenses":[{"id":"e","title":"x","amount":3}],
            "payments":[{"id":"y","customerId":"c","amount":1,"ts":0}],
            "docs":[{"id":"d","kind":"DAMAGE"}]}"""
        val b = BackupParser.parse(json, now = 1_800_000_000_000)
        assertEquals(1_700_000_000_000, b.moves[0].ts)      // export time
        assertEquals(1_690_000_000_000, b.sales[0].ts)      // the row's own updatedAt
        assertEquals(1_690_000_000_000, b.sales[0].updatedAt)
        assertEquals(1_700_000_000_000, b.expenses[0].ts)
        assertEquals(1_700_000_000_000, b.payments[0].ts)
        assertEquals(1_700_000_000_000, b.docs[0].updatedAt)
        val noExport = BackupParser.parse("""{"moves":[{"id":"m","productId":"p","qty":1,"reason":"SALE"}]}""", now = 42L)
        assertEquals(42L, noExport.moves[0].ts)
    }

    @Test fun newerBackupIsRefusedWithAClearMessage() {
        try {
            BackupParser.parse("""{"version":99}"""); fail()
        } catch (e: IllegalArgumentException) {
            assertEquals(BackupParser.NEWER_FILE, e.message)
        }
    }

    @Test fun historyBlocksMeasureChange() = runBlocking {
        repo.saveProduct(Product("n", "نو", sellPrice = 1, code = "20"))
        assertFalse(repo.hasHistory("n"))
        repo.saveProduct(Product("h", "قدیمی", sellPrice = 1, code = "21"), 5.0)
        assertTrue(repo.hasHistory("h"))
    }

    @Test fun collidingCodesAreRepaired() = runBlocking {
        db.products().upsert(Product("b", "x", code = "1005"))
        db.products().upsert(Product("a", "y", code = "1005"))
        repo.ensureCodes()
        assertEquals("1005", db.products().raw("a")!!.code)
        assertEquals("1006", db.products().raw("b")!!.code)
    }
}
