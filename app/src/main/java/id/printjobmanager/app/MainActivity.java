package id.printjobmanager.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.JavascriptInterface;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.ContentValues;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Print Job Manager 1.0
 * Native shell + SQLite database + JavaScript bridge.
 * Business data is stored locally on this Android device.
 */
public class MainActivity extends Activity {
    private WebView web;
    private AppDb db;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        db = new AppDb();
        // Force database creation/validation before loading the UI.
        db.getWritableDatabase();

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private long now() { return System.currentTimeMillis(); }

    private JSONObject rowToJson(Cursor c) throws Exception {
        JSONObject o = new JSONObject();
        for (int i = 0; i < c.getColumnCount(); i++) {
            String name = c.getColumnName(i);
            if (c.isNull(i)) o.put(name, JSONObject.NULL);
            else {
                int type = c.getType(i);
                if (type == Cursor.FIELD_TYPE_INTEGER) o.put(name, c.getLong(i));
                else if (type == Cursor.FIELD_TYPE_FLOAT) o.put(name, c.getDouble(i));
                else o.put(name, c.getString(i));
            }
        }
        return o;
    }

    private JSONArray queryJson(String sql, String[] args) {
        JSONArray result = new JSONArray();
        Cursor c = db.getReadableDatabase().rawQuery(sql, args);
        try {
            while (c.moveToNext()) result.put(rowToJson(c));
        } catch (Exception ignored) {
        } finally { c.close(); }
        return result;
    }

    private String safe(String value) { return value == null ? "" : value.trim(); }
    private int boundedLimit(int value) { return Math.max(1, Math.min(value, 100)); }

    public class Bridge {
        @JavascriptInterface public String databaseInfo() {
            JSONObject o = new JSONObject();
            try {
                o.put("app", "Print Job Manager");
                o.put("database", "print_job_manager.db");
                o.put("schema_version", AppDb.VERSION);
                o.put("storage", "local_sqlite");
                o.put("offline", true);
            } catch (Exception ignored) { }
            return o.toString();
        }

        // ---------------- Customers ----------------
        @JavascriptInterface public String listCustomers(String search, int limit, int offset) {
            String q = "%" + safe(search) + "%";
            return queryJson("SELECT id,name,phone,address,notes,created_at,updated_at " +
                    "FROM customers WHERE archived_at IS NULL AND name LIKE ? " +
                    "ORDER BY name COLLATE NOCASE LIMIT ? OFFSET ?",
                    new String[]{q, String.valueOf(boundedLimit(limit)), String.valueOf(Math.max(0, offset))}).toString();
        }

        @JavascriptInterface public long saveCustomer(long id, String name, String phone, String address, String notes) {
            if (safe(name).isEmpty()) return -1;
            ContentValues v = new ContentValues();
            v.put("name", safe(name)); v.put("phone", safe(phone));
            v.put("address", safe(address)); v.put("notes", safe(notes)); v.put("updated_at", now());
            SQLiteDatabase w = db.getWritableDatabase();
            if (id > 0) return w.update("customers", v, "id=? AND archived_at IS NULL", new String[]{String.valueOf(id)}) > 0 ? id : -1;
            v.put("created_at", now());
            return w.insert("customers", null, v);
        }

        // ---------------- Sales Orders ----------------
        @JavascriptInterface public String listSalesOrders(String search, int limit, int offset) {
            String q = "%" + safe(search) + "%";
            return queryJson("SELECT v.*, c.name AS customer_name FROM v_sales_order_summary v " +
                    "JOIN customers c ON c.id=v.customer_id " +
                    "JOIN sales_orders so ON so.id=v.sales_order_id " +
                    "WHERE so.archived_at IS NULL AND (v.order_number LIKE ? OR c.name LIKE ?) " +
                    "ORDER BY v.order_date DESC, v.sales_order_id DESC LIMIT ? OFFSET ?",
                    new String[]{q, q, String.valueOf(boundedLimit(limit)), String.valueOf(Math.max(0, offset))}).toString();
        }

        @JavascriptInterface public long createSalesOrder(long customerId, String orderDate, String notes) {
            if (customerId <= 0 || safe(orderDate).isEmpty()) return -1;
            SQLiteDatabase w = db.getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put("order_number", "SO-" + now());
            v.put("customer_id", customerId); v.put("order_date", safe(orderDate));
            v.put("notes", safe(notes)); v.put("created_at", now()); v.put("updated_at", now());
            return w.insert("sales_orders", null, v);
        }

        @JavascriptInterface public String getSalesOrder(long salesOrderId) {
            JSONArray rows = queryJson("SELECT v.*,c.name AS customer_name,c.phone AS customer_phone " +
                    "FROM v_sales_order_summary v JOIN customers c ON c.id=v.customer_id " +
                    "WHERE v.sales_order_id=?", new String[]{String.valueOf(salesOrderId)});
            return rows.length() > 0 ? rows.optJSONObject(0).toString() : "{}";
        }

        // ---------------- Projects ----------------
        @JavascriptInterface public String getProject(long projectId) {
            JSONArray rows = queryJson("SELECT p.*,so.order_number,c.name AS customer_name " +
                    "FROM projects p JOIN sales_orders so ON so.id=p.sales_order_id " +
                    "JOIN customers c ON c.id=so.customer_id WHERE p.id=?",
                    new String[]{String.valueOf(projectId)});
            return rows.length() > 0 ? rows.optJSONObject(0).toString() : "{}";
        }

        @JavascriptInterface public long addProjectStage(long projectId, String name, int sequence) {
            if (projectId <= 0 || safe(name).isEmpty() || sequence <= 0) return -1;
            ContentValues v = new ContentValues();
            v.put("project_id", projectId); v.put("name", safe(name));
            v.put("sequence", sequence); v.put("created_at", now()); v.put("updated_at", now());
            return db.getWritableDatabase().insert("project_stages", null, v);
        }

        @JavascriptInterface public boolean updateProjectStatus(long projectId, String status) {
            if (projectId <= 0 || !("draft".equals(status) || "active".equals(status) ||
                    "completed".equals(status) || "cancelled".equals(status))) return false;
            ContentValues v = new ContentValues(); v.put("status", status); v.put("updated_at", now());
            if ("completed".equals(status)) v.put("completed_at", now());
            return db.getWritableDatabase().update("projects", v, "id=?",
                    new String[]{String.valueOf(projectId)}) > 0;
        }

        @JavascriptInterface public String listProjects(long salesOrderId, String search, int limit, int offset) {
            String q = "%" + safe(search) + "%";
            String sql = "SELECT p.*, so.order_number, c.name AS customer_name " +
                    "FROM projects p JOIN sales_orders so ON so.id=p.sales_order_id " +
                    "JOIN customers c ON c.id=so.customer_id " +
                    "WHERE p.archived_at IS NULL AND (?=0 OR p.sales_order_id=?) " +
                    "AND (p.name LIKE ? OR p.project_number LIKE ? OR c.name LIKE ? OR so.order_number LIKE ?) " +
                    "ORDER BY p.created_at DESC LIMIT ? OFFSET ?";
            return queryJson(sql, new String[]{String.valueOf(salesOrderId), String.valueOf(salesOrderId), q, q, q, q,
                    String.valueOf(boundedLimit(limit)), String.valueOf(Math.max(0, offset))}).toString();
        }

        @JavascriptInterface public long createProject(long salesOrderId, String name, String description,
                double quantity, String unit, long saleAmount, long deadlineAt, long templateId) {
            if (salesOrderId <= 0 || safe(name).isEmpty() || quantity <= 0 || saleAmount < 0) return -1;
            SQLiteDatabase w = db.getWritableDatabase();
            long projectId = -1;
            w.beginTransaction();
            try {
                ContentValues v = new ContentValues();
                v.put("sales_order_id", salesOrderId);
                v.put("project_number", "PR-" + now());
                v.put("name", safe(name)); v.put("description", safe(description));
                v.put("quantity", quantity); v.put("unit", safe(unit).isEmpty() ? "unit" : safe(unit));
                v.put("sale_amount", saleAmount);
                if (deadlineAt > 0) v.put("deadline_at", deadlineAt);
                v.put("created_at", now()); v.put("updated_at", now());
                projectId = w.insertOrThrow("projects", null, v);

                if (templateId > 0) {
                    Cursor stages = w.rawQuery("SELECT id,name,sequence FROM workflow_template_stages WHERE template_id=? ORDER BY sequence",
                            new String[]{String.valueOf(templateId)});
                    try {
                        while (stages.moveToNext()) {
                            ContentValues sv = new ContentValues();
                            sv.put("project_id", projectId);
                            sv.put("template_stage_id", stages.getLong(0));
                            sv.put("name", stages.getString(1));
                            sv.put("sequence", stages.getInt(2));
                            sv.put("created_at", now()); sv.put("updated_at", now());
                            w.insertOrThrow("project_stages", null, sv);
                        }
                    } finally { stages.close(); }
                }
                w.setTransactionSuccessful();
            } catch (Exception e) {
                projectId = -1;
            } finally { w.endTransaction(); }
            return projectId;
        }

        // ---------------- Payments ----------------
        @JavascriptInterface public String listPayments(long salesOrderId) {
            return queryJson("SELECT id,sales_order_id,paid_at,amount,method,reference,notes,status,created_at,voided_at,void_reason " +
                    "FROM payments WHERE sales_order_id=? ORDER BY paid_at DESC,id DESC",
                    new String[]{String.valueOf(salesOrderId)}).toString();
        }

        @JavascriptInterface public long addPayment(long salesOrderId, long paidAt, long amount,
                String method, String reference, String notes) {
            if (salesOrderId <= 0 || paidAt <= 0 || amount <= 0) return -1;
            ContentValues v = new ContentValues();
            v.put("sales_order_id", salesOrderId); v.put("paid_at", paidAt); v.put("amount", amount);
            v.put("method", safe(method).isEmpty() ? "cash" : safe(method));
            v.put("reference", safe(reference)); v.put("notes", safe(notes)); v.put("created_at", now());
            return db.getWritableDatabase().insert("payments", null, v);
        }

        @JavascriptInterface public boolean voidPayment(long paymentId, String reason) {
            ContentValues v = new ContentValues(); v.put("status", "void");
            v.put("voided_at", now()); v.put("void_reason", safe(reason));
            return db.getWritableDatabase().update("payments", v,
                    "id=? AND status='valid'", new String[]{String.valueOf(paymentId)}) > 0;
        }

        // ---------------- Workflow templates and stages ----------------
        @JavascriptInterface public String listWorkflowTemplates() {
            return queryJson("SELECT id,name,description,created_at,updated_at FROM workflow_templates " +
                    "WHERE archived_at IS NULL ORDER BY name COLLATE NOCASE", null).toString();
        }

        @JavascriptInterface public String listTemplateStages(long templateId) {
            return queryJson("SELECT id,template_id,name,sequence,created_at FROM workflow_template_stages " +
                    "WHERE template_id=? ORDER BY sequence",
                    new String[]{String.valueOf(templateId)}).toString();
        }

        @JavascriptInterface public long createWorkflowTemplate(String name, String description) {
            if (safe(name).isEmpty()) return -1;
            ContentValues v = new ContentValues(); v.put("name", safe(name));
            v.put("description", safe(description)); v.put("created_at", now()); v.put("updated_at", now());
            return db.getWritableDatabase().insert("workflow_templates", null, v);
        }

        @JavascriptInterface public long addTemplateStage(long templateId, String name, int sequence) {
            if (templateId <= 0 || safe(name).isEmpty() || sequence <= 0) return -1;
            ContentValues v = new ContentValues(); v.put("template_id", templateId);
            v.put("name", safe(name)); v.put("sequence", sequence); v.put("created_at", now());
            return db.getWritableDatabase().insert("workflow_template_stages", null, v);
        }

        @JavascriptInterface public String listProjectStages(long projectId) {
            return queryJson("SELECT id,project_id,template_stage_id,name,sequence,estimated_at,assigned_to,status,started_at,completed_at,notes " +
                    "FROM project_stages WHERE project_id=? ORDER BY sequence",
                    new String[]{String.valueOf(projectId)}).toString();
        }

        @JavascriptInterface public boolean startProjectStage(long stageId) {
            ContentValues v = new ContentValues(); v.put("status", "in_progress");
            v.put("started_at", now()); v.put("updated_at", now());
            try {
                SQLiteDatabase w = db.getWritableDatabase();
                int changed = w.update("project_stages", v,
                        "id=? AND status='not_started'", new String[]{String.valueOf(stageId)});
                if (changed > 0) {
                    w.execSQL("UPDATE projects SET status='active',updated_at=? " +
                            "WHERE id=(SELECT project_id FROM project_stages WHERE id=?) AND status='draft'",
                            new Object[]{now(), stageId});
                }
                return changed > 0;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface public boolean completeProjectStage(long stageId) {
            ContentValues v = new ContentValues(); v.put("status", "completed");
            v.put("completed_at", now()); v.put("updated_at", now());
            try {
                SQLiteDatabase w = db.getWritableDatabase();
                int changed = w.update("project_stages", v,
                        "id=? AND status='in_progress'", new String[]{String.valueOf(stageId)});
                if (changed > 0) {
                    w.execSQL("UPDATE projects SET status='completed',completed_at=?,updated_at=? " +
                            "WHERE id=(SELECT project_id FROM project_stages WHERE id=?) " +
                            "AND NOT EXISTS (SELECT 1 FROM project_stages s WHERE s.project_id=projects.id AND s.status<>'completed')",
                            new Object[]{now(), now(), stageId});
                }
                return changed > 0;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface public boolean setStageEstimate(long stageId, long estimatedAt, String assignedTo) {
            ContentValues v = new ContentValues();
            if (estimatedAt > 0) v.put("estimated_at", estimatedAt); else v.putNull("estimated_at");
            v.put("assigned_to", safe(assignedTo)); v.put("updated_at", now());
            return db.getWritableDatabase().update("project_stages", v,
                    "id=? AND status<>'completed'", new String[]{String.valueOf(stageId)}) > 0;
        }

        // ---------------- HPP calculator ----------------
        @JavascriptInterface public String listCostComponents(String category, String search) {
            String q = "%" + safe(search) + "%";
            return queryJson("SELECT * FROM cost_components WHERE archived_at IS NULL " +
                    "AND (?='' OR category=?) AND (name LIKE ? OR specification LIKE ?) " +
                    "ORDER BY category,name COLLATE NOCASE",
                    new String[]{safe(category), safe(category), q, q}).toString();
        }

        @JavascriptInterface public long saveCostComponent(long id, String category, String name,
                String specification, String unit, long rate, String rateBasis,
                long minimumCharge, long setupCharge, String parametersJson) {
            if (safe(name).isEmpty() || safe(unit).isEmpty() || rate < 0) return -1;
            ContentValues v = new ContentValues();
            v.put("category", safe(category)); v.put("name", safe(name));
            v.put("specification", safe(specification)); v.put("unit", safe(unit));
            v.put("rate", rate); v.put("rate_basis", safe(rateBasis));
            v.put("minimum_charge", minimumCharge); v.put("setup_charge", setupCharge);
            v.put("parameters_json", safe(parametersJson).isEmpty() ? "{}" : safe(parametersJson));
            v.put("updated_at", now());
            SQLiteDatabase w = db.getWritableDatabase();
            if (id > 0) return w.update("cost_components", v, "id=? AND archived_at IS NULL",
                    new String[]{String.valueOf(id)}) > 0 ? id : -1;
            v.put("created_at", now());
            return w.insert("cost_components", null, v);
        }

        @JavascriptInterface public String listProjectCostLines(long projectId) {
            return queryJson("SELECT * FROM project_cost_lines WHERE project_id=? ORDER BY id",
                    new String[]{String.valueOf(projectId)}).toString();
        }

        @JavascriptInterface public String getProjectHppSummary(long projectId) {
            JSONArray rows = queryJson("SELECT * FROM v_project_hpp_summary WHERE project_id=?",
                    new String[]{String.valueOf(projectId)});
            return rows.length() > 0 ? rows.optJSONObject(0).toString() : "{}";
        }

        // Rate is charged per entered rate-basis unit. Formula:
        // max(quantity * rate, minimum charge) + setup charge.
        @JavascriptInterface public long addProjectCostLine(long projectId, long componentId,
                double quantity, String notes) {
            if (projectId <= 0 || componentId <= 0 || quantity <= 0) return -1;
            SQLiteDatabase w = db.getWritableDatabase();
            Cursor c = w.rawQuery("SELECT category,name,specification,unit,rate,rate_basis,minimum_charge,setup_charge " +
                    "FROM cost_components WHERE id=? AND archived_at IS NULL",
                    new String[]{String.valueOf(componentId)});
            try {
                if (!c.moveToFirst()) return -1;
                long rate = c.getLong(4);
                long minimum = c.getLong(6);
                long setup = c.getLong(7);
                long variable = Math.round(quantity * rate);
                long subtotal = Math.max(variable, minimum) + setup;
                ContentValues v = new ContentValues(); v.put("project_id", projectId);
                v.put("component_id", componentId); v.put("category_snapshot", c.getString(0));
                v.put("name_snapshot", c.getString(1)); v.put("specification_snapshot", c.getString(2));
                v.put("unit_snapshot", c.getString(3)); v.put("quantity", quantity);
                v.put("rate_snapshot", rate); v.put("rate_basis_snapshot", c.getString(5));
                v.put("minimum_charge_snapshot", minimum); v.put("setup_charge_snapshot", setup);
                v.put("subtotal", subtotal); v.put("notes", safe(notes)); v.put("created_at", now());
                return w.insert("project_cost_lines", null, v);
            } finally { c.close(); }
        }

        @JavascriptInterface public boolean removeProjectCostLine(long lineId) {
            return db.getWritableDatabase().delete("project_cost_lines", "id=?",
                    new String[]{String.valueOf(lineId)}) > 0;
        }

        // ---------------- Paper layout optimizer ----------------
        private class RowType {
            int widthCount;
            int heightUnits;
            String orientation;
            RowType(int count, int height, String label) {
                widthCount = count; heightUnits = height; orientation = label;
            }
        }

        private JSONArray makeRowsJson(java.util.List<RowType> rows) throws Exception {
            JSONArray arr = new JSONArray();
            for (RowType r : rows) {
                JSONObject o = new JSONObject();
                o.put("orientation", r.orientation);
                o.put("pieces_per_row", r.widthCount);
                o.put("row_height_mm", r.heightUnits / 10.0);
                arr.put(o);
            }
            return arr;
        }

        private java.util.List<RowType> solveRows(double usableW, double usableH,
                double pieceW, double pieceH, double kerf, boolean allowThisOrientation,
                boolean allowOtherOrientation, double otherW, double otherH) {
            java.util.List<RowType> result = new java.util.ArrayList<>();
            if (!allowThisOrientation) return result;
            int across = (int)Math.floor((usableW + kerf + 0.000001) / (pieceW + kerf));
            int rowHeight = (int)Math.ceil((pieceH + kerf) * 10.0 - 0.000001);
            int capacity = (int)Math.floor((usableH + kerf) * 10.0 + 0.000001);
            if (across <= 0 || rowHeight <= 0 || capacity <= 0) return result;
            int maxUnits = Math.min(capacity, 100000);
            int[] best = new int[maxUnits + 1];
            int[] previous = new int[maxUnits + 1];
            int[] chosen = new int[maxUnits + 1];
            java.util.Arrays.fill(best, -1000000000);
            best[0] = 0;
            int otherAcross = 0, otherRowHeight = 0;
            if (allowOtherOrientation) {
                otherAcross = (int)Math.floor((usableW + kerf + 0.000001) / (otherW + kerf));
                otherRowHeight = (int)Math.ceil((otherH + kerf) * 10.0 - 0.000001);
            }
            for (int h = 0; h <= maxUnits; h++) {
                if (best[h] < 0) continue;
                int nh = h + rowHeight;
                if (nh <= maxUnits && best[h] + across > best[nh]) {
                    best[nh] = best[h] + across; previous[nh] = h; chosen[nh] = 0;
                }
                if (otherAcross > 0 && otherRowHeight > 0) {
                    nh = h + otherRowHeight;
                    if (nh <= maxUnits && best[h] + otherAcross > best[nh]) {
                        best[nh] = best[h] + otherAcross; previous[nh] = h; chosen[nh] = 1;
                    }
                }
            }
            int at = 0;
            for (int h = 1; h <= maxUnits; h++) if (best[h] > best[at]) at = h;
            if (best[at] <= 0) return result;
            java.util.List<RowType> reversed = new java.util.ArrayList<>();
            while (at > 0) {
                int type = chosen[at];
                if (type == 0) reversed.add(new RowType(across, rowHeight, "normal"));
                else reversed.add(new RowType(otherAcross, otherRowHeight, "rotated"));
                at = previous[at];
            }
            for (int i = reversed.size() - 1; i >= 0; i--) result.add(reversed.get(i));
            return result;
        }

        private int sumPatternYield(java.util.List<RowType> rows) {
            int total = 0;
            for (RowType r : rows) total += r.widthCount;
            return total;
        }

        private long insertLayoutPattern(SQLiteDatabase w, long calculationId, String name,
                java.util.List<RowType> rows, int yield, int requiredQty,
                double sourceW, double sourceH, double cutW, double cutH) throws Exception {
            if (yield <= 0 || rows.isEmpty()) return -1;
            int sheets = (int)Math.ceil(requiredQty / (double)yield);
            double totalArea = sourceW * sourceH * sheets;
            double productArea = cutW * cutH * requiredQty;
            double waste = Math.max(0, totalArea - productArea);
            double wastePercent = totalArea <= 0 ? 0 : waste / totalArea * 100.0;
            double efficiency = totalArea <= 0 ? 0 : productArea / totalArea * 100.0;
            JSONArray rowJson = makeRowsJson(rows);
            JSONObject diagram = new JSONObject();
            diagram.put("source_width_mm", sourceW); diagram.put("source_height_mm", sourceH);
            diagram.put("cut_width_mm", cutW); diagram.put("cut_height_mm", cutH);
            diagram.put("rows", rowJson);
            ContentValues v = new ContentValues();
            v.put("calculation_id", calculationId); v.put("pattern_name", name);
            v.put("yield_per_sheet", yield); v.put("sheets_required", sheets);
            v.put("waste_area", waste); v.put("waste_percent", wastePercent);
            v.put("efficiency", efficiency); v.put("cutting_sequence_json", rowJson.toString());
            v.put("diagram_data", diagram.toString()); v.put("created_at", now());
            return w.insert("layout_patterns", null, v);
        }

        @JavascriptInterface public long calculatePaperLayout(long projectId,
                double sourceW, double sourceH, double cutW, double cutH,
                int requiredQty, double margin, double kerf, String grainDirection) {
            if (sourceW <= 0 || sourceH <= 0 || cutW <= 0 || cutH <= 0 || requiredQty <= 0 ||
                    margin < 0 || kerf < 0 || sourceW > 10000 || sourceH > 10000 ||
                    cutW > 10000 || cutH > 10000 || margin * 2 >= sourceW || margin * 2 >= sourceH) return -1;
            double usableW = sourceW - margin * 2;
            double usableH = sourceH - margin * 2;
            String grain = safe(grainDirection);
            boolean canNormal = !"height".equals(grain);
            boolean canRotate = !"width".equals(grain);
            java.util.List<RowType> normal = solveRows(usableW, usableH, cutW, cutH, kerf,
                    canNormal, false, 0, 0);
            if (!normal.isEmpty()) for (RowType r : normal) r.orientation = "normal";
            java.util.List<RowType> rotated = solveRows(usableW, usableH, cutH, cutW, kerf,
                    canRotate, false, 0, 0);
            if (!rotated.isEmpty()) for (RowType r : rotated) r.orientation = "rotated";
            java.util.List<RowType> mixed = new java.util.ArrayList<>();
            if (canNormal && canRotate) mixed = solveRows(usableW, usableH, cutW, cutH, kerf,
                    true, true, cutH, cutW);
            if (!mixed.isEmpty()) {
                boolean hasNormal = false, hasRotated = false;
                for (RowType r : mixed) {
                    if ("rotated".equals(r.orientation)) hasRotated = true;
                    else { r.orientation = "normal"; hasNormal = true; }
                }
                if (!hasNormal || !hasRotated) mixed.clear();
            }
            SQLiteDatabase w = db.getWritableDatabase();
            w.beginTransaction();
            long calculationId = -1;
            try {
                ContentValues cv = new ContentValues();
                if (projectId > 0) cv.put("project_id", projectId); else cv.putNull("project_id");
                cv.put("source_width_mm", sourceW); cv.put("source_height_mm", sourceH);
                cv.put("cut_width_mm", cutW); cv.put("cut_height_mm", cutH);
                cv.put("required_quantity", requiredQty); cv.put("margin_mm", margin);
                cv.put("kerf_mm", kerf); cv.put("grain_direction", grain); cv.put("created_at", now());
                calculationId = w.insertOrThrow("layout_calculations", null, cv);
                if (!normal.isEmpty()) insertLayoutPattern(w, calculationId, "Semua normal",
                        normal, sumPatternYield(normal), requiredQty, sourceW, sourceH, cutW, cutH);
                if (!rotated.isEmpty()) insertLayoutPattern(w, calculationId, "Semua diputar",
                        rotated, sumPatternYield(rotated), requiredQty, sourceW, sourceH, cutW, cutH);
                if (!mixed.isEmpty()) insertLayoutPattern(w, calculationId, "Campuran baris",
                        mixed, sumPatternYield(mixed), requiredQty, sourceW, sourceH, cutW, cutH);
                w.setTransactionSuccessful();
            } catch (Exception e) { calculationId = -1; }
            finally { w.endTransaction(); }
            return calculationId;
        }

        @JavascriptInterface public String listLayoutCalculations(long projectId) {
            if (projectId > 0) return queryJson("SELECT * FROM layout_calculations WHERE project_id=? ORDER BY id DESC",
                    new String[]{String.valueOf(projectId)}).toString();
            return queryJson("SELECT * FROM layout_calculations WHERE project_id IS NULL ORDER BY id DESC", null).toString();
        }

        @JavascriptInterface public String listLayoutPatterns(long calculationId) {
            return queryJson("SELECT * FROM layout_patterns WHERE calculation_id=? ORDER BY efficiency DESC,id",
                    new String[]{String.valueOf(calculationId)}).toString();
        }

        @JavascriptInterface public boolean selectLayoutPattern(long patternId) {
            SQLiteDatabase w = db.getWritableDatabase();
            long calculationId = -1;
            Cursor c = w.rawQuery("SELECT calculation_id FROM layout_patterns WHERE id=?",
                    new String[]{String.valueOf(patternId)});
            try { if (c.moveToFirst()) calculationId = c.getLong(0); }
            finally { c.close(); }
            if (calculationId <= 0) return false;
            w.beginTransaction();
            try {
                w.execSQL("UPDATE layout_patterns SET is_selected=0 WHERE calculation_id=?",
                        new Object[]{calculationId});
                w.execSQL("UPDATE layout_patterns SET is_selected=1 WHERE id=?",
                        new Object[]{patternId});
                w.setTransactionSuccessful(); return true;
            } finally { w.endTransaction(); }
        }

        @JavascriptInterface public long addLayoutPatternToProjectHpp(long projectId,
                long patternId, long componentId) {
            if (projectId <= 0 || patternId <= 0 || componentId <= 0) return -1;
            SQLiteDatabase w = db.getWritableDatabase();
            Cursor p = w.rawQuery("SELECT lp.sheets_required,lc.source_width_mm,lc.source_height_mm " +
                    "FROM layout_patterns lp JOIN layout_calculations lc ON lc.id=lp.calculation_id " +
                    "WHERE lp.id=? AND lc.project_id=?",
                    new String[]{String.valueOf(patternId), String.valueOf(projectId)});
            try {
                if (!p.moveToFirst()) return -1;
                double sheets = p.getDouble(0);
                Cursor c = w.rawQuery("SELECT category,name,specification,unit,rate,rate_basis,minimum_charge,setup_charge " +
                        "FROM cost_components WHERE id=? AND archived_at IS NULL AND category='media'",
                        new String[]{String.valueOf(componentId)});
                try {
                    if (!c.moveToFirst()) return -1;
                    long rate = c.getLong(4), minimum = c.getLong(6), setup = c.getLong(7);
                    long subtotal = Math.max(Math.round(sheets * rate), minimum) + setup;
                    ContentValues v = new ContentValues(); v.put("project_id", projectId);
                    v.put("component_id", componentId); v.put("layout_pattern_id", patternId);
                    v.put("category_snapshot", c.getString(0));
                    v.put("name_snapshot", c.getString(1));
                    v.put("specification_snapshot", c.getString(2));
                    v.put("unit_snapshot", c.getString(3)); v.put("quantity", sheets);
                    v.put("rate_snapshot", rate); v.put("rate_basis_snapshot", c.getString(5));
                    v.put("minimum_charge_snapshot", minimum); v.put("setup_charge_snapshot", setup);
                    v.put("subtotal", subtotal);
                    v.put("notes", "Kebutuhan plano dari Paper Layout Optimizer"); v.put("created_at", now());
                    return w.insert("project_cost_lines", null, v);
                } finally { c.close(); }
            } finally { p.close(); }
        }

        // ---------------- Purchasing (standalone) ----------------
        @JavascriptInterface public String listSuppliers(String search) {
            String q = "%" + safe(search) + "%";
            return queryJson("SELECT id,name,phone,address,notes FROM suppliers " +
                    "WHERE archived_at IS NULL AND name LIKE ? ORDER BY name COLLATE NOCASE",
                    new String[]{q}).toString();
        }

        @JavascriptInterface public long saveSupplier(long id, String name, String phone, String address, String notes) {
            if (safe(name).isEmpty()) return -1;
            ContentValues v = new ContentValues(); v.put("name", safe(name));
            v.put("phone", safe(phone)); v.put("address", safe(address));
            v.put("notes", safe(notes)); v.put("updated_at", now());
            SQLiteDatabase w = db.getWritableDatabase();
            if (id > 0) return w.update("suppliers", v, "id=? AND archived_at IS NULL",
                    new String[]{String.valueOf(id)}) > 0 ? id : -1;
            v.put("created_at", now()); return w.insert("suppliers", null, v);
        }

        @JavascriptInterface public long createPurchase(long supplierId, String purchaseDate,
                String purchaseType, String notes) {
            if (safe(purchaseDate).isEmpty()) return -1;
            if (!"direct_purchase".equals(purchaseType) && !"supplier_order".equals(purchaseType)) return -1;
            ContentValues v = new ContentValues();
            if (supplierId > 0) v.put("supplier_id", supplierId); else v.putNull("supplier_id");
            v.put("purchase_date", safe(purchaseDate)); v.put("purchase_type", purchaseType);
            v.put("status", "direct_purchase".equals(purchaseType) ? "received" : "draft");
            v.put("notes", safe(notes)); v.put("created_at", now()); v.put("updated_at", now());
            return db.getWritableDatabase().insert("purchases", null, v);
        }

        @JavascriptInterface public long addPurchaseLine(long purchaseId, String itemName,
                double quantity, String unit, long unitPrice) {
            if (purchaseId <= 0 || safe(itemName).isEmpty() || quantity <= 0 || safe(unit).isEmpty() || unitPrice < 0) return -1;
            long subtotal = Math.round(quantity * unitPrice);
            ContentValues v = new ContentValues(); v.put("purchase_id", purchaseId);
            v.put("item_name", safe(itemName)); v.put("quantity", quantity); v.put("unit", safe(unit));
            v.put("unit_price", unitPrice); v.put("subtotal", subtotal);
            return db.getWritableDatabase().insert("purchase_lines", null, v);
        }
    }

    private class AppDb extends SQLiteOpenHelper {
        static final int VERSION = 1;
        AppDb() { super(MainActivity.this, "print_job_manager.db", null, VERSION); }
        @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

        @Override public void onCreate(SQLiteDatabase d) {
            d.execSQL("CREATE TABLE customers (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, phone TEXT, address TEXT, notes TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, archived_at INTEGER)");
            d.execSQL("CREATE INDEX idx_customers_name ON customers(name)");

            d.execSQL("CREATE TABLE sales_orders (id INTEGER PRIMARY KEY AUTOINCREMENT, order_number TEXT NOT NULL UNIQUE, customer_id INTEGER NOT NULL, order_date TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'draft' CHECK(status IN ('draft','confirmed','completed','cancelled')), notes TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, archived_at INTEGER, FOREIGN KEY(customer_id) REFERENCES customers(id) ON DELETE RESTRICT)");
            d.execSQL("CREATE INDEX idx_sales_orders_customer ON sales_orders(customer_id)");
            d.execSQL("CREATE INDEX idx_sales_orders_date ON sales_orders(order_date)");
            d.execSQL("CREATE INDEX idx_sales_orders_status ON sales_orders(status)");

            d.execSQL("CREATE TABLE projects (id INTEGER PRIMARY KEY AUTOINCREMENT, sales_order_id INTEGER NOT NULL, project_number TEXT NOT NULL UNIQUE, name TEXT NOT NULL, description TEXT, quantity REAL NOT NULL DEFAULT 1 CHECK(quantity>0), unit TEXT NOT NULL DEFAULT 'unit', sale_amount INTEGER NOT NULL DEFAULT 0 CHECK(sale_amount>=0), deadline_at INTEGER, status TEXT NOT NULL DEFAULT 'draft' CHECK(status IN ('draft','active','completed','cancelled')), created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, completed_at INTEGER, archived_at INTEGER, FOREIGN KEY(sales_order_id) REFERENCES sales_orders(id) ON DELETE RESTRICT)");
            d.execSQL("CREATE INDEX idx_projects_sales_order ON projects(sales_order_id)");
            d.execSQL("CREATE INDEX idx_projects_status ON projects(status)");
            d.execSQL("CREATE INDEX idx_projects_deadline ON projects(deadline_at)");
            d.execSQL("CREATE INDEX idx_projects_name ON projects(name)");

            d.execSQL("CREATE TABLE payments (id INTEGER PRIMARY KEY AUTOINCREMENT, sales_order_id INTEGER NOT NULL, paid_at INTEGER NOT NULL, amount INTEGER NOT NULL CHECK(amount>0), method TEXT NOT NULL DEFAULT 'cash', reference TEXT, notes TEXT, status TEXT NOT NULL DEFAULT 'valid' CHECK(status IN ('valid','void')), created_at INTEGER NOT NULL, voided_at INTEGER, void_reason TEXT, FOREIGN KEY(sales_order_id) REFERENCES sales_orders(id) ON DELETE RESTRICT)");
            d.execSQL("CREATE INDEX idx_payments_sales_order ON payments(sales_order_id)");
            d.execSQL("CREATE INDEX idx_payments_paid_at ON payments(paid_at)");
            d.execSQL("CREATE INDEX idx_payments_status ON payments(status)");
            d.execSQL("CREATE TRIGGER prevent_payment_delete BEFORE DELETE ON payments BEGIN SELECT RAISE(ABORT,'Payment cannot be deleted. Use void instead.'); END");

            d.execSQL("CREATE TABLE workflow_templates (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, description TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, archived_at INTEGER)");
            d.execSQL("CREATE INDEX idx_workflow_templates_name ON workflow_templates(name)");
            d.execSQL("CREATE TABLE workflow_template_stages (id INTEGER PRIMARY KEY AUTOINCREMENT, template_id INTEGER NOT NULL, name TEXT NOT NULL, sequence INTEGER NOT NULL CHECK(sequence>0), created_at INTEGER NOT NULL, UNIQUE(template_id,sequence), FOREIGN KEY(template_id) REFERENCES workflow_templates(id) ON DELETE CASCADE)");
            d.execSQL("CREATE INDEX idx_template_stages_template ON workflow_template_stages(template_id,sequence)");

            d.execSQL("CREATE TABLE project_stages (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL, template_stage_id INTEGER, name TEXT NOT NULL, sequence INTEGER NOT NULL CHECK(sequence>0), estimated_at INTEGER, assigned_to TEXT, status TEXT NOT NULL DEFAULT 'not_started' CHECK(status IN ('not_started','in_progress','completed')), started_at INTEGER, completed_at INTEGER, notes TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, UNIQUE(project_id,sequence), FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE, FOREIGN KEY(template_stage_id) REFERENCES workflow_template_stages(id) ON DELETE SET NULL)");
            d.execSQL("CREATE INDEX idx_project_stages_project ON project_stages(project_id,sequence)");
            d.execSQL("CREATE INDEX idx_project_stages_status ON project_stages(status)");
            d.execSQL("CREATE INDEX idx_project_stages_estimated ON project_stages(estimated_at)");
            d.execSQL("CREATE TRIGGER enforce_sequential_stage_start_insert BEFORE INSERT ON project_stages WHEN NEW.status='in_progress' BEGIN SELECT CASE WHEN EXISTS(SELECT 1 FROM project_stages p WHERE p.project_id=NEW.project_id AND p.sequence<NEW.sequence AND p.status<>'completed') THEN RAISE(ABORT,'Previous project stage is not completed.') END; END");
            d.execSQL("CREATE TRIGGER enforce_sequential_stage_start_update BEFORE UPDATE OF status ON project_stages WHEN NEW.status='in_progress' AND OLD.status<>'in_progress' BEGIN SELECT CASE WHEN EXISTS(SELECT 1 FROM project_stages p WHERE p.project_id=NEW.project_id AND p.sequence<NEW.sequence AND p.status<>'completed') THEN RAISE(ABORT,'Previous project stage is not completed.') END; END");
            d.execSQL("CREATE TRIGGER enforce_stage_completion BEFORE UPDATE OF status ON project_stages WHEN NEW.status='completed' AND OLD.status<>'completed' AND OLD.status<>'in_progress' BEGIN SELECT RAISE(ABORT,'Stage must be started before completion.'); END");
            d.execSQL("CREATE TRIGGER prevent_completed_stage_reopen BEFORE UPDATE OF status ON project_stages WHEN OLD.status='completed' AND NEW.status<>'completed' BEGIN SELECT RAISE(ABORT,'Completed stage cannot be reopened.'); END");

            d.execSQL("CREATE TABLE cost_components (id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL CHECK(category IN ('media','print_process','finishing','other_costs')), name TEXT NOT NULL, specification TEXT, unit TEXT NOT NULL, rate INTEGER NOT NULL DEFAULT 0 CHECK(rate>=0), rate_basis TEXT NOT NULL, minimum_charge INTEGER NOT NULL DEFAULT 0 CHECK(minimum_charge>=0), setup_charge INTEGER NOT NULL DEFAULT 0 CHECK(setup_charge>=0), parameters_json TEXT NOT NULL DEFAULT '{}', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, archived_at INTEGER)");
            d.execSQL("CREATE INDEX idx_cost_components_category ON cost_components(category)");
            d.execSQL("CREATE INDEX idx_cost_components_name ON cost_components(name)");

            d.execSQL("CREATE TABLE layout_calculations (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER, source_width_mm REAL NOT NULL CHECK(source_width_mm>0), source_height_mm REAL NOT NULL CHECK(source_height_mm>0), cut_width_mm REAL NOT NULL CHECK(cut_width_mm>0), cut_height_mm REAL NOT NULL CHECK(cut_height_mm>0), required_quantity INTEGER NOT NULL CHECK(required_quantity>0), margin_mm REAL NOT NULL DEFAULT 0 CHECK(margin_mm>=0), kerf_mm REAL NOT NULL DEFAULT 0 CHECK(kerf_mm>=0), grain_direction TEXT, created_at INTEGER NOT NULL, FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE SET NULL)");
            d.execSQL("CREATE INDEX idx_layout_calculations_project ON layout_calculations(project_id)");
            d.execSQL("CREATE TABLE layout_patterns (id INTEGER PRIMARY KEY AUTOINCREMENT, calculation_id INTEGER NOT NULL, pattern_name TEXT NOT NULL, yield_per_sheet INTEGER NOT NULL CHECK(yield_per_sheet>0), sheets_required INTEGER NOT NULL CHECK(sheets_required>0), waste_area REAL NOT NULL DEFAULT 0, waste_percent REAL NOT NULL DEFAULT 0, efficiency REAL NOT NULL DEFAULT 0, cutting_sequence_json TEXT, diagram_data TEXT, is_selected INTEGER NOT NULL DEFAULT 0 CHECK(is_selected IN (0,1)), created_at INTEGER NOT NULL, FOREIGN KEY(calculation_id) REFERENCES layout_calculations(id) ON DELETE CASCADE)");
            d.execSQL("CREATE INDEX idx_layout_patterns_calculation ON layout_patterns(calculation_id)");
            d.execSQL("CREATE UNIQUE INDEX idx_one_selected_layout_pattern ON layout_patterns(calculation_id) WHERE is_selected=1");

            d.execSQL("CREATE TABLE project_cost_lines (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL, component_id INTEGER, layout_pattern_id INTEGER, category_snapshot TEXT NOT NULL, name_snapshot TEXT NOT NULL, specification_snapshot TEXT, unit_snapshot TEXT NOT NULL, quantity REAL NOT NULL CHECK(quantity>0), rate_snapshot INTEGER NOT NULL CHECK(rate_snapshot>=0), rate_basis_snapshot TEXT NOT NULL, minimum_charge_snapshot INTEGER NOT NULL DEFAULT 0, setup_charge_snapshot INTEGER NOT NULL DEFAULT 0, subtotal INTEGER NOT NULL CHECK(subtotal>=0), notes TEXT, created_at INTEGER NOT NULL, FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE, FOREIGN KEY(component_id) REFERENCES cost_components(id) ON DELETE SET NULL, FOREIGN KEY(layout_pattern_id) REFERENCES layout_patterns(id) ON DELETE SET NULL)");
            d.execSQL("CREATE INDEX idx_project_cost_lines_project ON project_cost_lines(project_id)");
            d.execSQL("CREATE INDEX idx_project_cost_lines_component ON project_cost_lines(component_id)");

            d.execSQL("CREATE TABLE suppliers (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, phone TEXT, address TEXT, notes TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, archived_at INTEGER)");
            d.execSQL("CREATE INDEX idx_suppliers_name ON suppliers(name)");
            d.execSQL("CREATE TABLE purchases (id INTEGER PRIMARY KEY AUTOINCREMENT, supplier_id INTEGER, purchase_date TEXT NOT NULL, purchase_type TEXT NOT NULL CHECK(purchase_type IN ('direct_purchase','supplier_order')), status TEXT NOT NULL DEFAULT 'draft' CHECK(status IN ('draft','ordered','received','void')), notes TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, FOREIGN KEY(supplier_id) REFERENCES suppliers(id) ON DELETE RESTRICT)");
            d.execSQL("CREATE INDEX idx_purchases_supplier ON purchases(supplier_id)");
            d.execSQL("CREATE INDEX idx_purchases_date ON purchases(purchase_date)");
            d.execSQL("CREATE INDEX idx_purchases_status ON purchases(status)");
            d.execSQL("CREATE TABLE purchase_lines (id INTEGER PRIMARY KEY AUTOINCREMENT, purchase_id INTEGER NOT NULL, item_name TEXT NOT NULL, quantity REAL NOT NULL CHECK(quantity>0), unit TEXT NOT NULL, unit_price INTEGER NOT NULL CHECK(unit_price>=0), subtotal INTEGER NOT NULL CHECK(subtotal>=0), FOREIGN KEY(purchase_id) REFERENCES purchases(id) ON DELETE CASCADE)");
            d.execSQL("CREATE INDEX idx_purchase_lines_purchase ON purchase_lines(purchase_id)");

            d.execSQL("CREATE TABLE project_files (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL, file_name TEXT NOT NULL, relative_path TEXT NOT NULL, mime_type TEXT, file_size INTEGER, created_at INTEGER NOT NULL, FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)");
            d.execSQL("CREATE INDEX idx_project_files_project ON project_files(project_id)");
            d.execSQL("CREATE TABLE notification_log (id INTEGER PRIMARY KEY AUTOINCREMENT, project_stage_id INTEGER NOT NULL, scheduled_at INTEGER NOT NULL, sent_at INTEGER, status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','sent','cancelled')), created_at INTEGER NOT NULL, FOREIGN KEY(project_stage_id) REFERENCES project_stages(id) ON DELETE CASCADE)");
            d.execSQL("CREATE INDEX idx_notification_log_stage ON notification_log(project_stage_id)");
            d.execSQL("CREATE INDEX idx_notification_log_schedule ON notification_log(status,scheduled_at)");

            d.execSQL("CREATE VIEW v_sales_order_summary AS SELECT so.id AS sales_order_id,so.order_number,so.customer_id,so.order_date,so.status,(SELECT COUNT(*) FROM projects p WHERE p.sales_order_id=so.id AND p.status<>'cancelled' AND p.archived_at IS NULL) AS project_count,(SELECT COALESCE(SUM(p.sale_amount),0) FROM projects p WHERE p.sales_order_id=so.id AND p.status<>'cancelled' AND p.archived_at IS NULL) AS total_amount,(SELECT COALESCE(SUM(pay.amount),0) FROM payments pay WHERE pay.sales_order_id=so.id AND pay.status='valid') AS total_paid,((SELECT COALESCE(SUM(p.sale_amount),0) FROM projects p WHERE p.sales_order_id=so.id AND p.status<>'cancelled' AND p.archived_at IS NULL)-(SELECT COALESCE(SUM(pay.amount),0) FROM payments pay WHERE pay.sales_order_id=so.id AND pay.status='valid')) AS balance FROM sales_orders so");
            d.execSQL("CREATE VIEW v_project_hpp_summary AS SELECT p.id AS project_id,p.project_number,p.name,p.quantity,COALESCE(SUM(pc.subtotal),0) AS total_hpp,CASE WHEN p.quantity>0 THEN CAST(COALESCE(SUM(pc.subtotal),0) AS REAL)/p.quantity ELSE 0 END AS hpp_per_unit FROM projects p LEFT JOIN project_cost_lines pc ON pc.project_id=p.id GROUP BY p.id");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Prototype has no production data. Future schema changes will be handled
            // with an explicit versioned upgrade or a clean reinstall, as appropriate.
        }
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
