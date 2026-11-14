package com.example.careway

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.careway.data.*
import com.example.careway.core.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CareStoreTest {
    @Test fun upgradePreservesV2RecordsAndExportsOnlySelectedMode() {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("test_v3_$name", mode)
        }
        for (name in listOf("careway", "careway_state_v2")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        try {
            val at = System.currentTimeMillis()
            val event = JSONObject().put("at", at).put("title", "v2 preserved").put("detail", "migration").put("demo", false)
            context.getSharedPreferences("careway_state_v2", Context.MODE_PRIVATE).edit().putString("events", JSONArray().put(event).toString()).commit()
            val store = CareStore(context)
            assertEquals(1, store.events().size)
            assertEquals(1, CareStore(context).events().size)
            store.addReaction(ReactionRecord(at, 450, false, false))
            store.addReaction(ReactionRecord(at, 900, false, true))
            store.addEvent(CareEvent(at, "demo only", "", true))
            val exported = JSONObject(store.export(false))
            assertEquals(1, exported.getJSONArray("events").length())
            assertEquals(450, exported.getJSONArray("reactions").getJSONObject(0).getInt("ms"))
            assertFalse(store.exportCsv(false).contains("900"))
            store.clearHistory(true)
            assertEquals(1, store.reactions().size)
            assertEquals("v2 preserved", store.events().single().title)
        } finally {
            for (name in listOf("careway", "careway_state_v2")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
