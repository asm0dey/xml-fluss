package xmlfluss.sample

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InventoryParserTest {

    @Test
    fun parsesAllMapShapes() = runTest {
        val xml = """
            <warehouse>
              <inventory region="us">
                <label k="version" v="1.0"/>
                <label k="env" v="prod"/>
                <count sku="A1"><n>5</n></count>
                <count sku="B2"><n>10</n></count>
                <tag cat="color"><name>red</name></tag>
                <tag cat="color"><name>blue</name></tag>
                <tag cat="size"><name>large</name></tag>
                <rule out="ok"><input>1</input><input>2</input></rule>
                <rule out="bad"><input>3</input></rule>
                <item sku="X1"><stock id="100"><name>Widget</name><qty>50</qty></stock></item>
                <item sku="Y2"><stock id="200"><name>Gadget</name><qty>20</qty></stock></item>
                <combo><member>a</member><member>b</member><score>1</score><score>2</score></combo>
                <combo><member>a</member><member>b</member><score>3</score></combo>
                <combo><member>c</member><score>9</score></combo>
                <tally bucket="hot" n="7"/>
                <tally bucket="hot" n="8"/>
                <tally bucket="cold" n="1"/>
                <opt k="a"><v>1</v></opt>
                <opt k="b"/>
                <opt k="c"><v>3</v></opt>
                <rev k="x"><v>10</v></rev>
                <rev><v>20</v></rev>
                <rev k="y"><v>30</v></rev>
                <slot k="hit"><stock id="9"><name>Bolt</name><qty>4</qty></stock></slot>
                <slot k="miss"/>
                <extra k="trace" v="on"/>
                <reports>
                  <region name="north">
                    <report code="r1"><title>Q1</title></report>
                  </region>
                  <region name="south">
                    <report code="r2"><title>Q2</title></report>
                  </region>
                </reports>
                <summary>
                  <bucket>
                    <report code="r3"><title>Q3</title></report>
                    <report code="r4"><title>Q4</title></report>
                  </bucket>
                </summary>
              </inventory>
            </warehouse>
        """.trimIndent()

        val invs = InventoryParser.parse(xml.byteInputStream()).toList()
        assertEquals(1, invs.size)
        val inv = invs[0]

        assertEquals("us", inv.region)
        assertEquals(mapOf("version" to "1.0", "env" to "prod"), inv.labels)
        assertEquals(mapOf("A1" to 5, "B2" to 10), inv.counts)
        assertEquals(
            mapOf("color" to listOf("red", "blue"), "size" to listOf("large")),
            inv.tags,
        )
        assertEquals(
            mapOf(listOf(1, 2) to "ok", listOf(3) to "bad"),
            inv.rules,
        )
        assertEquals(
            mapOf(
                "X1" to StockItem(100, "Widget", 50),
                "Y2" to StockItem(200, "Gadget", 20),
            ),
            inv.stock,
        )
        assertEquals(
            mapOf(
                listOf("a", "b") to listOf(1, 2, 3),
                listOf("c") to listOf(9),
            ),
            inv.combos,
        )
        assertEquals(
            mapOf(
                listOf("hot") to listOf(7, 8),
                listOf("cold") to listOf(1),
            ),
            inv.tallies,
        )
        assertEquals(mapOf("a" to 1, "b" to null, "c" to 3), inv.optionals)
        assertEquals(mapOf("x" to 10, null to 20, "y" to 30), inv.nullableKeys)
        assertEquals(
            mapOf("hit" to StockItem(9, "Bolt", 4), "miss" to null),
            inv.nullableNested,
        )
        assertEquals(mapOf("trace" to "on"), inv.extras)
        assertEquals(listOf("Q1", "Q2"), inv.reportTitles)
        assertEquals(listOf("r1", "r2"), inv.reportCodes)
        assertEquals(
            mapOf(listOf("r3", "r4") to listOf("Q3", "Q4")),
            inv.summaries,
        )
    }

    @Test
    fun emptyInventoryYieldsEmptyMaps() = runTest {
        val xml = """
            <warehouse>
              <inventory region="eu"/>
            </warehouse>
        """.trimIndent()
        val invs = InventoryParser.parse(xml.byteInputStream()).toList()
        assertEquals(1, invs.size)
        val inv = invs[0]
        assertEquals("eu", inv.region)
        assertEquals(emptyMap(), inv.labels)
        assertEquals(emptyMap(), inv.counts)
        assertEquals(emptyMap(), inv.tags)
        assertEquals(emptyMap(), inv.rules)
        assertEquals(emptyMap(), inv.stock)
        assertEquals(emptyMap(), inv.combos)
        assertEquals(emptyMap(), inv.tallies)
        assertEquals(emptyMap(), inv.optionals)
        assertEquals(emptyMap(), inv.nullableKeys)
        assertEquals(emptyMap(), inv.nullableNested)
        assertNull(inv.extras)
        assertEquals(emptyList(), inv.reportTitles)
        assertEquals(emptyList(), inv.reportCodes)
        assertEquals(emptyMap(), inv.summaries)
    }

    @Test
    fun lastWinsOnDuplicateScalarKey() = runTest {
        val xml = """
            <warehouse>
              <inventory region="ap">
                <label k="env" v="dev"/>
                <label k="env" v="staging"/>
                <label k="env" v="prod"/>
              </inventory>
            </warehouse>
        """.trimIndent()
        val inv = InventoryParser.parse(xml.byteInputStream()).toList().single()
        assertEquals(mapOf("env" to "prod"), inv.labels)
    }
}
