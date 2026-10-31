/* 智课堂 —— 公共脚本：接口封装、提示、图表、表格渲染 */
(function () {
    'use strict';

    /* ---------------- 提示 ---------------- */
    window.toast = function (msg, type) {
        type = type || 'ok';
        var wrap = document.querySelector('.toast-wrap');
        if (!wrap) {
            wrap = document.createElement('div');
            wrap.className = 'toast-wrap';
            document.body.appendChild(wrap);
        }
        var el = document.createElement('div');
        el.className = 'toastx ' + (type === 'err' ? 'err' : 'ok');
        el.textContent = msg;
        wrap.appendChild(el);
        setTimeout(function () { el.remove(); }, 2600);
    };

    /* ---------------- 接口 ---------------- */
    async function request(url, options) {
        var res = await fetch(url, options);
        if (res.status === 401) { location.href = '/login'; return null; }
        var json = await res.json();
        if (json.code !== 0) {
            toast(json.msg || '操作失败', 'err');
            return null;
        }
        return json.data;
    }

    window.apiGet = function (url) { return request(url); };

    window.apiPost = function (url, body) {
        return request(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body || {})
        });
    };

    window.apiPut = function (url, body) {
        return request(url, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body || {})
        });
    };

    /* ---------------- 图表 ---------------- */
    var PALETTE = ['#3B6EF5', '#22a06b', '#e5a23c', '#e5484d', '#3aa0d8',
                   '#8b5cf6', '#f97316', '#14b8a6', '#6366f1', '#ec4899'];

    window.PALETTE = PALETTE;

    window.mkChart = function (dom, option) {
        var chart = echarts.init(dom);
        option.color = option.color || PALETTE;
        if (!option.textStyle) option.textStyle = { fontFamily: 'Microsoft YaHei' };
        chart.setOption(option);
        window.addEventListener('resize', function () { chart.resize(); });
        return chart;
    };

    /* 表格渲染：cols = [{key,title,fmt}] */
    window.renderTable = function (el, cols, rows, emptyText) {
        if (!rows || rows.length === 0) {
            el.innerHTML = '<div class="empty"><span class="empty-ico">🗂️</span>' +
                (emptyText || '暂无数据') + '</div>';
            return;
        }
        var html = '<div class="table-scroll"><table class="tablex"><thead><tr>';
        cols.forEach(function (c) { html += '<th>' + c.title + '</th>'; });
        html += '</tr></thead><tbody>';
        rows.forEach(function (r, ri) {
            html += '<tr>';
            cols.forEach(function (c) {
                var v = c.fmt ? c.fmt(r, ri) : r[c.key];
                html += '<td>' + (v === undefined || v === null ? '-' : v) + '</td>';
            });
            html += '</tr>';
        });
        html += '</tbody></table></div>';
        el.innerHTML = html;
    };

    window.pct = function (v) { return (v === null || v === undefined) ? '-' : v + '%'; };

    window.riskTag = function (level) {
        var cls = level === '红' ? 'tag-red' : (level === '黄' ? 'tag-orange' : 'tag-green');
        return '<span class="tag ' + cls + '">' + level + '</span>';
    };
})();
