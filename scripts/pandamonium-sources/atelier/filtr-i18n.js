/* Retained lookup and DOM binding functions reused from Parlons panel/i18n.js.
 * Only marked app labels change. Pandamonium owns the explicit language choice. */
(function(root) {
  'use strict';
  const messages = root.FiltrMessages;
  const valid = code => ['en','zh-Hans','ru','uk'].includes(code);
  let language = 'en';
  const bindings = new WeakMap(), renderers = new WeakMap();
  function t(key, vars = {}) {
    let text = messages[language][key] || messages.en[key];
    if (text == null) throw new Error('Unknown translation key: ' + key);
    if (typeof text !== 'string') text = text[new Intl.PluralRules(language).select(Number(vars.count))] || text.other;
    return text.replace(/\{(\w+)\}/g, (match, name) => Object.hasOwn(vars, name) ? String(vars[name]) : match);
  }
  function apply(node = document) {
    const elements = [...node.querySelectorAll('[data-i18n], [data-i18n-render], [data-i18n-title], [data-i18n-placeholder], [data-i18n-aria-label], [data-i18n-alt]')];
    if (node.nodeType === 1) elements.unshift(node);
    for (const el of elements) {
      if (renderers.has(el)) el.textContent = renderers.get(el)();
      const vars = bindings.get(el) || JSON.parse(el.getAttribute('data-i18n-vars') || '{}');
      for (const attribute of ['text', 'title', 'placeholder', 'aria-label', 'alt']) {
        const key = el.getAttribute(attribute === 'text' ? 'data-i18n' : 'data-i18n-' + attribute);
        if (!key) continue;
        if (attribute === 'text') el.textContent = t(key, vars);
        else el.setAttribute(attribute, t(key, vars));
      }
    }
  }
  function bind(el, key, vars = {}) {
    el.removeAttribute('data-i18n-render'); renderers.delete(el);
    el.setAttribute('data-i18n', key); bindings.set(el, vars); el.textContent = t(key, vars);
  }
  function render(el, formatter) {
    el.removeAttribute('data-i18n'); bindings.delete(el);
    el.setAttribute('data-i18n-render', ''); renderers.set(el, formatter); el.textContent = formatter();
  }
  function text(el, value) {
    el.removeAttribute('data-i18n'); el.removeAttribute('data-i18n-render');
    bindings.delete(el); renderers.delete(el); el.textContent = value;
  }
  function attribute(el, name, key, vars = {}) {
    if (!['title', 'placeholder', 'aria-label', 'alt'].includes(name)) throw new Error('Unsupported translated attribute');
    el.setAttribute('data-i18n-' + name, key); bindings.set(el, vars); el.setAttribute(name, t(key, vars));
  }

  function setLanguage(code) {
    if (!valid(code)) throw new Error('Unsupported language');
    language = code;
    document.documentElement.lang = code;
    apply();
  }
  root.FiltrI18n = Object.freeze({t,apply,bind,render,text,attribute,setLanguage,getLanguage:()=>language});
  let code='en';
  if (root.AndroidFiltr && root.AndroidFiltr.getLanguage) code=root.AndroidFiltr.getLanguage();
  setLanguage(valid(code) ? code : 'en');
})(window);
