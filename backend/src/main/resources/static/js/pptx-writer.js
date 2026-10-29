/**
 * 슬라이드 데이터를 .pptx로 만들어 내려받게 하는 최소 구현(외부 라이브러리 없음, zip은 zip-writer.js 사용).
 * 16:9 빈 레이아웃 한 장에 글상자·표·사진만 그린다. 위치·크기 단위는 인치(슬라이드 13.333 × 7.5).
 *
 * 사용법:
 *   PptxWriter.download('파일명.pptx', [
 *     { elements: [
 *         { type: 'text', x, y, w, h, paragraphs: [{ text, size(pt), bold, color('RRGGBB') }], fill('RRGGBB', 선택) },
 *         { type: 'table', x, y, w, colWidths: [인치...], rows: [['머리', ...], ['값', ...]], size(pt, 선택) },
 *         { type: 'image', x, y, w, h, data: Uint8Array(JPEG) } ] }, ...]);
 */
(function (global) {
    const EMU = 914400; // 1인치
    const SLIDE_W = 12192000;
    const SLIDE_H = 6858000;
    const FONT = '맑은 고딕';
    const HEADER_FILL = '1F3864'; // 표 머리(엑셀 다운로드 헤더와 같은 색)
    const XML = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>';
    const NS = 'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
        + 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
        + 'xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"';
    const REL = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships/';

    /** XML 특수문자 이스케이프 + XML에 들어갈 수 없는 제어문자 제거. */
    function esc(value) {
        return String(value === null || value === undefined ? '' : value)
            .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '')
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function emu(inch) {
        return Math.round(inch * EMU);
    }

    function runXml(p, defaultSize) {
        const size = Math.round((p.size || defaultSize || 14) * 100);
        return '<a:r><a:rPr lang="ko-KR" sz="' + size + '"' + (p.bold ? ' b="1"' : '') + ' dirty="0">'
            + '<a:solidFill><a:srgbClr val="' + (p.color || '222222') + '"/></a:solidFill>'
            + '<a:latin typeface="' + FONT + '"/><a:ea typeface="' + FONT + '"/></a:rPr>'
            + '<a:t>' + esc(p.text) + '</a:t></a:r>';
    }

    function paragraphXml(p, defaultSize) {
        return '<a:p>' + (p.bullet ? '<a:pPr marL="228600" indent="-228600"><a:buChar char="•"/></a:pPr>' : '')
            + runXml(p, defaultSize) + '</a:p>';
    }

    function textXml(el, id) {
        return '<p:sp><p:nvSpPr><p:cNvPr id="' + id + '" name="글상자 ' + id + '"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>'
            + '<p:spPr><a:xfrm><a:off x="' + emu(el.x) + '" y="' + emu(el.y) + '"/><a:ext cx="' + emu(el.w) + '" cy="' + emu(el.h) + '"/></a:xfrm>'
            + '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>'
            + (el.fill ? '<a:solidFill><a:srgbClr val="' + el.fill + '"/></a:solidFill>' : '<a:noFill/>') + '</p:spPr>'
            // 넘치면 글자를 줄여 상자 안에 맞춘다(normAutofit)
            + '<p:txBody><a:bodyPr wrap="square" lIns="91440" tIns="45720" rIns="91440" bIns="45720"><a:normAutofit/></a:bodyPr><a:lstStyle/>'
            // 글이 없는 상자(색 띠)도 문단이 하나는 있어야 한다 — 없으면 파워포인트가 "복구"를 묻는다
            + ((el.paragraphs || []).length ? el.paragraphs.map(function (p) { return paragraphXml(p, el.size); }).join('')
                : '<a:p><a:endParaRPr lang="ko-KR"/></a:p>')
            + '</p:txBody></p:sp>';
    }

    function cellXml(text, header, size) {
        const border = '<a:solidFill><a:srgbClr val="BFBFBF"/></a:solidFill>';
        return '<a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p>'
            + runXml({ text: text, size: size, bold: header, color: header ? 'FFFFFF' : '222222' }) + '</a:p></a:txBody>'
            + '<a:tcPr marL="68580" marR="68580" marT="34290" marB="34290" anchor="ctr">'
            + ['lnL', 'lnR', 'lnT', 'lnB'].map(function (ln) { return '<a:' + ln + ' w="6350">' + border + '</a:' + ln + '>'; }).join('')
            + (header ? '<a:solidFill><a:srgbClr val="' + HEADER_FILL + '"/></a:solidFill>' : '<a:noFill/>')
            + '</a:tcPr></a:tc>';
    }

    function tableXml(el, id) {
        const size = el.size || 11;
        const rowH = emu(size / 72 * 2.1); // 글자 크기에 맞춘 행 높이 — 글이 길면 파워포인트가 행을 늘린다
        const total = el.colWidths.reduce(function (s, w) { return s + w; }, 0);
        const scale = el.w / total;
        return '<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="' + id + '" name="표 ' + id + '"/>'
            + '<p:cNvGraphicFramePr><a:graphicFrameLocks noGrp="1"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>'
            + '<p:xfrm><a:off x="' + emu(el.x) + '" y="' + emu(el.y) + '"/><a:ext cx="' + emu(el.w) + '" cy="' + rowH * el.rows.length + '"/></p:xfrm>'
            + '<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table"><a:tbl>'
            + '<a:tblPr firstRow="1" bandRow="0"/><a:tblGrid>'
            + el.colWidths.map(function (w) { return '<a:gridCol w="' + emu(w * scale) + '"/>'; }).join('') + '</a:tblGrid>'
            + el.rows.map(function (row, r) {
                return '<a:tr h="' + rowH + '">' + row.map(function (v) { return cellXml(v, r === 0, size); }).join('') + '</a:tr>';
            }).join('')
            + '</a:tbl></a:graphicData></a:graphic></p:graphicFrame>';
    }

    function imageXml(el, id, relId) {
        return '<p:pic><p:nvPicPr><p:cNvPr id="' + id + '" name="사진 ' + id + '"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>'
            + '<p:blipFill><a:blip r:embed="' + relId + '"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>'
            + '<p:spPr><a:xfrm><a:off x="' + emu(el.x) + '" y="' + emu(el.y) + '"/><a:ext cx="' + emu(el.w) + '" cy="' + emu(el.h) + '"/></a:xfrm>'
            + '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>';
    }

    /** 슬라이드 하나의 XML과 rels·사진 파일. */
    function slideParts(slide, index, media) {
        const rels = ['<Relationship Id="rId1" Type="' + REL + 'slideLayout" Target="../slideLayouts/slideLayout1.xml"/>'];
        let id = 2;
        const shapes = (slide.elements || []).map(function (el) {
            const shapeId = id++;
            if (el.type === 'table') return tableXml(el, shapeId);
            if (el.type === 'image') {
                const name = 'image' + (media.length + 1) + '.jpeg';
                media.push({ name: 'ppt/media/' + name, data: el.data });
                const relId = 'rId' + (rels.length + 1);
                rels.push('<Relationship Id="' + relId + '" Type="' + REL + 'image" Target="../media/' + name + '"/>');
                return imageXml(el, shapeId, relId);
            }
            return textXml(el, shapeId);
        }).join('');
        return {
            xml: XML + '<p:sld ' + NS + '><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>'
                + '<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>'
                + shapes + '</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>',
            rels: XML + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' + rels.join('') + '</Relationships>'
        };
    }

    const THEME = XML + '<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="브리프"><a:themeElements>'
        + '<a:clrScheme name="브리프"><a:dk1><a:srgbClr val="000000"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1>'
        + '<a:dk2><a:srgbClr val="1F3864"/></a:dk2><a:lt2><a:srgbClr val="E7E6E6"/></a:lt2>'
        + '<a:accent1><a:srgbClr val="E8456B"/></a:accent1><a:accent2><a:srgbClr val="1F3864"/></a:accent2>'
        + '<a:accent3><a:srgbClr val="A5A5A5"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4>'
        + '<a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6>'
        + '<a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>'
        + '<a:fontScheme name="브리프"><a:majorFont><a:latin typeface="' + FONT + '"/><a:ea typeface="' + FONT + '"/><a:cs typeface=""/></a:majorFont>'
        + '<a:minorFont><a:latin typeface="' + FONT + '"/><a:ea typeface="' + FONT + '"/><a:cs typeface=""/></a:minorFont></a:fontScheme>'
        + '<a:fmtScheme name="브리프"><a:fillStyleLst>'
        + '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill>'
        + '</a:fillStyleLst><a:lnStyleLst>'
        + '<a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>'
        + '<a:ln w="19050"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>'
        + '</a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle>'
        + '<a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst>'
        + '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill>'
        + '</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>';

    const EMPTY_TREE = '<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>'
        + '<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>'
        + '</p:spTree></p:cSld>';

    function build(slides) {
        const encoder = new TextEncoder();
        const media = [];
        const parts = slides.map(function (s, i) { return slideParts(s, i, media); });
        const files = [
            ['[Content_Types].xml', XML + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                + '<Default Extension="xml" ContentType="application/xml"/>'
                + '<Default Extension="jpeg" ContentType="image/jpeg"/>'
                + '<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>'
                + '<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>'
                + '<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>'
                + '<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>'
                + parts.map(function (_, i) {
                    return '<Override PartName="/ppt/slides/slide' + (i + 1) + '.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>';
                }).join('') + '</Types>'],
            ['_rels/.rels', XML + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                + '<Relationship Id="rId1" Type="' + REL + 'officeDocument" Target="ppt/presentation.xml"/></Relationships>'],
            ['ppt/presentation.xml', XML + '<p:presentation ' + NS + '>'
                + '<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>'
                + '<p:sldIdLst>' + parts.map(function (_, i) { return '<p:sldId id="' + (256 + i) + '" r:id="rId' + (i + 3) + '"/>'; }).join('') + '</p:sldIdLst>'
                + '<p:sldSz cx="' + SLIDE_W + '" cy="' + SLIDE_H + '"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>'],
            ['ppt/_rels/presentation.xml.rels', XML + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                + '<Relationship Id="rId1" Type="' + REL + 'slideMaster" Target="slideMasters/slideMaster1.xml"/>'
                + '<Relationship Id="rId2" Type="' + REL + 'theme" Target="theme/theme1.xml"/>'
                + parts.map(function (_, i) {
                    return '<Relationship Id="rId' + (i + 3) + '" Type="' + REL + 'slide" Target="slides/slide' + (i + 1) + '.xml"/>';
                }).join('') + '</Relationships>'],
            ['ppt/slideMasters/slideMaster1.xml', XML + '<p:sldMaster ' + NS + '>' + EMPTY_TREE
                + '<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" '
                + 'accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>'
                + '<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst></p:sldMaster>'],
            ['ppt/slideMasters/_rels/slideMaster1.xml.rels', XML + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                + '<Relationship Id="rId1" Type="' + REL + 'slideLayout" Target="../slideLayouts/slideLayout1.xml"/>'
                + '<Relationship Id="rId2" Type="' + REL + 'theme" Target="../theme/theme1.xml"/></Relationships>'],
            ['ppt/slideLayouts/slideLayout1.xml', XML + '<p:sldLayout ' + NS + ' type="blank" preserve="1">' + EMPTY_TREE
                + '<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>'],
            ['ppt/slideLayouts/_rels/slideLayout1.xml.rels', XML + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                + '<Relationship Id="rId1" Type="' + REL + 'slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>'],
            ['ppt/theme/theme1.xml', THEME]
        ];
        parts.forEach(function (p, i) {
            files.push(['ppt/slides/slide' + (i + 1) + '.xml', p.xml]);
            files.push(['ppt/slides/_rels/slide' + (i + 1) + '.xml.rels', p.rels]);
        });
        const entries = files.map(function (f) { return { name: f[0], data: encoder.encode(f[1]) }; })
            .concat(media.map(function (m) { return { name: m.name, data: m.data }; }));
        return global.ZipWriter.storedBlob(entries, 'application/vnd.openxmlformats-officedocument.presentationml.presentation');
    }

    function download(filename, slides) {
        const url = URL.createObjectURL(build(slides));
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
    }

    global.PptxWriter = { build: build, download: download };
})(window);
