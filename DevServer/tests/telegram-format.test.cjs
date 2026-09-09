const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { test } = require('node:test');
const { runInNewContext } = require('node:vm');

const script = readFileSync(join(__dirname, '../telegram-format.js'), 'utf8');
const base = {
  schema_version: 1, event_id: 'synthetic-event', message_type: 'sms',
  device_code: 'personal-phone', device_id: 'installation-id',
  sender: '+79991234567', source: 'sms', source_name: 'SMS',
  occurred_at: '2026-09-09T09:30:00Z', text: 'Your message',
};
const format = changes => runInNewContext(`(function () { ${script}\n})()`, {
  $: name => {
    assert.equal(name, 'Webhook');
    return { first: () => ({ json: { body: { ...base, ...changes } } }) };
  },
}).map(item => item.json.telegram_text);
const plain = html => html.replace(/<\/?b>/g, '')
  .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');

test('SMS uses the compact bold header and event time in Moscow', () => {
  assert.equal(format({})[0],
    'Message487: <b>+79991234567</b>\n' +
    '<b>SMS\npersonal-phone\n09.09.2026 12:30:00</b>\nYour message');
});

test('Notification preserves title and escapes every input field', () => {
  const result = format({
    message_type: 'notification', sender: undefined,
    source_name: '<App & Co>', device_code: '<phone>',
    title: '<b>Title</b>', text: 'A & B < C',
  })[0];
  assert.ok(result.startsWith('Message487: <b>&lt;App &amp; Co&gt;</b>\n'));
  assert.ok(result.includes('Уведомление\n&lt;phone&gt;'));
  assert.ok(result.endsWith('&lt;b&gt;Title&lt;/b&gt;\nA &amp; B &lt; C'));
});

test('Missing display fields fall back without inventing an event timestamp', () => {
  const result = format({
    message_type: 'test', sender: undefined, source_name: undefined,
    source: 'life.andre.message487', device_code: undefined, occurred_at: null,
  })[0];
  assert.equal(result, 'Message487: <b>life.andre.message487</b>\n' +
    '<b>Тест\ninstallation-id\n—</b>\nYour message');
  assert.ok(format({ occurred_at: 'invalid' })[0].includes('personal-phone\n—'));
});

test('Long headers and body preserve text, Unicode and balanced HTML in each part', () => {
  const sender = '😀<&>'.repeat(1500);
  const text = 'Hello 📨 & <world>\n'.repeat(1000);
  const parts = format({ sender, text });
  assert.ok(parts.length > 2);
  const restored = parts.map((part, index) => {
    assert.ok(part.startsWith(`[${index + 1}/${parts.length}]\n`));
    assert.ok(plain(part).length <= 4096);
    assert.equal((part.match(/<b>/g) || []).length, (part.match(/<\/b>/g) || []).length);
    assert.ok(plain(part).isWellFormed());
    return plain(part.replace(/^\[\d+\/\d+\]\n/, ''));
  }).join('');
  assert.equal(restored, `Message487: ${sender}\nSMS\npersonal-phone\n09.09.2026 12:30:00\n${text}`);
});

test('Invalid events fail before producing Telegram messages', () => {
  for (const changes of [
    { schema_version: 2 }, { event_id: '' }, { message_type: 'unknown' },
    { message_type: 'toString' }, { text: null },
  ]) {
    assert.throws(() => format(changes), /Invalid Message487 event/);
  }
});
