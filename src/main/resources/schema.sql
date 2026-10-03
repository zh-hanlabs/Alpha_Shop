-- ShopAgent 表结构（H2 MODE=MySQL，W7 可平移 MySQL 8）
-- 时间列全部相对 CURRENT_TIMESTAMP，避免「物流显示明年」（踩坑清单 #7）

CREATE TABLE IF NOT EXISTS product (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    category    VARCHAR(50)   NOT NULL,
    price       DECIMAL(10,2) NOT NULL,
    stock       INT           NOT NULL DEFAULT 0,
    description VARCHAR(500),
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS orders (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no     VARCHAR(20)   NOT NULL,
    user_id      VARCHAR(20)   NOT NULL,
    status       VARCHAR(20)   NOT NULL,
    total_amount DECIMAL(10,2) NOT NULL,
    created_at   TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_order_no UNIQUE (order_no)
);

-- 下单时快照商品名/单价，退款金额不随商品改价漂移（W3 依赖）
CREATE TABLE IF NOT EXISTS order_item (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no     VARCHAR(20)   NOT NULL,
    product_id   BIGINT        NOT NULL,
    product_name VARCHAR(100)  NOT NULL,
    quantity     INT           NOT NULL,
    unit_price   DECIMAL(10,2) NOT NULL
);

CREATE TABLE IF NOT EXISTS logistics (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no    VARCHAR(20) NOT NULL,
    carrier     VARCHAR(50) NOT NULL,
    tracking_no VARCHAR(30) NOT NULL,
    status      VARCHAR(20) NOT NULL,
    tracks      TEXT        NOT NULL,
    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_logistics_order UNIQUE (order_no)
);

-- W4D3 交易审计：谁在何时以哪个幂等键做了什么、结果如何（重放场景两条记录同 idempotent_key）
CREATE TABLE IF NOT EXISTS trade_audit_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       VARCHAR(20)  NOT NULL,
    action        VARCHAR(20)  NOT NULL,
    order_no      VARCHAR(20),
    idempotent_key VARCHAR(64) NOT NULL,
    result_code   INT          NOT NULL,
    result_msg    VARCHAR(255),
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_audit_user_time ON trade_audit_log (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_idem_key ON trade_audit_log (idempotent_key);
