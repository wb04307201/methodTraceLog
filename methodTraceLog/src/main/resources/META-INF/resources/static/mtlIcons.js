/* mtlIcons.js
 *
 * 集中管理 panel 用到的所有 icon。Lucide 风格(stroke + currentColor),
 * 颜色跟父元素 text-color 走,自动适配 light / dark 主题。
 *
 * 用法:
 *   - HTML 占位符: <span data-icon="search"></span>  (DOMContentLoaded 时自动展开)
 *   - JS 动态插入:  element.innerHTML = mtlIcon('search')
 *                  或 mtlIconHTML('search', '<span>继续</span>')
 */
(function () {
    'use strict';

    // 所有 SVG 共用的外壳属性
    const WRAPPER = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">{BODY}</svg>';

    // path / line / circle 等内层元素
    const ICONS = {
        'chart-bar':     '<line x1="12" y1="20" x2="12" y2="10"/><line x1="18" y1="20" x2="18" y2="4"/><line x1="6" y1="20" x2="6" y2="16"/><line x1="3" y1="20" x2="21" y2="20"/>',
        'key':           '<circle cx="7.5" cy="15.5" r="5.5"/><path d="m21 2-9.6 9.6"/><path d="m15.5 7.5 3 3L22 7l-3-3"/>',
        'log-out':       '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/>',
        'moon':          '<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>',
        'sun':           '<circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.34 17.66-1.41 1.41"/><path d="m19.07 4.93-1.41 1.41"/>',
        'folder':        '<path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.93a2 2 0 0 1-1.66-.9l-.82-1.2A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"/>',
        'search':        '<circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>',
        'refresh-cw':    '<path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/>',
        'download':      '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>',
        'radio':         '<path d="M4.9 19.1C1 15.2 1 8.8 4.9 4.9"/><path d="M7.8 16.2c-2.3-2.3-2.3-6.1 0-8.5"/><circle cx="12" cy="12" r="2"/><path d="M16.2 7.8c2.3 2.3 2.3 6.1 0 8.5"/><path d="M19.1 4.9C23 8.8 23 15.1 19.1 19"/>',
        'stop-circle':   '<circle cx="12" cy="12" r="10"/><rect x="9" y="9" width="6" height="6"/>',
        'x':             '<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>',
        'arrow-down':    '<line x1="12" y1="5" x2="12" y2="19"/><polyline points="19 12 12 19 5 12"/>',
        'x-circle':      '<circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/>',
        'alert-circle':  '<circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>',
        'check':         '<polyline points="20 6 9 17 4 12"/>',
        'check-circle':  '<circle cx="12" cy="12" r="10"/><polyline points="9 12 12 15 16 10"/>',
        'play':          '<polygon points="6 4 20 12 6 20 6 4"/>',
        'trash':         '<polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>',
        'inbox':         '<polyline points="22 12 16 12 14 15 10 15 8 12 2 12"/><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/>',
        'search-x':      '<path d="m13.5 8.5-5 5"/><path d="m8.5 8.5 5 5"/><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>',
        'file-x':        '<path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"/><polyline points="14 2 14 8 20 8"/><line x1="9.5" y1="12.5" x2="14.5" y2="17.5"/><line x1="14.5" y1="12.5" x2="9.5" y2="17.5"/>',
        'copy':          '<rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>',
        'check-check':   '<polyline points="2 12 7 17 12 12"/><polyline points="17 8 22 12 17 16"/>',
        'trending-up':   '<polyline points="22 7 13.5 15.5 8.5 10.5 2 17"/><polyline points="16 7 22 7 22 13"/>',
        'trending-down': '<polyline points="22 17 13.5 8.5 8.5 13.5 2 7"/><polyline points="16 17 22 17 22 11"/>',
        'activity':      '<polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>',
        'hash':          '<line x1="4" y1="9" x2="20" y2="9"/><line x1="4" y1="15" x2="20" y2="15"/><line x1="10" y1="3" x2="8" y2="21"/><line x1="16" y1="3" x2="14" y2="21"/>',
        'pulse':         '<path d="M22 12h-4l-3 9L9 3l-3 9H2"/>'
    };

    /**
     * 返回单个 SVG 字符串。
     * @param {string} name - icon 名
     * @returns {string} SVG 标签(包含 <svg>...</svg>)
     */
    function mtlIcon(name) {
        const body = ICONS[name];
        if (!body) {
            console.warn('[mtlIcons] unknown icon: ' + name);
            return '';
        }
        return WRAPPER.replace('{BODY}', body);
    }

    /**
     * 返回 "icon + 文字" 拼接的 HTML 片段。
     * @param {string} name
     * @param {string} text - 显示在 icon 旁的文字
     * @param {string} [className] - 包裹 span 的额外 class
     */
    function mtlIconHTML(name, text, className) {
        // data-icon 套在内层只包 SVG 的 span 上,让 [data-icon] svg { width:1em; height:1em } 约束尺寸
        // 但不能放外层 wrapper 上,否则 width:1em 限制会把文字挤到第二行
        const cls = className ? ' class="' + className + '"' : '';
        return '<span' + cls + '><span data-icon="' + name + '">' + mtlIcon(name) + '</span><span class="mtl-icon-text">' + (text == null ? '' : text) + '</span></span>';
    }

    /**
     * 把当前文档里所有 [data-icon] 占位符替换为 SVG。
     * 重复调用是幂等的(已替换过的元素会被跳过)。
     */
    function mountAll(root) {
        root = root || document;
        const nodes = root.querySelectorAll('[data-icon]');
        nodes.forEach(function (el) {
            if (el.__mtlIconMounted) return;
            const name = el.getAttribute('data-icon');
            const html = mtlIcon(name);
            if (html) {
                el.innerHTML = html;
                el.__mtlIconMounted = true;
            }
        });
    }

    window.MTL_ICONS = ICONS;
    window.mtlIcon = mtlIcon;
    window.mtlIconHTML = mtlIconHTML;
    window.mtlMountIcons = mountAll;
})();
