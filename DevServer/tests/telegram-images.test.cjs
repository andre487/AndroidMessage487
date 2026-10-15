const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { test } = require('node:test');
const { runInNewContext } = require('node:vm');
const script = readFileSync(join(__dirname, '../telegram-images.js'), 'utf8');
const run = images => runInNewContext(`(function () { ${script}\n})()`, {
  $: () => ({ first: () => ({ json: { body: { images } } }) }),
  $input: { all: () => [{ json: { telegram_text: 'Existing formatted text' } }] },
});
const image = (presentation, kind = 'attachment') => ({
  mime_type: 'image/png', kind, presentation, data_base64: 'AAAA',
});
test('text-only requests and unknown legacy attachments retain their meaning', () => {
  assert.equal(run(undefined).length, 1);
  const encoded = Buffer.alloc(3 * 1024 * 1024, 47).toString('base64');
  const result = run([{ mime_type: 'image/jpeg', kind: 'attachment', data_base64: encoded }]);
  assert.equal(result[0].json.telegram_text, 'Existing formatted text');
  assert.equal(result.length, 1);
  assert.equal(result[0].json.telegram_operation, 'sendRichMessage');
  assert.equal(result[0].json.rich_message.blocks[1].type, 'document');
  assert.equal(result[0].binary.image0.data, encoded);
});
test('BigPicture stays a photo while both icons form a collage inside the text message', () => {
  const result = run([image('big_picture'), image('big_picture', 'avatar'), image('gallery', 'app_icon')]);
  assert.equal(result.length, 1);
  assert.equal(result[0].json.telegram_operation, 'sendRichMessage');
  const blocks = result[0].json.rich_message.blocks;
  assert.equal(blocks.length, 3);
  assert.equal(blocks[1].type, 'photo');
  assert.equal(blocks[1].photo.media, 'attach://file0');
  assert.equal(blocks[2].type, 'collage');
  const photos = blocks[2].blocks;
  assert.equal(photos.length, 2);
  assert.equal(photos[0].type, 'photo');
  assert.equal(photos[0].photo.media, 'attach://file1');
  assert.equal(photos[1].photo.media, 'attach://file2');
  assert.equal(photos[1].caption.text, 'Значок приложения');
  assert.equal(Object.keys(result[0].binary).join(','), 'image0,image1,image2');
  assert.equal(result[0].json.image_key, undefined);
});
test('gallery stays a single collage attached to the text even above ten images', () => {
  const result = run(Array.from({ length: 21 }, () => image('gallery')));
  assert.equal(result.length, 1);
  const collage = result[0].json.rich_message.blocks[1];
  assert.equal(collage.type, 'collage');
  assert.equal(collage.blocks.length, 21);
  collage.blocks.forEach((photo, index) => {
    assert.equal(photo.photo.media, `attach://file${index}`);
    assert.ok(result[0].binary[`image${index}`]);
  });
});
test('all image types are in the main message with unique attachment references', () => {
  const result = run([image('big_picture'), image('gallery'), image('gallery'),
    image('file'), image('file', 'avatar'), image('file', 'app_icon')]);
  assert.equal(result.length, 1);
  const blocks = result[0].json.rich_message.blocks;
  assert.equal(blocks.map(block => block.type).join(','), 'paragraph,photo,document,collage,collage');
  const references = blocks.slice(1).flatMap(block => block.blocks ?? [block])
    .map(block => (block.photo ?? block.document).media);
  assert.equal(new Set(references).size, 6);
  assert.equal(Object.keys(result[0].binary).length, 6);
});
test('invalid image collection fails before any part can be sent', () => {
  for (const images of [{}, [null], [{ mime_type: 'image/gif', data_base64: 'AAAA' }],
    [{ mime_type: 'image/png', data_base64: 'invalid!' }],
    [{ mime_type: 'image/png', data_base64: '' }]]) assert.throws(() => run(images), /Invalid image/);
});

test('icon formatting preserves literal user HTML and only attaches to the first text part', () => {
  const parts = [{ json: { telegram_text: '<b>App &amp; Co</b>\n&lt;b&gt;user&lt;/b&gt;' } },
    { json: { telegram_text: 'Remaining text' } }];
  const result = runInNewContext(`(function () { ${script}\n})()`, {
    $: () => ({ first: () => ({ json: { body: { images: [image('file', 'avatar')] } } }) }),
    $input: { all: () => parts },
  });
  const text = result[0].json.rich_message.blocks[0].text;
  assert.equal(text[0].type, 'bold');
  assert.equal(text[0].text, 'App & Co');
  assert.equal(text[1], '\n<b>user</b>');
  assert.equal(result[0].json.rich_message.blocks[1].type, 'photo');
  assert.equal(result[1].json.telegram_text, 'Remaining text');
  assert.equal(result[1].binary, undefined);
});
