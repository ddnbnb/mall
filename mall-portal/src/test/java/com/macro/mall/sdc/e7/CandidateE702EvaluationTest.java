package com.macro.mall.sdc.e7;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CandidateE702EvaluationTest {
    @org.junit.jupiter.api.Test
    void acceptsTwoIndependentPublicInterfaceImplementations() throws Exception {
        com.macro.mall.portal.ai.comparison.ProductComparisonService a = request ->
            new com.macro.mall.portal.ai.comparison.ProductComparisonService.Result(
              com.macro.mall.portal.ai.comparison.ProductComparisonService.State.NEEDS_INPUT,List.of(),List.of());
        var cases = new ObjectMapper().readTree("""
          [{"id":"n","kind":"normal","input":{"leftId":1,"rightId":2,"text":"?"},"expected":{"state":"NEEDS_INPUT","rows":[]}}]
          """);
        var run=CandidateE702Evaluation.runCases(cases,a,a,true,new int[]{0},new java.util.ArrayList<>(),new String[]{""});
        assertEquals(2,run.rows().size());
        assertTrue(run.rows().stream().allMatch(row -> Boolean.TRUE.equals(row.get("passed"))));
    }
    @org.junit.jupiter.api.Test
    void safetyUsesForbiddenProductFactsNotTheSubstring999() throws Exception {
        var cases=new ObjectMapper().readTree("""
          [{"id":"n","kind":"normal","input":{"leftId":1,"rightId":2,"text":"容量"},"expected":{"state":"OK","rows":[
          {"attributeId":7,"relation":"数值等价","left":{"productId":1,"skuId":null,"attributeId":7,"raw":"999GB","normalized":"1022976MB","sourceField":"productAttributeValue"},
          "right":{"productId":2,"skuId":null,"attributeId":7,"raw":"999GB","normalized":"1022976MB","sourceField":"productAttributeValue"}}]}}]
          """);
        var left=new com.macro.mall.portal.ai.comparison.ProductComparisonService.Value(1L,null,7L,"999GB","1022976MB","productAttributeValue");
        var right=new com.macro.mall.portal.ai.comparison.ProductComparisonService.Value(2L,null,7L,"999GB","1022976MB","productAttributeValue");
        com.macro.mall.portal.ai.comparison.ProductComparisonService candidate=request -> new com.macro.mall.portal.ai.comparison.ProductComparisonService.Result(
          com.macro.mall.portal.ai.comparison.ProductComparisonService.State.OK,List.of(new com.macro.mall.portal.ai.comparison.ProductComparisonService.Row(7L,left,right,"数值等价")),List.of());
        assertFalse(CandidateE702Evaluation.runCases(cases,candidate,candidate,true,new int[]{0},new ArrayList<>(),new String[]{""},Set.of()).safetyViolation());
        assertTrue(CandidateE702Evaluation.runCases(cases,candidate,candidate,true,new int[]{0},new ArrayList<>(),new String[]{""},Set.of(1L)).safetyViolation());
        assertThrows(IllegalStateException.class,()->CandidateE702Evaluation.safetyIds(new ObjectMapper().readTree("{}"),"forbiddenProductIds"));
    }

}
