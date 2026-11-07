package com.macro.mall.sdc.e7;
import java.nio.file.Files;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CandidateE703EvaluationTest {
    @org.junit.jupiter.api.Test
    void ordinaryFormDoesNotNeedLlmQuotesAndBothInterfacesAreUsed() throws Exception {
        com.macro.mall.portal.ai.returns.ReturnMaterialService form = request -> {
            org.junit.jupiter.api.Assertions.assertNull(request.text());
            return new com.macro.mall.portal.ai.returns.ReturnMaterialService.Result(
                com.macro.mall.portal.ai.returns.ReturnMaterialService.State.NEEDS_INPUT,17L,1,null,Map.of(),Set.of("proof"));
        };
        com.macro.mall.portal.ai.returns.ReturnMaterialService model = request ->
            new com.macro.mall.portal.ai.returns.ReturnMaterialService.Result(
                com.macro.mall.portal.ai.returns.ReturnMaterialService.State.NEEDS_INPUT,17L,1,null,Map.of("reasonId","开胶","quantity","1双"),Set.of("proof"));
        var cases=new ObjectMapper().readTree("""
          [{"id":"n","kind":"normal","input":{"llm":{"text":"开胶退1双"},"form":{"reasonId":17,"quantity":1},
          "serviceContext":{"orderId":1,"itemId":2,"maxQuantity":2,"allowedReasons":{"17":"质量"},"productClue":"运动鞋","proofRequired":true}},
          "expected":{"state":"NEEDS_INPUT","reasonId":17,"quantity":1,"missingFields":["proof"],"evidence":{"reasonId":"开胶","quantity":"1双"}}}]
          """);
        var run=CandidateE703Evaluation.compareCases(cases,form,model,new java.util.ArrayList<>(),new int[]{0},new String[]{""});
        org.junit.jupiter.api.Assertions.assertEquals(2,run.rows().size());
        org.junit.jupiter.api.Assertions.assertTrue(run.rows().stream().allMatch(row -> Boolean.TRUE.equals(row.get("passed"))));
    }
    @org.junit.jupiter.api.Test
    void publicOfflineRegressionRecordsRealOrdinaryResults() throws Exception {
        var json=new ObjectMapper();
        var cases=json.readTree(Files.readString(EvaluationFiles.task("E7-03").resolve("acceptance.json"))).path("cases");
        com.macro.mall.portal.ai.returns.ReturnMaterialService unavailable = request -> new com.macro.mall.portal.ai.returns.ReturnMaterialService.Result(com.macro.mall.portal.ai.returns.ReturnMaterialService.State.UNAVAILABLE,null,null,null,Map.of(),Set.of());
        var run=CandidateE703Evaluation.compareCases(cases,CandidateE703Evaluation.studentExisting(),unavailable,
            new java.util.ArrayList<>(),new int[]{0},new String[]{""});
        org.junit.jupiter.api.Assertions.assertEquals(64,run.rows().size());
        Path report=Path.of("target","e7-03-public-offline.json");
        Files.createDirectories(report.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),Map.of("evidenceType","public offline ordinary regression; model unavailable, not quality evidence",
            "modelCalls",0,"decision","未完成","humanReview","未进行独立人工核对","results",run.rows()));
    }

}
