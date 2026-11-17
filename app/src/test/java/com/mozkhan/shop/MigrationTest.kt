package com.mozkhan.shop

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.mozkhan.shop.data.AppDb
import com.mozkhan.shop.data.MIGRATION_2_3
import com.mozkhan.shop.data.MIGRATION_3_4
import com.mozkhan.shop.data.MIGRATION_4_5
import com.mozkhan.shop.data.MIGRATION_5_6
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the real v2 -> v3 migration on a v2 database built from the exported schema (no device needed). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDb::class.java)

    @Test
    fun migrate2To3_keepsDataAndBackfills() {
        val db = helper.createDatabase("mig.db", 2)
        fun product(id: String, name: String, cat: String, track: Int) = db.execSQL(
            "INSERT INTO products (id,name,barcode,category,unit,buyPrice,sellPrice,minStock,fingerprints,favorite,deleted,updatedAt,boxBarcode,boxSize,boxBuyPrice,boxSellPrice,trackStock) " +
                "VALUES ('$id','$name',NULL,'$cat','PIECE',90000,100000,0,'',0,0,1,NULL,0,0,0,$track)",
        )
        product("p1", "بهمن", "سیگار", 1)
        product("p2", "اسپرسو", "قهوه", 0)      // cafe cup  -> must become a DRINK
        product("p3", "تنباکو", "تنباکو", 1)
        db.execSQL("INSERT INTO stock_moves (id,productId,qty,reason,refId,ts) VALUES ('m1','p1',50,'PURCHASE',NULL,10)")
        db.execSQL("INSERT INTO stock_moves (id,productId,qty,reason,refId,ts) VALUES ('m2','p1',-3,'SALE','s1',20)")
        db.execSQL("INSERT INTO stock_docs (id,ts,kind,note,cost) VALUES ('d1',777,'DAMAGE','x',1000)")
        db.execSQL("INSERT INTO sales (id,ts,subtotal,discount,total,cost,payment,note,voided,updatedAt,customerId) VALUES ('s1',20,300000,0,300000,270000,'CASH','',0,20,NULL)")
        db.execSQL("INSERT INTO sale_items (id,saleId,productId,name,qty,isWeight,unitPrice,unitCost,category) VALUES ('i1','s1','p1','بهمن',3,0,100000,90000,'سیگار')")
        db.execSQL("INSERT INTO sale_items (id,saleId,productId,name,qty,isWeight,unitPrice,unitCost,category) VALUES ('i2','s1','p2','اسپرسو',1,0,50000,20000,'قهوه')")
        db.close()

        val m = helper.runMigrationsAndValidate("mig.db", 3, true, MIGRATION_2_3)

        // nothing lost
        m.query("SELECT COUNT(*) FROM products").use { c -> c.moveToFirst(); assertEquals(3, c.getInt(0)) }
        m.query("SELECT SUM(qty) FROM stock_moves WHERE productId='p1'").use { c -> c.moveToFirst(); assertEquals(47.0, c.getDouble(0), 0.0001) }
        m.query("SELECT COUNT(*) FROM sales").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }

        // backfills
        m.query("SELECT kind FROM products WHERE id='p2'").use { c -> c.moveToFirst(); assertEquals("DRINK", c.getString(0)) }
        m.query("SELECT kind FROM products WHERE id='p1'").use { c -> c.moveToFirst(); assertEquals("GOODS", c.getString(0)) }
        m.query("SELECT dept FROM sale_items WHERE id='i2'").use { c -> c.moveToFirst(); assertEquals("CAFE", c.getString(0)) }
        m.query("SELECT dept FROM sale_items WHERE id='i1'").use { c -> c.moveToFirst(); assertEquals("SHOP", c.getString(0)) }
        m.query("SELECT updatedAt FROM stock_docs WHERE id='d1'").use { c -> c.moveToFirst(); assertEquals(777L, c.getLong(0)) }
        m.query("SELECT unitCost FROM stock_moves WHERE id='m1'").use { c -> c.moveToFirst(); assertEquals(0.0, c.getDouble(0), 0.0) }

        // unique, sequential product codes starting at 1001
        val codes = ArrayList<String>()
        m.query("SELECT code FROM products").use { c -> while (c.moveToNext()) codes.add(c.getString(0)) }
        assertEquals(3, codes.toSet().size)
        assertTrue(codes.all { it.toInt() in 1001..1003 })
    }

    @Test
    fun migrate3To4_addsEmptyDeviceNameAndKeepsInvoices() {
        val db = helper.createDatabase("mig34.db", 3)
        db.execSQL("INSERT INTO sales (id,ts,subtotal,discount,total,cost,payment,note,voided,updatedAt,customerId) VALUES ('s1',20,300000,0,300000,270000,'CASH','',0,20,NULL)")
        db.execSQL("INSERT INTO sales (id,ts,subtotal,discount,total,cost,payment,note,voided,updatedAt,customerId) VALUES ('s2',30,50000,0,50000,40000,'CREDIT','x',1,31,'c1')")
        db.execSQL("INSERT INTO sale_items (id,saleId,productId,name,qty,isWeight,unitPrice,unitCost,category,dept) VALUES ('i1','s1','p1','بهمن',3,0,100000,90000,'سیگار','SHOP')")
        db.close()

        val m = helper.runMigrationsAndValidate("mig34.db", 4, true, MIGRATION_3_4)

        m.query("SELECT COUNT(*) FROM sales").use { c -> c.moveToFirst(); assertEquals(2, c.getInt(0)) }
        m.query("SELECT COUNT(*) FROM sale_items").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        m.query("SELECT deviceName, voided, customerId, total FROM sales WHERE id='s2'").use { c ->
            c.moveToFirst()
            assertEquals("", c.getString(0))
            assertEquals(1, c.getInt(1))
            assertEquals("c1", c.getString(2))
            assertEquals(50000L, c.getLong(3))
        }
        // a new row written by the v4 app (without naming the column) still gets the default
        m.execSQL("INSERT INTO sales (id,ts,subtotal,discount,total,cost,payment,note,voided,updatedAt,customerId) VALUES ('s3',40,1,0,1,0,'CASH','',0,40,NULL)")
        m.query("SELECT deviceName FROM sales WHERE id='s3'").use { c -> c.moveToFirst(); assertEquals("", c.getString(0)) }
    }

    @Test
    fun migrate2To4_fullChain() {
        val db = helper.createDatabase("mig24.db", 2)
        db.execSQL("INSERT INTO sales (id,ts,subtotal,discount,total,cost,payment,note,voided,updatedAt,customerId) VALUES ('s1',20,300000,0,300000,270000,'CASH','',0,20,NULL)")
        db.close()
        val m = helper.runMigrationsAndValidate("mig24.db", 4, true, MIGRATION_2_3, MIGRATION_3_4)
        m.query("SELECT deviceName FROM sales WHERE id='s1'").use { c -> c.moveToFirst(); assertEquals("", c.getString(0)) }
    }

    @Test
    fun migrate4To5_addsEmptyOutboxAndKeepsData() {
        val db = helper.createDatabase("mig45.db", 4)
        db.execSQL("INSERT INTO sales (id,ts,subtotal,discount,total,cost,payment,note,voided,updatedAt,customerId,deviceName) VALUES ('s1',20,300000,0,300000,270000,'CASH','',0,20,NULL,'a')")
        db.close()
        val m = helper.runMigrationsAndValidate("mig45.db", 5, true, MIGRATION_4_5)
        m.query("SELECT COUNT(*) FROM sales").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        m.query("SELECT COUNT(*) FROM outbox").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        // re-marking the same document replaces its entry (unique index) and gets a newer seq
        m.execSQL("INSERT OR REPLACE INTO outbox (coll,docId) VALUES ('sales','s1')")
        m.execSQL("INSERT OR REPLACE INTO outbox (coll,docId) VALUES ('sales','s1')")
        m.query("SELECT COUNT(*), MAX(seq) FROM outbox").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)); assertEquals(2, c.getInt(1)) }
    }

    @Test
    fun migrate2To5_fullChain() {
        val db = helper.createDatabase("mig25.db", 2)
        db.execSQL("INSERT INTO sales (id,ts,subtotal,discount,total,cost,payment,note,voided,updatedAt,customerId) VALUES ('s1',20,300000,0,300000,270000,'CASH','',0,20,NULL)")
        db.close()
        val m = helper.runMigrationsAndValidate("mig25.db", 5, true, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        m.query("SELECT deviceName FROM sales WHERE id='s1'").use { c -> c.moveToFirst(); assertEquals("", c.getString(0)) }
        m.query("SELECT COUNT(*) FROM outbox").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
    }

    @Test
    fun migrate5To6_exitDocsNotCountedByDefault() {
        val db = helper.createDatabase("mig56.db", 5)
        db.execSQL("INSERT INTO stock_docs (id,ts,kind,note,cost,voided,updatedAt) VALUES ('d1',10,'DAMAGE','x',5000,0,10)")
        db.close()
        val m = helper.runMigrationsAndValidate("mig56.db", 6, true, MIGRATION_5_6)
        m.query("SELECT counted, cost FROM stock_docs WHERE id='d1'").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)); assertEquals(5000L, c.getLong(1)) }
    }

    @Test
    fun versionConstantMatchesDatabase() {
        // AppDb.VERSION drives the pre-migration safety copy: it must follow the @Database version
        assertEquals(6, AppDb.VERSION)
    }

    @Test
    fun migrate2To3_isSafeToRunOnPartiallyUpgradedDatabase() {
        // simulates an interrupted earlier attempt: some new columns already exist
        val db = helper.createDatabase("mig2.db", 2)
        db.execSQL("ALTER TABLE products ADD COLUMN code TEXT NOT NULL DEFAULT ''")
        db.execSQL("INSERT INTO products (id,name,barcode,category,unit,buyPrice,sellPrice,minStock,fingerprints,favorite,deleted,updatedAt,boxBarcode,boxSize,boxBuyPrice,boxSellPrice,trackStock,code) VALUES ('p9','x',NULL,'سایر','PIECE',1,2,0,'',0,0,1,NULL,0,0,0,1,'1500')")
        db.close()
        val m = helper.runMigrationsAndValidate("mig2.db", 3, true, MIGRATION_2_3)
        m.query("SELECT code FROM products WHERE id='p9'").use { c -> c.moveToFirst(); assertEquals("1500", c.getString(0)) }
    }
}
