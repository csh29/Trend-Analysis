/**
 * 분석(P-03 정형화·P-04 색인) 상태 표시와 결과 모달. 이미지 업로드·텍스트 등록 화면이 같이 쓴다(grid.js가 먼저 실려 있어야 한다).
 *
 * 사용법:
 *   TrendAnalysis.badge(status)                       // 상태 뱃지 엘리먼트(대기·분석 중·완료·실패)
 *   TrendAnalysis.openResult({ analysisUrl, reanalyzeUrl, onChanged })   // 결과 모달(재분석 버튼 포함)
 *   TrendAnalysis.pollWhilePending(rows, refreshFn)   // 대기·분석 중인 행이 있으면 5초 뒤 refreshFn(true)
 */
(function (global) {
    const STATUS = {
        PENDING: { label: '대기', css: 'pending' },
        PROCESSING: { label: '분석 중', css: 'running' },
        DONE: { label: '완료', css: 'success' },
        FAILED: { label: '실패', css: 'failed' }
    };
    const SENTIMENT = { positive: '긍정', neutral: '중립', negative: '부정' };
    const ITEM_COLUMNS = [
        { id: 'category', label: '카테고리', width: 90, align: 'center' },
        { id: 'type', label: '종류', width: 110, align: 'center' },
        { id: 'name', label: '아이템', width: 140 },
        { id: 'materials', label: '소재', title: true },
        { id: 'colors', label: '컬러', title: true },
        { id: 'silhouettes', label: '실루엣', title: true }
    ];
    const POLL_MS = 5000;

    function badge(status) {
        const span = document.createElement('span');
        const s = STATUS[status];
        span.className = 'badge ' + (s ? s.css : 'unknown');
        span.textContent = s ? s.label : '-';
        return span;
    }

    function isPending(status) {
        return status === 'PENDING' || status === 'PROCESSING';
    }

    let pollTimer = null;

    /** 분석이 끝나지 않은 행이 있으면 잠시 뒤 목록을 조용히(스피너 없이) 다시 읽는다. */
    function pollWhilePending(statuses, refreshFn) {
        clearTimeout(pollTimer);
        if (statuses.some(isPending)) {
            pollTimer = setTimeout(function () { refreshFn(true); }, POLL_MS);
        }
    }

    // ── 결과 모달 ──────────────────────────────────────────────

    let modal = null;

    function ensureModal() {
        if (modal) return modal;
        const backdrop = document.createElement('div');
        backdrop.className = 'modal-backdrop';
        backdrop.innerHTML =
            '<div class="modal" style="max-width:720px;">' +
            '  <h3 data-role="title">분석 결과</h3>' +
            '  <div data-role="body"></div>' +
            '  <div class="modal-actions">' +
            '    <button type="button" class="btn" data-role="reanalyze">재분석</button>' +
            '    <button type="button" class="btn" data-role="close">닫기</button>' +
            '  </div>' +
            '</div>';
        document.body.appendChild(backdrop);
        const close = function () { backdrop.classList.remove('open'); };
        backdrop.addEventListener('click', function (e) { if (e.target === backdrop) close(); });
        backdrop.querySelector('[data-role="close"]').addEventListener('click', close);
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && backdrop.classList.contains('open')) close();
        });
        modal = { backdrop: backdrop, close: close };
        return modal;
    }

    function addRow(dl, label, value) {
        const dt = document.createElement('dt');
        dt.textContent = label;
        const dd = document.createElement('dd');
        if (value instanceof Node) dd.appendChild(value); else dd.textContent = value == null || value === '' ? '-' : value;
        dl.append(dt, dd);
    }

    function names(codes) {
        return (codes || []).map(function (c) { return c.name; }).join(', ');
    }

    function render(view) {
        const body = document.createElement('div');
        const dl = document.createElement('dl');
        dl.className = 'detail-list';
        addRow(dl, '문서 ID', view.docId);
        const status = document.createElement('span');
        status.appendChild(badge(view.status));
        if (view.attempts > 1) status.append(' (시도 ' + view.attempts + '회)');
        addRow(dl, '상태', status);
        if (view.failReason) addRow(dl, '실패 사유', view.failReason);
        addRow(dl, '출처', [view.media, view.publishedAt].filter(Boolean).join(' · '));
        // 텍스트 원문에 첨부한 이미지 — 본문과 함께 분석했다. 누르면 원본을 새 창으로
        if (view.images && view.images.length) {
            const thumbs = document.createElement('div');
            thumbs.style.cssText = 'display:flex;flex-wrap:wrap;gap:6px;';
            view.images.forEach(function (image) {
                const a = document.createElement('a');
                a.href = image.url;
                a.target = '_blank';
                a.rel = 'noopener noreferrer';
                a.title = image.name;
                const img = document.createElement('img');
                img.src = image.url;
                img.alt = image.name;
                img.style.cssText = 'width:72px;height:72px;object-fit:cover;border:1px solid var(--line);border-radius:6px;display:block;';
                a.appendChild(img);
                thumbs.appendChild(a);
            });
            addRow(dl, '첨부 이미지 ' + view.images.length + '장', thumbs);
        }

        const r = view.result;
        if (r) {
            addRow(dl, '요약', r.summary);
            addRow(dl, '키워드', (r.keywords || []).join(', '));
            addRow(dl, '시즌', names(r.seasons));
            addRow(dl, '감성', SENTIMENT[r.sentiment] || r.sentiment);
            addRow(dl, '모델', r.model);
        }
        body.appendChild(dl);

        // 의류 아이템마다 카테고리·소재·컬러·실루엣이 따로 있다(코디 사진의 상의·하의 속성이 섞이지 않게)
        if (r && r.items && r.items.length) {
            const title = document.createElement('h3');
            title.textContent = '의류 아이템 ' + r.items.length + '개';
            title.style.marginTop = '16px';
            const table = document.createElement('table');
            table.className = 'grid';
            const thead = document.createElement('thead');
            const tbody = document.createElement('tbody');
            table.append(thead, tbody);
            Grid.renderHeader(thead, ITEM_COLUMNS);
            Grid.render(tbody, ITEM_COLUMNS, r.items.map(function (item) {
                return {
                    category: item.category ? item.category.name : '',
                    type: item.type ? item.type.name : '-',
                    name: item.name || '-',
                    materials: names(item.materials),
                    colors: names(item.colors),
                    silhouettes: names(item.silhouettes)
                };
            }));
            body.append(title, table);
        }

        if (r && r.json) {
            const title = document.createElement('h3');
            title.textContent = '정형 JSON';
            title.style.marginTop = '16px';
            const pre = document.createElement('pre');
            pre.className = 'code-block';
            pre.textContent = r.json;
            body.append(title, pre);
        }
        return body;
    }

    function openResult(options) {
        const m = ensureModal();
        fetch(options.analysisUrl)
            .then(function (res) {
                if (!res.ok) return res.text().then(function (msg) { throw new Error(msg || '분석 결과를 불러오지 못했습니다.'); });
                return res.json();
            })
            .then(function (view) {
                m.backdrop.querySelector('[data-role="title"]').textContent = '분석 결과 · ' + (view.title || view.docId);
                m.backdrop.querySelector('[data-role="body"]').replaceChildren(render(view));
                const reanalyze = m.backdrop.querySelector('[data-role="reanalyze"]');
                reanalyze.disabled = isPending(view.status);
                reanalyze.onclick = function () {
                    if (!confirm('이 문서를 다시 분석하시겠습니까? Claude API를 다시 호출합니다.')) return;
                    fetch(options.reanalyzeUrl, { method: 'POST' })
                        .then(function (res) {
                            if (!res.ok) return res.text().then(function (msg) { throw new Error(msg || '재분석 요청에 실패했습니다.'); });
                            m.close();
                            if (options.onChanged) options.onChanged();
                        })
                        .catch(function (err) { alert(err.message); });
                };
                m.backdrop.classList.add('open');
            })
            .catch(function (err) { alert(err.message); });
    }

    global.TrendAnalysis = { badge: badge, openResult: openResult, pollWhilePending: pollWhilePending, isPending: isPending };
})(window);
