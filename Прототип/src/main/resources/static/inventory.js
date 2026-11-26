const byId = id => document.getElementById(id);
const store = byId("store");
const date = byId("date");
const days = byId("days");
const products = byId("products");
const submit = byId("submit-sale");
const receipt = byId("receipt");
const controls = [...document.querySelectorAll(".tabs button")];
const money = new Intl.NumberFormat("ru-RU", { style: "currency", currency: "RUB" });
const dateLabel = value => value.split("-").reverse().join(".");
let active = "sale";
let catalog = [];
let catalogStore = "";
let loading = null;
let selling = false;

async function request(path, options) {
  const response = await fetch(path, { cache: "no-store", ...options });
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new Error(problem.detail || `Ошибка запроса (HTTP ${response.status})`);
  }
  return response.json();
}

function showNotice(message) {
  const notice = byId("notice");
  notice.textContent = message;
  notice.hidden = !message;
}

function writeRows(body, rows, columns, whenEmpty) {
  const rendered = rows.map(values => {
    const tr = document.createElement("tr");
    for (const value of values) {
      const td = document.createElement("td");
      if (value instanceof Node) td.append(value);
      else td.textContent = value;
      tr.append(td);
    }
    return tr;
  });
  body.replaceChildren(...rendered);
  if (!rows.length) {
    const td = body.insertRow().insertCell();
    td.colSpan = columns;
    td.textContent = whenEmpty;
    td.className = "muted";
  }
}

function nameOf(sku) {
  return catalog.find(product => product.sku === sku)?.name || sku;
}

function setProducts() {
  const rows = catalog.map(product => {
    const field = document.createElement("input");
    field.type = "number";
    field.min = 0;
    field.max = 2147483647;
    field.step = 1;
    field.value = 0;
    field.required = true;
    field.dataset.sku = product.sku;
    field.setAttribute("aria-label", `Количество: ${product.name}`);
    return [product.name, money.format(product.priceKopecks / 100), field];
  });
  writeRows(products, rows, 3, "В магазине нет товаров в ассортименте");
  submit.disabled = !rows.length;
}

function switchPanel(panel) {
  active = panel;
  for (const button of controls) {
    const selected = button.dataset.panel === panel;
    if (selected) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
    byId(button.dataset.panel).hidden = !selected;
  }
  if (panel !== "sale") refresh(false);
}

async function refresh(reloadCatalog) {
  loading?.abort();
  const controller = new AbortController();
  loading = controller;
  if (!store.value) return;
  showNotice("");
  const base = "/api/stores/" + store.value;
  const query = new URLSearchParams({ date: date.value });
  const options = { signal: controller.signal };
  try {
    if (reloadCatalog || catalogStore !== store.value) {
      catalog = [];
      submit.disabled = true;
      writeRows(products, [], 3, "Загрузка ассортимента…");
      catalog = await request(base + "/assortment", options);
      catalogStore = store.value;
      setProducts();
    }
    if (active !== "sale" && !date.reportValidity()) return;
    if (active === "expiry") {
      writeRows(byId("alerts"), [], 5, "Загрузка предупреждений…");
      if (!days.reportValidity()) return;
      query.set("days", days.value);
      const rows = await request(base + "/expiry-alerts?" + query, options);
      writeRows(byId("alerts"), rows.map(row => {
        const status = document.createElement("span");
        status.className = row.status === "EXPIRED" ? "expired" : "expiring";
        status.textContent = row.status === "EXPIRED" ? "Срок истёк" : "Срок заканчивается";
        return [row.batchId, nameOf(row.sku), dateLabel(row.expiresOn), row.quantity, status];
      }), 5, "По выбранной дате предупреждений нет");
    }
    if (active === "supply") {
      writeRows(byId("recommendations"), [], 4, "Загрузка рекомендаций…");
      const rows = await request(base + "/replenishment?" + query, options);
      writeRows(byId("recommendations"), rows.map(row =>
        [nameOf(row.sku), row.available, row.expected, row.quantity]), 4, "Пополнение не требуется");
    }
  } catch (error) {
    if (error.name !== "AbortError") showNotice(error.message);
  }
}

async function initialize() {
  try {
    const stores = await request("/api/stores");
    if (!stores.length) {
      showNotice("В базе нет магазинов");
      return;
    }
    store.replaceChildren(...stores.map(item => new Option(item.name, item.id)));
    store.disabled = false;
    await refresh(true);
  } catch (error) {
    showNotice(error.message);
  }
}

byId("context").addEventListener("submit", event => event.preventDefault());
store.addEventListener("change", () => { receipt.hidden = true; refresh(true); });
date.addEventListener("change", () => { receipt.hidden = true; refresh(false); });
for (const button of controls) button.addEventListener("click", () => switchPanel(button.dataset.panel));
byId("expiry-form").addEventListener("submit", event => { event.preventDefault(); refresh(false); });
byId("calculate").addEventListener("click", () => refresh(false));

byId("basket").addEventListener("submit", async event => {
  event.preventDefault();
  if (selling || !date.reportValidity()) return;
  const fields = [...products.querySelectorAll("input")];
  const items = Object.fromEntries(fields.filter(field => field.valueAsNumber > 0)
    .map(field => [field.dataset.sku, field.valueAsNumber]));
  if (!Object.keys(items).length) {
    showNotice("Укажите количество хотя бы для одного товара");
    return;
  }
  showNotice("");
  receipt.hidden = true;
  selling = true;
  for (const field of [store, date, submit, ...fields, ...controls]) field.disabled = true;
  try {
    const result = await request("/api/stores/" + store.value + "/sales", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ date: date.value, items })
    });
    byId("receipt-title").textContent = `Продажа № ${result.saleId} проведена`;
    byId("receipt-total").textContent = "Итого: " + money.format(result.totalKopecks / 100);
    writeRows(byId("allocations"), result.allocations.map(row =>
      [nameOf(row.sku), row.batchId, row.quantity]), 3, "");
    for (const field of fields) field.value = 0;
    receipt.hidden = false;
  } catch (error) {
    showNotice(error.message);
  } finally {
    for (const field of [store, date, submit, ...fields, ...controls]) field.disabled = false;
    selling = false;
  }
});

initialize();
