"use strict";

const DAY = 86_400_000;
const MINUTE = 60_000;
const SVG = "http://www.w3.org/2000/svg";
const $ = id => document.getElementById(id);
const fullDate = new Intl.DateTimeFormat("ru-RU", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
const shortDate = new Intl.DateTimeFormat("ru-RU", { day: "2-digit", month: "2-digit", timeZone: "UTC" });
const countFormat = new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 0 });
const names = { physical: "Физическое состояние", mental: "Психологическое состояние" };

const metrics = {
    pulse: { name: "Пульс", unit: "уд/мин", description: "Средний пульс за день по доступным измерениям." },
    oxygen: { name: "Кислород (SpO₂)", unit: "%", digits: 1, description: "Среднее насыщение крови кислородом за день по доступным измерениям." },
    stress: { name: "Стресс браслета", unit: "баллы", max: 100, digits: 1, description: "Средняя оценка стресса за день по данным браслета; не результат опросника." },
    steps: { name: "Шаги", unit: "шаги", total: true, description: "Число шагов за день по данным Health Connect." },
    active_kcal: { name: "Активные калории", unit: "ккал", total: true, description: "Энергия активности за день без учёта основного обмена." },
    distance: { name: "Расстояние", unit: "км", total: true, digits: 2, scale: 1000, description: "Расстояние за день по данным Health Connect." },
    sleep: { name: "Сон", unit: "мин", duration: true, total: true, scale: 60, description: "Сумма длительностей сеансов по дате пробуждения, без известных периодов бодрствования." },
    shower: { name: "Холодный душ", unit: "мин", duration: true, seconds: true, total: true, scale: 60, description: "Суммарная длительность записанных сеансов за день." },
    weight: { name: "Вес", unit: "кг", digits: 1, description: "Средний вес по взвешиваниям за день." },
    tension: { name: "Напряжение", unit: "баллы", max: 10, digits: 1, description: "Самооценка от 0 до 10. У каждого дня с отметкой одинаковый вес в среднем за период." },
    who5: { name: "WHO-5", unit: "баллы", max: 100, survey: true, description: "Общее самочувствие. Больше баллов — выше оценка самочувствия." },
    pss10: { name: "PSS-10", unit: "баллы", max: 40, survey: true, description: "Воспринимаемый стресс. Больше баллов — выше оценка стресса." },
    gad7: { name: "GAD-7", unit: "баллы", max: 21, survey: true, description: "Симптомы тревоги. Баллы опросника не являются диагнозом." },
    phq9: { name: "PHQ-9", unit: "баллы", max: 27, survey: true, description: "Симптомы сниженного настроения. Баллы опросника не являются диагнозом." },
};
const views = {
    physical: { keys: ["pulse", "oxygen", "stress", "steps", "active_kcal", "distance", "sleep", "shower", "weight"], period: "month", active: "pulse" },
    mental: { keys: ["tension", "who5", "pss10", "gad7", "phq9"], period: "month", active: "tension" },
};
const phases = {
    out_of_bed: { label: "Вне постели", color: "var(--out)" },
    awake: { label: "Бодрствование", color: "var(--awake)" },
    rem: { label: "REM", color: "var(--rem)" },
    light: { label: "Лёгкий", color: "var(--light)" },
    deep: { label: "Глубокий", color: "var(--deep)" },
    sleeping: { label: "Без фазы", color: "var(--sleeping)" },
    unknown: { label: "Нет данных", color: "var(--unknown)" },
};
const stageKeys = Object.keys(phases);
const state = {
    view: "physical", data: null, seriesRequest: null, model: null, day: null,
    sleepRequest: null, sleepDay: null, sessions: [], night: null,
};
const numberFormats = [countFormat, ...[1, 2].map(maximumFractionDigits => new Intl.NumberFormat("ru-RU", { maximumFractionDigits }))];

function dayNumber(date) {
    if (!date) return null;
    const value = Date.parse(date) / DAY;
    return Number.isInteger(value) ? value : null;
}

function isoDate(day) {
    return new Date(day * DAY).toISOString().slice(0, 10);
}

function dateRange(window) {
    if (window.start === null || window.end === null) return "Нет дат с записями";
    if (window.start === window.end) return fullDate.format(window.start * DAY);
    return fullDate.formatRange(window.start * DAY, window.end * DAY);
}

function duration(minutes, seconds = false) {
    const total = seconds ? Math.round(minutes * 60) : Math.round(minutes) * 60;
    const hours = Math.floor(total / 3600);
    const mins = Math.floor(total / 60) % 60;
    const secs = total % 60;
    return [hours && `${hours} ч`, mins && `${mins} мин`, seconds && secs && `${secs} с`].filter(Boolean).join(" ") || (seconds ? "0 с" : "0 мин");
}

function formatNumber(value, digits = 0) {
    return numberFormats[digits].format(value);
}

function formatValue(metric, value) {
    if (value === null || value === undefined) return "—";
    if (metric.duration) return duration(value, metric.seconds);
    const unit = metric.survey ? `из ${metric.max}` : metric.unit === "баллы" ? "б." : metric.unit === "шаги" ? "шаг." : metric.unit;
    return `${formatNumber(value, metric.digits)} ${unit}`;
}

function formatDelta(metric, value, previous) {
    const delta = value - previous;
    const absolute = metric.duration ? duration(Math.abs(delta), metric.seconds) : formatNumber(Math.abs(delta), metric.digits);
    const unchanged = metric.duration ? absolute === "0 мин" || absolute === "0 с" : absolute === "0";
    return unchanged ? "Без изменения" : `${delta < 0 ? "−" : "+"}${absolute} к пред. периоду`;
}

function mean(values) {
    return values.length ? values.reduce((total, value) => total + value, 0) / values.length : null;
}

function element(name, text, className) {
    const node = document.createElement(name);
    if (text !== undefined) node.textContent = text;
    if (className) node.className = className;
    return node;
}

function svg(name, attributes, text) {
    const node = document.createElementNS(SVG, name);
    for (const [key, value] of Object.entries(attributes)) node.setAttribute(key, value);
    if (text !== undefined) node.textContent = text;
    return node;
}

function stat(label, value, note) {
    const wrapper = element("div", undefined, "stat");
    const content = element("dd", value);
    if (note) content.append(element("small", note));
    wrapper.append(element("dt", label), content);
    return wrapper;
}

function table(target, headers, rows) {
    const grid = element("table");
    const head = grid.createTHead().insertRow();
    for (const label of headers) {
        const cell = element("th", label);
        cell.scope = "col";
        head.append(cell);
    }
    const body = grid.createTBody();
    for (const values of rows) {
        const row = body.insertRow();
        for (const value of values) row.insertCell().textContent = value;
    }
    target.replaceChildren(grid);
}

function sleepAnalysis(record) {
    const start = Date.parse(record.start);
    const end = Date.parse(record.end);
    if (!(start < end)) return null;
    const events = [{ at: start }, { at: end }];
    for (const stage of record.stages ?? []) {
        const from = Math.max(start, Date.parse(stage.start));
        const until = Math.min(end, Date.parse(stage.end));
        if (!(from < until)) continue;
        const kind = Object.hasOwn(phases, stage.stage) ? stage.stage : "unknown";
        events.push({ at: from, kind, delta: 1 }, { at: until, kind, delta: -1 });
    }
    events.sort((a, b) => a.at - b.at);
    const active = Object.fromEntries(stageKeys.map(kind => [kind, 0]));
    const segments = [];
    let awake = 0;
    let from = start;
    for (const { at, kind, delta } of events) {
        if (at > from) {
            const detail = active.rem || active.light || active.deep;
            const possible = stageKeys.filter(key => active[key] && key !== "unknown" && !(key === "sleeping" && detail) && !(key === "awake" && active.out_of_bed));
            const stage = possible.length === 1 ? possible[0] : "unknown";
            if (active.awake || active.out_of_bed) awake += at - from;
            const previous = segments.at(-1);
            if (previous?.stage === stage) previous.end = at;
            else segments.push({ start: from, end: at, stage });
            from = at;
        }
        if (kind) active[kind] += delta;
    }
    return { start, end, segments, minutes: (end - start - awake) / MINUTE, offset: timeOffset(record.start) };
}

function buildWindow(metric, source) {
    const start = dayNumber(source.start);
    const end = dayNumber(source.end);
    const days = new Map();
    let count = 0;
    for (const record of source.records) {
        const day = dayNumber(record.date);
        if (day === null || start === null || end === null || day < start || day > end || !Number.isFinite(record.value)) continue;
        const value = record.value / (metric.scale ?? 1);
        count++;
        const accumulated = days.get(day);
        if (metric.survey) {
            const instant = Date.parse(record.time);
            const time = Number.isFinite(instant) ? instant : day * DAY;
            if (!accumulated || time > accumulated.time || (time === accumulated.time && record.id > accumulated.id)) {
                days.set(day, { value, time, id: record.id });
            }
        } else if (accumulated) {
            accumulated.value += value;
            accumulated.count++;
        } else {
            days.set(day, { value, count: 1 });
        }
    }
    const points = [...days].sort(([a], [b]) => a - b).map(([day, item]) => ({ day, value: metric.survey || metric.total ? item.value : item.value / item.count }));
    const sum = points.reduce((total, point) => total + point.value, 0);
    const value = !points.length ? null : metric.survey ? points.at(-1).value : sum / points.length;
    return { start, end, points, lookup: new Map(points.map(point => [point.day, point.value])), value, sum, count };
}

function buildModel(key, envelope) {
    const metric = metrics[key];
    const current = buildWindow(metric, envelope.current);
    const previous = envelope.previous ? buildWindow(metric, envelope.previous) : null;
    const displacement = previous?.end != null && current.end !== null ? current.end - previous.end : 0;
    const aligned = previous && current.start !== null && current.end !== null
        ? previous.points.map(({ day, value }) => ({ day: day + displacement, value })).filter(({ day }) => day >= current.start && day <= current.end)
        : [];
    const average = envelope.all_time?.daily_mean;
    return { key, metric, current, previous, aligned, displacement,
        lifetimeAverage: Number.isFinite(average) ? average / (metric.scale ?? 1) : null,
        rawCurrent: envelope.current.records };
}

function shouldCompare() {
    return !!state.model?.previous && $("compare").checked;
}

function renderStats({ metric, current, previous }) {
    const primary = metric.survey ? "Последний результат" : "Среднее за день";
    const currentNote = metric.total && current.points.length ? `Всего: ${formatValue(metric, current.sum)}` : "";
    const periodStat = stat(primary, formatValue(metric, current.value), currentNote);
    const change = current.value !== null && previous?.value != null ? formatDelta(metric, current.value, previous.value) : "";
    const priorNote = previous ? [dateRange(previous), change].filter(Boolean).join(" · ") : "";
    const priorStat = previous ? stat("Предыдущий период", formatValue(metric, previous.value), priorNote)
        : stat("Записей за всё время", formatNumber(current.count));
    const days = current.start === null ? null : current.end - current.start + 1;
    const coverage = days === null ? "0" : `${formatNumber(current.points.length)} / ${formatNumber(days)}`;
    const lastObserved = current.points.at(-1);
    const coverageStat = stat("Дней с данными", coverage, lastObserved && `Последняя: ${fullDate.format(lastObserved.day * DAY)}`);
    $("metric-stats").replaceChildren(periodStat, priorStat, coverageStat);
}

function renderMetricMenu() {
    const view = views[state.view];
    const buttons = document.createDocumentFragment();
    const options = document.createDocumentFragment();
    for (const key of view.keys) {
        const metric = metrics[key];
        const button = element("button", undefined, "metric-button");
        button.type = "button";
        button.dataset.metric = key;
        button.append(element("span", metric.name), element("small", metric.unit));
        buttons.append(button);
        options.append(new Option(metric.name, key));
    }
    $("metric-list").replaceChildren(buttons);
    $("metric-select").replaceChildren(options);
}

function updateControls() {
    const view = views[state.view];
    for (const button of $("views").querySelectorAll("button")) button.setAttribute("aria-pressed", button.dataset.view === state.view);
    for (const button of $("periods").querySelectorAll("button")) button.setAttribute("aria-pressed", button.dataset.period === view.period);
    for (const button of $("metric-list").querySelectorAll("button")) button.setAttribute("aria-pressed", button.dataset.metric === view.active);
    $("metric-select").value = view.active;
    document.querySelector(".workspace").dataset.view = state.view;
}

async function getJson(path, signal) {
    const response = await fetch(path, { credentials: "same-origin", cache: "no-store", signal, headers: { Accept: "application/json" } });
    if (!response.ok) throw new Error(response.status === 401 || response.status === 403 ? "Доступ не подтверждён. Проверьте учётные данные сервера." : `Сервер вернул ошибку (${response.status}).`);
    return response.json();
}

async function loadSeries() {
    const { period } = views[state.view];
    state.seriesRequest?.abort();
    clearSleep();
    state.data = state.model = null;
    $("range-label").textContent = "";
    $("request-state").hidden = false;
    $("metric-panel").hidden = $("sleep-panel").hidden = $("dayparts-panel").hidden = true;
    $("request-title").textContent = "Загружаем показатели";
    $("request-message").textContent = "Получаем историю с личного сервера.";
    $("retry").hidden = true;
    const request = new AbortController();
    state.seriesRequest = request;
    try {
        const payload = await getJson(`/v1/series?period=${period}`, request.signal);
        if (request.signal.aborted) return;
        if (payload.period !== period || Object.keys(metrics).some(key => !Array.isArray(payload.series?.[key]?.current?.records))) {
            throw new Error("Формат истории не поддерживается этой версией панели.");
        }
        state.data = payload;
        renderMetric();
    } catch (error) {
        if (request.signal.aborted) return;
        state.data = state.model = null;
        clearSleep();
        $("metric-panel").hidden = $("sleep-panel").hidden = $("dayparts-panel").hidden = true;
        $("request-state").hidden = false;
        $("request-title").textContent = "Не удалось загрузить историю";
        $("request-message").textContent = error instanceof TypeError || error instanceof SyntaxError ? "Проверьте подключение к личному серверу и повторите запрос." : error.message;
        $("retry").hidden = false;
    } finally {
        if (state.seriesRequest === request) state.seriesRequest = null;
    }
}

function renderMetric() {
    if (!state.data) return;
    clearSleep();
    const key = views[state.view].active;
    const model = buildModel(key, state.data.series[key]);
    const { metric, current } = model;
    state.model = model;
    $("request-state").hidden = true;
    $("metric-panel").hidden = false;
    $("sleep-panel").hidden = key !== "sleep";
    $("dayparts-panel").hidden = key !== "tension";
    $("range-label").textContent = dateRange(current);
    $("metric-kicker").textContent = names[state.view];
    $("metric-title").textContent = metric.name;
    $("metric-description").textContent = metric.description;
    $("chart-unit").textContent = metric.max ? `${metric.unit} · 0–${metric.max}` : metric.unit;
    $("comparison-control").hidden = !model.aligned.length;
    $("data-details").open = false;
    $("data-table").replaceChildren();
    renderStats(model);
    if (key === "tension") renderDayparts(model.rawCurrent);
    if (key === "sleep") {
        const placeholder = new Option("Нет записи на дату", "");
        placeholder.disabled = true;
        const options = document.createDocumentFragment();
        options.append(placeholder);
        for (let i = current.points.length - 1; i >= 0; i--) {
            const { day } = current.points[i];
            options.append(new Option(fullDate.format(day * DAY), isoDate(day)));
        }
        $("sleep-date").replaceChildren(options);
        $("sleep-date-control").hidden = !current.points.length;
    }
    state.day = current.points.at(-1)?.day ?? current.end;
    renderChartState();
    selectDay(state.day);
    drawMetric();
}

function renderChartState() {
    const { current, aligned, metric } = state.model;
    const compared = shouldCompare() ? aligned : [];
    const empty = !current.points.length && !compared.length;
    $("metric-chart").hidden = empty;
    $("chart-legend").hidden = empty;
    $("empty-chart").hidden = !empty;
    $("empty-chart").textContent = "В этом периоде нет записей для показателя.";
    $("previous-key").hidden = !compared.length;
    $("chart-inspection").hidden = empty;
    $("data-details").hidden = empty;
    $("chart-note").textContent = empty ? "" : [
        metric.survey ? "Точки — последние результаты каждого дня; промежутки не интерполируются." : "Разрывы — дни без записей, а не нулевые значения.",
        compared.length ? "Дни совмещены относительно конца каждого периода." : "",
    ].filter(Boolean).join(" ");
    if (!empty && $("data-details").open) renderDataTable();
}

function selectDay(day) {
    const { current, key, metric, previous, displacement, lifetimeAverage } = state.model;
    if (day !== null && (day < current.start || day > current.end)) return;
    state.day = day;
    if (current.start !== null && current.end !== null && day !== null) {
        const input = $("inspect-date");
        input.min = isoDate(current.start);
        input.max = isoDate(current.end);
        input.value = isoDate(day);
        $("day-back").disabled = day === current.start;
        $("day-forward").disabled = day === current.end;
        const text = [`${shortDate.format(day * DAY)} · выбранный: ${current.lookup.has(day) ? formatValue(metric, current.lookup.get(day)) : "нет записи"}`];
        if (shouldCompare()) {
            const compareDay = day - displacement;
            if (compareDay >= previous.start && compareDay <= previous.end) {
                text.push(`${shortDate.format(compareDay * DAY)} · предыдущий: ${previous.lookup.has(compareDay) ? formatValue(metric, previous.lookup.get(compareDay)) : "нет записи"}`);
            }
        }
        if (lifetimeAverage !== null && key !== "sleep") text.push(`среднее за всё время: ${formatValue(metric, lifetimeAverage)}`);
        $("day-value").textContent = text.join(" / ");
    }
    drawMetricCursor();
    if (key === "sleep") {
        $("sleep-date").value = current.lookup.has(day) ? isoDate(day) : "";
        if (day === null || !current.lookup.has(day)) {
            clearSleep();
            $("sleep-status").textContent = day === null ? "Нет записей о сне за этот период." : `Нет записи о сне за ${fullDate.format(day * DAY)}.`;
        } else if (state.sleepDay !== day) {
            loadSleep(day);
        }
    }
}

function renderDataTable() {
    const { current, previous, aligned, displacement, metric } = state.model;
    const compare = shouldCompare();
    const days = [...new Set([...current.lookup.keys(), ...(compare ? aligned.map(point => point.day) : [])])].sort((a, b) => b - a);
    const headers = compare ? ["Дата", "Значение", "Пред. дата", "Значение"] : ["Дата", "Значение"];
    table($("data-table"), headers, days.map(day => {
        const row = [fullDate.format(day * DAY), formatValue(metric, current.lookup.get(day))];
        if (compare) {
            const prior = day - displacement;
            row.push(prior < previous.start || prior > previous.end ? "—" : fullDate.format(prior * DAY), formatValue(metric, previous.lookup.get(prior)));
        }
        return row;
    }));
}

function tickSize(raw) {
    const base = 10 ** Math.floor(Math.log10(raw));
    return ([1, 2, 2.5, 5, 10].find(value => value * base >= raw) ?? 10) * base;
}

function chartScale(metric, series) {
    if (metric.max) {
        const steps = metric.max === 21 || metric.max === 27 ? 3 : 4;
        return { min: 0, max: metric.max, ticks: Array.from({ length: steps + 1 }, (_, index) => index * metric.max / steps) };
    }
    let min = Infinity;
    let max = -Infinity;
    for (const points of series) for (const { value } of points) {
        if (value < min) min = value;
        if (value > max) max = value;
    }
    if (metric.total) min = Math.min(0, min);
    const span = Math.max(max - min, 10 ** -(metric.digits ?? 0));
    const step = tickSize(span / 4);
    if (max === min) {
        max += span / 2;
        min -= span / 2;
        if (metric.total && min < 0) min = 0;
    }
    const low = Math.floor(min / step);
    const high = Math.ceil(max / step);
    return { min: low * step, max: high * step, ticks: Array.from({ length: high - low + 1 }, (_, i) => (i + low) * step) };
}

function axisNumber(value) {
    if (Math.abs(value) >= 1000) return `${formatNumber(value / 1000, 1)} тыс.`;
    return formatNumber(value, 2);
}

function axisDateTicks(start, end, width) {
    const steps = Math.min(end - start, Math.max(1, Math.floor(width / 82)));
    return steps === 0 ? [start] : Array.from({ length: steps + 1 }, (_, i) => start + Math.round(i * (end - start) / steps));
}

let metricPlot = null;
function drawMetric() {
    const plot = $("metric-chart");
    if (plot.hidden || $("metric-panel").hidden) return;
    const width = plot.clientWidth;
    const height = plot.clientHeight;
    if (width < 120 || height < 90) return;
    const { current, aligned, metric } = state.model;
    const previous = shouldCompare() ? aligned : [];
    const { min, max, ticks } = chartScale(metric, [current.points, previous]);
    const left = Math.max(32, ...ticks.map(value => axisNumber(value).length * 7 + 10));
    const right = width - 4;
    const top = 10;
    const bottom = height - 27;
    const x = day => current.start === current.end ? (left + right) / 2 : left + (day - current.start) / (current.end - current.start) * (right - left);
    const y = value => bottom - (value - min) / (max - min) * (bottom - top);
    plot.setAttribute("viewBox", `0 0 ${width} ${height}`);
    plot.setAttribute("aria-label", `${metric.name}. ${dateRange(current)}. Значения доступны ниже графика.`);
    const content = document.createDocumentFragment();
    for (const value of ticks) {
        const row = y(value);
        content.append(svg("line", { x1: left, y1: row, x2: right, y2: row, class: "grid" }), svg("text", { x: left - 8, y: row + 4, "text-anchor": "end", class: "axis" }, axisNumber(value)));
    }
    const dates = axisDateTicks(current.start, current.end, right - left);
    dates.forEach((day, i) => {
        const anchor = dates.length === 1 ? "middle" : i === 0 ? "start" : i === dates.length - 1 ? "end" : "middle";
        content.append(svg("text", { x: x(day), y: bottom + 20, "text-anchor": anchor, class: "axis" }, shortDate.format(day * DAY)));
    });
    const cursor = svg("g", {});
    content.append(cursor);
    for (const [isPrior, points] of [[true, previous], [false, current.points]]) {
        const css = isPrior ? " previous" : "";
        const markers = [];
        const sparse = points.length <= (right - left) / 8;
        const path = points.map(({ day, value }, i) => {
            const connects = !metric.survey && points[i - 1]?.day === day - 1;
            const isolated = !connects && (metric.survey || points[i + 1]?.day !== day + 1);
            if (sparse || isolated) markers.push(svg("circle", { cx: x(day), cy: y(value), r: sparse ? 3 : 1.7, class: `point${css}` }));
            return `${connects ? "L" : "M"}${x(day).toFixed(2)} ${y(value).toFixed(2)}`;
        });
        if (path.length && !metric.survey) content.append(svg("path", { d: path.join(" "), class: `trace${css}` }));
        for (const marker of markers) content.append(marker);
    }
    const selector = svg("rect", { x: left - 5, y: top - 4, width: right - left + 9, height: bottom - top + 8, class: "selector" });
    selector.addEventListener("click", event => {
        const plotX = event.clientX - plot.getBoundingClientRect().left;
        const fraction = Math.min(1, Math.max(0, (plotX - left) / (right - left)));
        selectDay(current.start + Math.round(fraction * (current.end - current.start)));
    });
    content.append(selector);
    plot.replaceChildren(content);
    metricPlot = { model: state.model, cursor, x, y, top, bottom };
    drawMetricCursor();
}

function drawMetricCursor() {
    if (metricPlot?.model !== state.model || state.day === null) return;
    const { cursor, x, y, top, bottom } = metricPlot;
    const at = x(state.day);
    const { current, previous, displacement } = state.model;
    const now = current.lookup.get(state.day);
    const before = shouldCompare() && previous.lookup.get(state.day - displacement);
    cursor.replaceChildren(svg("line", { x1: at, x2: at, y1: top, y2: bottom, class: "cursor" }));
    if (Number.isFinite(before)) cursor.append(svg("circle", { cx: at, cy: y(before), r: 4.5, class: "point previous" }));
    if (Number.isFinite(now)) cursor.append(svg("circle", { cx: at, cy: y(now), r: 4.5, class: "point" }));
}

function timeOffset(iso) {
    const match = /([+-])(\d{2}):?(\d{2})$/.exec(iso);
    if (!match) return 0;
    return (match[1] === "-" ? -1 : 1) * (Number(match[2]) * 60 + Number(match[3]));
}

function clock(time, offset) {
    return new Date(time + offset * MINUTE).toISOString().slice(11, 16);
}

function offsetLabel(offset) {
    if (!offset) return "UTC";
    const abs = Math.abs(offset);
    return `UTC${offset < 0 ? "−" : "+"}${String(Math.floor(abs / 60)).padStart(2, "0")}:${String(abs % 60).padStart(2, "0")}`;
}

function renderDayparts(records) {
    const byDay = new Map();
    for (const record of records) {
        const hour = /^\d{4}-\d{2}-\d{2}T(\d{2}):/.exec(record.time)?.[1];
        if (hour === undefined || !Number.isFinite(record.value)) continue;
        const part = +hour >= 6 && +hour <= 11 ? 0 : +hour >= 18 && +hour <= 23 ? 1 : -1;
        if (part === -1) continue;
        if (!byDay.has(record.date)) byDay.set(record.date, [[], []]);
        byDay.get(record.date)[part].push(record.value);
    }
    const paired = [...byDay.values()].filter(([morning, evening]) => morning.length && evening.length);
    const enough = paired.length >= 2;
    $("dayparts-values").replaceChildren(
        stat("Утро · 06:00–11:59", enough ? formatNumber(mean(paired.map(([values]) => mean(values))), 1) : "—"),
        stat("Вечер · 18:00–23:59", enough ? formatNumber(mean(paired.map(([, values]) => mean(values))), 1) : "—"),
        stat("Парных дней", formatNumber(paired.length)),
    );
    $("dayparts-note").textContent = enough ? "Время отметок берётся с телефона." : "Для сравнения нужно не менее двух дней с утренней и вечерней отметками.";
}

function clearSleep() {
    state.sleepRequest?.abort();
    state.sleepRequest = null;
    state.sleepDay = null;
    state.sessions = [];
    state.night = null;
    $("sleep-retry").hidden = true;
    $("sleep-content").hidden = true;
}

async function loadSleep(day) {
    clearSleep();
    state.sleepDay = day;
    $("sleep-status").textContent = "Загружаем сеансы сна…";
    const request = new AbortController();
    state.sleepRequest = request;
    try {
        const date = isoDate(day);
        const payload = await getJson(`/v1/sleep?date=${date}`, request.signal);
        if (request.signal.aborted) return;
        if (payload.date !== date || !Array.isArray(payload.records)) throw new Error("Сервер вернул данные за другую дату или в неизвестном формате.");
        state.sessions = payload.records.map(sleepAnalysis).filter(Boolean).sort((a, b) => a.start - b.start);
        if (!state.sessions.length) {
            $("sleep-status").textContent = "Нет сохранённых сеансов за эту дату.";
            return;
        }
        $("sleep-session").replaceChildren(...state.sessions.map((night, i) => new Option(`${clock(night.start, night.offset)}–${clock(night.end, night.offset)}`, i)));
        $("sleep-session-control").hidden = state.sessions.length === 1;
        $("sleep-status").textContent = "";
        $("sleep-content").hidden = false;
        const comparison = payload.comparison;
        const previousDate = comparison?.previous_date ? fullDate.format(dayNumber(comparison.previous_date) * DAY) : "";
        const asDuration = minutes => Number.isFinite(minutes) ? duration(minutes) : "—";
        $("sleep-comparison").replaceChildren(
            stat("Выбранная ночь", asDuration(comparison?.minutes)),
            stat("Предыдущая ночь", asDuration(comparison?.previous_minutes), previousDate),
            stat("Среднее по более ранним ночам", asDuration(comparison?.earlier_mean_minutes)),
        );
        selectSession(0);
    } catch (error) {
        if (request.signal.aborted) return;
        $("sleep-content").hidden = true;
        $("sleep-status").textContent = error instanceof TypeError || error instanceof SyntaxError ? "Не удалось получить фазы сна. Проверьте подключение." : error.message;
        $("sleep-retry").hidden = false;
    }
}

function selectSession(index) {
    const night = state.sessions[index];
    if (!night) return;
    state.night = night;
    $("sleep-window").textContent = `${clock(night.start, night.offset)}–${clock(night.end, night.offset)} (${offsetLabel(night.offset)}) · сна ${duration(night.minutes)}`;
    const totals = new Map();
    for (const { stage, start, end } of night.segments) totals.set(stage, (totals.get(stage) ?? 0) + (end - start) / MINUTE);
    $("sleep-legend").replaceChildren(...stageKeys.filter(stage => totals.has(stage)).map(stage => {
        const key = svg("svg", { viewBox: "0 0 13 12", "aria-hidden": "true" });
        key.append(svg("line", { x1: 0, y1: 6, x2: 13, y2: 6, class: `stage-segment${stage === "unknown" ? " stage-gap" : ""}`, stroke: phases[stage].color }));
        const item = element("li");
        item.append(key, `${phases[stage].label} · ${duration(totals.get(stage), true)}`);
        return item;
    }));
    $("sleep-details").open = false;
    $("sleep-table").replaceChildren();
    if (totals.size === 1 && totals.has("unknown")) $("sleep-status").textContent = "Длительность сеанса сохранена, но однозначных данных о фазах нет.";
    else $("sleep-status").textContent = "";
    drawSleep();
}

function drawSleep() {
    const night = state.night;
    const plot = $("sleep-chart");
    if (!night || $("sleep-content").hidden || $("sleep-panel").hidden) return;
    const width = plot.clientWidth;
    if (width < 120) return;
    const stages = new Set(night.segments.map(segment => segment.stage));
    const rows = stageKeys.filter(stage => stages.has(stage));
    const left = Math.max(64, ...rows.map(stage => phases[stage].label.length * 7 + 8));
    const right = width - 5;
    const height = rows.length * 28 + 26;
    const x = time => left + (time - night.start) / (night.end - night.start) * (right - left);
    const y = stage => 14 + rows.indexOf(stage) * 28;
    plot.setAttribute("viewBox", `0 0 ${width} ${height}`);
    plot.setAttribute("height", height);
    plot.setAttribute("aria-label", `Фазы сна за ${fullDate.format(state.sleepDay * DAY)}. Список интервалов доступен ниже.`);
    const content = document.createDocumentFragment();
    for (const stage of rows) {
        const row = y(stage);
        content.append(svg("line", { x1: left, x2: right, y1: row, y2: row, class: "grid" }), svg("text", { x: left - 7, y: row + 4, "text-anchor": "end", class: "axis" }, phases[stage].label));
    }
    const ticks = Math.max(1, Math.min(6, Math.floor((right - left) / 66)));
    for (let tick = 0; tick <= ticks; tick++) {
        const time = night.start + tick * (night.end - night.start) / ticks;
        const anchor = tick === 0 ? "start" : tick === ticks ? "end" : "middle";
        content.append(svg("text", { x: x(time), y: height - 8, "text-anchor": anchor, class: "axis" }, clock(time, night.offset)));
    }
    night.segments.forEach(({ start, end, stage }, i) => {
        const row = y(stage);
        const at = x(start);
        const prior = night.segments[i - 1];
        if (prior && prior.stage !== "unknown" && stage !== "unknown") {
            content.append(svg("line", { x1: at, x2: at, y1: y(prior.stage), y2: row, class: "stage-join" }));
        }
        const line = svg("line", { x1: at, x2: x(end), y1: row, y2: row, class: `stage-segment${stage === "unknown" ? " stage-gap" : ""}`, stroke: phases[stage].color });
        line.append(svg("title", {}, `${phases[stage].label}: ${clock(start, night.offset)}–${clock(end, night.offset)}`));
        content.append(line);
    });
    plot.replaceChildren(content);
}

$("views").addEventListener("click", event => {
    const view = event.target.closest("button")?.dataset.view;
    if (!view || view === state.view) return;
    const period = views[state.view].period;
    state.view = view;
    renderMetricMenu();
    updateControls();
    if (state.data?.period === views[view].period) renderMetric();
    else if (period !== views[view].period || !state.seriesRequest) loadSeries();
});
$("periods").addEventListener("click", event => {
    const period = event.target.closest("button")?.dataset.period;
    if (!period || period === views[state.view].period) return;
    views[state.view].period = period;
    updateControls();
    loadSeries();
});
function chooseMetric(key) {
    if (key === views[state.view].active) return;
    views[state.view].active = key;
    updateControls();
    renderMetric();
}
$("metric-list").addEventListener("click", event => {
    const key = event.target.closest("button")?.dataset.metric;
    if (key) chooseMetric(key);
});
$("metric-select").addEventListener("change", event => chooseMetric(event.target.value));
$("retry").addEventListener("click", loadSeries);
$("compare").addEventListener("change", () => {
    renderChartState();
    drawMetric();
    selectDay(state.day);
});
$("inspect-date").addEventListener("change", event => {
    const day = dayNumber(event.target.value);
    if (day !== null && event.target.validity.valid) selectDay(day);
    else event.target.value = isoDate(state.day);
});
$("day-back").addEventListener("click", () => selectDay(state.day - 1));
$("day-forward").addEventListener("click", () => selectDay(state.day + 1));
$("sleep-date").addEventListener("change", event => selectDay(dayNumber(event.target.value)));
$("sleep-session").addEventListener("change", event => selectSession(Number(event.target.value)));
$("sleep-retry").addEventListener("click", () => loadSleep(state.day));
$("data-details").addEventListener("toggle", event => {
    if (event.currentTarget.open && state.model) renderDataTable();
});
$("sleep-details").addEventListener("toggle", event => {
    const night = state.night;
    if (!event.currentTarget.open || !night) return;
    table($("sleep-table"), ["Фаза", "Начало", "Конец", "Длительность"], night.segments.map(({ stage, start, end }) => [
        phases[stage].label, clock(start, night.offset), clock(end, night.offset), duration((end - start) / MINUTE, true),
    ]));
});
new ResizeObserver(drawMetric).observe($("metric-chart"));
new ResizeObserver(drawSleep).observe($("sleep-chart"));
renderMetricMenu();
updateControls();
loadSeries();
