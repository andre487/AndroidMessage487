// Attach all media to the first text part as one Rich Message.
const event = $('Webhook').first().json.body;
const images = event.images ?? [];
if (!Array.isArray(images)) throw new Error('Invalid images collection');
const result = $input.all();
const gallery = [];
const icons = [];
const binaryImage = (image, index) => {
  if (!image || !['image/jpeg', 'image/png'].includes(image.mime_type) ||
      typeof image.data_base64 !== 'string' || !image.data_base64.length ||
      image.data_base64.length % 4 !== 0 ||
      !/^[A-Za-z0-9+/]*={0,2}$/.test(image.data_base64)) {
    throw new Error(`Invalid image ${index + 1}`);
  }
  const extension = image.mime_type === 'image/png' ? 'png' : 'jpg';
  const kind = ['attachment', 'avatar', 'app_icon'].includes(image.kind) ? image.kind : 'attachment';
  return { data: image.data_base64, mimeType: image.mime_type, fileName: `${kind}-${index + 1}.${extension}` };
};
const blocks = [];
const binary = {};
for (const [index, image] of images.entries()) {
  binary[`image${index}`] = binaryImage(image, index);
  const photo = { type: 'photo', photo: { type: 'photo', media: `attach://file${index}` } };
  if (['avatar', 'app_icon'].includes(image.kind)) {
    photo.caption = { text: image.kind === 'avatar' ? 'Аватар' : 'Значок приложения' };
    icons.push(photo);
  } else if (image.presentation === 'gallery') {
    gallery.push(photo);
  } else if (image.presentation === 'big_picture') {
    blocks.push(photo);
  } else {
    blocks.push({ type: 'document', document: { type: 'document', media: `attach://file${index}` } });
  }
}
for (const photos of [gallery, icons]) {
  if (photos.length) blocks.push(photos.length > 1 ? { type: 'collage', blocks: photos } : photos[0]);
}
// Preserve the formatter's bold/italic text without interpreting escaped user HTML.
if (images.length) {
  const first = result[0];
  if (!first || typeof first.json.telegram_text !== 'string') throw new Error('Missing notification text');
  let bold = false;
  let italic = false;
  const text = [];
  for (const token of first.json.telegram_text.split(/(<\/?[bi]>)/)) {
    if (token === '<b>' || token === '</b>') { bold = token === '<b>'; continue; }
    if (token === '<i>' || token === '</i>') { italic = token === '<i>'; continue; }
    if (!token) continue;
    let value = token.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
    if (italic) value = { type: 'italic', text: value };
    if (bold) value = { type: 'bold', text: value };
    text.push(value);
  }
  blocks.unshift({ type: 'paragraph', text });
  first.json.telegram_operation = 'sendRichMessage';
  first.json.rich_message = { blocks };
  first.binary = binary;
}
return result;
