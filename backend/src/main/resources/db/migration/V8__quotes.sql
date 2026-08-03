/* ========================= 견적서 (Quote / QuoteItem) ========================= */

CREATE TABLE quotes (
  id               VARCHAR(36)   NOT NULL PRIMARY KEY,
  vendor_name      VARCHAR(255)  NOT NULL DEFAULT '',
  vendor_biz_no    VARCHAR(50)   DEFAULT '',
  quote_date       DATE          NULL DEFAULT NULL,
  complex_id       VARCHAR(36)   NULL DEFAULT NULL,
  complex_name     VARCHAR(255)  DEFAULT '',
  site_label       VARCHAR(255)  DEFAULT '',
  total_amount     DECIMAL(14,2) NOT NULL DEFAULT 0,
  item_count       INT           NOT NULL DEFAULT 0,
  file_url         VARCHAR(1000) DEFAULT '',
  file_path        VARCHAR(1000) DEFAULT '',
  status           VARCHAR(20)   NOT NULL DEFAULT 'active',
  uploaded_by      VARCHAR(36)   NULL DEFAULT NULL,
  uploaded_by_name VARCHAR(255)  DEFAULT '',
  created_at       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at       DATETIME(6)   NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_quote_complex FOREIGN KEY (complex_id) REFERENCES complexes(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE quote_items (
  id               VARCHAR(36)   NOT NULL PRIMARY KEY,
  quote_id         VARCHAR(36)   NOT NULL,
  line_no          INT           NOT NULL DEFAULT 0,
  raw_name         VARCHAR(500)  NOT NULL DEFAULT '',
  spec             VARCHAR(255)  DEFAULT '',
  unit             VARCHAR(50)   DEFAULT '',
  qty              INT           NOT NULL DEFAULT 0,
  unit_price       DECIMAL(14,2) NOT NULL DEFAULT 0,
  amount           DECIMAL(14,2) NOT NULL DEFAULT 0,
  vat_amount       DECIMAL(14,2) NOT NULL DEFAULT 0,
  sku_id           VARCHAR(36)   NULL DEFAULT NULL,
  suggested_sku_id VARCHAR(36)   NULL DEFAULT NULL,
  match_status     VARCHAR(20)   NOT NULL DEFAULT 'unmatched',
  note             VARCHAR(500)  DEFAULT '',
  created_at       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at       DATETIME(6)   NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_qitem_quote FOREIGN KEY (quote_id) REFERENCES quotes(id) ON DELETE CASCADE,
  CONSTRAINT fk_qitem_sku FOREIGN KEY (sku_id) REFERENCES skus(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_quote_items_sku ON quote_items(sku_id);
CREATE INDEX idx_quotes_date ON quotes(quote_date);
