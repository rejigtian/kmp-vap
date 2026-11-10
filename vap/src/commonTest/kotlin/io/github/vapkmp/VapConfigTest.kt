package io.github.vapkmp

import kotlin.test.*

internal class VapConfigTest {
    private val fixture = """{
      "info":{"v":2,"w":100,"h":80,"videoW":200,"videoH":100,"fps":20,"f":3,
        "rgbFrame":[0,0,100,80],"aFrame":[100,0,100,80]},
      "src":[{"srcId":"a","srcTag":"[avatar]","srcType":"img","w":32,"h":32,"fitType":"centerFull"},
             {"srcId":"t","srcTag":"[name]","srcType":"txt","w":80,"h":20,"color":"#fff18d","style":"b"}],
      "frame":[{"i":2,"obj":[
        {"srcId":"t","z":2,"frame":[0,20,80,20],"mFrame":[100,80,20,20],"mt":90},
        {"srcId":"a","z":1,"frame":[5,5,32,32],"mFrame":[120,80,20,20],"mt":0}]}]
    }"""
    @Test fun retainsSourceTagsAndSortsFusionLayers() {
        val parsed = parseVapConfig(fixture)
        assertEquals("[avatar]", parsed.resources[0].tag)
        assertTrue(parsed.resources[0].centerCrop)
        assertTrue(parsed.resources[1].bold)
        assertEquals(listOf("a", "t"), parsed.overlays.getValue(2).map { it.id })
        assertEquals(90, parsed.overlays.getValue(2)[1].rotation)
        assertNull(parsed.overlays[0]) // 没有融合帧的时间段仍播放基础视频。
    }
    @Test fun permitsFusionFramesPartlyOutsideCanvas() {
        val parsed = parseVapConfig(fixture.replace("[5,5,32,32]", "[-5,-5,32,32]"))
        assertEquals(-5, parsed.overlays.getValue(2)[0].rect.x)
    }
    @Test fun refusesInvalidColorBeforeComposition() {
        assertFailsWith<IllegalArgumentException> { parseVapConfig(fixture.replace("#fff18d", "#GG0000")) }
    }
    @Test fun refusesRegionsOutsidePackedVideo() {
        assertFailsWith<IllegalArgumentException> { parseVapConfig(fixture.replace("[120,80,20,20]", "[199,80,20,20]")) }
    }
    @Test fun refusesUnknownSourceAndMaskRotation() {
        assertFailsWith<IllegalArgumentException> { parseVapConfig(fixture.replace("\"mt\":90", "\"mt\":45")) }
        assertFailsWith<IllegalArgumentException> { parseVapConfig(fixture.replace("\"srcId\":\"t\",\"z\"", "\"srcId\":\"missing\",\"z\"")) }
    }
    @Test fun refusesUnsupportedVersionInsteadOfGuessingLayout() {
        assertFailsWith<IllegalArgumentException> { parseVapConfig(fixture.replace("\"v\":2", "\"v\":1")) }
    }
}
