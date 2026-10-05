package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 虫害监测（museum-8）验收：
 * 1) 展厅/库房布放诱捕点位，记录编号、布放日期、坐标与责任保管员；
 * 2) 现场记录编号、虫种数量与照片，按提交时规则版本固化阈值/结论；
 * 3) 超过处置阈值自动生成处置任务并关联责任区域（展厅/库区）；连续超阈并入，关闭后另开；
 * 4) 外包人员只能提交现场数据/照片，不能登记处置、不能关闭任务；关闭须处置+照片+馆内岗位；
 * 5) 更换诱捕片旧片置 REPLACED 留行、不删除旧监测记录；
 * 6) 点位迁移旧址置 MOVED、另立新点位，迁移记录保留前后坐标；
 * 7) 监测结果按月份冻结，冻结后不可补录/补传，当月不可冻结；
 * 8) 规则调整只能发新版本，旧版本置 SUPERSEDED，历史监测旧结论不被改写；
 * 9) requestNo 幂等；虫害台账全动作留痕。
 * 使用独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-pest;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PestMonitoringTest {

    @TempDir
    static Path imageDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("museum.image.storage-root", () -> imageDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void pest_monitoring_rules_hold() throws Exception {
        // ========== 一、展厅与库房各布一个诱捕点位 ==========
        long hallId = dataOf(postJson("/api/halls", "{"
                + "\"hallNo\":\"PH1\",\"name\":\"书画厅\",\"location\":\"一层东\",\"custodian\":\"王保管\"}"))
                .get("id").asLong();
        long areaId = dataOf(postJson("/api/storage/areas", "{"
                + "\"areaNo\":\"PA1\",\"name\":\"纸质库\",\"tempZone\":\"CONST_TEMP\","
                + "\"fireRating\":\"F1\",\"loadCapacityKg\":1000,\"manager\":\"李库管\"}"))
                .get("id").asLong();

        long hallPoint = dataOf(postJson("/api/pest-points", "{"
                + "\"pointNo\":\"TP-H1\",\"locationType\":\"HALL\",\"hallId\":" + hallId
                + ",\"coordX\":10.123,\"coordY\":20.5,\"coordDesc\":\"东南角展柜底\","
                + "\"trapType\":\"粘虫板\",\"laidAt\":\"2026-01-10T09:00:00\"}")).get("id").asLong();
        long storePoint = dataOf(postJson("/api/pest-points", "{"
                + "\"pointNo\":\"TP-A1\",\"locationType\":\"STORAGE\",\"storageAreaId\":" + areaId
                + ",\"coordX\":5.0,\"coordY\":6.0,\"coordDesc\":\"3号架底层\"}")).get("id").asLong();

        // 编号唯一
        expectFail(postJson("/api/pest-points", "{"
                + "\"pointNo\":\"TP-H1\",\"locationType\":\"HALL\",\"hallId\":" + hallId
                + ",\"coordX\":1,\"coordY\":1}"), "已存在");
        // 区域二选一校验
        expectFail(postJson("/api/pest-points", "{"
                + "\"pointNo\":\"TP-X1\",\"locationType\":\"HALL\",\"storageAreaId\":" + areaId
                + ",\"coordX\":1,\"coordY\":1}"), "hallId");

        JsonNode hallPointDetail = dataOf(getJson("/api/pest-points/" + hallPoint));
        assertEquals("PH1", hallPointDetail.get("point").get("areaNoSnapshot").asText(),
                "点位固化责任展厅编号快照");
        assertEquals("王保管", hallPointDetail.get("point").get("custodian").asText(),
                "未指定保管员时取展厅责任保管员");
        JsonNode storePointDetail = dataOf(getJson("/api/pest-points/" + storePoint));
        assertEquals("PA1", storePointDetail.get("point").get("areaNoSnapshot").asText());
        assertEquals("李库管", storePointDetail.get("point").get("custodian").asText(),
                "库房点位取库区 manager 为责任人");

        // ========== 二、发布规则 v1：衣鱼 warn2/alarm5，烟草甲 warn1/alarm3 ==========
        publishRule("PEST-R1", "v1", 2, 5, 1, 3);
        assertEquals("ACTIVE", dataOf(getJson("/api/pest-rules/active")).get("version").get("status").asText());

        // ========== 三、外包人员提交现场数据：正常单不产生任务 ==========
        long check1 = submit(hallPoint, "REQ-1", "外包赵", "OUTSOURCER", null,
                item("衣鱼", 0), item("烟草甲", 0));
        JsonNode c1 = dataOf(getJson("/api/pest-checks/" + check1));
        assertEquals(0, c1.get("check").get("abnormal").asInt());
        assertEquals("PEST-R1", c1.get("ruleVersionNo").asText());
        assertEquals("NORMAL", findLine(c1, "衣鱼").get("level").asText());
        assertEquals("OUTSOURCER", c1.get("check").get("inspectorRole").asText());
        assertEquals(0, dataOf(getJson("/api/pest-tasks?pointId=" + hallPoint)).size());
        // 外包人员可上传现场照片
        uploadCheckPhoto(check1, "c1.png", "外包赵", "OUTSOURCER", "点位全貌");
        assertEquals(1, dataOf(getJson("/api/pest-checks/" + check1)).get("photos").size());

        // ========== 四、超阈自动开任务并关联责任区域；外包不能处置/关闭 ==========
        long check2 = submit(hallPoint, "REQ-2", "外包赵", "OUTSOURCER", null,
                item("衣鱼", 6), item("烟草甲", 0));
        JsonNode task1 = openTaskOf(hallPoint, "衣鱼");
        long task1Id = task1.get("id").asLong();
        assertEquals("HALL", task1.get("locationType").asText());
        assertEquals(hallId, task1.get("hallId").asLong(), "任务关联责任展厅");
        assertEquals("PH1", task1.get("areaNoSnapshot").asText());
        assertEquals("王保管", task1.get("custodian").asText());
        assertEquals("PEST-R1", task1.get("ruleVersionNo").asText());
        assertEquals(5, task1.get("alarmThreshold").asInt());
        assertEquals("OPEN", task1.get("status").asText());

        // 外包人员不能登记处置、不能关闭
        expectFail(postJson("/api/pest-tasks/" + task1Id + "/handle",
                "{\"handler\":\"外包赵\",\"handlerRole\":\"OUTSOURCER\",\"handleRemark\":\"喷药\"}"), "外包");
        expectFail(postJson("/api/pest-tasks/" + task1Id + "/close",
                "{\"closedBy\":\"外包赵\",\"closedByRole\":\"OUTSOURCER\"}"), "外包");
        // 外包人员可以上传任务现场照片
        uploadTaskPhoto(task1Id, "t1.png", "外包赵", "OUTSOURCER", "虫迹现场");

        // ========== 五、连续超阈并入同一任务 ==========
        submit(hallPoint, "REQ-3", "外包赵", "OUTSOURCER", null,
                item("衣鱼", 7), item("烟草甲", 0));
        JsonNode merged = openTaskOf(hallPoint, "衣鱼");
        assertEquals(task1Id, merged.get("id").asLong(), "连续超阈必须并入同一处置任务");
        assertEquals(2, merged.get("checkCount").asInt());
        assertEquals(7, merged.get("peakCount").asInt());
        assertEquals(6, merged.get("firstCount").asInt());

        // 馆内保管员处置并复核关闭
        expectOk(postJson("/api/pest-tasks/" + task1Id + "/handle",
                "{\"handler\":\"王保管\",\"handlerRole\":\"CURATOR\",\"handleRemark\":\"清理并更换防虫药\"}"));
        // 已处置重复登记拒绝
        expectFail(postJson("/api/pest-tasks/" + task1Id + "/handle",
                "{\"handler\":\"王保管\",\"handlerRole\":\"CURATOR\",\"handleRemark\":\"再登记\"}"), "处置中");
        expectOk(postJson("/api/pest-tasks/" + task1Id + "/close",
                "{\"closedBy\":\"馆方主管\",\"closedByRole\":\"CURATOR\",\"closeRemark\":\"灭害效果确认\"}"));
        assertEquals("CLOSED", dataOf(getJson("/api/pest-tasks/" + task1Id)).get("task").get("status").asText());
        // 关闭后封存：不能补照片
        expectFail(uploadTaskPhotoMvc(task1Id, "late.png", "王保管", "CURATOR", "补传"), "已关闭");

        // 关闭后再次超阈另开新任务
        submit(hallPoint, "REQ-4", "钱检查", "CURATOR", null,
                item("衣鱼", 8), item("烟草甲", 0));
        List<JsonNode> hallTasks = tasksOf(hallPoint, null);
        long task2Id = -1;
        for (JsonNode t : hallTasks) {
            if (!"CLOSED".equals(t.get("status").asText())) {
                task2Id = t.get("id").asLong();
            }
        }
        assertNotEquals(-1L, task2Id, "关闭后再次超阈应另开新任务");
        assertNotEquals(task1Id, task2Id);

        // ========== 六、更换诱捕片：旧片留 REPLACED 不删，旧监测记录保留 ==========
        expectOk(postJson("/api/pest-points/" + hallPoint + "/strips",
                "{\"newStripNo\":\"TS-1\",\"operator\":\"李库管\"}"));
        // 更换必须填原因
        expectFail(postJson("/api/pest-points/" + hallPoint + "/strips",
                "{\"newStripNo\":\"TS-2\",\"operator\":\"李库管\"}"), "更换原因");
        expectOk(postJson("/api/pest-points/" + hallPoint + "/strips",
                "{\"newStripNo\":\"TS-2\",\"operator\":\"李库管\",\"replaceReason\":\"粘板积尘失效\"}"));
        JsonNode withStrips = dataOf(getJson("/api/pest-points/" + hallPoint));
        assertEquals(2, withStrips.get("strips").size(), "更换不删除旧片，两片记录都在");
        String stripStatuses = withStrips.get("strips").toString();
        assertTrue(stripStatuses.contains("REPLACED") && stripStatuses.contains("IN_USE"),
                "旧片 REPLACED、新片 IN_USE");
        // 更换后新检查单固化新片号
        long checkAfterStrip = submit(hallPoint, "REQ-5", "钱检查", "CURATOR", null,
                item("衣鱼", 1), item("烟草甲", 0));
        assertEquals("TS-2", dataOf(getJson("/api/pest-checks/" + checkAfterStrip))
                .get("check").get("stripNo").asText());
        // 旧检查单仍可查、不被删除（REQ-1~REQ-5 共 5 单）
        assertEquals(5, dataOf(getJson("/api/pest-checks?pointId=" + hallPoint)).size(),
                "换片不删除任何旧监测记录");

        // ========== 七、点位迁移：前后坐标都保留 ==========
        JsonNode moved = dataOf(postJson("/api/pest-points/" + hallPoint + "/move", "{"
                + "\"newPointNo\":\"TP-H1-B\",\"toCoordX\":30.0,\"toCoordY\":40.0,"
                + "\"coordDesc\":\"西墙展柜底\",\"reason\":\"展线调整\",\"movedBy\":\"王保管\"}"));
        long newHallPoint = moved.get("id").asLong();
        assertNotEquals(hallPoint, newHallPoint);
        JsonNode oldDetail = dataOf(getJson("/api/pest-points/" + hallPoint));
        assertEquals("MOVED", oldDetail.get("point").get("status").asText());
        assertEquals(newHallPoint, oldDetail.get("point").get("movedToId").asLong());
        JsonNode moveRec = oldDetail.get("moves").get(0);
        assertEquals(10.123, moveRec.get("fromCoordX").asDouble(), 0.001, "保留迁移前坐标");
        assertEquals(20.5, moveRec.get("fromCoordY").asDouble(), 0.001);
        assertEquals(30.0, moveRec.get("toCoordX").asDouble(), 0.001, "保留迁移后坐标");
        assertEquals(40.0, moveRec.get("toCoordY").asDouble(), 0.001);
        // 旧址不能再提交数据，新址可以
        expectFail(postJson("/api/pest-points/" + hallPoint + "/checks",
                checkBody("REQ-OLD", "钱检查", "CURATOR", null, item("衣鱼", 0))), "迁移");
        long checkAtNew = submit(newHallPoint, "REQ-6", "钱检查", "CURATOR", null,
                item("衣鱼", 0), item("烟草甲", 0));
        assertTrue(checkAtNew > 0);

        // ========== 八、监测结果按月份冻结 ==========
        String lastMonth = YearMonth.now().minusMonths(1).toString();
        // 上月在库房点位有一单（正常）
        long histCheck = submit(storePoint, "REQ-H1", "外包赵", "OUTSOURCER",
                lastMonth + "-15T10:00:00", item("烟草甲", 0));
        assertEquals(lastMonth, dataOf(getJson("/api/pest-checks/" + histCheck))
                .get("check").get("periodMonth").asText());
        // 冻结上月
        assertEquals(true, dataOf(postEmpty("/api/pest-freezes/month?periodMonth=" + lastMonth
                + "&frozenBy=保管员")).asBoolean());
        // 幂等：重复冻结返回 false
        assertEquals(false, dataOf(postEmpty("/api/pest-freezes/month?periodMonth=" + lastMonth
                + "&frozenBy=保管员")).asBoolean());
        // 冻结后补录上月被拒
        expectFail(postJson("/api/pest-points/" + storePoint + "/checks",
                checkBody("REQ-H2", "外包赵", "OUTSOURCER", lastMonth + "-20T10:00:00",
                        item("烟草甲", 0))), "冻结");
        // 冻结月补传照片被拒
        expectFail(uploadCheckPhotoMvc(histCheck, "h.png", "外包赵", "OUTSOURCER", "补传"), "冻结");
        // 当月不能冻结
        String thisMonth = YearMonth.now().toString();
        expectFail(postEmpty("/api/pest-freezes/month?periodMonth=" + thisMonth), "当月");
        // 冻结后当月新数据仍可正常提交
        submit(storePoint, "REQ-N1", "李库管", "WAREHOUSE_KEEPER", null, item("烟草甲", 0));

        // ========== 九、规则新版本：不改写历史旧结论 ==========
        // v2：衣鱼处置阈值放宽到 8（warn 仍 2）；烟草甲不变
        publishRule("PEST-R2", "v2", 2, 8, 1, 3);
        JsonNode versions = dataOf(getJson("/api/pest-rules"));
        assertEquals("PEST-R2", versions.get(0).get("version").get("versionNo").asText());
        assertEquals("SUPERSEDED", versions.get(1).get("version").get("status").asText(),
                "旧规则版本置 SUPERSEDED 只读");
        // 历史超阈单仍是 v1 口径：alarmThreshold=5、ALARM
        JsonNode oldAlarm = dataOf(getJson("/api/pest-checks/" + check2));
        assertEquals("PEST-R1", oldAlarm.get("ruleVersionNo").asText(), "历史记录标明旧规则版本");
        JsonNode oldLine = findLine(oldAlarm, "衣鱼");
        assertEquals(5, oldLine.get("alarmThreshold").asInt(), "规则新版本不改写旧结论阈值");
        assertEquals("ALARM", oldLine.get("level").asText(), "规则新版本不改写旧结论判定");
        // 新检查按 v2：衣鱼 5 只（2<=5<8）为 WARN，不再达到处置阈值、不开任务
        long warnCheck = submit(newHallPoint, "REQ-7", "钱检查", "CURATOR", null,
                item("衣鱼", 5), item("烟草甲", 0));
        JsonNode warnReport = dataOf(getJson("/api/pest-checks/" + warnCheck));
        assertEquals(0, warnReport.get("check").get("abnormal").asInt());
        assertEquals("WARN", findLine(warnReport, "衣鱼").get("level").asText());
        assertEquals(8, findLine(warnReport, "衣鱼").get("alarmThreshold").asInt());
        assertEquals("PEST-R2", warnReport.get("ruleVersionNo").asText());
        assertEquals(0, tasksOf(newHallPoint, null).size(), "WARN 不生成处置任务");

        // ========== 十、库房超阈任务关联库区；关闭前必须先处置且有照片 ==========
        submit(storePoint, "REQ-N2", "外包赵", "OUTSOURCER", null, item("烟草甲", 9));
        JsonNode storeTask = openTaskOf(storePoint, "烟草甲");
        long storeTaskId = storeTask.get("id").asLong();
        assertEquals("STORAGE", storeTask.get("locationType").asText());
        assertEquals(areaId, storeTask.get("storageAreaId").asLong(), "任务关联责任库区");
        assertEquals("PA1", storeTask.get("areaNoSnapshot").asText());
        assertEquals("李库管", storeTask.get("custodian").asText());
        // 未处置不能直接关闭
        expectFail(postJson("/api/pest-tasks/" + storeTaskId + "/close",
                "{\"closedBy\":\"李库管\",\"closedByRole\":\"WAREHOUSE_KEEPER\"}"), "处置");
        expectOk(postJson("/api/pest-tasks/" + storeTaskId + "/handle",
                "{\"handler\":\"李库管\",\"handlerRole\":\"WAREHOUSE_KEEPER\",\"handleRemark\":\"整库熏蒸\"}"));
        // 已处置但无照片不能关闭
        expectFail(postJson("/api/pest-tasks/" + storeTaskId + "/close",
                "{\"closedBy\":\"李库管\",\"closedByRole\":\"WAREHOUSE_KEEPER\"}"), "现场照片");
        uploadTaskPhoto(storeTaskId, "st.png", "李库管", "WAREHOUSE_KEEPER", "熏蒸记录");
        expectOk(postJson("/api/pest-tasks/" + storeTaskId + "/close",
                "{\"closedBy\":\"馆方主管\",\"closedByRole\":\"CURATOR\"}"));
        assertEquals("CLOSED",
                dataOf(getJson("/api/pest-tasks/" + storeTaskId)).get("task").get("status").asText());

        // ========== 十一、requestNo 幂等：重复提交不产生第二单 ==========
        JsonNode allBefore = dataOf(getJson("/api/pest-checks?pointId=" + storePoint));
        long retryId = submit(storePoint, "REQ-N2", "外包赵", "OUTSOURCER", null, item("烟草甲", 9));
        JsonNode allAfter = dataOf(getJson("/api/pest-checks?pointId=" + storePoint));
        assertEquals(allBefore.size(), allAfter.size(), "同一 requestNo 重复提交只形成一单");
        // REQ-N2 首单仍是超阈单（幂等返回原单，未被重复参数改写）
        assertEquals(1, dataOf(getJson("/api/pest-checks/" + retryId)).get("check").get("abnormal").asInt());

        // ========== 十二、虫害台账全动作留痕 ==========
        JsonNode ledger = dataOf(getJson("/api/pest-ledgers"));
        for (String action : new String[]{"PEST_RULE_PUBLISH", "POINT_LAY", "POINT_MOVE",
                "STRIP_INSTALL", "STRIP_REPLACE", "CHECK_OK", "CHECK_ALARM",
                "TASK_OPEN", "TASK_MERGE", "TASK_HANDLE", "TASK_CLOSE", "MONTH_FREEZE"}) {
            assertTrue(hasAction(ledger, action), "台账缺少动作: " + action);
        }
        for (JsonNode e : ledger) {
            String a = e.get("action").asText();
            if ("CHECK_ALARM".equals(a) || "TASK_OPEN".equals(a)) {
                assertFalse(e.get("ruleVersionNo").isNull(), "超阈检查/任务台账须标明规则版本");
            }
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void publishRule(String versionNo, String title,
                             int fishWarn, int fishAlarm, int beetleWarn, int beetleAlarm) throws Exception {
        String body = "{"
                + "\"versionNo\":\"" + versionNo + "\",\"title\":\"虫害规则" + title + "\","
                + "\"publishedBy\":\"藏品部\",\"items\":["
                + "{\"pestSpecies\":\"衣鱼\",\"warnThreshold\":" + fishWarn
                + ",\"alarmThreshold\":" + fishAlarm + "},"
                + "{\"pestSpecies\":\"烟草甲\",\"warnThreshold\":" + beetleWarn
                + ",\"alarmThreshold\":" + beetleAlarm + "}"
                + "]}";
        expectOk(postJson("/api/pest-rules/publish", body));
    }

    private long submit(long pointId, String requestNo, String inspector, String role,
                        String checkedAtIso, String... items) throws Exception {
        return dataOf(postJson("/api/pest-points/" + pointId + "/checks",
                checkBody(requestNo, inspector, role, checkedAtIso, items))).get("id").asLong();
    }

    private String checkBody(String requestNo, String inspector, String role,
                             String checkedAtIso, String... items) {
        return "{\"requestNo\":\"" + requestNo + "\",\"inspector\":\"" + inspector
                + "\",\"inspectorRole\":\"" + role + "\""
                + (checkedAtIso == null ? "" : ",\"checkedAt\":\"" + checkedAtIso + "\"")
                + ",\"items\":[" + String.join(",", items) + "]}";
    }

    private String item(String species, int count) {
        return "{\"pestSpecies\":\"" + species + "\",\"count\":" + count + "}";
    }

    private JsonNode openTaskOf(long pointId, String species) throws Exception {
        List<JsonNode> tasks = tasksOf(pointId, "OPEN");
        for (JsonNode t : tasks) {
            if (species.equals(t.get("pestSpecies").asText())) {
                return t;
            }
        }
        throw new AssertionError("未找到点位 " + pointId + " 虫种 " + species + " 的 OPEN 任务");
    }

    private List<JsonNode> tasksOf(long pointId, String status) throws Exception {
        String uri = "/api/pest-tasks?pointId=" + pointId + (status == null ? "" : "&status=" + status);
        JsonNode arr = dataOf(getJson(uri));
        List<JsonNode> list = new ArrayList<>();
        arr.forEach(list::add);
        return list;
    }

    private JsonNode findLine(JsonNode checkReport, String species) {
        for (JsonNode line : checkReport.get("lines")) {
            if (species.equals(line.get("pestSpecies").asText())) {
                return line;
            }
        }
        throw new AssertionError("虫种明细未找到: " + species);
    }

    private void uploadCheckPhoto(long checkId, String fileName, String by, String role, String remark)
            throws Exception {
        expectOk(uploadCheckPhotoMvc(checkId, fileName, by, role, remark));
    }

    private MvcResult uploadCheckPhotoMvc(long checkId, String fileName, String by, String role, String remark)
            throws Exception {
        return mockMvc.perform(photo("/api/pest-checks/" + checkId + "/photos", fileName)
                .param("uploadedBy", by).param("role", role).param("remark", remark))
                .andExpect(status().isOk()).andReturn();
    }

    private void uploadTaskPhoto(long taskId, String fileName, String by, String role, String remark)
            throws Exception {
        expectOk(uploadTaskPhotoMvc(taskId, fileName, by, role, remark));
    }

    private MvcResult uploadTaskPhotoMvc(long taskId, String fileName, String by, String role, String remark)
            throws Exception {
        return mockMvc.perform(photo("/api/pest-tasks/" + taskId + "/photos", fileName)
                .param("uploadedBy", by).param("role", role).param("remark", remark))
                .andExpect(status().isOk()).andReturn();
    }

    private MockHttpServletRequestBuilder photo(String uri, String fileName) throws Exception {
        BufferedImage img = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return multipart(uri)
                .file(new MockMultipartFile("file", fileName, MediaType.IMAGE_PNG_VALUE, bos.toByteArray()));
    }

    private boolean hasAction(JsonNode ledger, String action) {
        for (JsonNode e : ledger) {
            if (action.equals(e.get("action").asText())) {
                return true;
            }
        }
        return false;
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult postEmpty(String uri) throws Exception {
        return mockMvc.perform(post(uri)).andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private void expectOk(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(root.get("success").asBoolean(),
                "期望成功，实际: " + root.get("message").asText());
    }

    private void expectFail(MvcResult result, String messageContains) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertFalse(root.get("success").asBoolean());
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际: " + root.get("message").asText());
    }
}
