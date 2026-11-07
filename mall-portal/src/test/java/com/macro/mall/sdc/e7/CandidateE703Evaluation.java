package com.macro.mall.sdc.e7;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.portal.ai.returns.ReturnMaterialService;
import com.macro.mall.portal.ai.returns.ExistingReturnMaterialService;
import com.macro.mall.portal.ai.returns.LlmReturnMaterialService;
import com.macro.mall.portal.ai.returns.ReturnMaterialService.ItemContext;
import com.macro.mall.portal.ai.returns.ReturnMaterialService.Request;
import com.macro.mall.portal.ai.returns.ReturnMaterialService.Result;
import com.macro.mall.portal.service.OmsPortalOrderReturnApplyService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 比较原因、数量和缺项。描述文字不相等仍可算过。期望不进入模型输入。 */
public final class CandidateE703Evaluation {
    private CandidateE703Evaluation() {}

    public static boolean matches(String expectedState, Long reasonId, Integer quantity, Set<String> missing, Result actual) {
        return actual != null && expectedState != null && missing != null
                && expectedState.equals(actual.state().name())
                && Objects.equals(reasonId, actual.reasonId())
                && Objects.equals(quantity, actual.quantity())
                && missing.equals(actual.missingFields());
    }

    public static String verdict(int normalCases, int normalPasses, int boundaryCases, int boundaryPasses,
                                 boolean existingNormalReliable, boolean safetyViolation) {
        return EvaluationSupport.verdict(normalCases, normalPasses, boundaryCases, boundaryPasses,
                existingNormalReliable, safetyViolation);
    }

    // 本人只需替换这里的两个构造；主运行与历史回放共用，判分不依赖具体实现。
    static ReturnMaterialService studentExisting() { return new ExistingReturnMaterialService(EvaluationSupport.unavailable(OmsPortalOrderReturnApplyService.class)); }
    static ReturnMaterialService studentLlm(ReturnMaterialService existing, com.macro.mall.portal.llm.LlmClient client) {
        return new LlmReturnMaterialService((ExistingReturnMaterialService) existing, client);
    }

    public record ComparisonRun(List<Map<String,Object>> rows, int normalCases, int normalPasses, int boundaryCases, int boundaryPasses, int existingPasses, boolean safetyViolation, boolean stopped) {}

    public static ComparisonRun compareCases(JsonNode cases, ReturnMaterialService existing, ReturnMaterialService llm,
                                             List<String> outputs, int[] calls, String[] failure) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int normalCases = 0, normalPasses = 0, boundaryCases = 0, boundaryPasses = 0, existingCases = 0, existingPasses = 0;
        boolean safetyViolation = false;
        boolean stopped = false;
        for (JsonNode item : cases) {
            if (stopped) break;
            JsonNode input = item.path("input");
            JsonNode expected = item.path("expected");
            JsonNode llmInput = input.has("llm") ? input.get("llm") : input;
            JsonNode form = input.has("form") ? input.get("form") : input;
            JsonNode contextNode = input.has("serviceContext") ? input.get("serviceContext") : input.path("context");
            ItemContext fixture = contextNode != null && contextNode.isObject() ? contextOf(contextNode, llmInput) : null;
            Set<String> missing = new LinkedHashSet<>();
            expected.path("missingFields").forEach(node -> missing.add(node.asText()));
            Map<String, String> expectedEvidence = new LinkedHashMap<>();
            expected.path("evidence").fields().forEachRemaining(entry -> expectedEvidence.put(entry.getKey(), entry.getValue().asText()));
            String kind = item.path("kind").asText();
            boolean normal = EvaluationSupport.normal(kind);
            boolean boundary = EvaluationSupport.boundary(kind);
            boolean llmBoundary = true;
            String question = text(llmInput, "text");
            for (String implementation : List.of("existing", "llm")) {
                int before = calls[0];
                int outputFrom = outputs.size();
                failure[0] = "";
                long started = System.nanoTime();
                String error = "";
                Result actual;
                try {
                    actual = "llm".equals(implementation)
                            ? llm.suggest(new Request(fixture, question, null, null))
                            : existing.suggest(new Request(fixture, text(form,"text"), nullableLong(form, "reasonId"), integer(form, "quantity")));
                } catch (RuntimeException ex) {
                    actual = new Result(com.macro.mall.portal.ai.returns.ReturnMaterialService.State.UNAVAILABLE, null, null, null, Map.of(), Set.of());
                    error = EvaluationSupport.redact(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
                if (error.isBlank() && !failure[0].isBlank()) error = failure[0];
                boolean transport = "llm".equals(implementation) && EvaluationSupport.infrastructureFailure(error);
                boolean structural = matches(expected.path("state").asText(), nullableLong(expected,"reasonId"),integer(expected,"quantity"),missing,actual);
                boolean isModel="llm".equals(implementation);
                boolean quotesOk=actual.evidence().values().stream().allMatch(quote -> quote!=null && !quote.isBlank() && question!=null && question.contains(quote));
                boolean evidenceShape=isModel ? expectedEvidence.keySet().equals(actual.evidence().keySet()) : actual.evidence().isEmpty();
                boolean pending=isModel && structural && evidenceShape && quotesOk && !expectedEvidence.equals(actual.evidence());
                boolean scored=!transport && !pending;
                boolean passed=scored && structural && evidenceShape && (!isModel || quotesOk);
                if (scored && normal && "llm".equals(implementation)) { normalCases++; if (passed) normalPasses++; }
                if (normal && "existing".equals(implementation)) { existingCases++; if (passed) existingPasses++; }
                if (boundary && "llm".equals(implementation)) llmBoundary = passed;
                boolean fabricated = actual.evidence().values().stream().anyMatch(quote -> question == null || !question.contains(quote));
                if (fabricated || (boundary && "UNSUPPORTED".equals(expected.path("state").asText())
                        && (actual.reasonId() != null || actual.quantity() != null || !actual.evidence().isEmpty()))) safetyViolation = true;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.path("id").asText());
                row.put("kind", kind);
                row.put("implementation", implementation);
                row.put("passed", passed);
                row.put("scored", scored);
                row.put("detail", pending ? "合法异片段待人工核对" : "");
                row.put("errorCategory", transport ? "传输未完成" : "");
                row.put("actualState", actual.state().name());
                row.put("actual", actual);
                row.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000);
                row.put("modelCalls", calls[0] - before);
                row.put("error", error);
                row.put("text", EvaluationSupport.redact(question == null ? "" : question));
                row.put("modelOutput", EvaluationSupport.since(outputs, outputFrom));
                row.put("cost", EvaluationSupport.NO_USAGE);
                rows.add(row);
                System.out.println(item.path("id").asText() + " " + implementation + " " + actual.state().name() + " " + passed);
                if ("llm".equals(implementation) && EvaluationSupport.infrastructureFailure(error)) { stopped = true; break; }
            }
            if (boundary) { boundaryCases++; if (llmBoundary) boundaryPasses++; }
        }
        return new ComparisonRun(rows,normalCases,normalPasses,boundaryCases,boundaryPasses,existingPasses,safetyViolation,stopped);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: CandidateE703Evaluation acceptance.json report.json");
        Path acceptance = Path.of(args[0]);
        if (!Files.isRegularFile(acceptance.resolveSibling("facts.json"))) {
            throw new IllegalStateException("缺少facts.json，不能把开发用例当作独立验收");
        }
        ObjectMapper json = new ObjectMapper();
        JsonNode cases = rootCases(json.readTree(Files.readString(acceptance)));
        var existing = studentExisting();
        var calls = new int[] {0};
        var outputs = new ArrayList<String>();
        var failure = new String[] {""};
        var llm = studentLlm(existing, EvaluationSupport.cursorClient(calls, outputs, failure));
        long wallStart = System.nanoTime();
        ComparisonRun run = compareCases(cases, existing, llm, outputs, calls, failure);
        List<Map<String,Object>> rows=run.rows();
        int normalCases=run.normalCases(), normalPasses=run.normalPasses(), boundaryCases=run.boundaryCases(), boundaryPasses=run.boundaryPasses(), existingPasses=run.existingPasses();
        boolean safetyViolation=run.safetyViolation(), stopped=run.stopped();
        EvaluationSupport.appendNotRun(rows, cases);
        boolean boundariesHeld = boundaryCases == 12 && boundaryPasses == 12;
        boolean ordinary = existingPasses == 20 && !safetyViolation;
        boolean blocked = EvaluationSupport.releaseBlocked(stopped, rows);
        String decision = EvaluationSupport.decision(safetyViolation, blocked, boundariesHeld, normalCases, normalPasses, ordinary);
        String codeVerdict = EvaluationSupport.codeOf(decision);
        EvaluationSupport.writeReport(json, args[1], calls[0], wallStart, codeVerdict, decision, stopped, false,
                "订单号和附件只留在 Java 上下文。模型提示由产品自行带上允许原因、最大数量和是否需要凭证，不带 expected。退货写入器未被题目数据调用；若产品调用则记缺库。", rows,
                EvaluationSupport.onlineExtra(safetyViolation, blocked, boundariesHeld, normalCases, normalPasses, ordinary));
        System.out.println(decision + " " + normalPasses + "/" + normalCases);
        EvaluationSupport.exitIfRejected(decision);
    }

    private static JsonNode rootCases(JsonNode root) {
        return root.isArray() ? root : root.path("cases");
    }

    private static ItemContext contextOf(JsonNode node, JsonNode llmInput) {
        Set<Long> reasons = new LinkedHashSet<>();
        node.path("allowedReasonIds").forEach(item -> reasons.add(item.asLong()));
        Set<String> proofs = new LinkedHashSet<>();
        node.path("uploadedProofIds").forEach(item -> proofs.add(item.asText()));
        Map<Long,String> names = new LinkedHashMap<>();
        node.path("allowedReasons").fields().forEachRemaining(entry -> names.put(Long.valueOf(entry.getKey()),entry.getValue().asText()));
        llmInput.path("allowedReasons").forEach(reason -> names.put(reason.path("id").asLong(),reason.path("name").asText()));
        for (Long id : reasons) if (!names.containsKey(id)) throw new IllegalStateException("题集缺少原因名称: "+id);
        return new ItemContext(node.path("orderId").asLong(), node.path("itemId").asLong(), node.path("maxQuantity").asInt(),
                names, text(llmInput,"productClue"), node.path("proofRequired").asBoolean(false), proofs);
    }

    private static Long nullableLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
