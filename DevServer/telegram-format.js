const timeZone = 'Europe/Moscow';
const locale = 'ru-RU';
const messageTypes = { sms: 'SMS', notification: 'Уведомление', test: 'Тест' };
const event = $('Webhook').first().json.body;

if (!event || event.schema_version !== 1 ||
    typeof event.event_id !== 'string' || !event.event_id ||
    !Object.hasOwn(messageTypes, event.message_type) ||
    typeof event.text !== 'string') {
  throw new Error('Invalid Message487 event');
}

const escapeHtml = value => value.replace(/[&<>]/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;',
})[character]);
const firstText = (...values) => values.find(value => typeof value === 'string' && value) || '—';
const date = new Date(event.occurred_at);
const timestamp = typeof event.occurred_at === 'string' && !Number.isNaN(date.getTime())
  ? new Intl.DateTimeFormat(locale, {
    timeZone, day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).format(date).replace(/,/g, '')
  : '—';
const source = firstText(event.sender, event.source_name, event.source);
const device = firstText(event.device_code, event.device_id);
const segments = [
  { text: 'Message487: ', bold: false },
  { text: source, bold: true },
  { text: '\n', bold: false },
  { text: `${messageTypes[event.message_type]}\n${device}\n${timestamp}`, bold: true },
  { text: '\n', bold: false },
  { text: typeof event.title === 'string' && event.title ? `${event.title}\n` : '', bold: false },
  { text: event.text, bold: false },
];

// Split unescaped text and close formatting in each part to keep HTML valid.
const chunks = [];
let html = '';
let length = 0;
for (const segment of segments) {
  let run = '';
  const flush = () => {
    if (run) {
      const escaped = escapeHtml(run);
      html += segment.bold ? `<b>${escaped}</b>` : escaped;
      run = '';
    }
  };
  for (const character of segment.text) {
    if (length + character.length > 3500) {
      flush();
      chunks.push(html);
      html = '';
      length = 0;
    }
    run += character;
    length += character.length;
  }
  flush();
}
if (html) chunks.push(html);

return chunks.map((part, index) => ({
  json: {
    telegram_text: chunks.length > 1 ? `[${index + 1}/${chunks.length}]\n${part}` : part,
  },
}));
