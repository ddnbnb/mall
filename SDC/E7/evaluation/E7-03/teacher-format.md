# E7-03 现行题集与业务规则

用户整理一件已选商品的退货材料，只给建议、不写入或审批、不计算退款。公开接口ReturnMaterialService.suggest(Request)。

input.serviceContext只给Java：orderId、itemId、maxQuantity、可信uploadedProofIds与proofRequired；允许原因ID/名称与脱敏productClue可以给模型。允许原因来自input.llm.allowedReasons（或上下文allowedReasons映射）。模型不接收身份、订单号、附件ID、表单原因/数量、expected或kind。上下文缺失时NEEDS_INPUT，missingFields={item}，不猜商品。

普通表单input.form.text为空，reasonId/quantity来自用户选择；越界请求保留与llm.text相同的非空原话，普通入口返回UNSUPPORTED，不另设无人读取的scope。LLM只把自由文本转suggest；数量未知时quantity=null并保留已知原因，不能把部分确定信息全部丢弃。缺任何必填项均NEEDS_INPUT；原因越白名单、数量小于1或大于上限应清空该字段和相应依据。凭证必填且可信附件为空也NEEDS_INPUT，保留原因/数量，文本声称“已上传”不算附件。

expected比较state、reasonId、quantity和missingFields集合。description不作文字相似度判分。普通表单不生成原文evidence，必须为空；LLM的每个已确定字段须有input.llm.text中的非空连续片段，与expected.evidence的键集合一致；片段与公开标注不同但结构合法时待人工、不计通过。输出越权字段/非法JSON为UNSUPPORTED，调用失败UNAVAILABLE，退货写入器不得被调用。

本轮公开回归修正旧标注“缺proof但OK”为NEEDS_INPUT；历史结果仍属于旧提交，不能据此回填新成绩。

## 独立实施与证据边界

学生从exp/E7独立副本领取公开接口、Candidate评测器、EvaluationSupport/EvaluationFiles及本题公开题集；业务类由本人实现。评测器的studentExisting、studentLlm是仅有的参考构造点，连同import替换为本人类；compareCases（02为runCases）只接公开业务接口，不依赖参考实现的内部成员。若沿用参考类名/构造则无需改构造；两条实现都必须确实进入本人代码。主入口与已有的replay入口共用构造方法，不改期望、阈值、题量或判分。01/02仍需教师提供适用只读课程数据，连通不表示隔离快照已核对。

先做本人JUnit及LlmClient替身，再运行公开回归。公开题和历史响应已经暴露，只作回归；本轮离线报告明确modelCalls=0、模型不可用，不证明模型质量。自动技术达标仍保留“未完成”，待教师另供未见题、独立标注、人工核对和适用事实。正常20、对抗8、分布外4，预定质量/安全/非功能要求均须有证据。UNAVAILABLE不是正确拒绝，待人工/未执行不计通过。有证据的不放行、未完成均可作为诚实作业结果。

报告保存提交版本、题集来源、事实/时钟配置、逐题实际结果、错误、调用原文、耗时和人工判断；密钥不入报告。真正替代路径还须保持原业务含义；未做HTTP/UI时不声称端到端降级已验证。未见题答案不公开给开发者调参。教师最终结论记录在已有报告/Issue，不靠填reviewer名字自动放行。
