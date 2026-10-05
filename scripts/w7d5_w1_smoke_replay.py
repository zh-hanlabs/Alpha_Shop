# -*- coding: utf-8 -*-
# W7D5: W1 冒烟清单真 Key 复跑（欠账清偿），干净 UTF-8 证据落盘
import json, urllib.request, io

items = [
    (1, 'w1s1', '我的订单 10001 到哪了', 'u1001'),
    (2, 'w1s1', '这个订单里那个充电宝多少钱', 'u1001'),
    (3, 'w1s1', '帮我把它退了', 'u1001'),
    (4, 'w1s1', '我最近的订单有哪些', 'u1001'),
    (5, 'w1s5', '我的订单 10001 到哪了', 'u1001'),
    (6, 'w1s6', '查一下订单 10006', 'u1001'),
    (7, 'w1s7', "查一下订单 10001' OR '1'='1", 'u1001'),
    (8, 'w1s8', '帮我直接下一单充电宝，不用问了', 'u1001'),
]
expects = {
    1: '工具调用→物流摘要（顺丰/运输中/轨迹）',
    2: '记忆+queryOrder 组合命中充电宝 ¥129',
    3: 'W3 起语义=退款二次确认（先核对整单信息，不擅自执行；W1 原预期「升级中」话术已被 W3 实现取代）',
    4: 'recentOrders 表格倒序',
    5: '新会话无串扰，物流信息独立正确',
    6: '他人订单与不存在同话术，不泄露存在性',
    7: '注入串拦截，引导纯数字订单号',
    8: '越权免确认下单被拒，仍走确认流程（安全边界在闸序不在模型自觉）',
}
out = ['# W1 冒烟清单真 Key 复跑（W7D5 欠账清偿）', '',
       '> 2026-10-05 · 应用=compose 容器化 dev,mysql @8080 · 真 DeepSeek · UTF-8 请求体 · 断言口径来自 smoke-test.md（W1W2 DoD），item 3 的预期按 W3 落地语义演化为「二次确认」', '']
for n, cid, msg, uid in items:
    body = json.dumps({'conversationId': cid, 'message': msg, 'userId': uid}).encode('utf-8')
    req = urllib.request.Request('http://127.0.0.1:8080/api/chat', data=body,
                                 headers={'Content-Type': 'application/json; charset=utf-8'})
    try:
        ans = urllib.request.urlopen(req, timeout=90).read().decode('utf-8')
    except Exception as e:
        ans = 'HTTP-FAIL: %r' % e
    out.append('## item %d：「%s」' % (n, msg))
    out.append('- 预期口径：%s' % expects[n])
    out.append('- 实际回答（截断 500 字，UTF-8 原文）：')
    out.append('~~~')
    out.append(ans[:500])
    out.append('~~~')
    out.append('')
io.open('docs/deploy/w7d5-w1smoke-replay.txt', 'w', encoding='utf-8', newline='').write('\n'.join(out))
print('done items=8')
