package com.macro.mall.portal.ai.returns;

import java.util.Map;
import java.util.Set;

/** 退货材料建议。只返回待确认字段，不提交申请。 */
public interface ReturnMaterialService {
    enum State { OK, NEEDS_INPUT, UNSUPPORTED, UNAVAILABLE, PARTIAL }

    Result suggest(Request input);

    record ItemContext(long orderId, long itemId, int maxQuantity, Set<Long> allowedReasonIds,
                       boolean proofRequired, Set<String> uploadedProofIds, Map<Long,String> allowedReasons, String productClue) {
        public ItemContext(long orderId,long itemId,int maxQuantity,Set<Long> allowedReasonIds,boolean proofRequired,Set<String> uploadedProofIds) {
            this(orderId,itemId,maxQuantity,allowedReasonIds,proofRequired,uploadedProofIds,Map.of(),null);
        }
        public ItemContext(long orderId,long itemId,int maxQuantity,Map<Long,String> allowedReasons,String productClue,boolean proofRequired,Set<String> uploadedProofIds) {
            this(orderId,itemId,maxQuantity,allowedReasons.keySet(),proofRequired,uploadedProofIds,Map.copyOf(allowedReasons),productClue);
        }
    }

    record Request(ItemContext context, String text, Long reasonId, Integer quantity) {}

    record Result(State state, Long reasonId, Integer quantity, String description,
                  Map<String, String> evidence, Set<String> missingFields) {}
}
