const state = {
    user: null,
    tabs: [],
    active: null,
    currentDictType: "package_type",
    dictPageSize: 20
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
        const form = event.currentTarget;
        try {
            await handler(form);
        } catch (e) {
            $(".msg", form)?.replaceChildren(document.createTextNode(e.message));
        }
    });
}

function modal(title, content) {
    const box = $("#modal");
    box.innerHTML = `<div class="dialog"><div class="dialog-head"><h2>${esc(title)}</h2><button class="secondary" onclick="closeModal()">关闭</button></div><div class="dialog-body">${content}</div></div>`;
    box.classList.add("open");
}

function modal2(title, content) {
    const box = $("#modal2");
    box.innerHTML = `<div class="dialog"><div class="dialog-head"><h2>${esc(title)}</h2><button class="secondary" onclick="closeModal2()">关闭</button></div><div class="dialog-body">${content}</div></div>`;
    box.classList.add("open");
}

function closeModal() {
    $("#modal").classList.remove("open");
    $("#modal").innerHTML = "";
}

function closeModal2() {
    $("#modal2").classList.remove("open");
    $("#modal2").innerHTML = "";
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
    try {
        await api("api/auth/logout");
    } catch (e) {}
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
        openTab("master", "资料管理", renderMaster);
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
    const box = $("#inTable");
    if (!box) return;
    box.innerHTML = `
        <table>
            <thead><tr><th>入库单号</th><th>状态</th><th>物流节点</th><th>跟踪单号</th><th>入库日期</th><th>客户</th><th>销售</th><th>体积</th><th>重量</th><th>件数</th><th>库存件数</th><th>数量</th><th>库存数量</th><th>明细数</th><th>附件数</th><th>派送类型</th><th>派送单号</th><th>创建人</th><th>操作</th></tr></thead>
            <tbody>${html(page.items.map(row => `
                <tr>
                    <td>${esc(row.inOrderNo)}</td>
                    <td><span class="status ${row.status === "有效" ? "ok" : "warn"}">${esc(row.status)}</span></td>
                    <td>${esc(row.logisticsNode)}</td><td>${esc(row.trackNo)}</td><td>${esc(row.inDate)}</td><td>${esc(row.customer)}</td><td>${esc(row.sales)}</td>
                    <td>${esc(row.totalVolume)}</td><td>${esc(row.totalWeight)}</td><td>${esc(row.totalPackageQty)}</td><td>${esc(row.totalStockPackageQty)}</td>
                    <td>${esc(row.totalQty)}</td><td>${esc(row.totalStockQty)}</td><td>${esc(row.detailCount)}</td><td>${esc(row.attachCount)}</td>
                    <td>${esc(row.sendType)}</td><td>${esc(row.sendNo)}</td><td>${esc(row.creator)}</td>
                    <td><button class="secondary" onclick="editInbound('${esc(row.inOrderNo)}')">编辑</button> <button class="secondary" onclick="openRelatedOutbounds('${esc(row.inOrderNo)}')">出库信息</button> <button class="secondary" onclick="openInLog('${esc(row.inOrderNo)}')">查看操作日志</button></td>
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
    const customers = await api("api/master/customers?pageSize=1000");
    const salesUsers = await api("api/master/users?pageSize=1000&userType=员工");
    const isNew = !orderNo;
    const customerOptions = customers.items.map(c => `<option value="${esc(c.customerCnName)}" ${order.customer === c.customerCnName ? "selected" : ""}>${esc(c.customerCnName)}</option>`).join("");
    const salesOptions = salesUsers.items.map(u => `<option value="${esc(u.name)}" ${order.sales === u.name ? "selected" : ""}>${esc(u.name)}</option>`).join("");
    modal(orderNo ? `入库单 ${orderNo}` : "新增入库单", `
        <form id="inEdit">
            <div class="grid">
                ${orderNo ? `<div class="field"><label>入库单号</label><input name="inOrderNo" value="${esc(order.inOrderNo)}" readonly></div>` : `<input type="hidden" name="inOrderNo" value="">`}
                <div class="field"><label>跟踪单号</label><input name="trackNo" value="${esc(order.trackNo)}"></div>
                <div class="field"><label>客户</label><select name="customer"><option value="">请选择客户</option>${customerOptions}</select></div>
                <div class="field"><label>销售</label><select name="sales"><option value="">请选择销售</option>${salesOptions}</select></div>
                <div class="field"><label>入库日期</label><input type="date" name="inDate" value="${esc(order.inDate || today())}"></div>
                ${orderNo ? `<div class="field"><label>派送类型</label><input name="sendType" value="${esc(order.sendType)}" readonly class="readonly-field"></div>
                <div class="field"><label>派送单号</label><input name="sendNo" value="${esc(order.sendNo)}" readonly class="readonly-field"></div>` : ""}
            </div>
            ${orderNo ? `<h3>明细</h3><div id="inDetails"></div>` : ""}
            <div class="actions">
                ${isNew ? `<button>保存</button>` : `
                <button type="button" class="secondary" onclick="addInDetailRow()">新增明细</button>
                ${order.status === "草稿" ? `<button type="button" class="secondary" onclick="copyDetailRow()">复制明细</button>` : ""}
                ${order.status === "草稿" ? `<button>保存</button><button type="button" onclick="submitInbound('${esc(order.inOrderNo)}')">提交</button>` : ""}
                ${order.status === "有效" ? `<button type="button" class="secondary" onclick="cancelInbound('${esc(order.inOrderNo)}')">取消提交</button>` : ""}
                ${order.status === "草稿" ? `<button type="button" class="secondary" onclick="openInboundImport()">导入</button>` : ""}
                ${order.status === "有效" ? `<button type="button" class="secondary" onclick="openDelivery('${esc(order.inOrderNo)}')">完成派送</button>` : ""}
                <button type="button" class="danger" onclick="deleteInbound('${esc(order.inOrderNo)}')">删除</button>`}
            </div>
            <div class="msg"></div>
        </form>
        ${orderNo ? `<h3>操作记录</h3><div>${logTable(order.logs || [])}</div>` : ""}
    `);
    if (orderNo) {
        renderInDetailRows(order.details || [], packageTypes, order.status);
    }
    bindSubmit("#inEdit", saveInbound);
}

function renderInDetailRows(details, packageTypes = [], status = "草稿") {
    const isDraft = status === "草稿";
    $("#inDetails").innerHTML = `
        <table><thead><tr>${isDraft ? "<th></th>" : ""}<th>序号</th><th>品名</th><th>英文品名</th><th>唛头</th><th>件数</th><th>包装种类</th><th>重量(kg)</th><th>体积(m³)</th><th>数量</th><th>应付单价</th><th>应付币制</th><th>应付单位</th><th>应付金额</th><th>应收单价</th><th>应收币制</th><th>应收单位</th><th>应收金额</th><th>实收单价</th><th>实收币制</th><th>实收金额</th><th>操作</th></tr></thead>
        <tbody id="inDetailRows" data-package-options="${esc(dictOptions(packageTypes))}">${html(details.map((d, i) => inDetailRow(d, packageTypes, "", i + 1, status)))}</tbody></table>
    `;
    bindInboundAmountSync();
}

function inDetailRow(d = {}, packageTypes = [], packageOptionsHtml = "", seq = 1, status = "草稿") {
    const packageOptions = packageOptionsHtml || (packageTypes.length ? dictOptions(packageTypes, d.packageType, d.packageType) : `<option value="${esc(d.packageType || "")}">${esc(d.packageType || "请先维护包装字典")}</option>`);
    const isDraft = status === "草稿";
    return `<tr>
        ${isDraft ? `<td><input type="radio" name="detailCheck" onclick="selectDetailRow(this)"></td>` : ""}
        <td>${seq}</td>
        <td><input name="productName" value="${esc(d.productName)}"></td>
        <td><input name="productEnName" value="${esc(d.productEnName)}"></td>
        <td><input name="marks" value="${esc(d.marks)}"></td>
        <td><input type="number" name="packageQty" value="${esc(d.packageQty || 1)}"></td>
        <td><select name="packageType">${packageOptions}</select></td>
        <td><input type="number" step="0.01" name="weight" value="${esc(d.weight || 0)}"></td>
        <td><input type="number" step="0.01" name="volume" value="${esc(d.volume || 0)}"></td>
        <td><input type="number" name="qty" value="${esc(d.qty || 1)}"></td>
        <td><input type="number" step="0.01" name="costPrice" value="${esc(d.costPrice ?? "")}"></td>
        <td><select name="costCurrency"><option ${d.costCurrency === "USD" ? "selected" : ""}>USD</option><option ${d.costCurrency === "RMB" ? "selected" : ""}>RMB</option><option ${d.costCurrency === "PKR" ? "selected" : ""}>PKR</option></select></td>
        <td><select name="yfUnit"><option ${d.yfUnit === "按数量" ? "selected" : ""}>按数量</option><option ${d.yfUnit === "按重量" ? "selected" : ""}>按重量</option><option ${d.yfUnit === "按体积" ? "selected" : ""}>按体积</option><option ${d.yfUnit === "无" ? "selected" : ""}>无</option></select></td>
        <td><input type="number" step="0.01" name="costAmount" value="${esc(d.costAmount ?? "")}"></td>
        <td><input type="number" step="0.01" name="incomePrice" value="${esc(d.incomePrice ?? "")}"></td>
        <td><select name="incomeCurrency"><option ${d.incomeCurrency === "USD" ? "selected" : ""}>USD</option><option ${d.incomeCurrency === "RMB" ? "selected" : ""}>RMB</option><option ${d.incomeCurrency === "PKR" ? "selected" : ""}>PKR</option></select></td>
        <td><select name="ysUnit"><option ${d.ysUnit === "按数量" ? "selected" : ""}>按数量</option><option ${d.ysUnit === "按重量" ? "selected" : ""}>按重量</option><option ${d.ysUnit === "按体积" ? "selected" : ""}>按体积</option><option ${d.ysUnit === "无" ? "selected" : ""}>无</option></select></td>
        <td><input type="number" step="0.01" name="incomeAmount" value="${esc(d.incomeAmount ?? "")}"></td>
        <td><input type="number" step="0.01" name="recePrice" value="${esc(d.recePrice ?? "")}"></td>
        <td><select name="receCurrency"><option ${d.receCurrency === "USD" ? "selected" : ""}>USD</option><option ${d.receCurrency === "RMB" ? "selected" : ""}>RMB</option><option ${d.receCurrency === "PKR" ? "selected" : ""}>PKR</option></select></td>
        <td><input type="number" step="0.01" name="receAmount" value="${esc(d.receAmount ?? "")}"></td>
        <td>${d.inOrderDetailUuid ? (isDraft ? `<button type="button" class="secondary" onclick="openAttachment('detail','${esc(d.inOrderDetailUuid)}',false)">附件</button> <button type="button" class="danger" onclick="deleteInDetail('${esc(d.inOrderDetailUuid)}')">删除</button>` : "") : `<button type="button" class="danger" onclick="this.closest('tr').remove()">删除</button>`}</td>
    </tr>`;
}

function addInDetailRow() {
    const tbody = $("#inDetailRows");
    const emptyRow = tbody.querySelector("td[colspan]");
    if (emptyRow) emptyRow.closest("tr").remove();
    let seq = 1;
    tbody.querySelectorAll("tr:not(:has(td[colspan]))").forEach(tr => {
        const num = parseInt(tr.querySelector("td:nth-child(2)")?.textContent);
        if (!isNaN(num) && num >= seq) seq = num + 1;
    });
    tbody.insertAdjacentHTML("beforeend", inDetailRow({}, [], tbody.dataset.packageOptions || "", seq));
    bindInboundAmountSync();
}

function selectDetailRow(radio) {
    document.querySelectorAll("#inDetailRows input[name='detailCheck']").forEach(cb => {
        if (cb !== radio) cb.checked = false;
    });
}

function copyDetailRow() {
    const checked = document.querySelector("#inDetailRows input[name='detailCheck']:checked");
    if (!checked) { alert("请先选择要复制的明细行"); return; }
    const tr = checked.closest("tr");
    const data = {};
    tr.querySelectorAll("input, select").forEach(el => {
        if (el.type !== "radio") data[el.name] = el.value;
    });
    const rows = $("#inDetailRows").querySelectorAll("tr");
    const seq = rows.length + 1;
    $("#inDetailRows").insertAdjacentHTML("beforeend", inDetailRow(data, [], $("#inDetailRows").dataset.packageOptions || "", seq));
    bindInboundAmountSync();
}

function toggleAllDetailChecks() {
    const checked = document.querySelector("thead input[name='detailCheck']").checked;
    document.querySelectorAll("#inDetailRows input[name='detailCheck']").forEach(cb => cb.checked = checked);
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
    const body = Object.fromEntries(fd.entries());
    const errors = [];
    if (!body.trackNo || !body.trackNo.trim()) errors.push("跟踪单号");
    if (!body.customer || !body.customer.trim()) errors.push("客户");
    if (!body.sales || !body.sales.trim()) errors.push("销售");
    if (errors.length) { $(".msg", form).textContent = "请填写：" + errors.join("、"); return; }
    const detailRows = $("#inDetailRows");
    body.details = detailRows ? [...detailRows.querySelectorAll("tr")].map(tr => {
        const item = {};
        tr.querySelectorAll("input, select").forEach(input => item[input.name] = input.value);
        return item;
    }) : [];
    const hasAnyDetail = body.details.some(d => Object.values(d).some(v => v && String(v).trim()));
    const hasDetailRows = body.details.length > 0;
    if (hasDetailRows && !hasAnyDetail) { $(".msg", form).textContent = "你没有填入任何值"; return; }
    try {
        const orderNo = await api("api/inbound/orders", {method: "POST", body: JSON.stringify(body)});
        await Promise.all([editInbound(orderNo), loadInbound("")]);
    } catch (e) {
        $(".msg", form).textContent = e.message;
    }
}

async function submitInbound(orderNo) {
    if (!orderNo) return;
    try {
        await api(`api/inbound/orders/${orderNo}/submit`, {method: "POST"});
        await editInbound(orderNo);
    } catch (e) {
        $(".msg", $("#inEdit")).textContent = e.message;
    }
}

async function cancelInbound(orderNo) {
    if (!orderNo) return;
    try {
        await api(`api/inbound/orders/${orderNo}/cancel`, {method: "POST"});
        await editInbound(orderNo);
    } catch (e) { alert(e.message); }
}

function openDelivery(orderNo) {
    modal("完成派送", `
        <form id="deliveryForm">
            <div class="grid"><div class="field"><label>派送类型</label><select name="sendType"><option>自提</option><option>派送</option></select></div><div class="field"><label>派送单号</label><input name="sendNo"></div></div>
            <div class="actions"><button>保存</button><span class="msg"></span></div>
        </form>
    `);
    bindSubmit("#deliveryForm", async form => {
        try {
            await api(`api/inbound/orders/${orderNo}/delivery`, {method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
            closeModal();
            await editInbound(orderNo);
        } catch (e) { $(".msg", form).textContent = e.message; }
    });
}

async function deleteInbound(orderNo) {
    if (!confirm("确认删除该入库单？")) return;
    try {
        await api(`api/inbound/orders/${orderNo}`, {method: "DELETE"});
        closeModal();
        await renderInbound();
    } catch (e) {
        alert(e.message);
    }
}

async function openInLog(orderNo) {
    try {
        const logs = await api(`api/inbound/orders/${orderNo}`);
        modal(`操作日志 - ${orderNo}`, `
            <table>
                <thead><tr><th>时间</th><th>操作人</th><th>节点</th><th>备注</th></tr></thead>
                <tbody>${html((logs.logs || []).map(row => `<tr><td>${esc(row.operateTime)}</td><td>${esc(row.operateContact)}</td><td>${esc(row.operateDesc)}</td><td>${esc(row.remark)}</td></tr>`))}</tbody>
            </table>
        `);
    } catch (e) {
        alert("加载日志失败：" + e.message);
    }
}

function openInboundImport() {
    modal("入库导入", `
        <form id="inImport" enctype="multipart/form-data">
            <input type="file" name="file" accept=".xlsx,.xls">
            <div class="actions"><button>导入</button><span class="msg"></span></div>
        </form>
    `);
    bindSubmit("#inImport", async form => {
        try {
            const data = new FormData(form);
            await api("api/inbound/import", {method: "POST", body: data});
            closeModal();
        } catch (e) { $(".msg", form).textContent = e.message; }
    });
}

function openInboundCollection(detailUuid) {
    modal2("完成收款", `<form id="inCollection">
        <input type="hidden" name="detailUuid" value="${esc(detailUuid)}">
        <div class="grid"><div class="field"><label>实收单价</label><input type="number" step="0.01" name="recePrice"></div><div class="field"><label>币制</label><select name="receCurrency"><option>USD</option><option>RMB</option><option>PKR</option></select></div><div class="field"><label>实收金额</label><input type="number" step="0.01" name="receAmount"></div></div>
        <div class="actions"><button>保存</button><span class="msg"></span></div>
    </form>`);
    bindSubmit("#inCollection", async form => {
        try {
            await api("api/inbound/collection/finish", {method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
            closeModal2();
        } catch (e) { $(".msg", form).textContent = e.message; }
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
        try {
            await api("api/inbound/inventory/adjust", {method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
            closeModal();
            await renderInbound();
        } catch (e) { $(".msg", form).textContent = e.message; }
    });
}

async function deleteInDetail(detailUuid) {
    if (!confirm("确认删除该入库明细及附件？")) return;
    try {
        await api(`api/inbound/details?detailUuid=${encodeURIComponent(detailUuid)}`, {method: "DELETE"});
        const orderNo = $("input[name='inOrderNo']").value;
        await editInbound(orderNo);
    } catch (e) { alert(e.message); }
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
            <thead><tr><th>出库单号</th><th>SO号</th><th>状态</th><th>装柜日期</th><th>柜号</th><th>车牌</th><th>物流节点</th><th>出库件数</th><th>出库重量</th><th>出库体积</th><th>出库数量</th><th>报关行</th><th>出口口岸</th><th>创建人</th><th>费用</th><th>操作</th></tr></thead>
            <tbody>${html(page.items.map(row => `
                <tr><td>${esc(row.outOrderNo)}</td><td>${esc(row.soNo)}</td><td><span class="status ${row.status === "有效" ? "ok" : "warn"}">${esc(row.status)}</span></td>
                <td>${esc(row.loadingDate)}</td><td>${esc(row.containerNo)}</td><td>${esc(row.carPlate)}</td><td>${esc(row.wljd)}</td><td>${esc(row.totalPackageQty)}</td><td>${esc(row.totalWeight)}</td><td>${esc(row.totalVolume)}</td><td>${esc(row.totalQty)}</td><td>${esc(row.customsBroker)}</td><td>${esc(row.exportPort)}</td><td>${esc(row.creator)}</td>                <td>${Number(row.totalCost) > 0 ? `<span style="color:#1769aa">${money(row.totalCost)}</span>` : `<span style="color:#b42318">${money(row.totalCost)}</span>`}</td>
                <td><button class="secondary" onclick="editOutbound('${esc(row.outOrderNo)}')">编辑</button> <button class="danger" onclick="deleteOutbound('${esc(row.outOrderNo)}')">删除</button> <button class="secondary" onclick="openOutLog('${esc(row.outOrderNo)}')">操作日志</button></td></tr>
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
    try {
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
                <div class="field"><label>出库单编号</label><input name="outOrderNo" value="${esc(order.outOrderNo || '')}" readonly class="readonly-field"></div>
                <div class="field"><label>状态</label><input name="status" value="${esc(order.status || '草稿')}" readonly class="readonly-field"></div>
                <div class="field"><label>SO号</label><input name="soNo" value="${esc(order.soNo)}"></div>
                <div class="field"><label>装柜日期</label><input type="date" name="loadingDate" value="${esc(order.loadingDate || today())}"></div>
                <div class="field"><label>柜号</label><input name="containerNo" value="${esc(order.containerNo)}"></div>
                <div class="field"><label>车牌</label><input name="carPlate" value="${esc(order.carPlate)}"></div>
                <div class="field"><label>报关行</label><select name="customsBroker">${customsOptions}</select></div>
                <div class="field"><label>出口口岸</label><select name="exportPort">${portOptions}</select></div>
                <div class="field"><label>ATD日期</label><input value="${esc(order.atdTime || '')}" readonly class="readonly-field"></div>
                <div class="field"><label>ETA日期</label><input value="${esc(order.etaTime || '')}" readonly class="readonly-field"></div>
                <div class="field"><label>ATA日期</label><input value="${esc(order.ataTime || '')}" readonly class="readonly-field"></div>
                <div class="field"><label>总件数</label><input value="${esc(order.totalPackageQty || 0)}" readonly class="readonly-field"></div>
                <div class="field"><label>总数量</label><input value="${esc(order.totalQty || 0)}" readonly class="readonly-field"></div>
                <div class="field"><label>总体积(m³)</label><input value="${esc(order.totalVolume || 0)}" readonly class="readonly-field"></div>
                <div class="field"><label>总重量(kg)</label><input value="${esc(order.totalWeight || 0)}" readonly class="readonly-field"></div>
            </div>
            <div class="actions">
                <button>保存</button>
                ${orderNo ? `
                ${order.status === "草稿" ? `<button type="button" onclick="submitOutbound('${esc(order.outOrderNo)}')">提交</button>` : ""}
                ${order.status === "有效" ? `<button type="button" class="secondary" onclick="cancelOutbound('${esc(order.outOrderNo)}')">取消提交</button>` : ""}
                <button type="button" class="secondary" onclick="openStockPicker('${esc(order.outOrderNo)}')">添加明细</button>
                <button type="button" class="secondary" onclick="openCost('${esc(order.outOrderNo)}')">费用</button>
                <a class="button-link" href="api/outbound/orders/${esc(order.outOrderNo)}/export-details">导出明细</a>
                <a class="button-link" href="api/outbound/orders/${esc(order.outOrderNo)}/export-accounts">导出账款</a>
                ${order.status === "草稿" ? `<button type="button" class="danger" onclick="deleteOutbound('${esc(order.outOrderNo)}')">删除</button>` : ""}` : ""}
            </div>
            ${orderNo ? `<div class="actions" style="margin-top:8px">
                <button type="button" class="secondary" onclick="openNodeDate('${esc(order.outOrderNo)}','出库','出库日期')">出库日期</button>
                <button type="button" class="secondary" onclick="openNodeDate('${esc(order.outOrderNo)}','完成报关','完成日期')">完成日期</button>
                <button type="button" class="secondary" onclick="openNodeDate('${esc(order.outOrderNo)}','完成交重','完成交重日期')">完成交重日期</button>
                <button type="button" class="secondary" onclick="openNodeDate('${esc(order.outOrderNo)}','启运','启运日期录入')">启运日期录入</button>
                <button type="button" class="secondary" onclick="openNodeDate('${esc(order.outOrderNo)}','到港','到港日期录入')">到港日期录入</button>
                <button type="button" class="secondary" onclick="openNodeDate('${esc(order.outOrderNo)}','清关启动','清关启动日期')">清关启动日期</button>
                <button type="button" class="secondary" onclick="openNodeDate('${esc(order.outOrderNo)}','清关完成','清关完成日期')">清关完成日期</button>
                <button type="button" class="secondary" onclick="openNodeDate('${esc(order.outOrderNo)}','到仓','到仓日期')">到仓日期</button>
            </div>` : ""}
            <div class="msg"></div>
        </form>
        <h3>出库明细</h3>
        <div>${detailTable(order.details || [])}</div>
        <h3>费用</h3>
        <div>${costTable(order.costs || [])}</div>
        <h3>应收账款</h3>
        <div>${receivableTable(order.receivables || [])}</div>
        <h3>操作记录</h3>
        <div>${logTable(order.logs || [])}</div>
    `);
    bindSubmit("#outEdit", saveOutbound);
    const containerInput = $("#outEdit input[name='containerNo']");
    if (containerInput) containerInput.addEventListener("click", () => { $(".msg", $("#outEdit")).textContent = ""; });
    } catch (e) {
        alert("加载失败：" + e.message);
    }
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
    const msg = $(".msg", form);
    if (body.containerNo && !/^[A-Z]{4}\d{7}$/.test(body.containerNo)) {
        msg.textContent = "柜号格式错误，应为4个大写字母+7位数字";
        return;
    }
    msg.textContent = "";
    try {
        const orderNo = await api("api/outbound/orders", {method: "POST", body: JSON.stringify(body)});
        await Promise.all([editOutbound(orderNo), loadOutbound("")]);
    } catch (e) { msg.textContent = e.message; }
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
        try {
            await api("api/outbound/details", {method: "PATCH", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
            closeModal();
            await renderOutbound();
        } catch (e) { $(".msg", form).textContent = e.message; }
    });
}

async function deleteOutDetail(detailUuid) {
    if (!confirm("确认删除该出库明细并回补库存？")) return;
    try {
        await api(`api/outbound/details?detailUuid=${encodeURIComponent(detailUuid)}`, {method: "DELETE"});
        closeModal();
        await renderOutbound();
    } catch (e) { alert(e.message); }
}

async function submitOutbound(orderNo) {
    try {
        await api(`api/outbound/orders/${orderNo}/submit`, {method: "POST"});
        closeModal();
        await renderOutbound();
    } catch (e) { alert(e.message); }
}

async function cancelOutbound(orderNo) {
    try {
        await api(`api/outbound/orders/${orderNo}/cancel`, {method: "POST"});
        closeModal();
        await renderOutbound();
    } catch (e) { alert(e.message); }
}

async function deleteOutbound(orderNo) {
    if (!confirm("确认删除该出库单并回补库存？")) return;
    try {
        await api(`api/outbound/orders/${orderNo}`, {method: "DELETE"});
        closeModal();
        await renderOutbound();
    } catch (e) {
        alert(e.message);
    }
}

async function openOutLog(orderNo) {
    try {
        const logs = await api(`api/outbound/orders/${orderNo}/logs`);
        modal(`操作日志 - ${orderNo}`, `
            <table>
                <thead><tr><th>时间</th><th>操作人</th><th>操作类型</th><th>操作说明</th><th>备注</th></tr></thead>
                <tbody>${html(logs.map(row => `<tr><td>${esc(row.operateTime)}</td><td>${esc(row.operateContact)}</td><td>${esc(row.operateType)}</td><td>${esc(row.operateDesc)}</td><td>${esc(row.remark)}</td></tr>`))}</tbody>
            </table>
        `);
    } catch (e) {
        alert("加载日志失败：" + e.message);
    }
}

async function openStockPicker(orderNo, query = "") {
    modal("添加出库明细", `
        <form id="stockPick">
            <div class="toolbar">
                <div class="field"><label>跟踪单号</label><input name="trackNo"></div>
                <div class="field"><label>客户</label><input name="customer"></div>
                <div class="field"><label>销售</label><input name="sales"></div>
                <div class="field"><label>入库开始</label><input type="date" name="inStartDate"></div>
                <div class="field"><label>入库结束</label><input type="date" name="inEndDate"></div>
                <button type="button" class="secondary" onclick="searchStockPick('${esc(orderNo)}')">查询</button>
                <button type="button" class="secondary" onclick="resetStockPick()">重置</button>
            </div>
            <div id="stockPickResult"></div>
            <div class="actions" id="stockPickActions" style="display:none"><button type="button" onclick="confirmStockPick('${esc(orderNo)}')">添加</button><span class="msg"></span></div>
        </form>
    `);
    if (query) {
        await searchStockPick(orderNo, query);
    }
}

async function searchStockPick(orderNo, query = "") {
    if (!query) query = qs($("#stockPick"));
    try {
        const rows = await api(`api/outbound/inventory${query ? `?${query}` : ""}`);
        $("#stockPickResult").innerHTML = `<table><thead><tr><th>选择</th><th>序号</th><th>入库单号</th><th>跟踪单号</th><th>客户</th><th>销售</th><th>入库日期</th><th>品名</th><th>唛头</th><th>包装种类</th><th>重量</th><th>体积</th><th>库存件数</th><th>库存数量</th><th>出库件数</th><th>出库数量</th></tr></thead>
        <tbody>${html(rows.map((row, idx) => {
            const noAmount = Number(row.costAmount || 0) <= 0 && Number(row.incomeAmount || 0) <= 0;
            return `<tr>
            <td>${noAmount ? "<span class=\"status warn\">缺少金额</span>" : `<input type="checkbox" name="pick" value="${esc(row.inOrderDetailUuid)}">`}</td>
            <td>${idx + 1}</td><td>${esc(row.inOrderNo)}</td><td>${esc(row.trackNo)}</td><td>${esc(row.customer)}</td><td>${esc(row.sales)}</td><td>${esc(row.inDate)}</td><td>${esc(row.productName)}</td><td>${esc(row.marks)}</td><td>${esc(row.packageType)}</td><td>${esc(row.weight)}</td><td>${esc(row.volume)}</td><td>${esc(row.stockPackageQty)}</td><td>${esc(row.stockQty)}</td>
            <td><input type="number" step="0.01" min="0" max="${esc(row.stockPackageQty)}" data-field="outPackageQty" data-id="${esc(row.inOrderDetailUuid)}" value="${esc(row.stockPackageQty)}"></td>
            <td><input type="number" step="0.01" min="0" max="${esc(row.stockQty)}" data-field="outQty" data-id="${esc(row.inOrderDetailUuid)}" value="${esc(row.stockQty)}"></td>
        </tr>`;
        }))}</tbody></table>`;
        $("#stockPickActions").style.display = rows.length ? "" : "none";
    } catch (e) {
        $("#stockPickResult").innerHTML = `<div class="msg">${esc(e.message)}</div>`;
        $("#stockPickActions").style.display = "none";
    }
}

function resetStockPick() {
    $("#stockPick").reset();
    $("#stockPickResult").innerHTML = "";
}

async function confirmStockPick(orderNo) {
    const picked = [...document.querySelectorAll("#stockPick input[name=pick]:checked")].map(input => ({
        inOrderDetailUuid: input.value,
        outPackageQty: $(`input[data-field=outPackageQty][data-id="${CSS.escape(input.value)}"]`).value,
        outQty: $(`input[data-field=outQty][data-id="${CSS.escape(input.value)}"]`).value
    }));
    if (!picked.length) { $(".msg", $("#stockPick")).textContent = "请先选择明细"; return; }
    try {
        await api(`api/outbound/orders/${orderNo}/details`, {method: "POST", body: JSON.stringify({items: picked})});
        closeModal();
        await editOutbound(orderNo);
    } catch (e) {
        $(".msg", $("#stockPick")).textContent = e.message;
    }
}

async function openCost(orderNo) {
    try {
        const items = await api("api/master/dicts/enabled?type=cost_item");
        const options = items.length ? items.map(item => `<option value="${esc(item.dictName)}">${esc(item.dictName)}</option>`).join("") : `<option value="">请先维护费用字典</option>`;
        modal("新增费用", `
            <form id="costForm">
                <div class="grid"><div class="field"><label>费用名称</label><select name="costName">${options}</select></div><div class="field"><label>金额</label><input type="number" step="0.01" name="amount"></div><div class="field wide"><label>备注</label><input name="remark"></div></div>
                <div class="actions"><button>保存</button><span class="msg"></span></div>
            </form>
        `);
        bindSubmit("#costForm", async form => {
            try {
                await api(`api/outbound/orders/${orderNo}/costs`, {method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
                closeModal();
                await editOutbound(orderNo);
            } catch (e) { $(".msg", form).textContent = e.message; }
        });
    } catch (e) { alert(e.message); }
}

async function openCostEdit(costUuid, costName, amount, remark) {
    try {
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
            try {
                await api(`api/outbound/costs?costUuid=${encodeURIComponent(costUuid)}`, {method: "PATCH", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
                closeModal();
                await renderOutbound();
            } catch (e) { $(".msg", form).textContent = e.message; }
        });
    } catch (e) { alert(e.message); }
}

async function deleteCost(costUuid) {
    if (!confirm("确认删除该费用及附件？")) return;
    try {
        await api(`api/outbound/costs?costUuid=${encodeURIComponent(costUuid)}`, {method: "DELETE"});
        closeModal();
        await renderOutbound();
    } catch (e) { alert(e.message); }
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
        try {
            await api(`api/outbound/orders/${orderNo}/nodes`, {method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
            closeModal();
            await editOutbound(orderNo);
        } catch (e) { $(".msg", form).textContent = e.message; }
    });
}

function openNodeDate(orderNo, node, title) {
    modal2(title, `
        <form id="nodeDateForm">
            <div class="grid">
                <div class="field"><label>日期</label><input type="date" name="operateDate" value="${today()}"></div>
            </div>
            <div class="actions"><button>保存</button><span class="msg"></span></div>
        </form>
    `);
    bindSubmit("#nodeDateForm", async form => {
        try {
            const data = Object.fromEntries(new FormData(form).entries());
            data.node = node;
            await api(`api/outbound/orders/${orderNo}/nodes`, {method: "POST", body: JSON.stringify(data)});
            alert("保存成功");
            closeModal2();
            await editOutbound(orderNo);
        } catch (e) { $(".msg", form).textContent = e.message; }
    });
}

function openReceivable(detailUuid, mode) {
    const title = mode === "receivable" ? "录入实收" : "录入实付";
    const priceName = mode === "receivable" ? "recePrice" : "sfPrice";
    const currencyName = mode === "receivable" ? "receCurrency" : "sfCurrency";
    const amountName = mode === "receivable" ? "receAmount" : "sfAmount";
    modal2(title, `
        <form id="moneyForm">
            <input type="hidden" name="mode" value="${mode}">
            <input type="hidden" name="outOrderDetailUuid" value="${esc(detailUuid)}">
            <div class="grid"><div class="field"><label>单价</label><input type="number" step="0.01" name="${priceName}"></div><div class="field"><label>币制</label><select name="${currencyName}"><option>USD</option><option>RMB</option><option>PKR</option></select></div><div class="field"><label>金额</label><input type="number" step="0.01" name="${amountName}"></div></div>
            <div class="actions"><button>保存</button><span class="msg"></span></div>
        </form>
    `);
    bindSubmit("#moneyForm", async form => {
        try {
            await api("api/outbound/receivables", {method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
            closeModal2();
            const orderNo = $("input[name='outOrderNo']").value;
            await editOutbound(orderNo);
        } catch (e) { $(".msg", form).textContent = e.message; }
    });
}

async function renderMaster() {
    setView(`
        <section class="panel">
            <div class="actions">
                <button class="secondary" onclick="loadCustomers()">客户销售管理</button>
                <button class="secondary" onclick="loadDicts()">内部资料管理</button>
            </div>
            <div id="masterBox"></div>
        </section>
    `);
    await loadCustomers();
}

async function loadCustomers(query = "") {
    const page = await api(`api/master/customers?${query}`);
    $("#masterBox").innerHTML = `<h3>客户销售管理</h3>
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

const dictTypeNames = {
    cost_item: "费用项",
    customs_broker: "报关行",
    export_port: "出口口岸",
    package_type: "包装种类"
};

function dictTypeName(type) {
    return dictTypeNames[type] || type;
}

async function loadDicts() {
    const types = await api("api/master/dict-types");
    $("#masterBox").innerHTML = `<div class="dict-layout">
        <div class="dict-sidebar">
            <h3>字典类型</h3>
            <div id="dictTypeList">${types.map(t => `<div class="dict-type-item${state.currentDictType === t.dictType ? " active" : ""}" data-type="${esc(t.dictType)}" onclick="selectDictType('${esc(t.dictType)}')">${esc(dictTypeName(t.dictType))}</div>`).join("")}</div>
        </div>
        <div class="dict-main">
            <h3 id="dictTitle">字典项</h3>
            <div class="actions"><button id="dictAddBtn" onclick="openDict('${esc(state.currentDictType || "")}')">新增</button> <button class="secondary" id="dictEditBtn" onclick="editSelectedDict()" disabled>编辑</button> <button class="danger" id="dictDelBtn" onclick="deleteSelectedDict()" disabled>删除</button> <button class="secondary" id="dictEnableBtn" onclick="setSelectedDictStatus('1')" disabled>启用</button> <button class="secondary" id="dictDisableBtn" onclick="setSelectedDictStatus('0')" disabled>禁用</button></div>
            <div id="dictItems"><p style="color:var(--muted)">请在左侧选择字典类型</p></div>
        </div>
    </div>`;
    if (state.currentDictType && types.length) {
        await loadDictItems();
    } else if (types.length) {
        state.currentDictType = types[0].dictType;
        await loadDictItems();
    }
}

function selectDictType(type) {
    state.currentDictType = type;
    loadDictItems();
}

async function loadDictItems(query = "") {
    const type = state.currentDictType;
    state.selectedDictCode = null;
    document.querySelectorAll(".dict-type-item").forEach(el => {
        el.classList.toggle("active", el.dataset.type === type);
    });
    $("#dictTitle").textContent = "字典项：" + dictTypeName(type);
    $("#dictAddBtn").setAttribute("onclick", `openDict('${esc(type)}')`);
    const page = await api(`api/master/dicts?type=${encodeURIComponent(type)}&pageSize=${state.dictPageSize}${query ? "&" + query : ""}`);
    const prevPage = Math.max(1, page.page - 1);
    const nextPage = Math.min(page.totalPages || 1, page.page + 1);
    $("#dictItems").innerHTML = `
        <table><thead><tr><th>编码</th><th>名称</th><th>排序</th><th>状态</th><th>备注</th></tr></thead><tbody>${html(page.items.map(row => `<tr class="dict-row" data-code="${esc(row.dictCode)}" data-name="${esc(row.dictName)}" data-sort="${esc(row.sortOrder)}" data-status="${esc(row.status)}" data-remark="${esc(row.remark)}" onclick="selectDictRow(this)"><td>${esc(row.dictCode)}</td><td>${esc(row.dictName)}</td><td>${esc(row.sortOrder)}</td><td>${esc(row.status)}</td><td>${esc(row.remark)}</td></tr>`))}</tbody></table>
        <div class="dict-pager">
            <select onchange="changeDictPageSize(this.value)" class="dict-pager-select">
                <option value="10" ${state.dictPageSize==10?"selected":""}>10</option>
                <option value="20" ${state.dictPageSize==20?"selected":""}>20</option>
                <option value="50" ${state.dictPageSize==50?"selected":""}>50</option>
                <option value="100" ${state.dictPageSize==100?"selected":""}>100</option>
            </select><span class="dict-pager-label">条/页</span>
            <button type="button" class="secondary" ${page.page <= 1 ? "disabled" : ""} onclick="loadDictItems('page=${prevPage}')">上一页</button>
            <span>${page.page} / ${page.totalPages || 1}</span>
            <button type="button" class="secondary" ${page.page >= page.totalPages ? "disabled" : ""} onclick="loadDictItems('page=${nextPage}')">下一页</button>
        </div>`;
}

function changeDictPageSize(size) {
    state.dictPageSize = parseInt(size);
    loadDictItems();
}

function selectDictRow(tr) {
    document.querySelectorAll(".dict-row").forEach(r => r.classList.remove("selected"));
    tr.classList.add("selected");
    state.selectedDictCode = tr.dataset.code;
    state.selectedDict = {code: tr.dataset.code, name: tr.dataset.name, sort: tr.dataset.sort, status: tr.dataset.status, remark: tr.dataset.remark};
    ["dictEditBtn", "dictDelBtn", "dictEnableBtn", "dictDisableBtn"].forEach(id => {
        document.getElementById(id).disabled = false;
    });
}

function editSelectedDict() {
    if (!state.selectedDict) return;
    const d = state.selectedDict;
    openDict(state.currentDictType, d.code, d.name, d.sort, d.status, d.remark);
}

async function deleteSelectedDict() {
    if (!state.selectedDict) return;
    await deleteDict(state.currentDictType, state.selectedDict.code);
}

async function setSelectedDictStatus(status) {
    if (!state.selectedDict) return;
    await setDictStatus(state.currentDictType, state.selectedDict.code, status);
    alert(status === "1" ? "已启用" : "已禁用");
}

function openCustomer(code = "", cnName = "", enName = "", type = "Customer", superiorCode = "") {
    modal("客户销售管理", `<form id="customerForm"><div class="grid">
        <div class="field"><label>代码</label><input name="customerCode" value="${esc(code)}"></div>
        <div class="field"><label>中文名</label><input name="customerCnName" value="${esc(cnName)}"></div>
        <div class="field"><label>英文名</label><input name="customerEnName" value="${esc(enName)}"></div>
        <div class="field"><label>类型</label><select name="customerType"><option ${type === "Customer" ? "selected" : ""}>Customer</option><option ${type === "Sale" ? "selected" : ""}>Sale</option></select></div>
        <div class="field"><label>上级代码</label><input name="superiorCode" value="${esc(superiorCode)}"></div>
    </div><div class="actions"><button>保存</button><span class="msg"></span></div></form>`);
    bindSubmit("#customerForm", async form => {
        try {
            await api("api/master/customers", {method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
            closeModal();
            await loadCustomers();
        } catch (e) { $(".msg", form).textContent = e.message; }
    });
}

async function setCustomerStatus(code, status) {
    try {
        await api("api/master/customers", {method: "PATCH", body: JSON.stringify({code, status})});
        await loadCustomers();
    } catch (e) { alert(e.message); }
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
        try {
            await api("api/master/users", {method: "POST", body: JSON.stringify(Object.fromEntries(new FormData(form).entries()))});
            closeModal();
            await loadUsers();
        } catch (e) { $(".msg", form).textContent = e.message; }
    });
}

async function setUserStatus(username, status) {
    try {
        await api("api/master/users", {method: "PATCH", body: JSON.stringify({username, status})});
        await loadUsers();
    } catch (e) { alert(e.message); }
}

function openDict(dictType = "", dictCode = "", dictName = "", sortOrder = "0", status = "1", remark = "") {
    modal("字典", `<form id="dictForm"><div class="grid">
        <div class="field"><label>类型</label><input name="dictType" value="${esc(dictType)}" readonly></div>
        <div class="field"><label>代码</label><input name="dictCode" value="${esc(dictCode)}"></div>
        <div class="field"><label>名称</label><input name="dictName" value="${esc(dictName)}"></div>
        <div class="field"><label>排序</label><input type="number" name="sortOrder" value="${esc(sortOrder)}"></div>
        <div class="field"><label>状态</label><select name="status"><option value="1" ${status === "1" ? "selected" : ""}>启用</option><option value="0" ${status === "0" ? "selected" : ""}>禁用</option></select></div>
        <div class="field wide"><label>备注</label><input name="remark" value="${esc(remark)}"></div>
    </div><div class="actions"><button>保存</button><span class="msg"></span></div></form>`);
    bindSubmit("#dictForm", async form => {
        const data = Object.fromEntries(new FormData(form).entries());
        if (!data.dictCode || !data.dictCode.trim()) { alert("代码不能为空"); return; }
        if (!data.dictName || !data.dictName.trim()) { alert("名称不能为空"); return; }
        try {
            await api("api/master/dicts", {method: "POST", body: JSON.stringify(data)});
            closeModal();
            state.currentDictType = data.dictType || state.currentDictType;
            await reloadDicts();
        } catch (e) { $(".msg", form).textContent = e.message; }
    });
}

async function deleteDict(type, code) {
    if (!confirm("确认删除该字典？")) return;
    try {
        await api(`api/master/dicts?type=${encodeURIComponent(type)}&code=${encodeURIComponent(code)}`, {method: "DELETE"});
        state.currentDictType = type;
        await reloadDicts();
    } catch (e) { alert(e.message); }
}

async function setDictStatus(dictType, dictCode, status) {
    try {
        await api("api/master/dicts", {method: "PATCH", body: JSON.stringify({dictType, dictCode, status})});
        state.currentDictType = dictType;
        await reloadDicts();
    } catch (e) { alert(e.message); }
}

async function reloadDicts() {
    await loadDictItems();
}

function openAttachment(ownerType, ownerId, replace = false) {
    modal2("附件", `
        <div class="attach-layout">
            <div class="attach-list">
                <form id="attachForm" enctype="multipart/form-data">
                    <input type="file" name="file" accept="image/*">
                    <div class="actions"><button>上传</button><span class="msg"></span></div>
                </form>
                <div id="attachList"></div>
            </div>
            <div class="attach-preview" id="attachPreview">
                <div class="no-image">暂无图片</div>
            </div>
        </div>
    `);
    loadAttachments(ownerType, ownerId);
    bindSubmit("#attachForm", async form => {
        try {
            const data = new FormData(form);
            data.set("ownerType", ownerType);
            data.set("ownerId", ownerId);
            data.set("replace", replace ? "true" : "false");
            await api("api/attachments/", {method: "POST", body: data});
            await loadAttachments(ownerType, ownerId);
        } catch (e) { $(".msg", form).textContent = e.message; }
    });
}

async function loadAttachments(ownerType, ownerId) {
    try {
        const rows = await api(`api/attachments/?ownerType=${encodeURIComponent(ownerType)}&ownerId=${encodeURIComponent(ownerId)}`);
        const isDetail = ownerType === "detail";
        $("#attachList").innerHTML = `<table><thead><tr>${isDetail ? "<th>品名</th><th>唛头</th>" : ""}<th>附件名称</th><th>上传人</th><th>上传时间</th><th>操作</th></tr></thead><tbody>${html(rows.map(row => `<tr onclick="previewAttach('${esc(row.attachmentUuid)}')" style="cursor:pointer">${isDetail ? `<td>${esc(row.productName)}</td><td>${esc(row.marks)}</td>` : ""}<td>${esc(row.attachmentName)}</td><td>${esc(row.uploader)}</td><td>${esc(row.uploadTime)}</td><td><button type="button" class="danger" onclick="event.stopPropagation();deleteAttachment('${esc(row.attachmentUuid)}','${esc(ownerType)}','${esc(ownerId)}')">删除</button></td></tr>`))}</tbody></table>`;
    } catch (e) { $("#attachList").innerHTML = `<div class="msg">${esc(e.message)}</div>`; }
}

function previewAttach(uuid) {
    const preview = $("#attachPreview");
    preview.innerHTML = `<img src="api/attachments/${uuid}/content" onerror="this.parentElement.innerHTML='<div class=no-image>暂无图片</div>'" style="max-width:100%;max-height:100%;object-fit:contain;">`;
}

async function deleteAttachment(uuid, ownerType, ownerId) {
    try {
        await api(`api/attachments/${encodeURIComponent(uuid)}`, {method: "DELETE"});
        await loadAttachments(ownerType, ownerId);
    } catch (e) { alert(e.message); }
}
