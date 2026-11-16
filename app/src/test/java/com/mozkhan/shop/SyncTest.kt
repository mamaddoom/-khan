package com.mozkhan.shop

import com.mozkhan.shop.data.Sale
import com.mozkhan.shop.data.SaleItem
import com.mozkhan.shop.data.StockDoc
import com.mozkhan.shop.sync.RemoteMeta
import com.mozkhan.shop.sync.SyncRules
import com.mozkhan.shop.sync.toItem
import com.mozkhan.shop.sync.toMap
import com.mozkhan.shop.sync.toSale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure sync rules: field mapping and conflict handling (no Firebase needed). */
class SyncTest {
    private fun sale(id: String = "s1", updatedAt: Long = 100, voided: Boolean = false, device: String = "صندوق ۱") =
        Sale(id, ts = 50, subtotal = 300_000, discount = 10_000, total = 290_000, cost = 200_000, payment = "CARD",
            note = "n", voided = voided, updatedAt = updatedAt, customerId = null, deviceName = device)

    private fun merge(local: Sale?, remote: Sale) =
        SyncRules.merge(local, remote, { it.updatedAt }, { it.voided }, { s, f, t -> s.copy(voided = f, updatedAt = t) })

    @Test fun saleRoundTripsThroughFirestoreMap() {
        val s = sale().copy(customerId = "c9")
        // Firestore hands numbers back as Long / Double: simulate that
        val back = s.toMap().mapValues { (_, v) -> if (v is Int) v.toLong() else v }.toSale()
        assertEquals(s, back)
        assertEquals("صندوق ۱", back.deviceName)
    }

    @Test fun saleFromOlderPhoneWithoutDeviceNameStillParses() {
        val m = sale().toMap().filterKeys { it != "deviceName" }
        assertEquals("", m.toSale().deviceName)
    }

    @Test fun saleWithoutUpdatedAtFallsBackToItsTime() {
        val m = sale().toMap().filterKeys { it != "updatedAt" }
        assertEquals(50L, m.toSale().updatedAt)
    }

    @Test fun pingDocumentIsNotAnInvoice() {
        val r = runCatching { mapOf<String, Any?>("ts" to 5L).toSale() }
        assertTrue(r.isFailure)
    }

    @Test fun itemRoundTrips() {
        val i = SaleItem("i1", "s1", "p1", "بهمن", 3.0, false, 100_000.0, 90_000.0, "سیگار", "SHOP")
        assertEquals(i, i.toMap().toItem())
    }

    @Test fun unknownInvoiceIsStored() {
        val m = merge(null, sale())
        assertEquals(sale(), m.store)
        assertNull(m.uploadBack)
    }

    @Test fun sameInvoiceIsNotRewritten() {
        val m = merge(sale(), sale())
        assertNull(m.store)
        assertNull(m.uploadBack)
    }

    @Test fun newerRemoteWins() {
        val m = merge(sale(updatedAt = 100), sale(updatedAt = 200, voided = true))
        assertEquals(true, m.store?.voided)
        assertEquals(200L, m.store?.updatedAt)
    }

    @Test fun voidIsStickyEvenWhenTheOtherClockIsAhead() {
        // voided here at t=150, but the other phone's (clock-ahead) copy says updatedAt=900 and not voided
        val local = sale(updatedAt = 150, voided = true)
        val remote = sale(updatedAt = 900, voided = false)
        val m = merge(local, remote)
        assertEquals(true, m.store?.voided)
        assertEquals(900L, m.store?.updatedAt)
        // ...and the cloud must learn about the void
        assertEquals(true, m.uploadBack?.voided)
    }

    @Test fun voidedRemoteIsAppliedEvenWhenOlder() {
        val m = merge(sale(updatedAt = 900), sale(updatedAt = 150, voided = true))
        assertEquals(true, m.store?.voided)
        assertNull(m.uploadBack)
    }

    @Test fun docsUseTheSameRule() {
        val local = StockDoc("d", 1, "PURCHASE", voided = true, updatedAt = 5)
        val remote = StockDoc("d", 1, "PURCHASE", voided = false, updatedAt = 50)
        val m = SyncRules.merge(local, remote, { it.updatedAt }, { it.voided }, { d, f, t -> d.copy(voided = f, updatedAt = t) })
        assertTrue(m.store!!.voided)
    }

    @Test fun uploadDecision() {
        // missing in the cloud -> upload (mutable and immutable rows alike)
        assertTrue(SyncRules.shouldUpload(10, false, null))
        assertTrue(SyncRules.shouldUpload(null, false, null))
        // immutable row already there -> nothing to do
        assertFalse(SyncRules.shouldUpload(null, false, RemoteMeta(0, false)))
        // newer here -> upload; older or same -> not
        assertTrue(SyncRules.shouldUpload(20, false, RemoteMeta(10, false)))
        assertFalse(SyncRules.shouldUpload(10, false, RemoteMeta(10, false)))
        assertFalse(SyncRules.shouldUpload(5, false, RemoteMeta(10, false)))
        // voided here but not in the cloud -> upload even with an older clock
        assertTrue(SyncRules.shouldUpload(5, true, RemoteMeta(10, false)))
        // voided in the cloud but not here -> never upload (would resurrect the invoice)
        assertFalse(SyncRules.shouldUpload(50, false, RemoteMeta(10, true)))
    }
}
