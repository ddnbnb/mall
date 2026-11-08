# E3-02 AI 使用记录

本记录说明 E3-02（购物车合并修改时间）在一次 AI 结对编程中，AI 做了什么、人工需核对与补做什么。工具：Claude（Claude Code CLI）；JDK 17；Maven 3.9.9；JaCoCo 0.8.13。对照用 `SDC/compare.sh E3-20244350127`。

## 分工

- AI（Claude）执行：定位缺陷、写规格、写失败测试、做最小修复、运行 `compare.sh` 拿到改前/改后对照、生成覆盖率并抽目标方法。
- 人工（学生）待做：核对 AI 的 diff 是否确为最小修复、复述规格与红灯含义、本人录屏讲解（不能用 AI 录屏代替）、独立确认结果。

## 规格（先行的可判定规格）

- 用户故事：已登录用户往购物车添加一个已存在的商品条目（同会员、同商品、同 SKU、未删除）时，系统应把数量累加到已有条目，并把**已有条目**的修改时间刷新为当前时间。
- 验收准则：`add` 合并分支持久化的入参须满足 quantity=旧+新、modifyDate 落在调用时间窗内；新商品走插入分支、createDate 设为当前时间；memberId/memberNickname/deleteStatus 仍由当前会员与默认值覆盖。
- 不得修改：数量累加公式、插入分支、公开接口 `add` 签名、表结构、生成代码。

## 红灯（先失败，后修复）

- 第一个失败测试：`CartMergeModifyDateTest.mergeRefreshesSavedModifyDate`。它在 `updateByPrimaryKey` 接收参数时复制快照，用 `assertSame(existing, argument)` 锁住「保存的是已有条目」，并断言快照的 `modifyDate` 落在调用前后时间窗内。
- 红灯来自业务断言：修复前失败为 `saved modifyDate=1000 must not be before call start ==> expected: <false> but was: <true>`，即 modifyDate 停留在旧值 1000。Errors=0、Skipped=0，不是编译或环境错误。
- 测试提交 `99bc2136`；修复提交 `b2189bfd`。

## AI 建议的取舍（由 diff 印证）

- 采纳：只把 `OmsCartItemServiceImpl.add` 第 50 行 `cartItem.setModifyDate(new Date())` 改为 `existCartItem.setModifyDate(new Date())`，把修改时间写到真正被 `updateByPrimaryKey` 持久化的对象上。
- 驳回：不改 `existCartItem.setQuantity(...)` 的累加公式（第 51 行）；不把入参上的 `setModifyDate` 留着冒充已保存；不另写一套合并算法；不改公开接口与生成代码。diff 只改第 50 行一行。
- 测试侧取舍：断言信息用英文 ASCII，避免 Windows 控制台 GBK 编码在 `compare.txt` 中把中文断言信息写成乱码；红灯断言带上实际 `modifyDate` 值，便于核对失败原因。

## 人工核对项（学生须逐项确认）

- `evidence/compare-red.txt`：只提交测试、未改产品代码时，改前 fail、改后也 fail，结论「不符合」——证明红灯先于修复出现。
- `evidence/compare.txt`：修复后改前 fail（预期 fail）、改后 pass，结论「符合」。失败信息为 `saved modifyDate=1000 ...`，Errors=0、Skipped=0。
- `evidence/coverage.txt`：统计对象是 `OmsCartItemServiceImpl.add`，行 12/12、分支 2/2。唯一分支是第 46 行 `existCartItem == null`，两个测试分别覆盖合并与插入两条路径。
- 覆盖率的 `destFile/dataFile` 覆盖到 ASCII 临时目录，因为仓库路径含中文、默认 target 路径经 fork JVM 的 GBK 传参损坏导致 `jacoco.exec` 写不出；这是环境细节，不是业务结论。

## 限制

- 本次由 AI 全程执行，未保存独立的人工逐行审查记录；学生须在录屏中复述规格、红灯与修复理由，不能照读本文件。
- 覆盖率只证明 `add` 的两条路径被走到，不证明端到端（真实数据库、控制器、事务）行为；「保存的是已有项」靠断言而非覆盖率保证。
