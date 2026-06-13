package hyshweb.common;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class Page {
    private final List<Map<String, Object>> items;
    private final int page;
    private final int pageSize;
    private final int total;
    private final int totalPages;
    private final Map<String, Object> pageSummary;
    private final Map<String, Object> totalSummary;

    public Page(List<Map<String, Object>> items, int page, int pageSize, int total,
                Map<String, Object> pageSummary, Map<String, Object> totalSummary) {
        this.items = items;
        this.page = page;
        this.pageSize = pageSize;
        this.total = total;
        this.totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) pageSize);
        this.pageSummary = pageSummary == null ? Collections.emptyMap() : pageSummary;
        this.totalSummary = totalSummary == null ? Collections.emptyMap() : totalSummary;
    }

    public List<Map<String, Object>> getItems() {
        return items;
    }

    public int getPage() {
        return page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public int getTotal() {
        return total;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public Map<String, Object> getPageSummary() {
        return pageSummary;
    }

    public Map<String, Object> getTotalSummary() {
        return totalSummary;
    }
}
