package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 여러 단지/위치에 걸친 상품을 (단지+위치)별로 분할한다. (옛 모델 잔재 정리)
 *  - 원본 상품: primary(가장 작은 storage_location_id) 위치로 확정(코드 유지).
 *  - 그 외 위치: 새 상품 생성({단지코드}-{위치코드}-{순번}) + 해당 SKU 재배치.
 *  - 재고/원장/이력은 sku_id 기준이라 영향 없음. SKU 코드도 유지(불변).
 *  - 순수 DML → Flyway 트랜잭션으로 all-or-nothing. 분할 후엔 대상이 없어 재실행해도 무해(멱등).
 */
public class V21__split_spread_products extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();

        List<String> spread = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "select s.product_id from skus s join stock stk on stk.sku_id = s.id " +
                 "group by s.product_id having count(distinct stk.storage_location_id) > 1")) {
            while (rs.next()) spread.add(rs.getString(1));
        }
        for (String pid : spread) splitOne(c, pid);
    }

    private void splitOne(Connection c, String pid) throws Exception {
        // 위치별 그룹(storage_location_id 순) — 첫 그룹이 primary
        Map<String, Group> groups = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "select stk.storage_location_id, stk.complex_id, stk.complex_name, stk.storage_location_code, " +
                "stk.zone_id, stk.zone_name, stk.sub_zone_id, stk.sub_zone_name, stk.location_label, s.id sku_id " +
                "from skus s join stock stk on stk.sku_id = s.id where s.product_id = ? " +
                "order by stk.storage_location_id")) {
            ps.setString(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String loc = rs.getString("storage_location_id");
                    Group g = groups.get(loc);
                    if (g == null) {
                        g = new Group();
                        g.locId = loc;
                        g.complexId = rs.getString("complex_id");
                        g.complexName = nz(rs.getString("complex_name"));
                        g.locCode = nz(rs.getString("storage_location_code"));
                        g.zoneId = rs.getString("zone_id");
                        g.zoneName = nz(rs.getString("zone_name"));
                        g.subZoneId = rs.getString("sub_zone_id");
                        g.subZoneName = nz(rs.getString("sub_zone_name"));
                        g.locationLabel = nz(rs.getString("location_label"));
                        groups.put(loc, g);
                    }
                    g.skuIds.add(rs.getString("sku_id"));
                }
            }
        }
        if (groups.size() <= 1) return; // 안전장치

        Iterator<Group> it = groups.values().iterator();
        Group primary = it.next();
        fillOriginal(c, pid, primary);

        while (it.hasNext()) {
            Group g = it.next();
            String complexCode = queryString(c, "select code from complexes where id = ?", g.complexId);
            long seq = nextLocSeq(c, g.locId);
            String newCode = nz(complexCode) + "-" + g.locCode + "-" + String.format("%04d", seq);
            String newId = UUID.randomUUID().toString();
            insertCopy(c, pid, newId, newCode, g);
            repointSkus(c, newId, g);
        }
    }

    private void fillOriginal(Connection c, String pid, Group g) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "update products set complex_id=?, complex_name=?, storage_location_id=?, storage_location_code=?, " +
                "zone_id=?, zone_name=?, sub_zone_id=?, sub_zone_name=?, location_label=? where id=?")) {
            ps.setString(1, g.complexId); ps.setString(2, g.complexName);
            ps.setString(3, g.locId); ps.setString(4, g.locCode);
            ps.setString(5, g.zoneId); ps.setString(6, g.zoneName);
            ps.setString(7, g.subZoneId); ps.setString(8, g.subZoneName);
            ps.setString(9, g.locationLabel); ps.setString(10, pid);
            ps.executeUpdate();
        }
    }

    private void insertCopy(Connection c, String pid, String newId, String newCode, Group g) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into products (id, code, name, maker, barcode, note, main_image_url, images, price, " +
                "complex_id, complex_name, storage_location_id, storage_location_code, zone_id, zone_name, sub_zone_id, sub_zone_name, location_label, " +
                "category_id, category_name, product_code_id, product_code_name, product_detail_id, product_detail_name, path_label, sku_seq, created_at) " +
                "select ?, ?, name, maker, barcode, note, main_image_url, images, price, " +
                "?, ?, ?, ?, ?, ?, ?, ?, ?, " +
                "category_id, category_name, product_code_id, product_code_name, product_detail_id, product_detail_name, path_label, ?, now(6) " +
                "from products where id = ?")) {
            ps.setString(1, newId); ps.setString(2, newCode);
            ps.setString(3, g.complexId); ps.setString(4, g.complexName);
            ps.setString(5, g.locId); ps.setString(6, g.locCode);
            ps.setString(7, g.zoneId); ps.setString(8, g.zoneName);
            ps.setString(9, g.subZoneId); ps.setString(10, g.subZoneName);
            ps.setString(11, g.locationLabel);
            ps.setInt(12, g.skuIds.size());
            ps.setString(13, pid);
            ps.executeUpdate();
        }
    }

    private void repointSkus(Connection c, String newId, Group g) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "update skus set product_id=?, complex_id=?, complex_name=? where id=?")) {
            for (String sid : g.skuIds) {
                ps.setString(1, newId); ps.setString(2, g.complexId); ps.setString(3, g.complexName); ps.setString(4, sid);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** products:{locId} 카운터 증가(없으면 생성) → 위치별 1부터 */
    private long nextLocSeq(Connection c, String locId) throws Exception {
        String name = "products:" + locId;
        Long cur = null;
        try (PreparedStatement ps = c.prepareStatement("select val from seq_counters where name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) cur = rs.getLong(1); }
        }
        long next = (cur == null ? 0 : cur) + 1;
        if (cur == null) {
            try (PreparedStatement ps = c.prepareStatement("insert into seq_counters(name,val) values(?,?)")) {
                ps.setString(1, name); ps.setLong(2, next); ps.executeUpdate();
            }
        } else {
            try (PreparedStatement ps = c.prepareStatement("update seq_counters set val=? where name=?")) {
                ps.setLong(1, next); ps.setString(2, name); ps.executeUpdate();
            }
        }
        return next;
    }

    private String queryString(Connection c, String sql, String arg) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : ""; }
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static class Group {
        String locId, complexId, complexName, locCode, zoneId, zoneName, subZoneId, subZoneName, locationLabel;
        final List<String> skuIds = new ArrayList<>();
    }
}
