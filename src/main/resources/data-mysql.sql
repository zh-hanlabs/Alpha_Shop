-- ShopAgent mock 数据 · MySQL 8 方言版（W7D1，差异清单见 shopagent-w7-tasks.md D0/T0.4）
-- 与 data.sql（H2）数据完全一致，仅方言改写：
--   ①FORMATDATETIME(x,'yyyy-MM-dd HH:mm:ss') → DATE_FORMAT(x,'%Y-%m-%d %H:%i:%s')；
--   ②|| 字符串拼接 → CONCAT()（MySQL 默认 sql_mode 无 PIPES_AS_CONCAT，|| 是逻辑或）；
--   ③TIMESTAMPADD(HOUR,-n,CURRENT_TIMESTAMP) 原样兼容。
-- 故事线对齐 W1W2 演示脚本：
--   10001 已发货(充电宝+数据线)  →「订单10001到哪了」+「那个充电宝多少钱」
--   10004 退款中                →「帮我把它退了」钩子（W3 交易工具）
--   10006 属于第二用户 u1002    → W1D8 越权测试素材（查别人订单应被拒）

INSERT INTO product (id, name, category, price, stock, description) VALUES
(1, '闪充移动电源 20000mAh', '充电配件', 129.00, 45, '大容量充电宝，支持22.5W双向快充，双USB-C接口，自带数显电量，差旅出行必备'),
(2, '编织快充数据线 1m', '充电配件', 19.90, 200, '60W编织线身，抗弯折，支持快充'),
(3, '户外徒步背包 40L', '户外装备', 299.00, 30, '3D透气背负系统，防泼水涂层，含防雨罩'),
(4, '防水压缩袋 12L', '户外装备', 35.00, 80, 'IPX7级防水，滚动封口，三色可选'),
(5, '钛合金保温杯 450ml', '居家生活', 89.00, 120, '12小时保温保冷，一体拉伸成型'),
(6, '主动降噪蓝牙耳机 Pro', '数码影音', 399.00, 15, '42dB深度降噪，单次续航8小时'),
(7, '便携式露营灯', '户外装备', 59.00, 60, '三档调光，IPX5防水，可悬挂'),
(8, '智能手表运动版', '智能穿戴', 599.00, 25, 'GPS+心率监测，14天超长续航');

INSERT INTO orders (order_no, user_id, status, total_amount, created_at, updated_at) VALUES
('10001', 'u1001', 'SHIPPED', 148.90,
 TIMESTAMPADD(HOUR, -50, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -6, CURRENT_TIMESTAMP)),
('10002', 'u1001', 'DELIVERED', 334.00,
 TIMESTAMPADD(HOUR, -290, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -48, CURRENT_TIMESTAMP)),
('10003', 'u1001', 'PENDING_PAYMENT', 89.00,
 TIMESTAMPADD(HOUR, -3, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -3, CURRENT_TIMESTAMP)),
('10004', 'u1001', 'REFUNDING', 399.00,
 TIMESTAMPADD(HOUR, -120, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -20, CURRENT_TIMESTAMP)),
('10005', 'u1001', 'DELIVERED', 59.00,
 TIMESTAMPADD(HOUR, -480, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -360, CURRENT_TIMESTAMP)),
('10006', 'u1002', 'DELIVERED', 599.00,
 TIMESTAMPADD(HOUR, -720, CURRENT_TIMESTAMP), TIMESTAMPADD(HOUR, -600, CURRENT_TIMESTAMP));

INSERT INTO order_item (order_no, product_id, product_name, quantity, unit_price) VALUES
('10001', 1, '闪充移动电源 20000mAh', 1, 129.00),
('10001', 2, '编织快充数据线 1m', 1, 19.90),
('10002', 3, '户外徒步背包 40L', 1, 299.00),
('10002', 4, '防水压缩袋 12L', 1, 35.00),
('10003', 5, '钛合金保温杯 450ml', 1, 89.00),
('10004', 6, '主动降噪蓝牙耳机 Pro', 1, 399.00),
('10005', 7, '便携式露营灯', 1, 59.00),
('10006', 8, '智能手表运动版', 1, 599.00);

INSERT INTO logistics (order_no, carrier, tracking_no, status, tracks, updated_at) VALUES
('10001', '顺丰速运', 'SF1388880001001', 'IN_TRANSIT',
 CONCAT('[{"time":"', DATE_FORMAT(TIMESTAMPADD(HOUR, -30, CURRENT_TIMESTAMP), '%Y-%m-%d %H:%i:%s'), '","desc":"快件已揽收","location":"浙江省杭州市滨江区"},',
        '{"time":"', DATE_FORMAT(TIMESTAMPADD(HOUR, -18, CURRENT_TIMESTAMP), '%Y-%m-%d %H:%i:%s'), '","desc":"快件已到达杭州转运中心","location":"浙江省杭州市"},',
        '{"time":"', DATE_FORMAT(TIMESTAMPADD(HOUR, -6, CURRENT_TIMESTAMP), '%Y-%m-%d %H:%i:%s'), '","desc":"快件已从上海转运中心发出，下一站浦东新区","location":"上海市"}]'),
 TIMESTAMPADD(HOUR, -6, CURRENT_TIMESTAMP)),
('10002', '圆通速运', 'YT5380000000002', 'DELIVERED',
 CONCAT('[{"time":"', DATE_FORMAT(TIMESTAMPADD(HOUR, -288, CURRENT_TIMESTAMP), '%Y-%m-%d %H:%i:%s'), '","desc":"快件已揽收","location":"浙江省杭州市萧山区"},',
        '{"time":"', DATE_FORMAT(TIMESTAMPADD(HOUR, -60, CURRENT_TIMESTAMP), '%Y-%m-%d %H:%i:%s'), '","desc":"快件已签收，感谢使用","location":"上海市浦东新区"}]'),
 TIMESTAMPADD(HOUR, -60, CURRENT_TIMESTAMP)),
('10004', '京东物流', 'JD000100040004', 'DELIVERED',
 CONCAT('[{"time":"', DATE_FORMAT(TIMESTAMPADD(HOUR, -110, CURRENT_TIMESTAMP), '%Y-%m-%d %H:%i:%s'), '","desc":"快件已揽收","location":"广东省深圳市南山区"},',
        '{"time":"', DATE_FORMAT(TIMESTAMPADD(HOUR, -96, CURRENT_TIMESTAMP), '%Y-%m-%d %H:%i:%s'), '","desc":"快件已签收，感谢使用","location":"上海市浦东新区"}]'),
 TIMESTAMPADD(HOUR, -96, CURRENT_TIMESTAMP)),
('10005', '中通快递', 'ZT000100050005', 'DELIVERED',
 CONCAT('[{"time":"', DATE_FORMAT(TIMESTAMPADD(HOUR, -476, CURRENT_TIMESTAMP), '%Y-%m-%d %H:%i:%s'), '","desc":"快件已揽收","location":"浙江省杭州市余杭区"},',
        '{"time":"', DATE_FORMAT(TIMESTAMPADD(HOUR, -368, CURRENT_TIMESTAMP), '%Y-%m-%d %H:%i:%s'), '","desc":"快件已签收，感谢使用","location":"上海市闵行区"}]'),
 TIMESTAMPADD(HOUR, -368, CURRENT_TIMESTAMP)),
('10006', '京东物流', 'JD000100060006', 'DELIVERED',
 CONCAT('[{"time":"', DATE_FORMAT(TIMESTAMPADD(HOUR, -716, CURRENT_TIMESTAMP), '%Y-%m-%d %H:%i:%s'), '","desc":"快件已揽收","location":"北京市朝阳区"},',
        '{"time":"', DATE_FORMAT(TIMESTAMPADD(HOUR, -600, CURRENT_TIMESTAMP), '%Y-%m-%d %H:%i:%s'), '","desc":"快件已签收，感谢使用","location":"北京市海淀区"}]'),
 TIMESTAMPADD(HOUR, -600, CURRENT_TIMESTAMP));
