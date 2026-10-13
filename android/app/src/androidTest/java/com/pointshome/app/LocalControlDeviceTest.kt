package com.pointshome.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/** Run only against an isolated emulator and the disposable local QA family. */
@RunWith(AndroidJUnit4::class)
class LocalControlDeviceTest {
    @Test fun encryptedWalletAndDuplicateDeliveryThenSeedRestrictedApp() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("qaEnvironment") == "isolated")
        val store = LocalControlStore.get(context)
        val child = AccountInfo(args.getString("childId")!!, "CHILD", null, "qa_child", "小星")
        val device = TimeDeviceRegistration(args.getString("deviceId")!!, args.getString("deviceKey")!!)
        store.configure(child, "http://10.0.2.2:8080", device, "parent-qa-123")
        assertFalse(store.verifyPin("wrong")); assertTrue(store.verifyPin("parent-qa-123"))
        val grant = TimeGrant("qa-idempotent-delivery", 1)
        store.applyGrant(grant); store.applyGrant(grant)
        assertEquals(60_000L, store.snapshot().remainingMillis)
        val encrypted = context.getSharedPreferences("local_control_v1", 0).getString("state", "")!!
        assertFalse(encrypted.contains("parent-qa-123")); assertFalse(encrypted.contains(device.deviceKey))
        assertFalse(encrypted.contains(child.id)); assertFalse(encrypted.contains(grant.id))
        var clock = 0L
        val budget = TimeBudget({ store.snapshot().remainingMillis }, store::adjustRemainingMillis, { clock })
        assertTrue(budget.resume()); clock = 1_500; budget.pause()
        assertEquals(58_500L, store.snapshot().remainingMillis)
        store.setRemainingMillis(0)
        store.setRules(setOf("com.android.chrome"), true)
        TokenVault(context).save(args.getString("childToken")!!)
        context.getSharedPreferences("server_settings", 0).edit().putString("server_url", "http://10.0.2.2:8080").commit()
        Unit
    }
}
