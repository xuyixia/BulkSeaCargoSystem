const state = {
    user: null,
    tabs: [],
    active: null,
    currentDictType: "package_type"
};

async function api(url, options = {}) {
    const headers = options.headers || {};
    if (options.body && !(options.body instanceof FormData)) {
        headers["Content-Type"] = "application/json;charset=UTF-8";
    }
    const response = await fetch(url, {...options, headers});
    const json = await response.json().catch(() => ({success: false, message: "响应格式错误"}));
    if (!response.ok || json.success === false) {
        throw new Error(json.message || "请求失败");
    }
    return json.data;
}

function $(selector, root = document) {
    return root.querySelector(selector);
}

function html(rows, empty = "暂无数据") {
    return rows && rows.length ? rows.join("") : `<tr><td colspan="20">${empty}</td></tr>`;
}

function esc(value) {
    return String(value ?? "").replace(/[&<>"']/g, ch => ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        "\"": "&quot;",
        "'": "&#39;"
    }[ch]));
}

function qs(form) {
    const params = new URLSearchParams();
    new FormData(form).forEach((value, key) => {
        if (value !== "") params.set(key, value);
    });
    return params.toString();
}

function money(value) {
    const n = Number(value || 0);
    return Number.isFinite(n) ? n.toFixed(2) : "0.00";
}

function today(offset = 0) {
    const d = new Date();
    d.setDate(d.getDate() + offset);
    return d.toISOString().slice(0, 10);
}

function pager(page, formSelector, loadFn) {
    const prevPage = Math.max(1, page.page - 1);
    const nextPage = Math.min(page.totalPages || 1, page.page + 1);
    return `<div class="actions">
        <button type="button" class="secondary" ${page.page <= 1 ? "disabled" : ""} onclick="${loadFn}(withPage('${formSelector}', ${prevPage}))">上一页</button>
        <span>${page.page} / ${page.totalPages || 1}</span>
        <button type="button" class="secondary" ${page.page >= page.totalPages ? "disabled" : ""} onclick="${loadFn}(withPage('${formSelector}', ${nextPage}))">下一页</button>
    </div>`;
}

function withPage(formSelector, page) {
    const params = new URLSearchParams(qs($(formSelector)));
    params.set("page", page);
    return params.toString();
}

function fillForm(formSelector, query) {
    const form = $(formSelector);
    if (!form || !query) return;
    const params = new URLSearchParams(query);
    params.forEach((value, key) => {
        const input = form.elements[key];
        if (input) input.value = value;
    });
}

function dictOptions(items, selected = "", fallback = "") {
    const names = items.map(item => item.dictName);
    const options = [];
    if (fallback && !names.includes(fallback)) {
        options.push(`<option value="${esc(fallback)}">${esc(fallback)}</option>`);
    }
    options.push(...items.map(item => `<option value="${esc(item.dictName)}" ${item.dictName === selected ? "selected" : ""}>${esc(item.dictName)}</option>`));
    return options.join("");
}

function openTab(id, title, renderer) {
    if (!state.tabs.some(tab => tab.id === id)) {
        state.tabs.push({id, title, renderer});
    }
    state.active = id;
    renderTabs();
    renderer();
}

function closeTab(id) {
    state.tabs = state.tabs.filter(tab => tab.id !== id);
    if (state.active === id) {
        state.active = state.tabs[0]?.id || null;
    }
    renderTabs();
    const tab = state.tabs.find(t => t.id === state.active);
    if (tab) tab.renderer();
    else $("#view").innerHTML = "";
}

function renderTabs() {
    $("#tabs").innerHTML = state.tabs.map(tab => `
        <button class="tab ${tab.id === state.active ? "active" : ""}" onclick="activateTab('${tab.id}')">
            ${esc(tab.title)} <span class="close" onclick="event.stopPropagation();closeTab('${tab.id}')">x</span>
        </button>
    `).join("");
}

function activateTab(id) {
    const tab = state.tabs.find(item => item.id === id);
    if (!tab) return;
    state.active = id;
    renderTabs();
    tab.renderer();
}

function setView(markup) {
    $("#view").innerHTML = markup;
}

function bindSubmit(selector, handler) {
    $(selector).addEventListener("submit", async event => {
        event.preventDefault();
        try {
            await handler(event.currentTarget);
        } catch (e) {
            $(".msg", event.currentTarget)?.replaceChildren(document.createTextNode(e.message));
        }
    });
}

function modal(title, content) {
    const box = $("#modal");
    box.innerHTML = `<div class="dialog"><div class="dialog-head"><h2>${esc(title)}</h2><button class="secondary" onclick="closeModal()">关闭</button></div>${content}</div>`;
    box.classList.add("open");
}

function closeModal() {
    $("#modal").classList.remove("open");
    $("#modal").innerHTML = "";
}

async function initApp() {
    try {
        state.user = await api("api/auth/me");
        if (!state.user) location.href = "login.html";
        $("#user").textContent = `${state.user.name} / ${state.user.userType}`;
        document.querySelectorAll("[data-menu]").forEach(item => {
            if (!state.user.menus.includes(item.dataset.menu)) {
                item.remove();
            }
        });
        document.querySelectorAll(".nav button").forEach(btn => btn.addEventListener("click", () => {
            document.querySelectorAll(".nav button").forEach(item => item.classList.remove("active"));
            btn.classList.add("active");
            const key = btn.dataset.view;
            views[key]();
        }));
        views.inbound();
    } catch {
        location.href = "login.html";
    }
}

async function logout() {
    await api("api/auth/logout");
    location.href = "login.html";
}

const views = {
    inbound() {
        openTab("inbound", "入库查询", renderInbound);
    },
    outbound() {
        openTab("outbound", "出库查询", renderOutbound);
    },
    master() {
        openTab("master", "基础资料", renderMaster);
    },
    users() {
        openTab("users", "账号管理", renderUsers);
    }
};

async function renderInbound() {
    setView(`
        <section class="panel">
            <form id="inSearch" class="toolbar">
                <div class="field"><label>入库单号</label><input name="orderNo"></div>
                <div class="field"><label>跟踪单号</label><input name="trackNo"></div>
                <div class="field"><label>客户</label><input name="customer"></div>
                <div class="field"><label>销售</label><input name="sales"></div>
                <div class="field"><label>开始日期</label><input type="date" name="startDate"></div>
                <div class="field"><label>结束日期</label><input type="date" name="endDate"></div>
                <div class="field small"><label>状态</label><select name="status"><option>全部</option><option>草稿</option><option>有效</option></select></div>
                <div class="field small"><label>物流节点</label><select name="logisticsNode"><option>全部</option><option>入库</option><option>完成派送</option><option>完成收款</option><option>出库</option><option>启运</option><option>到港</option><option>到仓</option></select></div>
                <div class="field"><label>派送单号</label><input name="sendNo"></div>
                <div class="field small"><label>收款状态</label><select name="collectionStatus"><option>全部</option><option>未收款</option><option>部分收款</option><option>已收款</option></select></div>
                <button>查询</button>
                <button type="button" class="secondary" onclick="newInbound()">新增</button>
                <a class="button-link" href="api/inbound/template">下载模板</a>
                <button type="button" class="secondary" onclick="openInboundImport()">导入</button>
                <span class="msg"></span>
            </form>
            <div id="inTable"></div>
        </section>
    `);
    bindSubmit("#inSearch", form => loadInbound(qs(form)));
    await loadInbound("");
}

async function loadInbound(query) {
    const page = await api(`api/inbound/orders?${query}`);
    $("#inTable").innerHTML = `
        <table>
            <thead><tr><th>入库单号</th><th>状态</th><th>跟踪单号</th><th>客户</th><th>销售</th><th>入库日期</th><th>件数</th><th>重量</th><th>体积</th><th>物流节点</th><th>操作</th></tr></thead>
            <tbody>${html(page.items.map(row => `
                <tr>
                    <td>${esc(row.inOrderNo)}</td>
                    <td><span class="status ${row.status === "有效" ? "ok" : "warn"}">${esc(row.status)}</span></td>
                    <td>${esc(row.trackNo)}</td><td>${esc(row.customer)}</td><td>${esc(row.sales)}</td><td>${esc(row.inDate)}</td>
                    <td>${esc(row.totalPackageQty)}</td><td>${esc(row.totalWeight)}</td><td>${esc(row.totalVolume)}</td><td>${esc(row.logisticsNode)}</td>
                    <td><button class="secondary" onclick="editInbound('${esc(row.inOrderNo)}')">编辑</button></td>
                </tr>`))}</tbody>
        </table>
        <div class="summary"><span>总数：${page.total}</span><span>总件数：${page.totalSummary.totalPackageQty || 0}</span><span>总重量：${page.totalSummary.totalWeight || 0}</span><span>总体积：${page.totalSummary.totalVolume || 0}</span></div>
        ${pager(page, "#inSearch", "loadInbound")}
    `;
}

function newInbound() {
    editInbound("");
}

async function editInbound(orderNo) {
    const order = orderNo ? await api(`api/inbound/orders/${orderNo}`) : {details: []};
    const packageTypes = await api("api/master/dicts/enabled?type=package_type");
    modal(orderNo ? `入库单 ${orderNo}` : "新增入库单", `
        <form id="inEdit">
            <div class="grid">
                <div class="field"><label>入库单号</label><input name="inOrderNo" value="${esc(order.inOrderNo)}" readonly></div>
                <div class="field"><label>跟踪单号</label><input name="trackNo" value="${esc(order.trackNo)}"></div>
                <div class="field"><label>客户</label><input name="customer" value="${esc(order.customer)}"></div>
                <div class="field"><label>销售</label><input name="sales" value="${esc(order.sales)}"></div>
                <div class="field"><label>入库日期</label><input type="date" name="inDate" value="${esc(order.inDate || today())}"></div>
                <div class="field"><label>派送类型</label><select name="sendType"><option ${order.sendType === "自提" ? "selected" : ""}>自提</option><option ${order.sendType === "派送" ? "selected" : ""}>派送</option></select></div>
                <div class="field"><label>派送单号</label><input name="sendNo" value="${esc(order.sendNo)}"></div>
            </div>
            <h3>明细</h3>
            <div id="inDetails"></div>
            <div class="actions">
                <button type="button" class="secondary" onclick="addInDetailRow()">新增明细</button>
                <button>保存</button>
                <button type="button" onclick="submitInbound('${esc(order.inOrderNo)}')">提交</button>
                <button type="button" class="secondary" onclick="cancelInbound('${esc(order.inOrderNo)}')">取消提交</button>
                <button type="button" class="secondary" onclick="openDelivery('${esc(order.inOrderNo)}')">完成派送</button>
                ${orderNo ? `<button type="button" class="secondary" onclick="openRelatedOutbounds('${esc(order.inOrderNo)}')">关联出库</button>` : ""}
                ${orderNo ? `<button type="button" class="secondary" onclick="openInboundNodes('${esc(order.inOrderNo)}')">物流节点详情</button>` : ""}
                ${orderNo ? `<button type="button" class="danger" onclick="deleteInbound('${esc(order.inOrderNo)}')">删除</button>` : ""}
            </div>
            <div class="msg"></div>
        </form>
        ${orderNo ? `<h3>操作记录</h3><div>${logTable(order.logs || [])}</div>` : ""}
    `);
    renderInDetailRows(order.details || [], packageTypes);
    bindSubmit("#inEdit", saveInbound);
}

function renderInDetailRows(details, packageTypes = []) {
    $("#inDetails").innerHTML = `
        <table><thead><tr><th>跟踪单号</th><th>品名</th><th>英文品名</th><th>件数</th><th>库存件数</th><th>包装</th><th>重量</th><th>体积</th><th>数量</th><th>库存数量</th><th>唛头</th><th>应付单价</th><th>应付币制</th><th>应付单位</th><th>应付金额</th><th>应收单价</th><th>应收币制</th><th>应收单位</th><th>应收金额</th><th>操作</th></tr></thead>
        <tbody id="inDetailRows" data-package-options="${esc(dictOptions(packageTypes))}">${html(details.map(d => inDetailRow(d, packageTypes)))}</tbody></table>
    `;
    bindInboundAmountSync();
}

function inDetailRow(d = {}, packageTypes = [], packageOptionsHtml = "") {
    const packageOptions = packageOptionsHtml || (packageTypes.length ? dictOptions(packageTypes, d.packageType, d.packageType) : `<option value="${esc(d.packageType || "")}">${esc(d.packageType || "请先维护包装字典")}</option>`);
    return `<tr>
        <td><input name="trackNo" value="${esc(d.trackNo)}"></td>
        <td><input name="productName" value="${esc(d.productName)}"></td>
        <td><input name="productEnName" value="${esc(d.productEnName)}"></td>
        <td><input type="number" name="packageQty" value="${esc(d.packageQty || 1)}"></td>
        <td>${esc(d.stockPackageQty ?? "")}</td>
        <td><select name="packageType">${packageOptions}</select></td>
        <td><input type="number" step="0.01" name="weight" value="${esc(d.weight || 0)}"></td>
        <td><input type="number" step="0.01" name="volume" value="${esc(d.volume || 0)}"></td>
        <td><input type="number" name="qty" value="${esc(d.qty || 1)}"></td>
        <td>${esc(d.stockQty ?? "")}</td>
        <td><input name="marks" value="${esc(d.marks)}"></td>
        <td><input type="number" step="0.01" name="costPrice" value="${esc(d.costPrice ?? "")}"></td>
        <td><select name="costCurrency"><option ${d.costCurrency === "USD" ? "selected" : ""}>USD</option><option ${d.costCurrency === "RMB" ? "selected" : ""}>RMB</option><option ${d.costCurrency === "PKR" ? "selected" : ""}>PKR</option></select></td>
        <td><select name="yfUnit"><option ${d.yfUnit === "按数量" ? "selected" : ""}>按数量</option><option ${d.yfUnit === "按重量" ? "selected" : ""}>按重量</option><option ${d.yfUnit === "按体积" ? "selected" : ""}>按体积</option><option ${d.yfUnit === "无" ? "selected" : ""}>无</option></select></td>
        <td><input type="number" step="0.01" name="costAmount" value="${esc(d.costAmount ?? "")}"></td>
        <td><input type="number" step="0.01" name="incomePrice" value="${esc(d.incomePrice ?? "")}"></td>
        <td><select name="incomeCurrency"><option ${d.incomeCurrency === "USD" ? "selected" : ""}>USD</option><option ${d.incomeCurrency === "RMB" ? "selected" : ""}>RMB</option><option ${d.incomeCurrency === "PKR" ? "selected" : ""}>PKR</option></select></td>
        <td><select name="ysUnit"><option ${d.ysUnit === "按数量" ? "selected" : ""}>按数量</option><option ${d.ysUnit === "按重量" ? "selected" : ""}>按重量</option><option ${d.ysUnit === "按体积" ? "selected" : ""}>按体积</option><option ${d.ysUnit === "无" ? "selected" : ""}>无</option></select></td>
        <td><input type="number" step="0.01" name="incomeAmount" value="${esc(d.incomeAmount ?? "")}"></td>
        <td>${d.inOrderDetailUuid ? `<button type="button" class="secondary" onclick="openInboundCollection('${esc(d.inOrderDetailUuid)}')">收款</button> <button type="button" class="secondary" onclick="openInventoryAdjust('${esc(d.inOrderDetailUuid)}','${esc(d.packageQty)}','${esc(d.stockPackageQty)}','${esc(d.qty)}','${esc(d.stockQty)}','${esc(d.weight)}','${esc(d.volume)}')">库存修正</button> <button type="button" class="secondary" onclick="openAttachment('detail','${esc(d.inOrderDetailUuid)}',false)">附件</button> <button type="button" class="danger" onclick="deleteInDetail('${esc(d.inOrderDetailUuid)}')">删除</button>` : `<button type="button" class="danger" onclick="this.closest('tr').remove()">删除</button>`}</td>
    </tr>`;
}

function addInDetailRow() {
    $("#inDetailRows").insertAdjacentHTML("beforeend", inDetailRow({}, [], $("#inDetailRows").dataset.packageOptions || ""));
    bindInboundAmountSync();
}

function bindInboundAmountSync() {
    document.querySelectorAll("#inDetailRows tr").forEach(syncAmountFields);
    document.querySelectorAll("#inDetailRows tr input, #inDetailRows tr select").forEach(input => {
        input.oninput = () => syncAmountFields(input.closest("tr"));
        input.onchange = () => syncAmountFields(input.closest("tr"));
    });
}

function syncAmountFields(tr) {
    if (!tr) return;
    syncAmountField(tr, "cost");
    syncAmountField(tr, "income");
}

function syncAmountField(tr, prefix) {
    const price = Number($(`input[name=${prefix}Price]`, tr)?.value || 0);
    const unitName = prefix === "cost" ? "yfUnit" : "ysUnit";
    const amountName = prefix === "cost" ? "costAmount" : "incomeAmount";
    const unit = $(`select[name=${unitName}]`, tr)?.value || "";
    const amount = $(`input[name=${amountName}]`, tr);
    if (!amount) return;
    const manual = !unit || unit === "无";
    amount.readOnly = !manual;
    if (manual) return;
    const base = unit === "按重量" ? Number($("input[name=weight]", tr)?.value || 0)
        : unit === "按体积" ? Number($("input[name=volume]", tr)?.value || 0)
            : Number($("input[name=qty]", tr)?.value || 0);
    amount.value = Math.round(price * base * 10) / 10;
}

async function saveInbound(form) {
    const fd = new FormData(form);
    const rows = [...$("#inDetailRows").querySelectorAll("tr")].map(tr => {
        const item = {};
        tr.querySelectorAll("input, select").forEach(input => item[input.name] = input.value);
        return item;
    });
    const body = Object.fromEntries(fd.entries());
    body.details = rows;
    await api("api/inbound/orders", {method: "POST", body: JSON.stringify(body)});
    closeModal();
    await renderInbound();
}

async function submitInbound(orderNo) {
    if (!orderNo) return;
    await api(`api/inbound/orders/${orderNo}/submit`, {method: "POST"});
    closeModal();
    await renderInbound();
}

async function cancelInbound(orderNo) {
    if (!orderNo) return;
    await api(`api/inbound/orders/${orderNo}/cancel`, {method: "POST"});
    closeModal();
    await renderInbound();
}

function openDelivery(orderNo) {
    modal("完成派送", `
        <form id="deliveryForm">
            <div class="grid"><div class="field"><label>派送类型</label><select name="sendType"><option>自提</option><option>派送</option></select></div><div class="field"><label>派送单号</label><input name="sendNo"></div></div>
            <div class="actions"><button>保存</button><span class="msg"></span></div>
        </form>
    `);
    bindSubmit("#deliveryForm", async form => {
        await api(`api/inbound/orders/${orderNo}/delivery`, {method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
        closeModal();
        await editInbound(orderNo);
    });
}

async function deleteInbound(orderNo) {
    if (!confirm("确认删除该入库单？")) return;
    await api(`api/inbound/orders/${orderNo}`, {method: "DELETE"});
    closeModal();
    await renderInbound();
}

function openInboundImport() {
    modal("入库导入", `
        <form id="inImport" enctype="multipart/form-data">
            <input type="file" name="file" accept=".xlsx,.xls">
            <div class="actions"><button>导入</button><span class="msg"></span></div>
        </form>
    `);
    bindSubmit("#inImport", async form => {
        const data = new FormData(form);
        await api("api/inbound/import", {method: "POST", body: data});
    });
}

function openInboundCollection(detailUuid) {
    modal("完成收款", `<form id="inCollection">
        <input type="hidden" name="detailUuid" value="${esc(detailUuid)}">
        <div class="grid"><div class="field"><label>实收单价</label><input type="number" step="0.01" name="recePrice"></div><div class="field"><label>币制</label><select name="receCurrency"><option>USD</option><option>RMB</option><option>PKR</option></select></div><div class="field"><label>实收金额</label><input type="number" step="0.01" name="receAmount"></div></div>
        <div class="actions"><button>保存</button><button type="button" class="secondary" onclick="openAttachment('detail','${esc(detailUuid)}',true)">上传附件</button><span class="msg"></span></div>
    </form>`);
    bindSubmit("#inCollection", async form => {
        await api("api/inbound/collection/finish", {method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
        closeModal();
        await renderInbound();
    });
}

function openInventoryAdjust(detailUuid, packageQty, stockPackageQty, qty, stockQty, weight, volume) {
    modal("库存修正", `<form id="inventoryAdjust">
        <input type="hidden" name="detailUuid" value="${esc(detailUuid)}">
        <div class="grid">
            <div class="field"><label>件数</label><input type="number" step="0.01" name="packageQty" value="${esc(packageQty)}"></div>
            <div class="field"><label>库存件数</label><input type="number" step="0.01" name="stockPackageQty" value="${esc(stockPackageQty)}"></div>
            <div class="field"><label>数量</label><input type="number" step="0.01" name="qty" value="${esc(qty)}"></div>
            <div class="field"><label>库存数量</label><input type="number" step="0.01" name="stockQty" value="${esc(stockQty)}"></div>
            <div class="field"><label>重量</label><input type="number" step="0.01" name="weight" value="${esc(weight)}"></div>
            <div class="field"><label>体积</label><input type="number" step="0.01" name="volume" value="${esc(volume)}"></div>
        </div>
        <div class="actions"><button>保存</button><span class="msg"></span></div>
    </form>`);
    bindSubmit("#inventoryAdjust", async form => {
        await api("api/inbound/inventory/adjust", {method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
        closeModal();
        await renderInbound();
    });
}

async function deleteInDetail(detailUuid) {
    if (!confirm("确认删除该入库明细及附件？")) return;
    await api(`api/inbound/details?detailUuid=${encodeURIComponent(detailUuid)}`, {method: "DELETE"});
    closeModal();
    await renderInbound();
}

async function openRelatedOutbounds(orderNo) {
    const rows = await api(`api/inbound/orders/${encodeURIComponent(orderNo)}/outbounds`);
    modal("关联出库信息", `
        <table><thead><tr><th>出库单号</th><th>SO号</th><th>状态</th><th>装柜日期</th><th>柜号</th><th>节点</th><th>跟踪单号</th><th>品名</th><th>件数</th><th>数量</th></tr></thead>
        <tbody>${html(rows.map(row => `<tr><td>${esc(row.outOrderNo)}</td><td>${esc(row.soNo)}</td><td>${esc(row.status)}</td><td>${esc(row.loadingDate)}</td><td>${esc(row.containerNo)}</td><td>${esc(row.wljd)}</td><td>${esc(row.trackNo)}</td><td>${esc(row.productName)}</td><td>${esc(row.outPackageQty)}</td><td>${esc(row.outQty)}</td></tr>`))}</tbody></table>
    `);
}

async function openInboundNodes(orderNo) {
    const rows = await api(`api/inbound/orders/${encodeURIComponent(orderNo)}/nodes`);
    modal("物流节点详情", `
        <table><thead><tr><th>节点</th><th>时间</th><th>ATD</th><th>ETA</th><th>ATA</th></tr></thead>
        <tbody>${html(rows.map(row => `<tr><td>${esc(row.operateDesc)}</td><td>${esc(row.operateTime)}</td><td>${esc(row.atd)}</td><td>${esc(row.eta)}</td><td>${esc(row.ata)}</td></tr>`))}</tbody></table>
    `);
}

async function renderOutbound() {
    setView(`
        <section class="panel">
            <form id="outSearch" class="toolbar">
                <div class="field"><label>SO号</label><input name="soNo"></div>
                <div class="field"><label>出库单号</label><input name="orderNo"></div>
                <div class="field"><label>跟踪单号</label><input name="trackNo"></div>
                <div class="field"><label>柜号</label><input name="containerNo"></div>
                <div class="field small"><label>状态</label><select name="status"><option>全部</option><option>草稿</option><option>有效</option></select></div>
                <div class="field"><label>报关行</label><input name="customsBroker"></div>
                <div class="field"><label>出库口岸</label><input name="exportPort"></div>
                <div class="field small"><label>物流节点</label><select name="node"><option>全部</option><option>出库</option><option>完成报关</option><option>完成交重</option><option>启运</option><option>到港</option><option>清关启动</option><option>清关完成</option><option>到仓</option></select></div>
                <div class="field"><label>装柜开始</label><input type="date" name="loadingStart"></div>
                <div class="field"><label>装柜结束</label><input type="date" name="loadingEnd"></div>
                <div class="field"><label>ATD开始</label><input type="date" name="atdStart"></div>
                <div class="field"><label>ATD结束</label><input type="date" name="atdEnd"></div>
                <div class="field"><label>ETA开始</label><input type="date" name="etaStart"></div>
                <div class="field"><label>ETA结束</label><input type="date" name="etaEnd"></div>
                <div class="field"><label>ATA开始</label><input type="date" name="ataStart"></div>
                <div class="field"><label>ATA结束</label><input type="date" name="ataEnd"></div>
                <button>查询</button>
                <button type="button" class="secondary" onclick="newOutbound()">新增</button>
                <span class="msg"></span>
            </form>
            <div id="outTable"></div>
        </section>
    `);
    bindSubmit("#outSearch", form => loadOutbound(qs(form)));
    await loadOutbound("");
}

async function loadOutbound(query) {
    const page = await api(`api/outbound/orders?${query}`);
    $("#outTable").innerHTML = `
        <table>
            <thead><tr><th>出库单号</th><th>SO号</th><th>状态</th><th>装柜日期</th><th>柜号</th><th>车牌</th><th>节点</th><th>件数</th><th>费用</th><th>操作</th></tr></thead>
            <tbody>${html(page.items.map(row => `
                <tr><td>${esc(row.outOrderNo)}</td><td>${esc(row.soNo)}</td><td><span class="status ${row.status === "有效" ? "ok" : "warn"}">${esc(row.status)}</span></td>
                <td>${esc(row.loadingDate)}</td><td>${esc(row.containerNo)}</td><td>${esc(row.carPlate)}</td><td>${esc(row.wljd)}</td><td>${esc(row.totalPackageQty)}</td><td>${money(row.totalCost)}</td>
                <td><button class="secondary" onclick="editOutbound('${esc(row.outOrderNo)}')">编辑</button></td></tr>
            `))}</tbody>
        </table>
        <div class="summary"><span>总数：${page.total}</span><span>总件数：${page.totalSummary.totalPackageQty || 0}</span><span>总费用：${money(page.totalSummary.totalCost)}</span></div>
        ${pager(page, "#outSearch", "loadOutbound")}
    `;
}

function newOutbound() {
    editOutbound("");
}

async function editOutbound(orderNo) {
    const order = orderNo ? await api(`api/outbound/orders/${orderNo}`) : {};
    const [customsBrokers, exportPorts] = await Promise.all([
        api("api/master/dicts/enabled?type=customs_broker"),
        api("api/master/dicts/enabled?type=export_port")
    ]);
    const customsOptions = dictOptions(customsBrokers, order.customsBroker, order.customsBroker) || `<option value="">请先维护报关行字典</option>`;
    const portOptions = dictOptions(exportPorts, order.exportPort, order.exportPort) || `<option value="">请先维护口岸字典</option>`;
    modal(orderNo ? `出库单 ${orderNo}` : "新增出库单", `
        <form id="outEdit">
            <div class="grid">
                <div class="field"><label>出库单号</label><input name="outOrderNo" value="${esc(order.outOrderNo)}" readonly></div>
                <div class="field"><label>SO号</label><input name="soNo" value="${esc(order.soNo)}"></div>
                <div class="field"><label>装柜日期</label><input type="date" name="loadingDate" value="${esc(order.loadingDate || today())}"></div>
                <div class="field"><label>柜号</label><input name="containerNo" value="${esc(order.containerNo)}"></div>
                <div class="field"><label>车牌</label><input name="carPlate" value="${esc(order.carPlate)}"></div>
                <div class="field"><label>报关行</label><select name="customsBroker">${customsOptions}</select></div>
                <div class="field"><label>出库口岸</label><select name="exportPort">${portOptions}</select></div>
                <div class="field"><label>仓库</label><input value="${esc(order.warehouseCode || "WH001")}" readonly></div>
            </div>
            <div class="actions">
                <button>保存</button>
                ${orderNo ? `<button type="button" onclick="submitOutbound('${esc(order.outOrderNo)}')">提交</button>
                <button type="button" class="secondary" onclick="cancelOutbound('${esc(order.outOrderNo)}')">取消提交</button>
                <button type="button" class="secondary" onclick="openStockPicker('${esc(order.outOrderNo)}')">添加明细</button>
                <button type="button" class="secondary" onclick="openCost('${esc(order.outOrderNo)}')">费用</button>
                <button type="button" class="secondary" onclick="openNode('${esc(order.outOrderNo)}')">物流节点</button>
                <a class="button-link" href="api/outbound/orders/${esc(order.outOrderNo)}/export-details">导出明细</a>
                <a class="button-link" href="api/outbound/orders/${esc(order.outOrderNo)}/export-accounts">导出账款</a>
                <button type="button" class="danger" onclick="deleteOutbound('${esc(order.outOrderNo)}')">删除</button>` : ""}
            </div>
            <div class="msg"></div>
        </form>
        <h3>出库明细</h3>
        <div>${detailTable(order.details || [])}</div>
        <h3>费用</h3>
        <div>${costTable(order.costs || [])}</div>
        <h3>操作记录</h3>
        <div>${logTable(order.logs || [])}</div>
        <h3>应收账款</h3>
        <div>${receivableTable(order.receivables || [])}</div>
    `);
    bindSubmit("#outEdit", saveOutbound);
}

function detailTable(rows) {
    return `<table><thead><tr><th>跟踪单号</th><th>品名</th><th>件数</th><th>包装</th><th>重量</th><th>体积</th><th>数量</th><th>附件</th><th>操作</th></tr></thead><tbody>${html(rows.map(row => `<tr><td>${esc(row.trackNo)}</td><td>${esc(row.productName)}</td><td>${esc(row.outPackageQty)}</td><td>${esc(row.packageType)}</td><td>${esc(row.weight)}</td><td>${esc(row.volume)}</td><td>${esc(row.outQty)}</td><td>${esc(row.attachCount)}</td><td><button class="secondary" onclick="openOutDetailEdit('${esc(row.outOrderDetailUuid)}','${esc(row.outPackageQty)}','${esc(row.outQty)}')">编辑</button> <button class="danger" onclick="deleteOutDetail('${esc(row.outOrderDetailUuid)}')">删除</button></td></tr>`))}</tbody></table>`;
}

function costTable(rows) {
    return `<table><thead><tr><th>费用</th><th>金额</th><th>备注</th><th>附件</th><th>操作</th></tr></thead><tbody>${html(rows.map(row => `<tr><td>${esc(row.costName)}</td><td>${money(row.amount)}</td><td>${esc(row.remark)}</td><td>${esc(row.attachCount)}</td><td><button class="secondary" onclick="openCostEdit('${esc(row.costOrderUuid)}','${esc(row.costName)}','${esc(row.amount)}','${esc(row.remark)}')">编辑</button> <button class="secondary" onclick="openAttachment('cost','${esc(row.costOrderUuid)}',true)">附件</button> <button class="danger" onclick="deleteCost('${esc(row.costOrderUuid)}')">删除</button></td></tr>`))}</tbody></table>`;
}

function logTable(rows) {
    return `<table><thead><tr><th>时间</th><th>操作人</th><th>节点</th><th>备注</th></tr></thead><tbody>${html(rows.map(row => `<tr><td>${esc(row.operateTime)}</td><td>${esc(row.operateContact)}</td><td>${esc(row.operateDesc)}</td><td>${esc(row.remark)}</td></tr>`))}</tbody></table>`;
}

function receivableTable(rows) {
    return `<table><thead><tr><th>跟踪单号</th><th>品名</th><th>应付</th><th>应收</th><th>实收</th><th>实付</th><th>操作</th></tr></thead><tbody>${html(rows.map(row => `<tr>
        <td>${esc(row.trackNo)}</td><td>${esc(row.productName)}</td><td>${money(row.costAmount)}</td><td>${money(row.incomeAmount)}</td><td>${money(row.receAmount)}</td><td>${money(row.sfAmount)}</td>
        <td><button class="secondary" onclick="openReceivable('${esc(row.outOrderDetailUuid)}','receivable')">实收</button> <button class="secondary" onclick="openReceivable('${esc(row.outOrderDetailUuid)}','payable')">实付</button> <button class="secondary" onclick="openAttachment('detail','${esc(row.inOrderDetailUuid)}',true)">收款附件</button></td>
    </tr>`))}</tbody></table>`;
}

async function saveOutbound(form) {
    const body = Object.fromEntries(new FormData(form).entries());
    const orderNo = await api("api/outbound/orders", {method: "POST", body: JSON.stringify(body)});
    closeModal();
    await editOutbound(orderNo);
}

function openOutDetailEdit(detailUuid, packages, qty) {
    modal("编辑出库明细", `
        <form id="outDetailEdit">
            <input type="hidden" name="outOrderDetailUuid" value="${esc(detailUuid)}">
            <div class="grid"><div class="field"><label>出库件数</label><input type="number" step="0.01" name="outPackageQty" value="${esc(packages)}"></div><div class="field"><label>出库数量</label><input type="number" step="0.01" name="outQty" value="${esc(qty)}"></div></div>
            <div class="actions"><button>保存</button><span class="msg"></span></div>
        </form>
    `);
    bindSubmit("#outDetailEdit", async form => {
        await api("api/outbound/details", {method: "PATCH", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
        closeModal();
        await renderOutbound();
    });
}

async function deleteOutDetail(detailUuid) {
    if (!confirm("确认删除该出库明细并回补库存？")) return;
    await api(`api/outbound/details?detailUuid=${encodeURIComponent(detailUuid)}`, {method: "DELETE"});
    closeModal();
    await renderOutbound();
}

async function submitOutbound(orderNo) {
    await api(`api/outbound/orders/${orderNo}/submit`, {method: "POST"});
    closeModal();
    await renderOutbound();
}

async function cancelOutbound(orderNo) {
    await api(`api/outbound/orders/${orderNo}/cancel`, {method: "POST"});
    closeModal();
    await renderOutbound();
}

async function deleteOutbound(orderNo) {
    if (!confirm("确认删除该出库单并回补库存？")) return;
    await api(`api/outbound/orders/${orderNo}`, {method: "DELETE"});
    closeModal();
    await renderOutbound();
}

async function openStockPicker(orderNo, query = "") {
    const rows = await api(`api/outbound/inventory${query ? `?${query}` : ""}`);
    modal("选择库存明细", `
        <form id="stockPick">
            <div class="toolbar">
                <div class="field"><label>跟踪单号</label><input name="trackNo"></div>
                <div class="field"><label>客户</label><input name="customer"></div>
                <div class="field"><label>销售</label><input name="sales"></div>
                <div class="field"><label>入库开始</label><input type="date" name="inStartDate"></div>
                <div class="field"><label>入库结束</label><input type="date" name="inEndDate"></div>
                <button type="button" class="secondary" onclick="openStockPicker('${esc(orderNo)}', qs($('#stockPick')))">筛选</button>
            </div>
            <table><thead><tr><th></th><th>入库单号</th><th>跟踪单号</th><th>客户</th><th>销售</th><th>入库日期</th><th>品名</th><th>库存件数</th><th>出库件数</th><th>库存数量</th><th>出库数量</th></tr></thead>
            <tbody>${html(rows.map(row => {
                const noAmount = Number(row.costAmount || 0) <= 0 && Number(row.incomeAmount || 0) <= 0;
                return `<tr>
                <td>${noAmount ? "<span class=\"status warn\">缺少金额</span>" : `<input type="checkbox" name="pick" value="${esc(row.inOrderDetailUuid)}">`}</td><td>${esc(row.inOrderNo)}</td><td>${esc(row.trackNo)}</td><td>${esc(row.customer)}</td><td>${esc(row.sales)}</td><td>${esc(row.inDate)}</td><td>${esc(row.productName)}</td><td>${esc(row.stockPackageQty)}</td>
                <td><input type="number" step="0.01" min="0" max="${esc(row.stockPackageQty)}" data-field="outPackageQty" data-id="${esc(row.inOrderDetailUuid)}" value="${esc(row.stockPackageQty)}"></td><td>${esc(row.stockQty)}</td>
                <td><input type="number" step="0.01" min="0" max="${esc(row.stockQty)}" data-field="outQty" data-id="${esc(row.inOrderDetailUuid)}" value="${esc(row.stockQty)}"></td>
            </tr>`;
            }))}</tbody></table>
            <div class="actions"><button>添加</button><span class="msg"></span></div>
        </form>
    `);
    bindSubmit("#stockPick", async form => {
        const picked = [...form.querySelectorAll("input[name=pick]:checked")].map(input => ({
            inOrderDetailUuid: input.value,
            outPackageQty: $(`input[data-field=outPackageQty][data-id="${CSS.escape(input.value)}"]`).value,
            outQty: $(`input[data-field=outQty][data-id="${CSS.escape(input.value)}"]`).value
        }));
        await api(`api/outbound/orders/${orderNo}/details`, {method: "POST", body: JSON.stringify({items: picked})});
        closeModal();
        await editOutbound(orderNo);
    });
}

async function openCost(orderNo) {
    const items = await api("api/master/dicts/enabled?type=cost_item");
    const options = items.length ? items.map(item => `<option value="${esc(item.dictName)}">${esc(item.dictName)}</option>`).join("") : `<option value="">请先维护费用字典</option>`;
    modal("新增费用", `
        <form id="costForm">
            <div class="grid"><div class="field"><label>费用名称</label><select name="costName">${options}</select></div><div class="field"><label>金额</label><input type="number" step="0.01" name="amount"></div><div class="field wide"><label>备注</label><input name="remark"></div></div>
            <div class="actions"><button>保存</button><span class="msg"></span></div>
        </form>
    `);
    bindSubmit("#costForm", async form => {
        await api(`api/outbound/orders/${orderNo}/costs`, {method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
        closeModal();
        await editOutbound(orderNo);
    });
}

async function openCostEdit(costUuid, costName, amount, remark) {
    const items = await api("api/master/dicts/enabled?type=cost_item");
    const selected = items.some(item => item.dictName === costName) ? costName : "";
    const options = `${selected ? "" : `<option value="${esc(costName)}">${esc(costName)}</option>`}${items.map(item => `<option value="${esc(item.dictName)}" ${item.dictName === selected ? "selected" : ""}>${esc(item.dictName)}</option>`).join("")}`;
    modal("编辑费用", `
        <form id="costEditForm">
            <div class="grid"><div class="field"><label>费用名称</label><select name="costName">${options}</select></div><div class="field"><label>金额</label><input type="number" step="0.01" name="amount" value="${esc(amount)}"></div><div class="field wide"><label>备注</label><input name="remark" value="${esc(remark)}"></div></div>
            <div class="actions"><button>保存</button><span class="msg"></span></div>
        </form>
    `);
    bindSubmit("#costEditForm", async form => {
        await api(`api/outbound/costs?costUuid=${encodeURIComponent(costUuid)}`, {method: "PATCH", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
        closeModal();
        await renderOutbound();
    });
}

async function deleteCost(costUuid) {
    if (!confirm("确认删除该费用及附件？")) return;
    await api(`api/outbound/costs?costUuid=${encodeURIComponent(costUuid)}`, {method: "DELETE"});
    closeModal();
    await renderOutbound();
}

function openNode(orderNo) {
    modal("物流节点", `
        <form id="nodeForm">
            <div class="grid">
                <div class="field"><label>节点</label><select name="node"><option>出库</option><option>完成报关</option><option>完成交重</option><option>启运</option><option>到港</option><option>清关启动</option><option>清关完成</option><option>到仓</option></select></div>
                <div class="field"><label>节点日期</label><input type="date" name="operateDate" value="${today()}"></div>
                <div class="field"><label>ATD</label><input type="date" name="atd"></div>
                <div class="field"><label>ETA</label><input type="date" name="eta"></div>
                <div class="field"><label>ATA</label><input type="date" name="ata"></div>
                <div class="field wide"><label>备注</label><input name="remark"></div>
            </div>
            <div class="actions"><button>保存</button><span class="msg"></span></div>
        </form>
    `);
    bindSubmit("#nodeForm", async form => {
        await api(`api/outbound/orders/${orderNo}/nodes`, {method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
        closeModal();
        await editOutbound(orderNo);
    });
}

function openReceivable(detailUuid, mode) {
    const title = mode === "receivable" ? "录入实收" : "录入实付";
    const priceName = mode === "receivable" ? "recePrice" : "sfPrice";
    const currencyName = mode === "receivable" ? "receCurrency" : "sfCurrency";
    const amountName = mode === "receivable" ? "receAmount" : "sfAmount";
    modal(title, `
        <form id="moneyForm">
            <input type="hidden" name="mode" value="${mode}">
            <input type="hidden" name="outOrderDetailUuid" value="${esc(detailUuid)}">
            <div class="grid"><div class="field"><label>单价</label><input type="number" step="0.01" name="${priceName}"></div><div class="field"><label>币制</label><select name="${currencyName}"><option>USD</option><option>RMB</option><option>PKR</option></select></div><div class="field"><label>金额</label><input type="number" step="0.01" name="${amountName}"></div></div>
            <div class="actions"><button>保存</button><span class="msg"></span></div>
        </form>
    `);
    bindSubmit("#moneyForm", async form => {
        await api("api/outbound/receivables", {method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
        closeModal();
        await renderOutbound();
    });
}

async function renderMaster() {
    setView(`
        <section class="panel">
            <div class="actions">
                <button class="secondary" onclick="loadCustomers()">客户/销售</button>
                <button class="secondary" onclick="loadDicts()">字典</button>
            </div>
            <div id="masterBox"></div>
        </section>
    `);
    await loadCustomers();
}

async function loadCustomers(query = "") {
    const page = await api(`api/master/customers?${query}`);
    $("#masterBox").innerHTML = `<h3>客户/销售</h3>
        <form id="customerSearch" class="toolbar">
            <div class="field"><label>代码</label><input name="code"></div>
            <div class="field"><label>中文名</label><input name="cnName"></div>
            <div class="field"><label>英文名</label><input name="enName"></div>
            <div class="field small"><label>类型</label><select name="type"><option>全部</option><option>Customer</option><option>Sale</option></select></div>
            <button>查询</button><button type="button" onclick="openCustomer()">新增</button><span class="msg"></span>
        </form>
        <table><thead><tr><th>代码</th><th>中文名</th><th>英文名</th><th>类型</th><th>状态</th><th>上级代码</th><th>操作</th></tr></thead><tbody>${html(page.items.map(row => `<tr><td>${esc(row.customerCode)}</td><td>${esc(row.customerCnName)}</td><td>${esc(row.customerEnName)}</td><td>${esc(row.customerType)}</td><td>${esc(row.status)}</td><td>${esc(row.superiorCode)}</td><td><button class="secondary" onclick="openCustomer('${esc(row.customerCode)}','${esc(row.customerCnName)}','${esc(row.customerEnName)}','${esc(row.customerType)}','${esc(row.superiorCode)}')">编辑</button> <button class="danger" onclick="setCustomerStatus('${esc(row.customerCode)}','禁用')">禁用</button> <button class="secondary" onclick="setCustomerStatus('${esc(row.customerCode)}','启用')">启用</button></td></tr>`))}</tbody></table>
        ${pager(page, "#customerSearch", "loadCustomers")}`;
    fillForm("#customerSearch", query);
    bindSubmit("#customerSearch", form => loadCustomers(qs(form)));
}

async function renderUsers() {
    setView(`<section class="panel"><div id="usersBox"></div></section>`);
    await loadUsers("");
}

async function loadUsers(query = "") {
    const page = await api(`api/master/users?${query}`);
    const box = $("#usersBox") || $("#masterBox");
    box.innerHTML = `<h3>账号</h3>
        <form id="userSearch" class="toolbar">
            <div class="field"><label>账号代码</label><input name="username"></div>
            <div class="field"><label>账号名称</label><input name="name"></div>
            <div class="field small"><label>账号类型</label><select name="userType"><option>全部</option><option>员工</option><option>管理员</option></select></div>
            <div class="field small"><label>状态</label><select name="status"><option>全部</option><option>启用</option><option>禁用</option></select></div>
            <button>查询</button><button type="button" onclick="openUser()">新增</button><span class="msg"></span>
        </form>
        <table><thead><tr><th>账号</th><th>姓名</th><th>电话</th><th>类型</th><th>状态</th><th>销售代码</th><th>操作</th></tr></thead><tbody>${html(page.items.map(row => `<tr><td>${esc(row.username)}</td><td>${esc(row.name)}</td><td>${esc(row.tel)}</td><td>${esc(row.usertype)}</td><td>${esc(row.status)}</td><td>${esc(row.sealCode)}</td><td><button class="secondary" onclick="openUser('${esc(row.username)}','${esc(row.name)}','${esc(row.tel)}','${esc(row.usertype)}','${esc(row.status)}','${esc(row.sealCode)}')">编辑</button> <button class="danger" onclick="setUserStatus('${esc(row.username)}','禁用')">禁用</button> <button class="secondary" onclick="setUserStatus('${esc(row.username)}','启用')">启用</button></td></tr>`))}</tbody></table>
        ${pager(page, "#userSearch", "loadUsers")}`;
    fillForm("#userSearch", query);
    bindSubmit("#userSearch", form => loadUsers(qs(form)));
}

async function loadDicts() {
    const type = prompt("字典类型", state.currentDictType || "package_type");
    if (!type) return;
    state.currentDictType = type;
    const rows = await api(`api/master/dicts?type=${encodeURIComponent(type)}`);
    $("#masterBox").innerHTML = `<h3>字典：${esc(type)}</h3><div class="actions"><button onclick="openDict('${esc(type)}')">新增</button></div><table><thead><tr><th>代码</th><th>名称</th><th>排序</th><th>状态</th><th>备注</th><th>操作</th></tr></thead><tbody>${html(rows.map(row => `<tr><td>${esc(row.dictCode)}</td><td>${esc(row.dictName)}</td><td>${esc(row.sortOrder)}</td><td>${esc(row.status)}</td><td>${esc(row.remark)}</td><td><button class="secondary" onclick="openDict('${esc(row.dictType)}','${esc(row.dictCode)}','${esc(row.dictName)}','${esc(row.sortOrder)}','${esc(row.status)}','${esc(row.remark)}')">编辑</button> <button class="danger" onclick="deleteDict('${esc(row.dictType)}','${esc(row.dictCode)}')">删除</button> <button class="secondary" onclick="setDictStatus('${esc(row.dictType)}','${esc(row.dictCode)}','1')">启用</button> <button class="secondary" onclick="setDictStatus('${esc(row.dictType)}','${esc(row.dictCode)}','0')">禁用</button></td></tr>`))}</tbody></table>`;
}

function openCustomer(code = "", cnName = "", enName = "", type = "Customer", superiorCode = "") {
    modal("客户/销售", `<form id="customerForm"><div class="grid">
        <div class="field"><label>代码</label><input name="customerCode" value="${esc(code)}"></div>
        <div class="field"><label>中文名</label><input name="customerCnName" value="${esc(cnName)}"></div>
        <div class="field"><label>英文名</label><input name="customerEnName" value="${esc(enName)}"></div>
        <div class="field"><label>类型</label><select name="customerType"><option ${type === "Customer" ? "selected" : ""}>Customer</option><option ${type === "Sale" ? "selected" : ""}>Sale</option></select></div>
        <div class="field"><label>上级代码</label><input name="superiorCode" value="${esc(superiorCode)}"></div>
    </div><div class="actions"><button>保存</button><span class="msg"></span></div></form>`);
    bindSubmit("#customerForm", async form => {
        await api("api/master/customers", {method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
        closeModal();
        await loadCustomers();
    });
}

async function setCustomerStatus(code, status) {
    await api("api/master/customers", {method: "PATCH", body: JSON.stringify({code, status})});
    await loadCustomers();
}

function openUser(username = "", name = "", tel = "", userType = "员工", status = "启用", sealCode = "") {
    modal("账号", `<form id="userForm"><div class="grid">
        <div class="field"><label>账号</label><input name="username" value="${esc(username)}"></div>
        <div class="field"><label>密码</label><input type="password" name="password" placeholder="留空不修改"></div>
        <div class="field"><label>姓名</label><input name="name" value="${esc(name)}"></div>
        <div class="field"><label>电话</label><input name="tel" value="${esc(tel)}"></div>
        <div class="field"><label>类型</label><select name="userType"><option ${userType === "员工" ? "selected" : ""}>员工</option><option ${userType === "管理员" ? "selected" : ""}>管理员</option></select></div>
        <div class="field"><label>状态</label><select name="status"><option ${status === "启用" ? "selected" : ""}>启用</option><option ${status === "禁用" ? "selected" : ""}>禁用</option></select></div>
        <div class="field"><label>销售代码</label><input name="sealCode" value="${esc(sealCode)}"></div>
    </div><div class="actions"><button>保存</button><span class="msg"></span></div></form>`);
    bindSubmit("#userForm", async form => {
        await api("api/master/users", {method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
        closeModal();
        await loadUsers();
    });
}

async function setUserStatus(username, status) {
    await api("api/master/users", {method: "PATCH", body: JSON.stringify({username, status})});
    await loadUsers();
}

function openDict(dictType = "", dictCode = "", dictName = "", sortOrder = "0", status = "1", remark = "") {
    modal("字典", `<form id="dictForm"><div class="grid">
        <div class="field"><label>类型</label><input name="dictType" value="${esc(dictType)}"></div>
        <div class="field"><label>代码</label><input name="dictCode" value="${esc(dictCode)}"></div>
        <div class="field"><label>名称</label><input name="dictName" value="${esc(dictName)}"></div>
        <div class="field"><label>排序</label><input type="number" name="sortOrder" value="${esc(sortOrder)}"></div>
        <div class="field"><label>状态</label><select name="status"><option value="1" ${status === "1" ? "selected" : ""}>启用</option><option value="0" ${status === "0" ? "selected" : ""}>禁用</option></select></div>
        <div class="field wide"><label>备注</label><input name="remark" value="${esc(remark)}"></div>
    </div><div class="actions"><button>保存</button><span class="msg"></span></div></form>`);
    bindSubmit("#dictForm", async form => {
        await api("api/master/dicts", {method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
        closeModal();
        state.currentDictType = new FormData(form).get("dictType") || state.currentDictType;
        await reloadDicts();
    });
}

async function deleteDict(type, code) {
    if (!confirm("确认删除该字典？")) return;
    await api(`api/master/dicts?type=${encodeURIComponent(type)}&code=${encodeURIComponent(code)}`, {method: "DELETE"});
    state.currentDictType = type;
    await reloadDicts();
}

async function setDictStatus(dictType, dictCode, status) {
    await api("api/master/dicts", {method: "PATCH", body: JSON.stringify({dictType, dictCode, status})});
    state.currentDictType = dictType;
    await reloadDicts();
}

async function reloadDicts() {
    const type = state.currentDictType || "package_type";
    const rows = await api(`api/master/dicts?type=${encodeURIComponent(type)}`);
    $("#masterBox").innerHTML = `<h3>字典：${esc(type)}</h3><div class="actions"><button onclick="openDict('${esc(type)}')">新增</button></div><table><thead><tr><th>代码</th><th>名称</th><th>排序</th><th>状态</th><th>备注</th><th>操作</th></tr></thead><tbody>${html(rows.map(row => `<tr><td>${esc(row.dictCode)}</td><td>${esc(row.dictName)}</td><td>${esc(row.sortOrder)}</td><td>${esc(row.status)}</td><td>${esc(row.remark)}</td><td><button class="secondary" onclick="openDict('${esc(row.dictType)}','${esc(row.dictCode)}','${esc(row.dictName)}','${esc(row.sortOrder)}','${esc(row.status)}','${esc(row.remark)}')">编辑</button> <button class="danger" onclick="deleteDict('${esc(row.dictType)}','${esc(row.dictCode)}')">删除</button> <button class="secondary" onclick="setDictStatus('${esc(row.dictType)}','${esc(row.dictCode)}','1')">启用</button> <button class="secondary" onclick="setDictStatus('${esc(row.dictType)}','${esc(row.dictCode)}','0')">禁用</button></td></tr>`))}</tbody></table>`;
}

function openAttachment(ownerType, ownerId, replace = false) {
    modal("附件", `<form id="attachForm" enctype="multipart/form-data">
        <input type="file" name="file" accept="image/*">
        <div class="actions"><button>上传</button><span class="msg"></span></div>
        <div id="attachList"></div>
    </form>`);
    loadAttachments(ownerType, ownerId);
    bindSubmit("#attachForm", async form => {
        const data = new FormData(form);
        data.set("ownerType", ownerType);
        data.set("ownerId", ownerId);
        data.set("replace", replace ? "true" : "false");
        await api("api/attachments/", {method: "POST", body: data});
        await loadAttachments(ownerType, ownerId);
    });
}

async function loadAttachments(ownerType, ownerId) {
    const rows = await api(`api/attachments/?ownerType=${encodeURIComponent(ownerType)}&ownerId=${encodeURIComponent(ownerId)}`);
    $("#attachList").innerHTML = `<table><thead><tr><th>文件名</th><th>大小</th><th>上传人</th><th>操作</th></tr></thead><tbody>${html(rows.map(row => `<tr><td><a href="api/attachments/${esc(row.attachmentUuid)}/content" target="_blank">${esc(row.attachmentName)}</a></td><td>${esc(row.fileSize)}</td><td>${esc(row.uploader)}</td><td><button type="button" class="danger" onclick="deleteAttachment('${esc(row.attachmentUuid)}','${esc(ownerType)}','${esc(ownerId)}')">删除</button></td></tr>`))}</tbody></table>`;
}

async function deleteAttachment(uuid, ownerType, ownerId) {
    await api(`api/attachments/${encodeURIComponent(uuid)}`, {method: "DELETE"});
    await loadAttachments(ownerType, ownerId);
}
