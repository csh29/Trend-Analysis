/**
 * 꺾은선 차트(SVG). 외부 차트 라이브러리를 싣지 않으려고 둔 최소 구현 — 주별 추이처럼 x축이 범주이고 값이 0 이상인 정수일 때 쓴다.
 *
 * 사용법:
 *   LineChart.render(containerEl, {
 *       labels: ['09/07', '09/14', ...],                  // x축
 *       series: [{ name: '레더', values: [1, 2, ...] }],  // labels와 길이가 같다
 *       height: 260,                                       // 생략하면 260
 *       emptyMessage: '데이터가 없습니다.'
 *   });
 *
 * x칸에 마우스를 올리면 안내선과 함께 그 칸의 모든 시리즈 값이 커스텀 툴팁으로 바로 뜬다(값 큰 순). 범례는 차트 아래에 그린다.
 * 같은 x에서 값이 같은 시리즈는 세로로 몇 px씩 비켜 그린다 — 그대로 그리면 마지막 선만 보인다(값은 바꾸지 않는다).
 */
(function (global) {
    const NS = 'http://www.w3.org/2000/svg';
    // 색은 구분이 잘 되는 순서로 둔다(첫 값이 가장 눈에 띄게 — 화면 강조색).
    const COLORS = ['#f43f68', '#2f5bd3', '#2e7d32', '#b8860b', '#7b4fd6', '#0f9aa8', '#8b8ba7'];
    const PAD = { top: 16, right: 18, bottom: 28, left: 36 };
    const DODGE = 3; // 겹친 점끼리 벌리는 간격(px)

    function el(name, attrs, text) {
        const node = document.createElementNS(NS, name);
        Object.keys(attrs || {}).forEach(function (k) { node.setAttribute(k, attrs[k]); });
        if (text !== undefined) node.textContent = text;
        return node;
    }

    /** y축 눈금 — 정수만, 4칸 안팎. */
    function ticks(max) {
        const top = Math.max(1, max);
        const step = Math.max(1, Math.ceil(top / 4));
        const result = [];
        for (let v = 0; v <= top + step - 1; v += step) result.push(v);
        return result;
    }

    function render(container, options) {
        const labels = options.labels || [];
        const series = (options.series || []).filter(function (s) { return s.values && s.values.length; });
        const height = options.height || 260;
        container.innerHTML = '';

        if (!labels.length || !series.length) {
            const empty = document.createElement('div');
            empty.className = 'line-chart-empty';
            empty.textContent = options.emptyMessage || '데이터가 없습니다.';
            container.appendChild(empty);
            return;
        }

        const width = Math.max(320, container.clientWidth || 640);
        const max = Math.max.apply(null, series.map(function (s) { return Math.max.apply(null, s.values); }));
        const yTicks = ticks(max);
        const yMax = yTicks[yTicks.length - 1] || 1;
        const plotW = width - PAD.left - PAD.right;
        const plotH = height - PAD.top - PAD.bottom;
        const x = function (i) { return PAD.left + (labels.length === 1 ? plotW / 2 : plotW * i / (labels.length - 1)); };
        const y = function (v) { return PAD.top + plotH - plotH * v / yMax; };

        const svg = el('svg', { width: width, height: height, viewBox: '0 0 ' + width + ' ' + height, class: 'line-chart' });

        yTicks.forEach(function (v) {
            svg.appendChild(el('line', { x1: PAD.left, x2: width - PAD.right, y1: y(v), y2: y(v), class: 'line-chart-grid' }));
            svg.appendChild(el('text', { x: PAD.left - 8, y: y(v) + 4, 'text-anchor': 'end', class: 'line-chart-axis' }, String(v)));
        });
        // 라벨이 많으면 겹치지 않게 건너뛴다
        const every = Math.ceil(labels.length / Math.max(1, Math.floor(plotW / 56)));
        labels.forEach(function (label, i) {
            if (i % every !== 0 && i !== labels.length - 1) return;
            svg.appendChild(el('text', { x: x(i), y: height - 8, 'text-anchor': 'middle', class: 'line-chart-axis' }, label));
        });

        // offsets[si][i]: 같은 x에서 같은 값을 가진 시리즈들을 가운데 기준으로 위아래로 나눠 비킨다
        const offsets = series.map(function () { return new Array(labels.length).fill(0); });
        labels.forEach(function (label, i) {
            const groups = {};
            series.forEach(function (s, si) { (groups[s.values[i]] = groups[s.values[i]] || []).push(si); });
            Object.keys(groups).forEach(function (v) {
                const members = groups[v];
                members.forEach(function (si, k) { offsets[si][i] = (k - (members.length - 1) / 2) * DODGE; });
            });
        });

        series.forEach(function (s, si) {
            const color = COLORS[si % COLORS.length];
            const py = function (i) { return y(s.values[i]) + offsets[si][i]; };
            const points = s.values.map(function (v, i) { return x(i) + ',' + py(i); }).join(' ');
            svg.appendChild(el('polyline', { points: points, fill: 'none', stroke: color, 'stroke-width': 2.2, 'stroke-linejoin': 'round' }));
            s.values.forEach(function (v, i) {
                svg.appendChild(el('circle', { cx: x(i), cy: py(i), r: 3.5, fill: '#fff', stroke: color, 'stroke-width': 2 }));
            });
        });

        // 마우스를 올린 x칸의 안내선 + 커스텀 툴팁. 브라우저 기본 <title> 툴팁은 1초 가까이 늦게 떠서 쓰지 않는다.
        const guide = el('line', { y1: PAD.top, y2: PAD.top + plotH, class: 'line-chart-guide', visibility: 'hidden' });
        svg.appendChild(guide);
        const tip = document.createElement('div');
        tip.className = 'line-chart-tip';

        /** x칸 i의 값을 큰 순으로 툴팁에 채운다(이름은 textContent — 값에 HTML이 섞여도 안전). */
        function fillTip(i) {
            tip.innerHTML = '';
            const head = document.createElement('div');
            head.className = 'line-chart-tip-head';
            head.textContent = labels[i];
            tip.appendChild(head);
            series.map(function (s, si) { return { name: s.name, v: s.values[i], color: COLORS[si % COLORS.length] }; })
                .sort(function (a, b) { return b.v - a.v; })
                .forEach(function (r) {
                    const row = document.createElement('div');
                    row.className = 'line-chart-tip-row';
                    const swatch = document.createElement('i');
                    swatch.style.background = r.color;
                    const name = document.createElement('span');
                    name.textContent = r.name;
                    const value = document.createElement('b');
                    value.textContent = String(r.v);
                    row.appendChild(swatch);
                    row.appendChild(name);
                    row.appendChild(value);
                    tip.appendChild(row);
                });
        }

        /** 커서 옆에 두되 차트 영역 밖으로 나가면 반대쪽으로 넘긴다. */
        function moveTip(e) {
            const box = container.getBoundingClientRect();
            const left = e.clientX - box.left + 14;
            const top = e.clientY - box.top + 14;
            tip.style.left = (left + tip.offsetWidth > box.width ? left - tip.offsetWidth - 28 : left) + 'px';
            tip.style.top = (top + tip.offsetHeight > box.height ? top - tip.offsetHeight - 28 : top) + 'px';
        }

        const band = labels.length === 1 ? plotW : plotW / (labels.length - 1);
        labels.forEach(function (label, i) {
            const hit = el('rect', { x: x(i) - band / 2, y: PAD.top, width: band, height: plotH, fill: 'transparent', class: 'line-chart-hit' });
            hit.addEventListener('mouseenter', function (e) {
                guide.setAttribute('x1', x(i));
                guide.setAttribute('x2', x(i));
                guide.setAttribute('visibility', 'visible');
                fillTip(i);
                tip.style.display = 'block';
                moveTip(e);
            });
            hit.addEventListener('mousemove', moveTip);
            hit.addEventListener('mouseleave', function () {
                guide.setAttribute('visibility', 'hidden');
                tip.style.display = 'none';
            });
            svg.appendChild(hit);
        });
        container.appendChild(svg);
        // 툴팁은 컨테이너 기준으로 놓는다 — 화면이 컨테이너에 position을 주지 않았으면 relative로 둔다
        if (!container.style.position) container.style.position = 'relative';
        container.appendChild(tip);

        const legend = document.createElement('div');
        legend.className = 'line-chart-legend';
        series.forEach(function (s, si) {
            const item = document.createElement('span');
            const swatch = document.createElement('i');
            swatch.style.background = COLORS[si % COLORS.length];
            item.appendChild(swatch);
            item.appendChild(document.createTextNode(s.name));
            legend.appendChild(item);
        });
        container.appendChild(legend);
    }

    global.LineChart = { render: render };
})(window);
