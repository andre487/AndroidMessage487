const event = $('Webhook').first().json.body;
let reason = '';
if (!event || event.schema_version !== 1 || typeof event.event_id !== 'string' ||
    !event.event_id || !['test', 'notification', 'sms'].includes(event.message_type) ||
    typeof event.text !== 'string') reason = 'Invalid Message487 event';
const images = event?.images ?? [];
if (!Array.isArray(images)) reason = 'images must be an array';
else if (processImages && images.some(image => !image || !['image/jpeg', 'image/png'].includes(image.mime_type) ||
    !Number.isInteger(image.width) || image.width <= 0 || !Number.isInteger(image.height) || image.height <= 0 ||
    typeof image.data_base64 !== 'string' || !image.data_base64.length ||
    image.data_base64.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(image.data_base64))) {
  reason = 'Invalid JPEG/PNG image';
}
return [{ json: { valid: !reason, reason, event_id: event?.event_id ?? null } }];
